"""One training job, run as its own process.

The service never trains inside its own event loop: a training run allocates gigabytes, pins the CPU
and can be killed mid-epoch. This module is the bridge between the job contract and the existing
pipeline (``aeroml.training.train.run``), and it does exactly three things:

* translate the pipeline's stage/epoch callbacks into one atomically-replaced ``status.json`` the
  service can poll;
* stop cleanly when the service drops a ``cancel.requested`` marker next to the status file;
* write ``result.json`` on success or ``failure.json`` (machine code + Russian reason) on failure.

It adds no model code. Splits, normalisation order, calibration, evaluation and the ONNX export
remain the ones in ``train.py``.

Usage (the service builds this command):

    python -m aeroml.training.worker --job job.json --status status.json \
        --result result.json --failure failure.json --log train.log
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import traceback
from pathlib import Path

from ..reporting import clean
from .contract import (
    CALIBRATING,
    COMPLETED,
    EVALUATING,
    EXPORTING,
    FAILED,
    CANCELLED,
    STAGE_BASE_PROGRESS,
    STAGE_TO_STATE,
    STAGE_MESSAGES,
    TRAINING,
    AUTOMATIC_DEPLOYMENT,
    PROMOTION_STATUS,
    UNLABELED_POLICY,
    failure_from_exception,
    progress_for,
    stage_message,
)


class JobCancelled(Exception):
    """Raised inside the training loop when the service asks the job to stop."""


class StatusWriter:
    """Writes status.json atomically. A half-written status file is never observable."""

    def __init__(self, path: Path, cancel_marker: Path) -> None:
        self.path = Path(path)
        self.cancel_marker = Path(cancel_marker)
        self.payload: dict = {}
        # Every stage this run entered, in order. The supervisor samples this file; a stage shorter
        # than one poll would otherwise leave no trace at all, and an operator would see a job jump
        # from TRAINING to COMPLETED with no calibration in between.
        self.stages: list[dict] = []

    def cancelled(self) -> bool:
        return self.cancel_marker.exists()

    def write(self, state: str, **fields) -> None:
        payload = {"state": state, "stage": fields.pop("stage", state.lower()),
                   "updatedAtMillis": int(time.time() * 1000), "pid": os.getpid()}
        payload.update(fields)
        # ``state`` is already the positional argument of stage_message; passing the payload's own
        # copy of the key as well would be a duplicate keyword argument.
        message_fields = {key: value for key, value in payload.items() if key != "state"}
        if not self.stages or self.stages[-1]["state"] != state:
            self.stages.append({"state": state, "at": payload["updatedAtMillis"],
                                "epoch": payload.get("epoch")})
        payload["message"] = payload.pop("message", None) or stage_message(state, **message_fields)
        payload["stages"] = list(self.stages)
        self.payload = payload
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        # A loss that turns out non-finite must arrive as JSON null. ``NaN`` is not JSON, and a client
        # that parses strictly would drop the whole status object over one number.
        temporary.write_text(json.dumps(clean(payload), ensure_ascii=False, allow_nan=False), encoding="utf-8")
        replace_with_retry(temporary, self.path)


def _format_loss(value) -> str:
    return "н/д" if value is None else f"{float(value):.4f}"


def _read_json(path: Path) -> dict:
    """The bundle manifest after a run, or an empty mapping. Never a reason to fail a finished job."""
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def replace_with_retry(source: Path, target: Path, *, attempts: int = 50, delay: float = 0.01) -> None:
    """``os.replace`` with a short retry loop.

    On Windows a reader that holds the target open blocks the rename with ``PermissionError``, and the
    supervisor polls exactly these files. Losing an epoch update because someone read it is not an
    acceptable failure mode, so the swap is retried instead of aborting the run.
    """
    for attempt in range(attempts):
        try:
            os.replace(source, target)
            return
        except OSError:
            if attempt == attempts - 1:
                raise
            time.sleep(delay)


def monotone_stage(order: dict, reached: dict, state: str) -> str:
    """Never report a stage behind the furthest one already reported.

    The pipeline evaluates the held-out fold after exporting the bundle, so a naive mapping would
    rewind the reported stage from EXPORTING to EVALUATING and the client's progress bar with it.
    A stage that moves backwards is not information; it is a bug the operator would have to explain.
    """
    previous = order.get(reached.get("state"))
    if previous is not None and order.get(state, 0.0) < previous:
        return reached["state"]
    reached["state"] = state
    return state


def load_job(path: Path) -> dict:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    required = ("jobId", "datasetRoot", "preset", "window", "heads", "featureSchemaVersion", "seed", "bundleDir")
    missing = [name for name in required if name not in data]
    if missing:
        raise ValueError(f"job file is missing {missing}")
    return data


def build_config(job: dict):
    from .config import TrainingConfig

    return TrainingConfig(
        dataset=Path(job["datasetRoot"]),
        output=Path(job["bundleDir"]),
        window=job["window"],
        sequence_length=int(job.get("sequenceLength") or (31 if job["preset"] == "flash" else 96)),
        stride=int(job.get("stride", 4)),
        heads=tuple(job["heads"]),
        preset=job["preset"],
        epochs=int(job.get("epochs", 30)),
        batch_size=int(job.get("batchSize", 128)),
        seed=int(job["seed"]),
        device="cpu",
        notes=job.get("notes", "") or "external training service job",
        allow_synthetic=bool(job.get("allowSynthetic", False)),
        include_review=bool(job.get("includeReview", False)),
        torch_threads=int(job.get("torchThreads", 2)),
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run one Aero AC training job as a subprocess")
    parser.add_argument("--job", type=Path, required=True)
    parser.add_argument("--status", type=Path, required=True)
    parser.add_argument("--result", type=Path, required=True)
    parser.add_argument("--failure", type=Path, required=True)
    parser.add_argument("--cancel-marker", type=Path, required=True)
    parser.add_argument("--log", type=Path)
    arguments = parser.parse_args(argv)

    started = time.monotonic()
    job = load_job(arguments.job)
    writer = StatusWriter(arguments.status, arguments.cancel_marker)
    total_epochs = int(job.get("epochs", 30))
    epoch = 0
    train_loss = None
    validation_loss = None
    last_stage = "auditing"
    reached: dict = {"state": None}  # the furthest contract state this run has reported

    def emit(state: str, *, stage: str | None = None, **fields) -> None:
        nonlocal last_stage
        if stage:
            last_stage = stage
        state = monotone_stage(STAGE_BASE_PROGRESS, reached, state)
        payload = dict(fields)
        payload.setdefault("epoch", epoch)
        payload.setdefault("totalEpochs", total_epochs)
        payload.setdefault("trainLoss", train_loss)
        payload.setdefault("validationLoss", validation_loss)
        payload.setdefault("elapsedSeconds", round(time.monotonic() - started, 3))
        payload.setdefault("lastStage", last_stage)
        payload.setdefault("progress", progress_for(state, payload["epoch"], total_epochs))
        if state == TRAINING:
            payload.setdefault("message", stage_message(
                TRAINING, epoch=payload["epoch"], totalEpochs=total_epochs,
                trainLoss=_format_loss(train_loss), validationLoss=_format_loss(validation_loss)))
        writer.write(state, stage=stage or state.lower(), **payload)

    def progress(event: dict) -> None:
        nonlocal epoch, train_loss, validation_loss
        if writer.cancelled():
            raise JobCancelled("cancel requested by the service")
        stage = str(event.get("stage", "training"))
        state = STAGE_TO_STATE.get(stage, TRAINING)
        if stage == "training":
            epoch = int(event.get("epoch", epoch)) + 1
            train_loss = event.get("trainLoss", train_loss)
            validation_loss = event.get("validationLoss", validation_loss)
        emit(state, stage=stage, epoch=epoch, totalEpochs=int(event.get("totalEpochs", total_epochs)),
             trainLoss=train_loss, validationLoss=validation_loss, **{
                 key: value for key, value in event.items()
                 if key not in ("stage", "epoch", "totalEpochs", "trainLoss", "validationLoss")})

    try:
        from ..schema import default_schema
        from .train import run

        schema = default_schema()
        if int(job["featureSchemaVersion"]) != int(schema.version):
            from .contract import RequestError
            raise RequestError("feature_schema_mismatch", requested=job["featureSchemaVersion"], deployed=schema.version)
        config = build_config(job)
        emit("AUDITING", stage="auditing")
        outcome = run(config, schema, progress=progress)
        if writer.cancelled():
            raise JobCancelled("cancel requested by the service")
        emit(COMPLETED, stage="completed", bundle=str(outcome.get("bundle", config.output)))
        bundle_path = Path(outcome.get("bundle", config.output))
        manifest = _read_json(bundle_path / "manifest.json")
        provenance = manifest.get("provenance", {}) if manifest else {}
        payload = {
            "jobId": job["jobId"],
            "status": COMPLETED,
            "state": COMPLETED,
            "modelVersion": outcome.get("modelVersion") or manifest.get("modelVersion"),
            "bundlePath": str(bundle_path),
            "modelKind": job["preset"],
            "window": job["window"],
            "sequenceLength": int(job.get("sequenceLength") or (31 if job["preset"] == "flash" else 96)),
            "featureSchemaVersion": schema.version,
            "heads": manifest.get("heads") if manifest else list(job["heads"]),
            "datasetVersion": provenance.get("datasetVersion") or outcome.get("datasetVersion"),
            "calibration": (manifest or {}).get("calibration") or None,
            "evaluation": outcome.get("evaluation", {}),
            "smokeOnly": bool(job.get("allowSynthetic", False)),
            "unlabeledPolicy": UNLABELED_POLICY,
            "promotion": {"status": PROMOTION_STATUS, "automaticDeployment": AUTOMATIC_DEPLOYMENT},
            "elapsedSeconds": round(time.monotonic() - started, 3),
            "message": STAGE_MESSAGES[COMPLETED],
        }
        Path(arguments.result).write_text(json.dumps(clean(payload), ensure_ascii=False, allow_nan=False),
                                          encoding="utf-8")
        if arguments.log:
            with Path(arguments.log).open("a", encoding="utf-8") as handle:
                handle.write(f"\n[job {job['jobId']}] COMPLETED in {payload['elapsedSeconds']}s\n")
        return 0
    except JobCancelled as cancelled:
        emit(CANCELLED, stage="cancelled", message=STAGE_MESSAGES[CANCELLED])
        _write_failure(arguments.failure, job["jobId"], CANCELLED, "cancelled",
                       STAGE_MESSAGES[CANCELLED], traceback.format_exc(), {}, started)
        _log(arguments.log, job, cancelled)
        return 3
    except BaseException as error:  # noqa: BLE001 - every failure must reach the operator as a message
        code, reason = failure_from_exception(error)
        emit(FAILED, stage="failed", message=stage_message(FAILED, reason=reason))
        _write_failure(arguments.failure, job["jobId"], FAILED, code,
                       stage_message(FAILED, reason=reason), traceback.format_exc(),
                       {"stage": last_stage}, started)
        _log(arguments.log, job, error)
        return 1


def _log(log_path: Path | None, job: dict, error: BaseException) -> None:
    if not log_path:
        return
    try:
        with Path(log_path).open("a", encoding="utf-8") as handle:
            handle.write(f"\n[job {job.get('jobId')}] FAILED: {type(error).__name__}: {error}\n")
            handle.write(traceback.format_exc())
    except OSError:  # pragma: no cover - logging must never mask the original failure
        pass


def _write_failure(path, job_id: str, state: str, code: str, message: str, trace: str,
                   extra: dict, started: float) -> None:
    payload = {"jobId": job_id, "state": state, "errorCode": code, "message": message,
               "technical": {"traceback": (trace or "").strip().splitlines()[-12:]},
               "elapsedSeconds": round(time.monotonic() - started, 3)}
    payload.update(extra)
    target = Path(path)
    temporary = target.with_suffix(target.suffix + ".tmp")
    temporary.write_text(json.dumps(clean(payload), ensure_ascii=False, allow_nan=False), encoding="utf-8")
    # Same reason as the status file: the supervisor reads this one while the run is ending.
    replace_with_retry(temporary, target)


if __name__ == "__main__":
    sys.exit(main())

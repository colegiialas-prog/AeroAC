"""Job registry for the external training service.

The service is a thin, boring supervisor in front of the existing pipeline:

* one job runs at a time, in its own subprocess, so a training run cannot stall the HTTP loop and
  cancellation is a real process kill rather than a flag nobody reads;
* the queue is bounded, so a broken caller cannot schedule a week of CPU on a shared machine;
* every state the operator sees comes from a status file the worker writes, never from a guess;
* bundles are written under one service-owned root; datasets are read from configured roots.
  A job request cannot name an arbitrary path.

No deployment happens here. A finished job produces a candidate bundle and stops.
"""

from __future__ import annotations

import json
import logging
import os
import subprocess
import sys
import threading
import time
import uuid
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from ..reporting import clean
from ..schema import default_schema
from ..training.contract import (
    ACTIVE_STATES,
    AUTOMATIC_DEPLOYMENT,
    CANCELLED,
    COMPLETED,
    CONTRACT_VERSION,
    FAILED,
    IDLE,
    JAVA_STATUS,
    PROMOTION_STATUS,
    QUEUED,
    RUNNING_STATES,
    STAGE_BASE_PROGRESS,
    STAGE_MESSAGES,
    STATES,
    TERMINAL_STATES,
    UNLABELED_POLICY,
    JobRequest,
    RequestError,
    human,
    progress_for,
    validate_request,
)

LOGGER = logging.getLogger("aeroml.service.training")
ML_ROOT = Path(__file__).resolve().parents[2]
HISTORY_LIMIT = 64
SERVICE_NAME = "aero-training-service"
# States a worker's status file may report. Anything else is a bug in the worker, not a state change.
STATUS_STATES = frozenset(STATES)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _posix(path: Path) -> str:
    return Path(path).as_posix()


@dataclass
class Job:
    job_id: str
    request: JobRequest
    work_dir: Path
    bundle_dir: Path
    state: str = QUEUED
    message: str = STAGE_MESSAGES[QUEUED]
    error_code: str | None = None
    epoch: int = 0
    total_epochs: int = 30
    train_loss: float | None = None
    validation_loss: float | None = None
    last_stage: str = "queued"
    progress: float = 0.0
    dataset_version: str = ""
    created_at: str = field(default_factory=_now)
    started_at: str | None = None
    finished_at: str | None = None
    updated_at_millis: int = field(default_factory=lambda: int(time.time() * 1000))
    pid: int | None = None
    cancel_requested: bool = False
    status_mtime: float = 0.0
    result: dict | None = None
    failure: dict | None = None
    log_file: Path | None = None
    history: list[dict] = field(default_factory=list)
    queue_position: int | None = None

    @property
    def state_is_terminal(self) -> bool:
        return self.state in TERMINAL_STATES

    def remember(self, state: str, **fields) -> None:
        """Timeline of the states this job actually passed through, for the admin screen."""
        if self.history and self.history[-1]["state"] == state:
            return
        self.history.append({"state": state, "at": _now(), **fields})
        del self.history[:-HISTORY_LIMIT]

    def to_dict(self) -> dict:
        """The job object of the wire contract, plus the fields the admin screen reads.

        The first block is the contract itself: ``status`` carries the real state (IDLE, QUEUED,
        AUDITING, …, COMPLETED, FAILED, CANCELLED) and ``javaStatus`` the coarse state the plugin's
        ``TrainingJob.Status`` enum already understands. Non-finite numbers become JSON ``null``
        instead of ``NaN``, because ``NaN`` is not JSON and a client that parses it strictly dies on
        the whole response, not on one field.
        """
        return clean({
            # -- contract fields -------------------------------------------------
            "status": self.state,
            "jobId": self.job_id,
            "modelType": self.request.model_kind,
            "datasetVersion": self.dataset_version or self.request.dataset_version or None,
            "featureSchemaVersion": self.request.feature_schema_version,
            "window": self.request.window,
            "heads": list(self.request.heads),
            "epoch": int(self.epoch),
            "totalEpochs": int(self.total_epochs),
            "progress": self.progress,
            "trainLoss": self.train_loss,
            "validationLoss": self.validation_loss,
            "elapsedSeconds": self._elapsed(),
            "updatedAtMillis": self.updated_at_millis,
            "message": self.message,
            # -- service fields --------------------------------------------------
            "state": self.state,
            "javaStatus": JAVA_STATUS.get(self.state, self.state),
            "dataset": self.request.dataset,
            "datasetPath": _posix(self.request.dataset_path or Path(self.request.dataset)),
            "preset": self.request.preset,
            "sequenceLength": self.request.sequence_length,
            "seed": self.request.seed,
            "epochs": self.request.epochs,
            "lastStage": self.last_stage,
            "errorCode": self.error_code,
            "allowSynthetic": self.request.allow_synthetic,
            "includeReview": self.request.include_review,
            "createdAt": self.created_at,
            "startedAt": self.started_at,
            "finishedAt": self.finished_at,
            "resultAvailable": self.state == COMPLETED and self.result is not None,
            "queuePosition": self.queue_position,
            "cancelRequested": self.cancel_requested,
            "stateHistory": [entry["state"] for entry in self.history],
            "stateTimeline": list(self.history),
            "logFile": _posix(self.log_file) if self.log_file else None,
        })

    def _elapsed(self) -> float:
        if self.started_at is None:
            return 0.0
        end = self.finished_at or _now()
        try:
            start = datetime.fromisoformat(self.started_at)
            finish = datetime.fromisoformat(end)
        except ValueError:  # pragma: no cover - defensive
            return 0.0
        return round(max(0.0, (finish - start).total_seconds()), 3)

    def record(self) -> dict:
        return clean({"recordVersion": 1, "spec": self.request.to_dict(), "state": self.state,
                      "datasetVersion": self.dataset_version or self.request.dataset_version or None,
                      "message": self.message, "errorCode": self.error_code, "epoch": self.epoch,
                      "totalEpochs": self.total_epochs, "trainLoss": self.train_loss,
                      "validationLoss": self.validation_loss, "lastStage": self.last_stage,
                      "progress": self.progress, "createdAt": self.created_at, "startedAt": self.started_at,
                      "finishedAt": self.finished_at, "result": self.result, "failure": self.failure,
                      "history": self.history})


def idle_snapshot(schema, *, message: str | None = None) -> dict:
    """The job object answered when the service holds no job at all."""
    return {
        "status": IDLE,
        "jobId": None,
        "modelType": None,
        "datasetVersion": None,
        "featureSchemaVersion": schema.version,
        "window": None,
        "heads": [],
        "epoch": 0,
        "totalEpochs": 0,
        "progress": 0.0,
        "trainLoss": None,
        "validationLoss": None,
        "elapsedSeconds": 0.0,
        "updatedAtMillis": int(time.time() * 1000),
        "message": message or STAGE_MESSAGES[IDLE],
        "state": IDLE,
        "javaStatus": JAVA_STATUS[IDLE],
        "resultAvailable": False,
    }


def _read_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


# ---------------------------------------------------------------------------
# Inventory: what can be trained on, and what has been produced.
def dataset_entry(path: Path) -> dict:
    """Counts read straight from session metadata. A full audit happens inside the job, not here."""
    from ..dataset.records import read_metadata

    entry = {
        "name": path.name,
        "path": _posix(path.resolve()),
        "sessionsTotal": 0,
        "sessionsByLabel": {"LEGIT": 0, "CHEAT": 0, "UNLABELED": 0, "UNKNOWN": 0},
        "players": 0,
        "incompleteSessions": 0,
        "sessionsWithDroppedRecords": 0,
        "datasetVersions": [],
        "featureSchemaVersions": [],
        "unreadableSessions": 0,
        "unlabeledPolicy": UNLABELED_POLICY,
        "readyForTraining": False,
        "readinessNote": "",
    }
    players: set[str] = set()
    versions: set[str] = set()
    schemas: set[int] = set()
    metadata_dir = path / "metadata"
    for metadata_path in sorted(metadata_dir.glob("session-*.json")):
        entry["sessionsTotal"] += 1
        try:
            metadata = read_metadata(metadata_path)
        except Exception as error:  # noqa: BLE001 - an unreadable session is counted, not hidden
            entry["unreadableSessions"] += 1
            entry["sessionsByLabel"]["UNKNOWN"] += 1
            LOGGER.debug("unreadable session metadata %s: %s", metadata_path, error)
            continue
        label = metadata.label if metadata.label in entry["sessionsByLabel"] else "UNKNOWN"
        entry["sessionsByLabel"][label] += 1
        players.add(metadata.player_id)
        if metadata.dataset_version:
            versions.add(metadata.dataset_version)
        schemas.add(int(metadata.schema_version))
        if not metadata.complete:
            entry["incompleteSessions"] += 1
        if metadata.dropped_records:
            entry["sessionsWithDroppedRecords"] += 1
    entry["players"] = len(players)
    entry["datasetVersions"] = sorted(versions)
    entry["featureSchemaVersions"] = sorted(schemas)
    legit = entry["sessionsByLabel"]["LEGIT"]
    cheat = entry["sessionsByLabel"]["CHEAT"]
    entry["readyForTraining"] = legit >= 2 and cheat >= 2
    if entry["sessionsTotal"] == 0:
        entry["readinessNote"] = "в датасете нет сессий"
    elif not entry["readyForTraining"]:
        entry["readinessNote"] = f"нужны обе метки: LEGIT={legit}, CHEAT={cheat}"
    elif entry["sessionsByLabel"]["UNLABELED"]:
        entry["readinessNote"] = "UNLABELED-сессии исключаются из обучения"
    return entry


def dataset_versions(path: Path) -> list[str]:
    """``datasetVersion`` values recorded in session metadata. Never guessed from the folder name."""
    from ..dataset.records import read_metadata

    versions: set[str] = set()
    for metadata_path in sorted((Path(path) / "metadata").glob("session-*.json")):
        try:
            metadata = read_metadata(metadata_path)
        except Exception as error:  # noqa: BLE001 - an unreadable session is reported elsewhere
            LOGGER.debug("unreadable session metadata %s: %s", metadata_path, error)
            continue
        if metadata.dataset_version:
            versions.add(metadata.dataset_version)
    return sorted(versions)


def dataset_version_label(path: Path) -> str:
    """One version, ``mixed`` for a corpus of several, ``unknown`` when metadata records none."""
    versions = dataset_versions(path)
    if not versions:
        return "unknown"
    if len(versions) == 1:
        return versions[0]
    return "mixed:" + ",".join(versions)


def describe_dataset_roots(roots: tuple[Path, ...]) -> list[dict]:
    described = []
    for root in roots:
        datasets = []
        if root.is_dir():
            for child in sorted(root.iterdir()):
                if child.is_dir() and (child / "metadata").is_dir():
                    datasets.append(dataset_entry(child))
        described.append({"root": _posix(root), "exists": root.is_dir(), "datasets": datasets})
    return described


def list_bundles(bundle_root: Path) -> list[dict]:
    bundles = []
    if not bundle_root.is_dir():
        return bundles
    for child in sorted(bundle_root.iterdir()):
        manifest = _read_json(child / "manifest.json")
        if not manifest:
            continue
        provenance = manifest.get("provenance", {})
        bundles.append({
            "name": child.name,
            "path": _posix(child.resolve()),
            "modelVersion": manifest.get("modelVersion"),
            "modelKind": manifest.get("modelKind"),
            "window": manifest.get("window"),
            "sequenceLength": manifest.get("sequenceLength"),
            "heads": manifest.get("heads", []),
            "calibrated": bool(manifest.get("calibration")),
            "featureSchemaVersion": manifest.get("featureSchemaVersion"),
            "datasetVersion": provenance.get("datasetVersion"),
            "created": provenance.get("created"),
            "datasetRoot": provenance.get("datasetRoot"),
            "gitCommit": provenance.get("gitCommit"),
            "candidate": True,
            "deployed": False,
            "promotionStatus": PROMOTION_STATUS,
        })
    return bundles


# ---------------------------------------------------------------------------
class SubprocessRunner:
    """Default runner: spawn ``python -m aeroml.training.worker`` and follow its status file."""

    def __init__(self, python: str | None = None, module: str = "aeroml.training.worker",
                 poll_interval: float = 0.25, ml_root: Path = ML_ROOT) -> None:
        self.python = python or sys.executable
        self.module = module
        self.poll_interval = poll_interval
        self.ml_root = ml_root

    def spawn(self, job: Job, spec_path: Path, status_path: Path, result_path: Path,
              failure_path: Path, cancel_marker: Path, log_path: Path) -> subprocess.Popen:
        environment = os.environ.copy()
        existing = environment.get("PYTHONPATH", "")
        environment["PYTHONPATH"] = str(self.ml_root) + (os.pathsep + existing if existing else "")
        environment["PYTHONUNBUFFERED"] = "1"
        creationflags = 0
        if os.name == "nt":  # its own process group, so a cancel kills the whole tree
            creationflags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0) | getattr(subprocess, "CREATE_NO_WINDOW", 0)
        command = [self.python, "-m", self.module,
                   "--job", str(spec_path), "--status", str(status_path),
                   "--result", str(result_path), "--failure", str(failure_path),
                   "--cancel-marker", str(cancel_marker), "--log", str(log_path)]
        LOGGER.info("starting training job %s: %s", job.job_id, " ".join(command[:4]))
        handle = log_path.open("ab")
        try:
            return subprocess.Popen(command, cwd=str(self.ml_root), env=environment, stdout=handle,
                                    stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                    creationflags=creationflags,
                                    start_new_session=(os.name != "nt"))
        finally:
            handle.close()

    def terminate(self, pid: int | None) -> None:
        """Cancellation is a real kill of the process tree, with a graceful window first."""
        if not pid:
            return
        if os.name == "nt":
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True, check=False)
            return
        import signal

        try:
            os.killpg(os.getpgid(pid), signal.SIGTERM)
        except ProcessLookupError:
            return
        except OSError:  # pragma: no cover - defensive
            os.kill(pid, signal.SIGTERM)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                os.kill(pid, 0)
            except OSError:
                return
            time.sleep(0.2)
        try:
            os.killpg(os.getpgid(pid), signal.SIGKILL)
        except OSError:  # pragma: no cover - defensive
            pass


class JobManager:
    """Owns the queue, exactly one training slot, and the on-disk job records."""

    def __init__(self, *, dataset_roots, bundle_root: Path, state_root: Path, schema=None,
                 max_queue_depth: int = 4, max_jobs_retained: int = 32, job_timeout_seconds: int = 3600,
                 max_epochs: int = 200, max_batch_size: int = 4096, max_stride: int = 512,
                 torch_threads: int = 2, runner: SubprocessRunner | None = None,
                 poll_interval: float = 0.25) -> None:
        self.schema = schema or default_schema()
        self.dataset_roots = tuple(Path(root).resolve() for root in dataset_roots)
        self.bundle_root = Path(bundle_root).resolve()
        self.state_root = Path(state_root).resolve()
        self.jobs_root = self.state_root / "jobs"
        self.jobs_root.mkdir(parents=True, exist_ok=True)
        self.bundle_root.mkdir(parents=True, exist_ok=True)
        self.max_queue_depth = int(max_queue_depth)
        self.max_jobs_retained = max(1, int(max_jobs_retained))
        self.job_timeout_seconds = int(job_timeout_seconds)
        self.max_epochs = int(max_epochs)
        self.max_batch_size = int(max_batch_size)
        self.max_stride = int(max_stride)
        self.torch_threads = int(torch_threads)
        self.runner = runner or SubprocessRunner(poll_interval=poll_interval)
        self.poll_interval = poll_interval
        self._lock = threading.RLock()
        self._idle = threading.Condition(self._lock)
        self._queue: deque[str] = deque()
        self._jobs: dict[str, Job] = {}
        self._stopping = threading.Event()
        self._worker: threading.Thread | None = None
        self._restore()

    # -- lifecycle ----------------------------------------------------------
    def start(self) -> None:
        if self._worker and self._worker.is_alive():
            return
        self._stopping.clear()
        self._worker = threading.Thread(target=self._loop, name="training-worker", daemon=True)
        self._worker.start()

    def stop(self, timeout: float = 15.0) -> None:
        self._stopping.set()
        with self._idle:
            self._idle.notify_all()
        for job in self.list_jobs():
            if job.state in ACTIVE_STATES or job.state == QUEUED:
                self.cancel(job.job_id, reason="service_shutdown")
        if self._worker:
            self._worker.join(timeout=timeout)

    def _restore(self) -> None:
        """Jobs found mid-flight after a restart are not silently resumed."""
        for record_path in sorted(self.jobs_root.glob("*/record.json")):
            data = _read_json(record_path)
            if not data or "spec" not in data:
                continue
            try:
                request = self._request_from_record(data["spec"])
            except Exception as error:  # noqa: BLE001 - a damaged record must not stop the service
                LOGGER.warning("ignoring damaged job record %s: %s", record_path, error)
                continue
            job_id = record_path.parent.name
            job = Job(job_id=job_id, request=request, work_dir=record_path.parent,
                      bundle_dir=self.bundle_root / job_id,
                      state=data.get("state", FAILED), message=data.get("message", ""),
                      error_code=data.get("errorCode"), epoch=int(data.get("epoch", 0)),
                      total_epochs=int(data.get("totalEpochs", request.epochs)),
                      train_loss=data.get("trainLoss"), validation_loss=data.get("validationLoss"),
                      last_stage=data.get("lastStage", "restored"), progress=float(data.get("progress", 0.0)),
                      created_at=data.get("createdAt", _now()), started_at=data.get("startedAt"),
                      finished_at=data.get("finishedAt"), result=data.get("result"), failure=data.get("failure"),
                      log_file=record_path.parent / "train.log")
            if job.state in RUNNING_STATES:
                job.state = FAILED
                job.remember(FAILED, reason="service-restarted")
                job.error_code = "service_restarted"
                job.message = human("service_restarted")
                job.finished_at = job.finished_at or _now()
            job.dataset_version = data.get("datasetVersion") or request.dataset_version or ""
            self._jobs[job_id] = job
            self._write_record(job)
        with self._lock:
            self._prune_locked()

    def _request_from_record(self, spec: dict) -> JobRequest:
        dataset_path = spec.get("datasetPath") or spec.get("datasetRoot")
        return JobRequest(
            dataset=spec["dataset"], preset=spec["preset"], window=spec["window"],
            heads=tuple(spec.get("heads", ("overall", "aimAssist"))),
            feature_schema_version=int(spec["featureSchemaVersion"]), seed=int(spec["seed"]),
            epochs=int(spec.get("epochs", 30)), batch_size=int(spec.get("batchSize", 128)),
            stride=int(spec.get("stride", 4)), allow_synthetic=bool(spec.get("allowSynthetic", False)),
            include_review=bool(spec.get("includeReview", False)), notes=spec.get("notes", ""),
            dataset_path=Path(dataset_path) if dataset_path else None)

    def allowed_datasets(self) -> tuple[str, ...]:
        """The server-side allowlist: names of directories that actually hold session metadata."""
        names = []
        for root in self.dataset_roots:
            if not root.is_dir():
                continue
            for child in sorted(root.iterdir()):
                if child.is_dir() and (child / "metadata").is_dir():
                    names.append(child.name)
        return tuple(sorted(set(names)))

    def _prune_locked(self, limit: int | None = None) -> int:
        """Bound the in-memory job journal.

        Only finished jobs are dropped, oldest first, and only from memory: bundles, datasets and
        the ``record.json`` of every job stay on disk. Nothing an operator can audit is deleted to
        make room in a list.
        """
        limit = self.max_jobs_retained if limit is None else max(0, int(limit))
        removed = 0
        while len(self._jobs) > limit:
            victim = next((job for job in sorted(self._jobs.values(), key=lambda item: item.created_at)
                           if job.state_is_terminal), None)
            if victim is None:
                break
            self._jobs.pop(victim.job_id, None)
            removed += 1
        return removed

    # -- public API ---------------------------------------------------------
    def submit(self, payload) -> Job:
        request = validate_request(payload, schema=self.schema, dataset_roots=self.dataset_roots,
                                   available_datasets=self.allowed_datasets(), max_epochs=self.max_epochs,
                                   max_batch_size=self.max_batch_size, max_stride=self.max_stride)
        request.dataset_version = dataset_version_label(request.dataset_path)
        with self._lock:
            depth = sum(1 for job in self._jobs.values() if job.state == QUEUED)
            if depth >= self.max_queue_depth:
                raise RequestError("queue_full", status=429, depth=depth, maxDepth=self.max_queue_depth)
            if len(self._jobs) >= self.max_jobs_retained:
                # Retire the oldest finished job to make room. Queued and running jobs are never
                # dropped, so a journal that is full of live work is refused instead of truncated.
                self._prune_locked(self.max_jobs_retained - 1)
            if len(self._jobs) >= self.max_jobs_retained:
                raise RequestError("job_count_limit", status=429, maxJobs=self.max_jobs_retained)
            job_id = uuid.uuid4().hex[:16]
            work_dir = self.jobs_root / job_id
            work_dir.mkdir(parents=True, exist_ok=True)
            job = Job(job_id=job_id, request=request, work_dir=work_dir,
                      bundle_dir=self.bundle_root / job_id, total_epochs=request.epochs,
                      log_file=work_dir / "train.log", dataset_version=request.dataset_version,
                      message=STAGE_MESSAGES[QUEUED])
            self._jobs[job_id] = job
            self._queue.append(job_id)
            job.queue_position = len(self._queue)
            job.remember(QUEUED, position=job.queue_position)
            self._write_record(job)
            self._idle.notify_all()
            return job

    def get(self, job_id: str) -> Job:
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                raise RequestError("job_unknown", status=404, jobId=job_id)
            return job

    def list_jobs(self) -> list[Job]:
        with self._lock:
            return sorted(self._jobs.values(), key=lambda job: job.created_at, reverse=True)

    def cancel(self, job_id: str, reason: str = "operator_request") -> Job:
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                raise RequestError("job_unknown", status=404, jobId=job_id)
            if job.state_is_terminal:
                raise RequestError("job_terminal", status=409, jobId=job_id, state=job.state)
            job.cancel_requested = True
            if job.state == QUEUED:
                job.state = CANCELLED
                job.remember(CANCELLED, reason="cancelled-before-start")
                job.error_code = "cancelled"
                job.message = STAGE_MESSAGES[CANCELLED]
                job.finished_at = _now()
                job.last_stage = "cancelled"
                job.queue_position = None
                job.updated_at_millis = int(time.time() * 1000)
                self._write_record(job)
                return job
            marker = job.work_dir / "cancel.requested"
            marker.write_text(reason, encoding="utf-8")
            pid = job.pid
        # Kill outside the lock: the monitor thread needs the lock to record the exit.
        if pid:
            self.runner.terminate(pid)
        LOGGER.info("cancellation requested for job %s (%s)", job_id, reason)
        return job

    def result(self, job_id: str) -> dict:
        job = self.get(job_id)
        if job.state == COMPLETED and job.result:
            return self._result_payload(job)
        if job.state not in TERMINAL_STATES:
            raise RequestError("job_not_terminal", status=409, jobId=job_id, state=job.state)
        raise RequestError("job_no_result", status=409, jobId=job_id, state=job.state)

    def datasets(self) -> list[dict]:
        return describe_dataset_roots(self.dataset_roots)

    def models(self) -> list[dict]:
        return list_bundles(self.bundle_root)

    def queue_status(self) -> dict:
        with self._lock:
            jobs = list(self._jobs.values())
            queued = sum(1 for job in jobs if job.state == QUEUED)
            running = next((job.job_id for job in jobs if job.state in ACTIVE_STATES), None)
            position = next((job.queue_position for job in sorted(jobs, key=lambda item: item.created_at)
                             if job.state == QUEUED), None)
            return {"depth": queued, "maxDepth": self.max_queue_depth, "running": running is not None,
                    "runningJobId": running, "nextQueuePosition": position, "slots": 1,
                    "jobsRetained": len(jobs), "maxJobsRetained": self.max_jobs_retained}

    def current_job(self) -> Job | None:
        """The job the service is talking about: the running one, else the newest queued, else the last.

        A finished job stays visible until another one is submitted, so ``GET /training/status`` never
        answers "COMPLETED" and then "nothing" a second later.
        """
        with self._lock:
            jobs = sorted(self._jobs.values(), key=lambda item: item.created_at, reverse=True)
        for job in jobs:
            if job.state in ACTIVE_STATES:
                return job
        for job in jobs:
            if job.state == QUEUED:
                return job
        return jobs[0] if jobs else None

    def job_snapshot(self) -> dict:
        """Job object directly, or an IDLE placeholder when no job was ever submitted."""
        job = self.current_job()
        return job.to_dict() if job is not None else idle_snapshot(self.schema)

    def status(self) -> dict:
        """``GET /training/status``: the current job object, plus the service catalogue around it."""
        datasets = self.datasets()
        payload = self.job_snapshot()
        payload.update({
            "contractVersion": CONTRACT_VERSION,
            "service": SERVICE_NAME,
            "featureCount": self.schema.feature_count,
            # The declared heads of the schema. The job's own ``heads`` above stay untouched: they are
            # what was requested, and a client must be able to read the job object without surprises.
            "schemaHeads": list(self.schema.heads),
            "unlabeledPolicy": UNLABELED_POLICY,
            "datasetRoots": datasets,
            "datasets": [entry for root in datasets for entry in root["datasets"]],
            "jobs": [job.to_dict() for job in self.list_jobs()],
            "models": self.models(),
            "queue": self.queue_status(),
            "limits": {"maxQueueDepth": self.max_queue_depth, "maxJobsRetained": self.max_jobs_retained,
                       "maxEpochs": self.max_epochs, "jobTimeoutSeconds": self.job_timeout_seconds},
            "promotionPolicy": {"automaticDeployment": AUTOMATIC_DEPLOYMENT, "status": PROMOTION_STATUS,
                                "note": "Результат обучения — кандидат. Развёртывание выполняет человек."},
        })
        return clean(payload)

    # -- worker loop --------------------------------------------------------
    def _loop(self) -> None:
        while not self._stopping.is_set():
            with self._idle:
                while not self._queue and not self._stopping.is_set():
                    self._idle.wait(timeout=1.0)
                if self._stopping.is_set():
                    return
                job_id = self._queue.popleft()
                job = self._jobs.get(job_id)
                if job is None or job.state != QUEUED:
                    continue
            try:
                self._execute(job)
            except Exception:  # noqa: BLE001 - the loop must survive any single job
                LOGGER.exception("training job %s crashed the supervisor", job.job_id)
                with self._lock:
                    job.state = FAILED
                    job.remember(FAILED, reason="supervisor-error")
                    job.error_code = "internal"
                    job.message = human("internal", errorType="SupervisorError")
                    job.finished_at = _now()
                    self._write_record(job)

    def _execute(self, job: Job) -> None:
        work = job.work_dir
        spec_path = work / "job.json"
        status_path = work / "status.json"
        result_path = work / "result.json"
        failure_path = work / "failure.json"
        cancel_marker = work / "cancel.requested"
        log_path = job.log_file or (work / "train.log")
        for stale in (status_path, result_path, failure_path, cancel_marker):
            stale.unlink(missing_ok=True)
        spec = dict(job.request.to_worker_dict())
        spec.update({"jobId": job.job_id, "bundleDir": _posix(job.bundle_dir),
                     "torchThreads": self.torch_threads})
        spec_path.write_text(json.dumps(spec, ensure_ascii=False), encoding="utf-8")

        with self._lock:
            job.state = "AUDITING"
            job.remember("AUDITING", at=_now())
            job.started_at = _now()
            job.last_stage = "auditing"
            job.message = STAGE_MESSAGES["AUDITING"]
            job.updated_at_millis = int(time.time() * 1000)
            self._write_record(job)

        process = self.runner.spawn(job, spec_path, status_path, result_path, failure_path,
                                    cancel_marker, log_path)
        with self._lock:
            job.pid = process.pid
        deadline = time.monotonic() + self.job_timeout_seconds
        timed_out = False
        while process.poll() is None:
            self._apply_status(job, status_path)
            if time.monotonic() > deadline:
                timed_out = True
                job.cancel_requested = True
                cancel_marker.write_text("timeout", encoding="utf-8")
                self.runner.terminate(process.pid)
                break
            time.sleep(self.poll_interval)
        return_code = process.poll()
        if return_code is None:  # the kill above; wait briefly for the process to actually die
            try:
                return_code = process.wait(timeout=10)
            except subprocess.TimeoutExpired:  # pragma: no cover - defensive
                return_code = -1
        self._apply_status(job, status_path)

        failure = _read_json(failure_path)
        result = _read_json(result_path)
        with self._lock:
            job.pid = None
            job.finished_at = _now()
            job.updated_at_millis = int(time.time() * 1000)
            if timed_out:
                job.state = FAILED
                job.remember(FAILED, reason="job_timeout")
                job.error_code = "job_timeout"
                job.message = human("job_timeout", seconds=self.job_timeout_seconds)
            elif job.cancel_requested or return_code == 3:
                job.state = CANCELLED
                job.remember(CANCELLED, reason="worker-stopped")
                job.error_code = "cancelled"
                job.message = STAGE_MESSAGES[CANCELLED]
            elif return_code == 0 and result is not None:
                job.state = COMPLETED
                job.remember(COMPLETED, exitCode=return_code)
                job.result = result
                job.error_code = None
                job.message = result.get("message") or STAGE_MESSAGES[COMPLETED]
                job.progress = 1.0
                job.last_stage = "completed"
                job.epoch = job.total_epochs
            else:
                job.state = FAILED
                job.remember(FAILED, exitCode=return_code)
                job.error_code = (failure or {}).get("errorCode", "training_failed")
                job.message = (failure or {}).get("message") or human("training_failed")
                job.failure = failure
            job.last_stage = job.last_stage if job.state in (COMPLETED,) else job.last_stage
            self._write_record(job)
        LOGGER.info("job %s finished as %s (exit %s)", job.job_id, job.state, return_code)

    def _apply_status(self, job: Job, status_path: Path) -> None:
        try:
            stat = status_path.stat()
        except OSError:
            return
        if stat.st_mtime <= job.status_mtime:
            return
        payload = _read_json(status_path)
        if not payload:
            return
        with self._lock:
            if job.state_is_terminal:
                return
            job.status_mtime = stat.st_mtime
            # The worker's own stage list is the timeline: it records every stage it entered, so a
            # stage shorter than the poll interval still shows up. Merging is idempotent by name.
            known = {entry["state"] for entry in job.history}
            for entry in payload.get("stages") or []:
                name = entry.get("state") if isinstance(entry, dict) else None
                if name in STATUS_STATES and name != IDLE and name not in known:
                    known.add(name)
                    job.remember(name, at=entry.get("at"), source="worker")
            state = payload.get("state")
            # Only the supervisor decides that a job is over, and only after it has read the exit
            # code and the result file. A worker announcing COMPLETED the moment its last epoch ends
            # would otherwise publish "COMPLETED" for a job whose bundle is not on disk yet.
            if state in ACTIVE_STATES:
                # The stage never moves backwards, whatever a worker writes: progress is a bar the
                # operator watches, and a bar that jumps back is worse than a stale one.
                if STAGE_BASE_PROGRESS.get(state, 0.0) < STAGE_BASE_PROGRESS.get(job.state, 0.0):
                    state = job.state
                previous = job.state
                job.state = state
                # The worker's own report is the only source for the stage timeline: every epoch
                # callback that changes the stage leaves a line here, whether or not a client polled.
                if state != previous:
                    job.remember(state, epoch=int(payload.get("epoch") or 0),
                                 progress=payload.get("progress"))
            job.epoch = int(payload.get("epoch") or 0)
            job.total_epochs = int(payload.get("totalEpochs") or job.total_epochs)
            train_loss = payload.get("trainLoss", job.train_loss)
            if train_loss is not None:
                job.train_loss = train_loss
            validation_loss = payload.get("validationLoss", job.validation_loss)
            if validation_loss is not None:
                job.validation_loss = validation_loss
            job.last_stage = payload.get("lastStage", job.last_stage)
            if payload.get("message"):
                job.message = payload["message"]
            if payload.get("progress") is not None:
                # progress is a 0..1 bar. A worker that reports 1.2 is wrong, and passing that on
                # would make a client's progress bar overflow. The bar only ever moves forward.
                job.progress = max(job.progress, min(1.0, max(0.0, float(payload["progress"]))))
            else:
                job.progress = progress_for(job.state, job.epoch, job.total_epochs)
            job.updated_at_millis = int(time.time() * 1000)
            job.queue_position = None

    def _write_record(self, job: Job) -> None:
        try:
            (job.work_dir / "record.json").write_text(
                json.dumps(job.record(), ensure_ascii=False, allow_nan=False), encoding="utf-8")
        except (OSError, ValueError):  # pragma: no cover - the record is a convenience, not the source of truth
            LOGGER.warning("cannot write record for job %s", job.job_id)

    # -- result assembly ----------------------------------------------------
    def _result_payload(self, job: Job) -> dict:
        result = dict(job.result or {})
        bundle = Path(result.get("bundlePath", job.bundle_dir))
        audit = _read_json(bundle / "dataset_audit.json") or {}
        report = _read_json(bundle / "evaluation" / "report.json") or {}
        manifest = _read_json(bundle / "manifest.json") or {}
        provenance = manifest.get("provenance", {})
        evaluation = provenance.get("evaluation", {})
        blockers = (report.get("promotion", {}) or {}).get("blockers")
        if blockers is None:
            blockers = []
            if evaluation.get("synthetic"):
                blockers.append("synthetic pipeline-test data")
            blockers.append("human-reviewed golden and independent server validation still required")
        payload = {
            "jobId": job.job_id,
            "status": job.state,
            "state": job.state,
            "javaStatus": JAVA_STATUS.get(job.state, job.state),
            "modelVersion": result.get("modelVersion") or manifest.get("modelVersion"),
            "modelKind": manifest.get("modelKind", job.request.model_kind),
            "modelType": manifest.get("modelKind", job.request.model_kind),
            "dataset": job.request.dataset,
            "datasetVersion": (result.get("datasetVersion") or provenance.get("datasetVersion")
                               or job.dataset_version or None),
            "window": manifest.get("window", job.request.window),
            "sequenceLength": manifest.get("sequenceLength", job.request.sequence_length),
            "featureSchemaVersion": manifest.get("featureSchemaVersion", job.request.feature_schema_version),
            "heads": manifest.get("heads", list(job.request.heads)),
            "calibrated": bool(manifest.get("calibration")),
            # Temperature scaling, per head, exactly as written into the bundle. ``null`` means the
            # bundle was published uncalibrated and the risk engine will not accept its evidence.
            "calibration": manifest.get("calibration") or None,
            "bundlePath": _posix(bundle),
            "artifacts": {
                "manifest": _posix(bundle / "manifest.json"),
                "model": _posix(bundle / "model.onnx"),
                "datasetAudit": _posix(bundle / "dataset_audit.json"),
                "splitManifest": _posix(bundle / "split_manifest.json"),
                "evaluationReport": _posix(bundle / "evaluation" / "report.json"),
                "evaluationDirectory": _posix(bundle / "evaluation"),
                "log": _posix(job.log_file) if job.log_file else None,
            },
            "evaluation": result.get("evaluation", {}),
            "evaluationReport": report or None,
            "audit": {
                "sessionsTotal": audit.get("sessionsTotal"),
                "sessionsByLabel": audit.get("sessionsByLabel"),
                "quality": audit.get("quality"),
                "warnings": audit.get("warnings", []),
                "leakageFindings": audit.get("leakage", []),
                "unlabeledPolicy": UNLABELED_POLICY,
                "reviewSessionsIncluded": bool(job.request.include_review),
            },
            "smokeOnly": bool(result.get("smokeOnly")),
            "promotion": {"status": PROMOTION_STATUS, "automaticDeployment": AUTOMATIC_DEPLOYMENT,
                          "blockers": blockers,
                          "note": "Кандидат на ручную проверку. Сервис обучения ничего не развёртывает."},
            "elapsedSeconds": result.get("elapsedSeconds", job._elapsed()),
            "updatedAtMillis": job.updated_at_millis,
            "message": STAGE_MESSAGES[COMPLETED],
            "unlabeledPolicy": UNLABELED_POLICY,
        }
        return clean(payload)

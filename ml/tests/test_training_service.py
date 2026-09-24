"""The external training service: HTTP contract, job lifecycle and the limits around it.

Nothing here trains. A fake runner writes exactly the files the real worker writes
(``status.json``, ``result.json``, ``failure.json``), so the contract can be tested in milliseconds and
without torch. The real pipeline is exercised end to end in ``test_training_service_smoke.py``.

What is asserted is the wire contract the Java client is built against: endpoint shapes, field names,
the state machine, Russian operator messages and JSON that is always finite.
"""

from __future__ import annotations

import json
import os
import threading
import time
from pathlib import Path

import pytest

from aeroml.service.training_api import _handler, build_manager, create_server
from aeroml.training.contract import AUTOMATIC_DEPLOYMENT, PROMOTION_STATUS

SCHEMA_VERSION = 2
JOB_STATES = ("QUEUED", "AUDITING", "PREPARING", "TRAINING", "CALIBRATING", "EVALUATING",
              "EXPORTING", "COMPLETED", "FAILED", "CANCELLED")
TERMINAL = ("COMPLETED", "FAILED", "CANCELLED")


# ---------------------------------------------------------------------------
# A worker that writes the same files, on demand.
class FakeProcess:
    def __init__(self, runner, spec, paths):
        self._runner = runner
        self._spec = spec
        self._paths = paths
        self.pid = os.getpid()
        self.returncode: int | None = None
        self.terminated = False
        self._thread = threading.Thread(target=self._main, name="fake-worker", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def poll(self):
        return self.returncode

    def wait(self, timeout=None):
        self._thread.join(timeout)
        return self.returncode

    def _write_json(self, key: str, payload: dict) -> None:
        target = Path(self._paths[key])
        temporary = target.with_suffix(target.suffix + ".tmp")
        temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        # The real worker retries the swap because the supervisor polls these files and on Windows a
        # reader blocks a rename; the fake speaks the same protocol, including its robustness.
        for attempt in range(50):
            try:
                os.replace(temporary, target)
                return
            except OSError:
                if attempt == 49:
                    raise
                time.sleep(0.01)

    def _status(self, state: str, **fields) -> None:
        payload = {"state": state, "stage": state.lower(), "message": f"тест: {state}",
                   "updatedAtMillis": int(time.time() * 1000), "pid": self.pid}
        payload.update(fields)
        self._write_json("status", payload)

    def _result(self) -> None:
        bundle = Path(self._spec["bundleDir"])
        (bundle / "evaluation").mkdir(parents=True, exist_ok=True)
        manifest = {
            "bundleFormat": 1,
            "modelVersion": "fake-flash-v1",
            "modelKind": self._spec["preset"],
            "window": self._spec["window"],
            "sequenceLength": self._spec.get("sequenceLength", 31),
            "featureSchemaVersion": self._spec["featureSchemaVersion"],
            "featureCount": 63,
            "heads": list(self._spec["heads"]),
            "calibration": {"method": "per-head-temperature", "heads": ["overall", "aimAssist"],
                            "scalers": {"overall": {"method": "temperature", "temperature": 1.5}}},
            "provenance": {"datasetVersion": "dataset-v1", "created": "2026-01-01T00:00:00+00:00"},
        }
        (bundle / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        (bundle / "model.onnx").write_bytes(b"not really a model")
        (bundle / "dataset_audit.json").write_text(json.dumps({"sessionsTotal": 4}), encoding="utf-8")
        (bundle / "evaluation" / "report.json").write_text(
            json.dumps({"folds": {"test": {"rocAuc": 0.5}}}), encoding="utf-8")
        self._write_json("result", {
            "jobId": self._spec["jobId"], "status": "COMPLETED", "state": "COMPLETED",
            "modelVersion": "fake-flash-v1", "bundlePath": str(bundle),
            "datasetVersion": "dataset-v1", "window": self._spec["window"],
            "featureSchemaVersion": self._spec["featureSchemaVersion"],
            "heads": list(self._spec["heads"]),
            "calibration": manifest["calibration"],
            "evaluation": {"validation": {"rocAuc": 0.5}},
            "smokeOnly": True, "elapsedSeconds": 0.5, "message": "готово",
        })

    def _main(self) -> None:
        epochs = int(self._spec.get("epochs", 2))
        behaviour = self._runner.behaviour
        if behaviour == "hang":
            deadline = time.monotonic() + 30
            marker = Path(self._paths["cancel"])
            while time.monotonic() < deadline and not marker.exists():
                time.sleep(0.01)
            self.returncode = 3 if marker.exists() else 1
            return
        if behaviour == "fail":
            self._status("AUDITING")
            self._write_json("failure", {"jobId": self._spec["jobId"], "state": "FAILED",
                                         "errorCode": "no_windows",
                                         "message": "не набралось ни одного полного окна"})
            self.returncode = 1
            return
        self._status("AUDITING")
        time.sleep(self._runner.pause)
        self._status("PREPARING", totalEpochs=epochs)
        for epoch in range(1, epochs + 1):
            time.sleep(self._runner.pause)
            self._status("TRAINING", epoch=epoch, totalEpochs=epochs,
                         trainLoss=float("nan") if behaviour == "nan" else 0.9 - 0.1 * epoch,
                         validationLoss=0.8 - 0.1 * epoch,
                         progress=0.10 + 0.68 * epoch / epochs, lastStage="training")
        for state in ("CALIBRATING", "EVALUATING", "EXPORTING"):
            time.sleep(self._runner.pause)
            # The real worker carries epoch/losses forward on every status; so does the fake.
            self._status(state, epoch=epochs, totalEpochs=epochs, progress=0.9)
        if behaviour != "fail":
            self._result()
        self.returncode = 0


class FakeRunner:
    """The supervisor only needs ``spawn`` and ``terminate``; the file protocol stays the worker's."""

    def __init__(self, *, behaviour: str = "complete", pause: float = 0.05) -> None:
        self.behaviour = behaviour
        self.pause = pause
        self.spawned: list[FakeProcess] = []
        self.terminated: list[int] = []

    def spawn(self, job, spec_path, status_path, result_path, failure_path, cancel_marker, log_path):
        spec = json.loads(Path(spec_path).read_text(encoding="utf-8"))
        process = FakeProcess(self, spec, {"status": status_path, "result": result_path,
                                           "failure": failure_path, "cancel": cancel_marker})
        self.spawned.append(process)
        process.start()
        return process

    def terminate(self, pid) -> None:
        self.terminated.append(pid)


# ---------------------------------------------------------------------------
class Client:
    """Tiny proxy-free HTTP client, so the tests read like the contract they check."""

    def __init__(self, opener, base: str) -> None:
        self.opener = opener
        self.base = base

    def _call(self, request):
        import urllib.error

        try:
            with self.opener.open(request, timeout=10) as answer:
                body = answer.read().decode("utf-8")
                return answer.status, body, json.loads(body)
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8")
            return error.code, body, json.loads(body)

    def get(self, path: str, headers: dict | None = None):
        import urllib.request

        request = urllib.request.Request(self.base + path, headers=headers or {})
        return self._call(request)

    def post(self, path: str, payload: dict | None = None, headers: dict | None = None):
        import urllib.request

        head = {"Content-Type": "application/json"}
        head.update(headers or {})
        data = json.dumps(payload if payload is not None else {}).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=data, headers=head)
        return self._call(request)


@pytest.fixture
def service_factory(tmp_path, synthetic_root, loopback_opener):
    """Builds services on demand; every one is stopped again so a failing test cannot leak threads."""
    started: list[tuple] = []

    def factory(*, behaviour="complete", pause=0.05, token=None, max_queue_depth=4,
                max_jobs_retained=8, job_timeout=60, host="127.0.0.1", runner=None):
        runner = runner or FakeRunner(behaviour=behaviour, pause=pause)
        manager = build_manager(dataset_roots=[synthetic_root.parent], bundle_root=tmp_path / "bundles",
                                state_root=tmp_path / "state", max_queue_depth=max_queue_depth,
                                max_jobs_retained=max_jobs_retained, job_timeout_seconds=job_timeout,
                                torch_threads=1, runner=runner, poll_interval=0.01)
        server = create_server(manager, host, 0, token=token)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        manager.start()
        client = Client(loopback_opener, f"http://127.0.0.1:{server.server_address[1]}")
        started.append((manager, server, thread))
        return manager, client, runner

    yield factory
    for manager, server, thread in started:
        manager.stop(timeout=10)
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def dataset_name(synthetic_root) -> str:
    return synthetic_root.name


def request_body(synthetic_root, **overrides) -> dict:
    payload = {"dataset": dataset_name(synthetic_root), "preset": "flash", "window": "attack",
               "heads": ["overall", "aimAssist"], "featureSchemaVersion": SCHEMA_VERSION,
               "seed": 42, "epochs": 2, "batchSize": 8, "stride": 4,
               "allowSynthetic": True, "notes": "api test"}
    payload.update(overrides)
    return payload


def wait_for(client, job_id, states, timeout=20.0):
    """Poll until the job reaches one of ``states``; returns the last payload and everything seen."""
    seen = []
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        status, _, payload = client.get(f"/training/jobs/{job_id}")
        assert status == 200, payload
        seen.append(payload)
        if payload["status"] in states:
            return payload, seen
        time.sleep(0.01)
    raise AssertionError(f"job {job_id} never reached {states}; last={seen[-1]}")


CONTRACT_FIELDS = ("status", "jobId", "modelType", "datasetVersion", "featureSchemaVersion", "window",
                   "heads", "epoch", "totalEpochs", "progress", "trainLoss", "validationLoss",
                   "elapsedSeconds", "updatedAtMillis", "message")


# ---------------------------------------------------------------------------
# health
def test_health_describes_the_schema_the_queue_and_the_reach_of_the_socket(service_factory, synthetic_root):
    _, client, _ = service_factory()
    status, body, health = client.get("/health")
    assert status == 200
    assert health["status"] == "ok"
    assert health["featureSchemaVersion"] == SCHEMA_VERSION
    assert health["rawSchemaVersion"] == 1
    assert health["featureCount"] == 63
    assert health["localOnly"] is True and health["authRequired"] is False
    assert health["automaticDeployment"] is AUTOMATIC_DEPLOYMENT is False
    assert dataset_name(synthetic_root) in health["datasets"]
    assert health["queue"] == {"depth": 0, "maxDepth": 4, "running": False, "runningJobId": None,
                               "nextQueuePosition": None, "slots": 1, "jobsRetained": 0,
                               "maxJobsRetained": 8}
    assert health["limits"]["maxQueueDepth"] == 4
    assert "NaN" not in body


# ---------------------------------------------------------------------------
# status is a job object directly
def test_status_is_a_job_object_and_says_idle_before_any_job(service_factory):
    _, client, _ = service_factory()
    status, body, payload = client.get("/training/status")
    assert status == 200
    for field in CONTRACT_FIELDS:
        assert field in payload, field
    assert payload["status"] == "IDLE" and payload["state"] == "IDLE"
    assert payload["javaStatus"] == "IDLE"
    assert payload["jobId"] is None and payload["window"] is None and payload["heads"] == []
    assert payload["progress"] == 0.0 and payload["trainLoss"] is None
    assert isinstance(payload["updatedAtMillis"], int) and payload["updatedAtMillis"] > 0
    assert "свободен" in payload["message"]
    # service fields around the job object
    assert payload["contractVersion"] == 1
    assert payload["unlabeledPolicy"] == "excluded-from-training"
    assert payload["promotionPolicy"]["automaticDeployment"] is False
    assert payload["jobs"] == [] and payload["models"] == []
    assert "NaN" not in body


def test_status_follows_the_current_job_once_one_exists(service_factory, synthetic_root):
    _, client, _ = service_factory(behaviour="hang")
    _, _, submitted = client.post("/training/jobs", request_body(synthetic_root))
    job_id = submitted["jobId"]
    _, _, payload = client.get("/training/status")
    assert payload["jobId"] == job_id
    assert payload["status"] in JOB_STATES
    client.post(f"/training/jobs/{job_id}/cancel")


# ---------------------------------------------------------------------------
# start
def test_post_starts_a_job_and_reports_the_contract_fields(service_factory, synthetic_root):
    _, client, _ = service_factory(pause=0.4)
    status, _, answer = client.post("/training/jobs", request_body(synthetic_root))
    assert status == 202
    assert answer["jobId"] and answer["state"] in JOB_STATES
    job = answer["job"]
    for field in CONTRACT_FIELDS:
        assert field in job, field
    assert job["modelType"] == "flash" and job["window"] == "attack"
    assert job["heads"] == ["overall", "aimAssist"]
    assert job["featureSchemaVersion"] == SCHEMA_VERSION
    assert job["datasetVersion"] == "dataset-v1"
    assert job["totalEpochs"] == 2
    assert 0.0 <= job["progress"] <= 1.0
    assert job["message"] and job["message"] != job["message"].encode("ascii", "ignore").decode()
    _, _, fetched = client.get(f"/training/jobs/{answer['jobId']}")
    assert fetched["jobId"] == answer["jobId"]
    assert fetched["javaStatus"] in ("QUEUED", "TRAINING")
    done, _ = wait_for(client, answer["jobId"], TERMINAL)
    assert done["status"] == "COMPLETED"


def test_pro_preset_pairs_with_a_continuous_window(service_factory, synthetic_root):
    _, client, _ = service_factory(behaviour="hang")
    status, _, answer = client.post("/training/jobs", request_body(
        synthetic_root, preset="pro", window="continuous"))
    assert status == 202
    assert answer["job"]["modelType"] == "pro"
    assert answer["job"]["window"] == "continuous"
    assert answer["job"]["sequenceLength"] == 96
    client.post(f"/training/jobs/{answer['jobId']}/cancel")


# ---------------------------------------------------------------------------
# progress from the epoch callback
def test_progress_is_published_per_epoch_and_ends_at_one(service_factory, synthetic_root):
    _, client, runner = service_factory(pause=0.15)
    _, _, answer = client.post("/training/jobs", request_body(synthetic_root, epochs=3))
    job_id = answer["jobId"]
    done, seen = wait_for(client, job_id, TERMINAL)
    assert done["status"] == "COMPLETED"

    training = [entry for entry in seen if entry["status"] == "TRAINING"]
    assert training, "the TRAINING state was never observable"
    epochs = [entry["epoch"] for entry in training]
    assert epochs == sorted(epochs) and epochs[-1] == 3
    losses = [entry["trainLoss"] for entry in training if entry["trainLoss"] is not None]
    assert losses and losses == sorted(losses, reverse=True)
    assert all(entry["validationLoss"] is not None for entry in training)
    progress = [entry["progress"] for entry in seen]
    assert progress == sorted(progress)
    assert all(0.0 <= value <= 1.0 for value in progress)
    assert done["epoch"] == done["totalEpochs"] == 3
    assert done["progress"] == 1.0
    assert done["elapsedSeconds"] >= 0
    # The stage order is taken from the service's own timeline, not from sampling: a short stage is
    # still evidence even if no poll happened to land inside it.
    history = list(done["stateHistory"])
    assert history == ["QUEUED", "AUDITING", "PREPARING", "TRAINING", "CALIBRATING", "EVALUATING",
                       "EXPORTING", "COMPLETED"], history
    assert [entry["state"] for entry in done["stateTimeline"]] == history
    assert all(entry["at"] for entry in done["stateTimeline"])
    # ...and what polling saw must be a subsequence of that timeline, never a state outside it.
    assert all(state in history for state in {entry["status"] for entry in seen})
    assert runner.spawned, "the job never reached a worker"
    assert runner.terminated == [] and "готов" in done["message"]


def test_a_worker_that_reports_nan_sends_json_null_instead(service_factory, synthetic_root):
    _, client, _ = service_factory(behaviour="nan", pause=0.05)
    _, _, answer = client.post("/training/jobs", request_body(synthetic_root, epochs=2))
    job_id = answer["jobId"]
    seen = []
    deadline = time.monotonic() + 20
    body = ""
    while time.monotonic() < deadline:
        status, body, payload = client.get(f"/training/jobs/{job_id}")
        seen.append(payload)
        assert "NaN" not in body and "Infinity" not in body
        if payload["status"] in TERMINAL:
            break
        time.sleep(0.01)
    assert seen[-1]["status"] == "COMPLETED"
    assert any(entry["trainLoss"] is None for entry in seen), "NaN loss should surface as null"
    assert json.loads(body)["trainLoss"] is None


# ---------------------------------------------------------------------------
# result
def test_result_carries_bundle_evaluation_and_calibration(service_factory, synthetic_root):
    _, client, _ = service_factory()
    _, _, answer = client.post("/training/jobs", request_body(synthetic_root))
    job_id = answer["jobId"]
    status, _, refused = client.get(f"/training/jobs/{job_id}/result")
    assert status == 409 and "завершена" in refused["message"]
    wait_for(client, job_id, TERMINAL)
    status, body, result = client.get(f"/training/jobs/{job_id}/result")
    assert status == 200
    assert result["jobId"] == job_id and result["status"] == "COMPLETED"
    assert result["modelVersion"] == "fake-flash-v1"
    assert result["modelType"] == "flash" and result["datasetVersion"] == "dataset-v1"
    assert result["featureSchemaVersion"] == SCHEMA_VERSION
    assert result["heads"] == ["overall", "aimAssist"]
    assert result["calibrated"] is True
    assert result["calibration"]["method"] == "per-head-temperature"
    assert result["calibration"]["scalers"]["overall"]["temperature"] == 1.5
    assert result["evaluation"] == {"validation": {"rocAuc": 0.5}}
    assert Path(result["bundlePath"]).is_dir()
    assert Path(result["artifacts"]["manifest"]).is_file()
    assert Path(result["artifacts"]["log"]).parent.exists()
    assert result["smokeOnly"] is True
    assert result["promotion"]["status"] == PROMOTION_STATUS
    assert result["promotion"]["automaticDeployment"] is False
    assert result["promotion"]["blockers"], "a candidate must state why it is not promoted"
    assert "NaN" not in body


def test_a_failed_job_has_no_result_and_a_russian_reason(service_factory, synthetic_root):
    _, client, _ = service_factory(behaviour="fail")
    _, _, answer = client.post("/training/jobs", request_body(synthetic_root))
    job_id = answer["jobId"]
    done, _ = wait_for(client, job_id, TERMINAL)
    assert done["status"] == "FAILED"
    assert done["errorCode"] == "no_windows"
    assert "окна" in done["message"]
    status, _, refusal = client.get(f"/training/jobs/{job_id}/result")
    assert status == 409 and refusal["error"] == "job_no_result"


# ---------------------------------------------------------------------------
# cancel
def test_cancel_stops_the_external_worker(service_factory, synthetic_root):
    manager, client, runner = service_factory(behaviour="hang")
    _, _, answer = client.post("/training/jobs", request_body(synthetic_root))
    job_id = answer["jobId"]
    running, _ = wait_for(client, job_id, ("TRAINING", "AUDITING", "PREPARING"))
    assert running["status"] != "QUEUED"
    status, body, cancelled = client.post(f"/training/jobs/{job_id}/cancel")
    assert status == 200
    assert "Отмена" in cancelled["message"]
    final, _ = wait_for(client, job_id, TERMINAL)
    assert final["status"] == "CANCELLED"
    assert final["progress"] < 1.0
    assert final["stateHistory"] == ["QUEUED", "AUDITING", "CANCELLED"]
    assert runner.terminated == [runner.spawned[0].pid], "the worker process was never asked to stop"
    assert (manager.get(job_id).work_dir / "cancel.requested").is_file(), "the worker was never asked to stop"
    status, _, refusal = client.get(f"/training/jobs/{job_id}/result")
    assert status == 409, "a cancelled job must not offer a bundle"
    status, _, again = client.post(f"/training/jobs/{job_id}/cancel")
    assert status == 409 and again["error"] == "job_terminal"


def test_cancelling_a_queued_job_leaves_the_running_one_alone(service_factory, synthetic_root):
    manager, client, _ = service_factory(behaviour="hang", max_queue_depth=2)
    _, _, first = client.post("/training/jobs", request_body(synthetic_root))
    _, _, second = client.post("/training/jobs", request_body(synthetic_root))
    assert second["state"] == "QUEUED" and second["job"]["queuePosition"] in (1, 2)
    status, _, cancelled = client.post(f"/training/jobs/{second['jobId']}/cancel")
    assert status == 200
    queued = manager.get(second["jobId"])
    assert queued.state == "CANCELLED" and queued.started_at is None
    running = manager.get(first["jobId"])
    assert running.state not in TERMINAL
    client.post(f"/training/jobs/{first['jobId']}/cancel")


def test_cancel_of_an_unknown_job_is_a_404_in_russian(service_factory):
    _, client, _ = service_factory()
    status, _, refusal = client.post("/training/jobs/deadbeef/cancel")
    assert status == 404 and refusal["error"] == "job_unknown"
    assert "не найдена" in refusal["message"]
    assert "deadbeef" in refusal["message"]


# ---------------------------------------------------------------------------
# bounds
def test_the_queue_depth_is_bounded(service_factory, synthetic_root):
    """One job runs, one waits, the third is refused: a broken caller cannot book a week of CPU."""
    manager, client, _ = service_factory(behaviour="hang", max_queue_depth=1)
    _, _, first = client.post("/training/jobs", request_body(synthetic_root))
    deadline = time.monotonic() + 10
    while manager.get(first["jobId"]).state == "QUEUED" and time.monotonic() < deadline:
        time.sleep(0.01)
    assert manager.get(first["jobId"]).state != "QUEUED"
    _, _, second = client.post("/training/jobs", request_body(synthetic_root))
    assert second["state"] == "QUEUED"
    status, _, refusal = client.post("/training/jobs", request_body(synthetic_root))
    assert status == 429 and refusal["error"] == "queue_full"
    assert "Очередь" in refusal["message"]
    assert manager.queue_status()["depth"] == 1
    client.post(f"/training/jobs/{first['jobId']}/cancel")


def test_the_job_journal_is_bounded_and_nothing_is_deleted_from_disk(service_factory, synthetic_root):
    manager, client, _ = service_factory(max_jobs_retained=2)
    for _ in range(4):
        _, _, answer = client.post("/training/jobs", request_body(synthetic_root))
        wait_for(client, answer["jobId"], TERMINAL)
    assert len(manager.list_jobs()) <= 2
    records = list((manager.state_root / "jobs").glob("*/record.json"))
    assert len(records) == 4, "the on-disk journal is the audit trail and must survive pruning"
    assert manager.queue_status()["maxJobsRetained"] == 2


# ---------------------------------------------------------------------------
# schema and dataset refusals
@pytest.mark.parametrize("override, code, fragment", [
    ({"featureSchemaVersion": 1}, "feature_schema_mismatch", "схемы признаков"),
    ({"featureSchemaVersion": "2"}, "invalid_type", "целым числом"),
    ({"preset": "turbo"}, "unknown_preset", "Неизвестный пресет"),
    ({"preset": "pro"}, "preset_window_mismatch", "окне continuous"),
    ({"window": "continuous"}, "preset_window_mismatch", "окне attack"),
    ({"window": "legacy"}, "invalid_type", "attack или continuous"),
    ({"heads": ["aimAssist"]}, "overall_required", "«overall» обязателен"),
    ({"heads": ["overall", "wallhack"]}, "unknown_head", "не объявлен в схеме"),
    ({"heads": []}, "heads_empty", "пуст"),
    ({"seed": -1}, "seed_range", "seed"),
    ({"epochs": 0}, "epochs_range", "эпох"),
    ({"dataset": "../datasets"}, "dataset_not_allowed", "запрещены"),
    ({"dataset": "C:\\secrets"}, "dataset_not_allowed", "запрещены"),
    ({"dataset": "a/b"}, "dataset_not_allowed", "запрещены"),
    ({"dataset": "not-catalogued"}, "dataset_unknown", "неизвестен"),
    ({"datasetPath": "/etc/passwd"}, "unknown_field", "не поддерживается"),
    ({"extra": 1}, "unknown_field", "не поддерживается"),
    ({"dataset": None}, "missing_field", "отсутствует обязательное поле"),
])
def test_invalid_requests_are_refused_with_a_russian_explanation(service_factory, synthetic_root,
                                                                override, code, fragment):
    _, client, _ = service_factory()
    status, body, refusal = client.post("/training/jobs", request_body(synthetic_root, **override))
    assert status == 422, refusal
    assert refusal["error"] == code
    assert fragment in refusal["message"], refusal["message"]
    assert "NaN" not in body


def test_a_non_json_body_is_refused(service_factory):
    import urllib.request

    _, client, _ = service_factory()
    request = urllib.request.Request(client.base + "/training/jobs", data=b"not json",
                                     headers={"Content-Type": "application/json"})
    status, _, refusal = client._call(request)
    assert status == 422 and refusal["error"] == "malformed_json"
    assert "JSON" in refusal["message"]


def test_an_unknown_endpoint_lists_what_exists(service_factory):
    _, client, _ = service_factory()
    status, _, refusal = client.get("/training/nope")
    assert status == 404 and refusal["error"] == "not_found"
    assert "training/status" in refusal["message"]


# ---------------------------------------------------------------------------
# inventory
def test_datasets_and_models_endpoints_describe_what_exists(service_factory, synthetic_root):
    _, client, _ = service_factory()
    status, _, datasets = client.get("/datasets")
    assert status == 200
    entry = next(item for item in datasets["datasetRoots"][0]["datasets"]
                 if item["name"] == dataset_name(synthetic_root))
    assert entry["sessionsByLabel"]["LEGIT"] > 0 and entry["sessionsByLabel"]["CHEAT"] > 0
    assert entry["unlabeledPolicy"] == "excluded-from-training"
    assert datasets["unlabeledPolicy"] == "excluded-from-training"
    status, _, models = client.get("/models")
    assert status == 200 and models["models"] == []
    assert models["promotionPolicy"]["automaticDeployment"] is False


def test_labels_in_the_inventory_never_include_unlabeled_as_training_data(service_factory, synthetic_root):
    manager, _, _ = service_factory()
    entry = next(item for item in manager.datasets()[0]["datasets"]
                 if item["name"] == dataset_name(synthetic_root))
    assert set(entry["sessionsByLabel"]) == {"LEGIT", "CHEAT", "UNLABELED", "UNKNOWN"}
    assert entry["datasetVersions"] == ["dataset-v1"]


# ---------------------------------------------------------------------------
# reachability
def test_a_remote_bind_without_a_token_refuses_to_start(service_factory):
    manager, _, _ = service_factory()
    with pytest.raises(SystemExit) as error:
        _handler(manager, host="0.0.0.0")
    assert "token" in str(error.value)


def test_a_configured_token_is_required_on_every_endpoint(service_factory, synthetic_root):
    _, client, _ = service_factory(token="s3cret")
    for path in ("/health", "/training/status", "/datasets", "/models"):
        status, _, refusal = client.get(path)
        assert status == 401 and refusal["error"] == "unauthorized", path
        assert "токен" in refusal["message"].lower()
    status, _, refusal = client.post("/training/jobs", request_body(synthetic_root))
    assert status == 401 and refusal["error"] == "unauthorized"

    status, _, wrong = client.get("/health", headers={"Authorization": "Bearer nope"})
    assert status == 401 and wrong["error"] == "unauthorized"

    status, _, health = client.get("/health", headers={"Authorization": "Bearer s3cret"})
    assert status == 200 and health["authRequired"] is True
    status, _, answer = client.post("/training/jobs", request_body(synthetic_root),
                                    headers={"X-Auth-Token": "s3cret"})
    assert status == 202
    client.post(f"/training/jobs/{answer['jobId']}/cancel", headers={"X-Auth-Token": "s3cret"})


# ---------------------------------------------------------------------------
# restart behaviour
def test_a_job_that_vanished_with_the_service_is_reported_failed_not_resumed(service_factory, synthetic_root):
    manager, client, _ = service_factory(behaviour="hang")
    _, _, answer = client.post("/training/jobs", request_body(synthetic_root))
    job_id = answer["jobId"]
    wait_for(client, job_id, ("TRAINING", "AUDITING", "PREPARING"))
    job = manager.get(job_id)
    job.state = "TRAINING"
    job.result = None
    manager._write_record(job)

    restarted = build_manager(dataset_roots=manager.dataset_roots, bundle_root=manager.bundle_root,
                             state_root=manager.state_root, max_jobs_retained=8,
                             torch_threads=1, runner=FakeRunner(behaviour="hang"))
    restored = restarted.get(job_id)
    assert restored.state == "FAILED"
    assert restored.error_code == "service_restarted"
    assert "перезапущен" in restored.message
    client.post(f"/training/jobs/{job_id}/cancel")

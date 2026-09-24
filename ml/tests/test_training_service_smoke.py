"""Real end-to-end smoke through the training service: HTTP job -> torch -> ONNX -> prediction.

This is the one test that actually trains. It runs the *real* worker subprocess (torch), on a
synthetic corpus generated into a temporary directory, over the *real* HTTP API on loopback, and then
loads the produced bundle with the real onnxruntime backend and asks it for a prediction on a real
feature window taken from that corpus.

Nothing here says anything about model quality: a corpus this small cannot support a claim about
detection. The claim is narrower and testable — the service turns a dataset into a candidate bundle
whose ONNX graph ranks the data it was trained on, and every artefact along the way is on disk.

Artefacts are kept in ``ml/smoke-output`` (bundles, service state, logs, a summary JSON) so a human
can look at what the machine produced without re-running anything. The corpus itself stays in a
temporary directory, like every other test's data.
"""

from __future__ import annotations

import json
import sys
import threading
import time
from pathlib import Path

import pytest

torch = pytest.importorskip("torch", reason="the smoke run trains for real")
pytest.importorskip("onnxruntime", reason="the smoke run verifies and serves ONNX")

from aeroml.dataset.records import load_dataset  # noqa: E402
from aeroml.dataset.windows import attack_windows  # noqa: E402
from aeroml.export.bundle import Bundle  # noqa: E402
from aeroml.service.runtime import InferenceService, LoadedModel  # noqa: E402
from aeroml.service.training_api import build_manager, create_server  # noqa: E402
from aeroml.tools.make_synthetic import generate_dataset  # noqa: E402

SMOKE_ROOT = Path(__file__).resolve().parents[1] / "smoke-output"
JOB_TIMEOUT_SECONDS = 1800
WAIT_TIMEOUT_SECONDS = 1500


def _http(client, method: str, path: str, payload: dict | None = None):
    import urllib.error
    import urllib.request

    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    request = urllib.request.Request(client.base + path, data=data, headers=headers, method=method)
    try:
        with client.opener.open(request, timeout=60) as answer:
            return answer.status, json.loads(answer.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8"))


class _Client:
    def __init__(self, opener, base):
        self.opener = opener
        self.base = base


def test_a_real_job_trains_calibrates_exports_and_predicts(tmp_path, loopback_opener, schema):
    corpus = tmp_path / "synthetic"
    generate_dataset(corpus, players=8, seconds=16.0, seed=11)

    bundles = SMOKE_ROOT / "bundles"
    state = SMOKE_ROOT / "state"
    manager = build_manager(dataset_roots=[corpus.parent], bundle_root=bundles, state_root=state,
                            max_queue_depth=2, max_jobs_retained=4,
                            job_timeout_seconds=JOB_TIMEOUT_SECONDS, torch_threads=4,
                            poll_interval=0.1)
    server = create_server(manager, "127.0.0.1", 0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    manager.start()
    client = _Client(loopback_opener, f"http://127.0.0.1:{server.server_address[1]}")
    timeline = []
    try:
        status, health = _http(client, "GET", "/health")
        assert status == 200, health
        assert corpus.name in health["datasets"], "the corpus is not in the server's allowlist"

        status, answer = _http(client, "POST", "/training/jobs", {
            "dataset": corpus.name, "preset": "flash", "window": "attack",
            "heads": ["overall", "aimAssist"], "featureSchemaVersion": schema.version,
            "seed": 42, "epochs": 4, "batchSize": 32, "stride": 4,
            "allowSynthetic": True, "notes": "smoke: real training through the service",
        })
        assert status == 202, answer
        job_id = answer["jobId"]

        deadline = time.monotonic() + WAIT_TIMEOUT_SECONDS
        last = None
        while time.monotonic() < deadline:
            _, last = _http(client, "GET", f"/training/jobs/{job_id}")
            timeline.append({"status": last["status"], "epoch": last["epoch"],
                             "progress": last["progress"], "trainLoss": last["trainLoss"],
                             "validationLoss": last["validationLoss"], "message": last["message"]})
            if last["status"] in ("COMPLETED", "FAILED", "CANCELLED"):
                break
            time.sleep(0.5)
        assert last is not None and last["status"] == "COMPLETED", last
        assert last["stateHistory"] == ["QUEUED", "AUDITING", "PREPARING", "TRAINING", "CALIBRATING",
                                        "EVALUATING", "EXPORTING", "COMPLETED"], last["stateHistory"]
        assert last["totalEpochs"] == 4 and last["epoch"] == 4 and last["progress"] == 1.0
        assert last["trainLoss"] is not None and last["validationLoss"] is not None
        assert all(0.0 <= entry["progress"] <= 1.0 for entry in timeline)
        assert any(0.0 < entry["progress"] < 1.0 for entry in timeline), "no real progress was published"
        progress = [entry["progress"] for entry in timeline]
        assert progress == sorted(progress), f"the progress bar went backwards: {progress}"

        status, result = _http(client, "GET", f"/training/jobs/{job_id}/result")
        assert status == 200, result
        assert result["status"] == "COMPLETED"
        assert result["promotion"]["automaticDeployment"] is False
        assert result["promotion"]["blockers"], "a candidate must say why it is not deployed"
        bundle_dir = Path(result["bundlePath"])
        assert bundle_dir == bundles / job_id and bundle_dir.is_dir()

        # The evaluation and the calibration are the pipeline's, not the service's invention.
        report = json.loads((bundle_dir / "evaluation" / "report.json").read_text(encoding="utf-8"))
        assert report["fold"] == "test" and report["modelVersion"] == result["modelVersion"]
        assert 0.0 <= report["windowMetrics"]["rocAuc"] <= 1.0
        bundle = Bundle.load(bundle_dir, schema)
        assert set(bundle.manifest.provenance.evaluation["folds"]) >= {"validation", "test"}
        assert bundle.manifest.feature_schema_version == schema.version
        assert bundle.manifest.model_kind == "flash" and bundle.manifest.window == "attack"
        assert bundle.manifest.calibration, "the bundle carries no calibration"
        assert bundle.manifest.calibration["method"] in ("per-head", "per-head-temperature")
        methods = {scaler["method"] for scaler in bundle.manifest.calibration["scalers"].values()}
        assert methods <= {"platt", "temperature"}, methods

        # Real inference: onnxruntime, the exported graph, and a window taken from the corpus.
        model = LoadedModel.load(bundle_dir, schema)
        service = InferenceService({"flash": model}, schema)
        sessions = load_dataset(corpus, schema)
        index = attack_windows(sessions, schema=schema)
        assert len(index) > 0, "the corpus contains no attack window to predict on"
        windows = index.encode(schema=schema)
        payload = {
            "protocolVersion": 1, "featureSchemaVersion": schema.version, "requestId": 7,
            "model": "flash", "window": "attack", "sequenceLength": int(windows.shape[1]),
            "featureCount": int(windows.shape[2]),
            "features": [float(value) for value in windows[0].reshape(-1)],
        }
        prediction = service.predict(payload)
        assert prediction["modelVersion"] == bundle.manifest.model_version
        assert set(prediction["heads"]) == set(bundle.manifest.heads)
        assert all(isinstance(value, float) and 0.0 <= value <= 1.0
                   for value in prediction["heads"].values())
        assert prediction["calibrated"] is True

        SMOKE_ROOT.mkdir(parents=True, exist_ok=True)
        (SMOKE_ROOT / "smoke-summary.json").write_text(json.dumps({
            "corpus": {"players": 8, "seconds": 16.0, "windows": int(len(index)),
                       "temporaryDirectory": str(corpus)},
            "job": last,
            "result": result,
            "bundle": {"path": str(bundle_dir),
                       "manifest": json.loads((bundle_dir / "manifest.json").read_text(encoding="utf-8")),
                       "heads": list(bundle.manifest.heads),
                       "modelVersion": bundle.manifest.model_version,
                       "calibrationMethod": bundle.manifest.calibration["method"]},
            "inference": {"protocolVersion": prediction["protocolVersion"], "heads": prediction["heads"],
                          "calibrated": prediction["calibrated"], "device": "onnxruntime/CPU"},
            "timeline": timeline,
        }, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    finally:
        manager.stop(timeout=30)
        server.shutdown()
        server.server_close()
        thread.join(timeout=10)

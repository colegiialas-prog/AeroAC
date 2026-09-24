from __future__ import annotations

import json
import threading
import urllib.error
import urllib.request

import numpy as np
import pytest

from aeroml.evaluation.calibration import TemperatureScaler
from aeroml.export.bundle import Bundle, BundleManifest, Provenance, write_manifest
from aeroml.service.runtime import (
    ConstantBackend,
    InferenceService,
    LoadedModel,
    ProtocolError,
    PROTOCOL_VERSION,
)
from aeroml.service.server import create_server


def make_bundle(tmp_path, schema, *, kind="flash", window="attack", length=31,
                heads=("overall", "aimAssist"), calibrated=False, name="bundle"):
    path = tmp_path / name
    manifest = BundleManifest(
        model_version=f"test-{kind}-v1",
        model_kind=kind,
        window=window,
        sequence_length=length,
        feature_schema_version=schema.version,
        channels=schema.channel_names,
        heads=tuple(heads),
        normalization={
            "featureSchemaVersion": schema.version,
            "channels": list(schema.channel_names),
            "mean": [0.0] * schema.feature_count,
            "std": [1.0] * schema.feature_count,
            "knownCounts": [1] * schema.feature_count,
        },
        calibration={"method": "temperature", "temperature": 2.0} if calibrated else None,
        provenance=Provenance(notes="unit test"),
    )
    write_manifest(path, manifest)
    return Bundle.load(path, schema)


def make_service(tmp_path, schema, *, logits=(2.0, 1.0), **kwargs):
    bundle = make_bundle(tmp_path, schema, **kwargs)
    model = LoadedModel(
        bundle=bundle,
        backend=ConstantBackend(np.asarray(logits, dtype=np.float64)),
        calibration=TemperatureScaler.from_dict(bundle.manifest.calibration) if bundle.manifest.calibration else None,
        mean=np.zeros(schema.feature_count, dtype=np.float32),
        std=np.ones(schema.feature_count, dtype=np.float32),
    )
    return InferenceService({bundle.manifest.model_kind: model}, schema)


def request_body(schema, *, length=31, model="flash", window="attack", **overrides):
    payload = {
        "protocolVersion": PROTOCOL_VERSION,
        "featureSchemaVersion": schema.version,
        "requestId": 17,
        "model": model,
        "window": window,
        "sequenceLength": length,
        "featureCount": schema.feature_count,
        "features": [0.0] * (length * schema.feature_count),
    }
    payload.update(overrides)
    return payload


def test_a_valid_request_is_answered_with_named_heads(tmp_path, schema):
    service = make_service(tmp_path, schema)
    response = service.predict(request_body(schema))
    assert response["protocolVersion"] == PROTOCOL_VERSION
    assert response["featureSchemaVersion"] == schema.version
    assert response["requestId"] == 17
    assert response["model"] == "flash"
    assert response["modelVersion"] == "test-flash-v1"
    assert set(response["heads"]) == {"overall", "aimAssist"}
    assert 0.0 <= response["heads"]["overall"] <= 1.0
    assert response["calibrated"] is False


def test_fastapi_parses_a_body_and_keeps_inference_off_the_event_loop(tmp_path, schema):
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient
    from aeroml.service.app import create_app
    service = make_service(tmp_path, schema)
    original = service.predict

    def predict(payload):
        import asyncio
        with pytest.raises(RuntimeError):
            asyncio.get_running_loop()
        return original(payload)

    service.predict = predict
    with TestClient(create_app(service)) as client:
        response = client.post("/predict", json=request_body(schema))
        assert response.status_code == 200, response.text
        assert response.json()["requestId"] == 17
        assert client.post("/predict", content=b"not json").status_code == 422
        assert client.get("/health").status_code == 200


def test_health_describes_what_is_actually_loaded(tmp_path, schema):
    service = make_service(tmp_path, schema)
    health = service.health()
    assert health["featureSchemaVersion"] == schema.version
    assert health["featureCount"] == schema.feature_count
    assert health["models"]["flash"]["sequenceLength"] == 31
    assert health["models"]["flash"]["calibrated"] is False


def test_calibration_is_applied_and_declared(tmp_path, schema):
    plain = make_service(tmp_path, schema, logits=(2.0, 2.0), name="plain")
    calibrated = make_service(tmp_path, schema, logits=(2.0, 2.0), calibrated=True, name="calibrated")
    uncalibrated_value = plain.predict(request_body(schema))["heads"]["overall"]
    response = calibrated.predict(request_body(schema))
    assert response["calibrated"] is True
    # Temperature 2 halves the logit, so an over-confident score moves towards 0.5.
    assert response["heads"]["overall"] < uncalibrated_value


@pytest.mark.parametrize("override, fragment", [
    ({"protocolVersion": 2}, "protocolVersion"),
    ({"featureSchemaVersion": 99}, "featureSchemaVersion"),
    ({"featureCount": 7}, "featureCount"),
    ({"model": "pro"}, "not loaded"),
    ({"sequenceLength": 30}, "expects 31"),
    ({"sequenceLength": 0}, "outside"),
    ({"window": "continuous"}, "trained on attack"),
])
def test_incompatible_requests_are_refused_permanently(tmp_path, schema, override, fragment):
    service = make_service(tmp_path, schema)
    payload = request_body(schema)
    if "sequenceLength" in override and override["sequenceLength"] > 0:
        payload["features"] = [0.0] * (override["sequenceLength"] * schema.feature_count)
    payload.update(override)
    with pytest.raises(ProtocolError) as error:
        service.predict(payload)
    assert error.value.status == 422
    assert fragment in error.value.message


def test_a_missing_field_is_named(tmp_path, schema):
    service = make_service(tmp_path, schema)
    payload = request_body(schema)
    del payload["features"]
    with pytest.raises(ProtocolError, match="missing features"):
        service.predict(payload)


def test_a_wrong_sized_or_non_finite_payload_is_refused(tmp_path, schema):
    service = make_service(tmp_path, schema)
    short = request_body(schema)
    short["features"] = short["features"][:-1]
    with pytest.raises(ProtocolError, match="must hold"):
        service.predict(short)
    infinite = request_body(schema)
    infinite["features"][0] = float("nan")
    with pytest.raises(ProtocolError, match="finite"):
        service.predict(infinite)


def test_a_bundle_whose_kind_disagrees_with_its_slot_is_refused(tmp_path, schema):
    bundle = make_bundle(tmp_path, schema, kind="flash")
    model = LoadedModel(bundle, ConstantBackend(np.zeros(2)), None,
                        np.zeros(schema.feature_count, dtype=np.float32),
                        np.ones(schema.feature_count, dtype=np.float32))
    with pytest.raises(ValueError, match="declares kind"):
        InferenceService({"pro": model}, schema)


def test_a_bundle_for_another_feature_schema_is_refused(tmp_path, schema):
    path = tmp_path / "foreign"
    manifest = make_bundle(tmp_path, schema, name="foreign").manifest.to_dict()
    manifest["featureSchemaVersion"] = 99
    (path / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="feature schema"):
        Bundle.load(path, schema)


def test_a_bundle_without_an_overall_head_is_refused(tmp_path, schema):
    path = tmp_path / "headless"
    manifest = make_bundle(tmp_path, schema, name="headless").manifest.to_dict()
    manifest["heads"] = ["aimAssist"]
    (path / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="overall"):
        Bundle.load(path, schema)


def test_normalisation_keeps_unknown_entries_at_zero(tmp_path, schema):
    bundle = make_bundle(tmp_path, schema)
    mean = np.full(schema.feature_count, 5.0, dtype=np.float32)
    std = np.full(schema.feature_count, 2.0, dtype=np.float32)
    model = LoadedModel(bundle, ConstantBackend(np.zeros(2)), None, mean, std)
    windows = np.zeros((1, 31, schema.feature_count), dtype=np.float32)
    scaled = model.normalize(windows, schema)
    channel = schema.index("PING_MS")
    assert scaled[0, 0, channel] == 0.0
    windows[0, 0, schema.mask_index("PING_MS")] = 1.0
    windows[0, 0, channel] = 9.0
    scaled = model.normalize(windows, schema)
    assert scaled[0, 0, channel] == pytest.approx((9.0 - 5.0) / 2.0)


def test_http_round_trip_over_the_stdlib_server(tmp_path, schema):
    service = make_service(tmp_path, schema)
    server = create_server(service, "127.0.0.1", 0)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=5) as answer:
            assert json.loads(answer.read())["featureSchemaVersion"] == schema.version
        body = json.dumps(request_body(schema)).encode()
        post = urllib.request.Request(f"http://127.0.0.1:{port}/predict", data=body,
                                      headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(post, timeout=5) as answer:
            payload = json.loads(answer.read())
        assert payload["requestId"] == 17
        assert "overall" in payload["heads"]

        broken = json.dumps(request_body(schema, protocolVersion=2)).encode()
        request = urllib.request.Request(f"http://127.0.0.1:{port}/predict", data=broken,
                                         headers={"Content-Type": "application/json"})
        with pytest.raises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(request, timeout=5)
        assert error.value.code == 422

        garbage = urllib.request.Request(f"http://127.0.0.1:{port}/predict", data=b"not json",
                                         headers={"Content-Type": "application/json"})
        with pytest.raises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(garbage, timeout=5)
        assert error.value.code == 422

        with pytest.raises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/nope", timeout=5)
        assert error.value.code == 404
    finally:
        server.shutdown()
        server.server_close()


def _request_fixture():
    from pathlib import Path

    path = Path(__file__).resolve().parent / "data" / "request_golden.json"
    if not path.is_file():
        pytest.skip("request fixture missing; run python -m aeroml.tools.make_request_golden")
    return json.loads(path.read_text(encoding="utf-8"))


def test_the_canonical_java_request_is_served(tmp_path, schema):
    """Companion to Java InferenceRequestGoldenTest: same object, both sides of the wire."""
    fixture = _request_fixture()
    assert fixture["featureSchemaVersion"] == schema.version
    assert fixture["featureCount"] == schema.feature_count
    assert len(fixture["features"]) == fixture["sequenceLength"] * schema.feature_count
    service = make_service(tmp_path, schema, length=fixture["sequenceLength"], name="golden")
    payload = {key: value for key, value in fixture.items() if key != "note"}
    response = service.predict(payload)
    assert response["requestId"] == fixture["requestId"]
    assert response["model"] == fixture["model"]
    assert 0.0 <= response["heads"]["overall"] <= 1.0

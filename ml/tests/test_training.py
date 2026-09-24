"""End-to-end Phase 3 run on synthetic data.

These tests need torch (and onnxruntime for the export check) and skip cleanly without them, so the
dataset pipeline stays testable on a machine that has neither. What they assert is the pipeline's
contract, not model quality: a bundle is produced, it declares the schema it was built for, its
ONNX graph agrees with the weights, and the service can serve it.
"""

from __future__ import annotations

import json

import numpy as np
import pytest

torch = pytest.importorskip("torch", reason="training needs torch")

from aeroml.dataset.normalize import Normalizer  # noqa: E402
from aeroml.export.bundle import Bundle  # noqa: E402
from aeroml.models.tcn import FLASH, PRO, AeroTemporalNet, ModelConfig, build  # noqa: E402
from aeroml.training.config import HEAD_FAMILIES, TrainingConfig, head_positive  # noqa: E402
from aeroml.training.train import run  # noqa: E402


def test_model_shapes_and_size(schema):
    model = build(FLASH, schema.feature_count)
    windows = torch.zeros(3, FLASH.sequence_length, schema.feature_count)
    logits = model(windows)
    assert logits.shape == (3, 2)
    probabilities = model.probabilities(windows)
    assert set(probabilities) == {"overall", "aimAssist"}
    assert 100_000 <= model.parameter_count() <= 500_000, model.parameter_count()
    assert model.config.receptive_field >= FLASH.sequence_length


def test_pro_is_larger_and_sees_a_longer_window(schema):
    flash = build(FLASH, schema.feature_count)
    pro = build(PRO, schema.feature_count)
    assert pro.parameter_count() > flash.parameter_count()
    assert pro.config.sequence_length > flash.config.sequence_length


def test_heads_are_data_driven_not_hard_coded(schema):
    model = AeroTemporalNet(ModelConfig(schema.feature_count, 16, heads=("overall", "aimAssist", "killAura")))
    assert model(torch.zeros(2, 16, schema.feature_count)).shape == (2, 3)
    assert model.config.to_dict()["heads"] == ["overall", "aimAssist", "killAura"]


def test_head_family_mapping_is_explicit():
    assert head_positive("overall", "CHEAT", "anything-at-all")
    assert head_positive("aimAssist", "CHEAT", "aimassist")
    assert head_positive("aimAssist", "CHEAT", "aim-assist-smooth")
    assert not head_positive("aimAssist", "CHEAT", "killaura")
    assert not head_positive("overall", "LEGIT", None)
    assert not head_positive("killAura", "LEGIT", "killaura")
    assert "overall" in HEAD_FAMILIES


def test_a_head_without_positive_training_labels_is_not_published(tmp_path, synthetic_root, schema):
    pytest.importorskip("onnxruntime", reason="export verification needs onnxruntime")
    output = tmp_path / "bundle"
    config = TrainingConfig(
        dataset=synthetic_root, output=output, epochs=2, batch_size=32,
        heads=("overall", "aimAssist", "killAura"), notes="unit test", allow_synthetic=True,
    )
    run(config, schema)
    bundle = Bundle.load(output, schema)
    # The synthetic corpus only contains aim assist, so a kill aura head would be a fabricated "no".
    assert "killAura" not in bundle.manifest.heads
    assert "overall" in bundle.manifest.heads
    assert bundle.manifest.provenance.evaluation["headsNotTrained"] == ["killAura"]


def test_training_produces_a_servable_bundle(tmp_path, synthetic_root, schema):
    pytest.importorskip("onnxruntime", reason="export verification needs onnxruntime")
    output = tmp_path / "flash"
    config = TrainingConfig(dataset=synthetic_root, output=output, epochs=3, batch_size=32,
                            seed=5, notes="unit test", allow_synthetic=True)
    result = run(config, schema)
    bundle = Bundle.load(output, schema)
    assert bundle.model_path.is_file()
    assert bundle.manifest.feature_schema_version == schema.version
    assert tuple(bundle.manifest.channels) == schema.channel_names
    assert bundle.manifest.sequence_length == 31
    assert bundle.manifest.window == "attack"
    assert result["modelVersion"] == bundle.manifest.model_version

    provenance = bundle.manifest.provenance
    assert provenance.training_config["epochs"] == 3
    assert provenance.split_manifest["strategy"] == "unknown-client"
    assert "validation" in provenance.evaluation["folds"]
    mean, std = bundle.normalization_arrays()
    assert mean.size == schema.feature_count
    assert np.all(std > 0)

    from aeroml.service.runtime import InferenceService, LoadedModel

    model = LoadedModel.load(output, schema)
    service = InferenceService({"flash": model}, schema)
    payload = {
        "protocolVersion": 1,
        "featureSchemaVersion": schema.version,
        "requestId": 1,
        "model": "flash",
        "window": "attack",
        "sequenceLength": 31,
        "featureCount": schema.feature_count,
        "features": [0.0] * (31 * schema.feature_count),
    }
    response = service.predict(payload)
    assert 0.0 <= response["heads"]["overall"] <= 1.0
    assert response["modelVersion"] == bundle.manifest.model_version


def test_export_refuses_a_graph_that_disagrees_with_its_weights(tmp_path, schema):
    pytest.importorskip("onnxruntime", reason="needs onnxruntime")
    from aeroml.export.bundle import Provenance
    from aeroml.export.onnx_export import export

    model = build(FLASH, schema.feature_count)
    windows = np.zeros((4, FLASH.sequence_length, schema.feature_count), dtype=np.float32)
    normalizer = Normalizer.fit(windows, schema)
    bundle = export(model, tmp_path / "ok", model_version="v1", model_kind="flash", window="attack",
                    normalizer=normalizer, calibration=None, provenance=Provenance(), schema=schema)
    assert bundle.model_path.is_file()
    with pytest.raises(RuntimeError, match="differs from PyTorch"):
        export(model, tmp_path / "strict", model_version="v1", model_kind="flash", window="attack",
               normalizer=normalizer, calibration=None, provenance=Provenance(), schema=schema,
               tolerance=-1.0)


def test_a_model_built_for_another_schema_cannot_be_exported(tmp_path, schema):
    from aeroml.export.bundle import Provenance
    from aeroml.export.onnx_export import export

    model = AeroTemporalNet(ModelConfig(schema.feature_count - 1, 16))
    windows = np.zeros((2, 16, schema.feature_count), dtype=np.float32)
    with pytest.raises(ValueError, match="channels"):
        export(model, tmp_path / "mismatch", model_version="v1", model_kind="flash", window="attack",
               normalizer=Normalizer.fit(windows, schema), calibration=None,
               provenance=Provenance(), schema=schema)


def test_training_config_rejects_a_head_without_a_family(tmp_path):
    with pytest.raises(ValueError, match="no family mapping"):
        TrainingConfig(dataset=tmp_path, output=tmp_path, heads=("overall", "wallhack"))
    with pytest.raises(ValueError, match="overall head is required"):
        TrainingConfig(dataset=tmp_path, output=tmp_path, heads=("aimAssist",))


def test_training_config_round_trips_through_json(tmp_path):
    config = TrainingConfig(dataset=tmp_path / "ds", output=tmp_path / "out", epochs=7)
    path = tmp_path / "config.json"
    path.write_text(json.dumps(config.to_dict()), encoding="utf-8")
    restored = TrainingConfig.from_json(path)
    assert restored.epochs == 7
    assert restored.heads == config.heads

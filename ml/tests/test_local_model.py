"""Python half of the in-JVM model contract; Java ``LocalModelGoldenTest`` replays the same bundle."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from aeroml.export.bundle import Bundle
from aeroml.export.java_weights import export_weights, load_weights

FIXTURE = Path(__file__).resolve().parent / "data" / "local_model"


def test_the_fixture_is_a_loadable_bundle_with_intact_weights(schema):
    bundle = Bundle.load(FIXTURE, schema)
    index, tensors = load_weights(FIXTURE)
    assert index["architecture"]["featureCount"] == schema.feature_count == len(bundle.manifest.channels)
    assert index["architecture"]["heads"] == list(bundle.manifest.heads)
    assert tensors["project.0.weight"].shape == (16, schema.feature_count, 1)
    assert bundle.manifest.calibration["priors"]["overall"] == 0.25


def test_pytorch_still_computes_the_fixture_logits(schema):
    torch = pytest.importorskip("torch")
    from aeroml.models.tcn import AeroTemporalNet, ModelConfig

    index, tensors = load_weights(FIXTURE)
    model = AeroTemporalNet(ModelConfig.from_dict(index["architecture"]))
    model.load_state_dict({name: torch.as_tensor(np.array(value)) for name, value in tensors.items()})
    model.eval()
    manifest = json.loads((FIXTURE / "manifest.json").read_text(encoding="utf-8"))
    mean = np.asarray(manifest["normalization"]["mean"])
    std = np.asarray(manifest["normalization"]["std"])
    cases = json.loads((FIXTURE / "cases.json").read_text(encoding="utf-8"))
    for case in cases["cases"]:
        window = np.asarray(case["features"], dtype=np.float32).reshape(cases["sequenceLength"], -1)
        known = np.ones_like(window, dtype=np.float64)
        mask = schema.value_count
        for channel, value in enumerate(schema.values):
            if value.nullable:
                known[:, channel] = window[:, mask]
                mask += 1
        scaled = (window - mean) / std
        normalised = np.where(np.arange(schema.feature_count) < schema.value_count, scaled * known, window)
        with torch.no_grad():
            logits = model(torch.as_tensor(normalised[None].astype(np.float32))).numpy()[0]
        np.testing.assert_allclose(logits, case["logits"], atol=1e-5)


def test_export_refuses_non_finite_weights(tmp_path):
    torch = pytest.importorskip("torch")
    from aeroml.models.tcn import AeroTemporalNet, ModelConfig

    model = AeroTemporalNet(ModelConfig(feature_count=4, sequence_length=3, width=8, blocks=1, groups=4))
    with torch.no_grad():
        next(model.parameters()).fill_(float("nan"))
    with pytest.raises(ValueError, match="non-finite"):
        export_weights(model, tmp_path)

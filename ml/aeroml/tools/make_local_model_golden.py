"""Regenerates the in-JVM model fixture.

A deliberately small temporal ConvNet (width 16, two blocks) with seeded, non-trivial weights is
written as a real bundle — manifest, ``weights.json``, ``model.weights`` — together with a handful of
encoded windows and what PyTorch plus the service's normalisation and calibration make of them.
Java ``LocalModelGoldenTest`` loads the same directory and must reproduce every logit and
probability; ``tests/test_local_model.py`` checks the Python side of the same file.

    python -m aeroml.tools.make_local_model_golden
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

import numpy as np

from ..evaluation.calibration import HeadCalibration, PlattScaler, TemperatureScaler
from ..export.bundle import BundleManifest, Provenance, write_manifest
from ..export.java_weights import export_weights
from ..schema import default_schema

SEQUENCE_LENGTH = 9
HEADS = ("overall", "aimAssist")
CASES = 4


def build(target: Path) -> dict:
    import torch
    from ..models.tcn import AeroTemporalNet, ModelConfig

    schema = default_schema()
    torch.manual_seed(20260927)
    rng = np.random.default_rng(20260927)
    config = ModelConfig(feature_count=schema.feature_count, sequence_length=SEQUENCE_LENGTH, heads=HEADS,
                         width=16, blocks=2, kernel_size=3, dropout=0.1, groups=8)
    model = AeroTemporalNet(config)
    with torch.no_grad():
        # Default initialisation leaves GroupNorm at identity and heads near zero; perturb every
        # tensor so each code path in the Java port actually moves the logits.
        for parameter in model.parameters():
            parameter.add_(torch.randn_like(parameter) * 0.3)
    model.eval()

    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    export_weights(model, target)

    mean = np.zeros(schema.feature_count)
    std = np.ones(schema.feature_count)
    mean[:schema.value_count] = rng.normal(0.0, 2.0, schema.value_count)
    std[:schema.value_count] = rng.uniform(0.5, 4.0, schema.value_count)
    calibration = HeadCalibration(
        HEADS,
        (PlattScaler(slope=0.8, bias=-1.3, fitted_on=100, nll_before=1.0, nll_after=0.9, ece_before=0.1, ece_after=0.05),
         TemperatureScaler(temperature=1.7, fitted_on=100, nll_before=1.0, nll_after=0.9, ece_before=0.1, ece_after=0.05)),
        {"overall": 0.25, "aimAssist": 0.25},
    )
    manifest = BundleManifest(
        model_version="golden-local-v1", model_kind="flash", window="attack", sequence_length=SEQUENCE_LENGTH,
        feature_schema_version=schema.version, channels=schema.channel_names, heads=HEADS,
        normalization={"featureSchemaVersion": schema.version, "channels": list(schema.channel_names),
                       "mean": mean.tolist(), "std": std.tolist(), "knownCounts": [1] * schema.feature_count},
        calibration=calibration.to_dict(), provenance=Provenance(notes="cross-language fixture, not a model"))
    write_manifest(target, manifest)

    windows = np.zeros((CASES, SEQUENCE_LENGTH, schema.feature_count), dtype=np.float32)
    windows[:, :, :schema.value_count] = rng.normal(0.0, 3.0, (CASES, SEQUENCE_LENGTH, schema.value_count))
    masks = (rng.uniform(size=(CASES, SEQUENCE_LENGTH, schema.mask_count)) > 0.3).astype(np.float32)
    windows[:, :, schema.value_count:] = masks
    mask_index = schema.value_count
    for channel, value in enumerate(schema.values):   # unknown values encode as 0, as the encoder writes them
        if value.nullable:
            windows[:, :, channel] *= windows[:, :, mask_index]
            mask_index += 1

    known = np.ones_like(windows, dtype=np.float64)
    mask_index = schema.value_count
    for channel, value in enumerate(schema.values):
        if value.nullable:
            known[..., channel] = windows[..., mask_index]
            mask_index += 1
    scaled = (windows.astype(np.float64) - mean) / std
    normalised = np.where(np.arange(schema.feature_count) < schema.value_count, scaled * known, windows).astype(np.float32)
    with torch.no_grad():
        logits = model(torch.as_tensor(normalised)).numpy().astype(np.float64)
    probabilities = np.clip(calibration.apply_logits(logits), 0.0, 1.0)

    cases = {"note": "Inputs are encoded windows (row-major [t][channel]) before normalisation.",
             "sequenceLength": SEQUENCE_LENGTH, "featureCount": schema.feature_count, "heads": list(HEADS),
             "cases": [{"features": windows[i].ravel().tolist(), "logits": logits[i].tolist(),
                        "probabilities": probabilities[i].tolist()} for i in range(CASES)]}
    (target / "cases.json").write_text(json.dumps(cases) + "\n", encoding="utf-8")
    return cases


def main() -> None:
    target = Path(__file__).resolve().parents[2] / "tests" / "data" / "local_model"
    cases = build(target)
    print(f"wrote {target}: {len(cases['cases'])} cases, logits {[c['logits'] for c in cases['cases']]}")


if __name__ == "__main__":
    main()

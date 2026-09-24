"""PyTorch to ONNX, with the checks that make the export trustworthy.

Two things are verified before a bundle is written: the ONNX graph reproduces the PyTorch outputs
on real windows, and the manifest it ships with matches the schema the training run used. An export
that silently disagrees with its own weights is worse than no export.

The batch axis is dynamic; the time and channel axes are fixed, because a window of the wrong shape
is a protocol error the service must refuse rather than reinterpret.
"""

from __future__ import annotations

from pathlib import Path
import hashlib

import numpy as np

from ..dataset.normalize import Normalizer
from ..evaluation.calibration import TemperatureScaler
from ..schema import FeatureSchema, default_schema
from .bundle import Bundle, BundleManifest, MODEL_NAME, Provenance, write_manifest

INPUT_NAME = "windows"
OUTPUT_NAME = "logits"
OPSET = 17


def export(model, path: Path | str, *, model_version: str, model_kind: str, window: str,
           normalizer: Normalizer, calibration: TemperatureScaler | None, provenance: Provenance,
           sample_windows: np.ndarray | None = None, schema: FeatureSchema | None = None,
           tolerance: float = 1.0e-4) -> Bundle:
    import torch

    schema = schema or default_schema()
    path = Path(path)
    path.mkdir(parents=True, exist_ok=True)
    model = model.eval()
    config = model.config
    if config.feature_count != schema.feature_count:
        raise ValueError(
            f"model takes {config.feature_count} channels, schema declares {schema.feature_count}"
        )
    example = torch.zeros(1, config.sequence_length, config.feature_count, dtype=torch.float32)
    if sample_windows is not None and len(sample_windows) > 0:
        example = torch.as_tensor(np.asarray(sample_windows[:8], dtype=np.float32))
    target = path / MODEL_NAME
    # Whichever exporter this torch version defaults to. The graph is verified against the
    # PyTorch outputs below, so an exporter change cannot ship a silently different model.
    torch.onnx.export(
        model,
        example,
        str(target),
        input_names=[INPUT_NAME],
        output_names=[OUTPUT_NAME],
        dynamic_axes={INPUT_NAME: {0: "batch"}, OUTPUT_NAME: {0: "batch"}},
        opset_version=OPSET,
        # Quiet: newer exporters print status with emoji, which a legacy Windows console
        # codepage cannot encode, and an export must not die on its own progress output.
        verbose=False,
    )
    parity = _verify(model, target, example, tolerance)
    provenance.evaluation["onnxVerification"] = {"passed": True, "maxAbsoluteLogitError": parity, "tolerance": tolerance,
        "sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in path.glob("model.onnx*") if p.is_file()}}
    manifest = BundleManifest(
        model_version=model_version,
        model_kind=model_kind,
        window=window,
        sequence_length=config.sequence_length,
        feature_schema_version=schema.version,
        channels=schema.channel_names,
        heads=tuple(config.heads),
        normalization=normalizer.to_dict(),
        calibration=calibration.to_dict() if calibration else None,
        provenance=provenance,
    )
    write_manifest(path, manifest)
    return Bundle(path, manifest)


def _verify(model, onnx_path: Path, example, tolerance: float) -> float:
    """An exported graph that disagrees with its own weights must not reach a bundle."""
    import torch

    with torch.no_grad():
        expected = model(example).cpu().numpy()
    try:
        import onnxruntime
    except ImportError:
        raise RuntimeError(
            "onnxruntime is required to verify an export; install ml/requirements-serve.txt "
            "or the export cannot be trusted"
        )
    session = onnxruntime.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    actual = session.run([OUTPUT_NAME], {INPUT_NAME: example.cpu().numpy()})[0]
    if expected.shape != actual.shape or not np.all(np.isfinite(expected)) or not np.all(np.isfinite(actual)):
        raise RuntimeError("ONNX output differs from PyTorch: invalid shape or non-finite logits")
    difference = float(np.max(np.abs(expected - actual))) if expected.size else 0.0
    if difference > tolerance:
        raise RuntimeError(f"ONNX output differs from PyTorch by {difference:g} (> {tolerance:g})")
    return difference

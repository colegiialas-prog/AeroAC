"""Inference service logic, independent of any web framework.

The whole protocol contract lives here so it can be tested without a server and without
onnxruntime: version checks, shape checks, normalisation, the model call, calibration, and the
response shape the Java client accepts.

Refusal policy: anything the caller could not fix by retrying the same payload is answered 422.
The Java client counts that as a permanent refusal and stops hammering the endpoint. Transient
conditions (model still loading) are 503, which the client treats as an ordinary failure.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Protocol

import numpy as np

from ..evaluation.calibration import TemperatureScaler, HeadCalibration, load_calibration, sigmoid
from ..export.bundle import Bundle
from ..schema import FeatureSchema, default_schema

PROTOCOL_VERSION = 1
MAX_SEQUENCE_LENGTH = 512
MAX_BATCH = 1


class ProtocolError(Exception):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


class ModelBackend(Protocol):
    def run(self, windows: np.ndarray) -> np.ndarray:
        """(B, T, C) float32 -> (B, heads) logits in the bundle's head order."""


@dataclass
class OnnxBackend:
    session: object
    input_name: str
    output_name: str

    @classmethod
    def load(cls, bundle: Bundle, providers: list[str] | None = None) -> "OnnxBackend":
        import onnxruntime
        import hashlib
        for name, expected in bundle.manifest.provenance.evaluation.get("onnxVerification", {}).get("sha256", {}).items():
            if Path(name).name != name or hashlib.sha256((bundle.path / name).read_bytes()).hexdigest() != expected:
                raise ValueError("ONNX bundle content differs from verified export")

        session = onnxruntime.InferenceSession(
            str(bundle.model_path), providers=providers or ["CPUExecutionProvider"])
        inputs = session.get_inputs()
        outputs = session.get_outputs()
        if len(inputs) != 1 or len(outputs) != 1:
            raise ValueError("expected a single-input, single-output graph")
        return cls(session, inputs[0].name, outputs[0].name)

    def run(self, windows: np.ndarray) -> np.ndarray:
        return np.asarray(self.session.run([self.output_name], {self.input_name: windows})[0], dtype=np.float64)


@dataclass
class LoadedModel:
    bundle: Bundle
    backend: ModelBackend
    calibration: TemperatureScaler | HeadCalibration | None
    mean: np.ndarray
    std: np.ndarray

    @classmethod
    def load(cls, path: Path | str, schema: FeatureSchema | None = None,
             backend: ModelBackend | None = None) -> "LoadedModel":
        bundle = Bundle.load(path, schema)
        mean, std = bundle.normalization_arrays()
        calibration = load_calibration(bundle.manifest.calibration, bundle.manifest.heads)
        return cls(bundle, backend or OnnxBackend.load(bundle), calibration, mean, std)

    @property
    def kind(self) -> str:
        return self.bundle.manifest.model_kind

    def normalize(self, windows: np.ndarray, schema: FeatureSchema) -> np.ndarray:
        """Mirrors Normalizer.apply: mask channels pass through and unknown entries stay zero."""
        windows = np.asarray(windows, dtype=np.float64)
        known = np.ones_like(windows)
        mask_index = schema.value_count
        for channel, value in enumerate(schema.values):
            if value.nullable:
                known[..., channel] = windows[..., mask_index]
                mask_index += 1
        scaled = (windows - self.mean) / self.std
        out = np.where(np.arange(windows.shape[-1]) < schema.value_count, scaled * known, windows)
        return out.astype(np.float32)

    def calibration_prior(self, head: str = "overall") -> float | None:
        prior = getattr(self.calibration, "prior", None)
        return prior(head) if callable(prior) else None

    def predict(self, windows: np.ndarray, schema: FeatureSchema) -> dict[str, float]:
        logits = np.asarray(self.backend.run(self.normalize(windows, schema)), dtype=np.float64)
        heads = self.bundle.manifest.heads
        if logits.shape != (len(windows), len(heads)) or not np.all(np.isfinite(logits)):
            raise ProtocolError(503, f"model returned {logits.shape}, expected (batch, {len(heads)})")
        raw = logits[0]
        if self.calibration is not None:
            probabilities = self.calibration.apply_logits(raw)
        else:
            probabilities = sigmoid(raw)
        return {name: float(np.clip(value, 0.0, 1.0)) for name, value in zip(heads, probabilities)}


class InferenceService:
    """Holds one model per kind. Unknown kinds are refused, never silently served by another model."""

    def __init__(self, models: Mapping[str, LoadedModel], schema: FeatureSchema | None = None) -> None:
        self.schema = schema or default_schema()
        self.models = dict(models)
        for kind, model in self.models.items():
            model.bundle.manifest.validate(self.schema)
            if model.bundle.manifest.model_kind != kind:
                raise ValueError(f"bundle for {kind!r} declares kind {model.bundle.manifest.model_kind!r}")

    def health(self) -> dict:
        return {
            "protocolVersion": PROTOCOL_VERSION,
            "featureSchemaVersion": self.schema.version,
            "featureCount": self.schema.feature_count,
            "models": {
                kind: {
                    "modelVersion": model.bundle.manifest.model_version,
                    "window": model.bundle.manifest.window,
                    "sequenceLength": model.bundle.manifest.sequence_length,
                    "heads": list(model.bundle.manifest.heads),
                    "calibrated": model.calibration is not None,
                }
                for kind, model in self.models.items()
            },
        }

    def predict(self, payload: Mapping) -> dict:
        model, windows = self._validate(payload)
        heads = model.predict(windows, self.schema)
        if "overall" not in heads:
            raise ProtocolError(503, "model does not publish an 'overall' head")
        response = {
            "protocolVersion": PROTOCOL_VERSION,
            "featureSchemaVersion": self.schema.version,
            "requestId": int(payload["requestId"]),
            "model": model.kind,
            "modelVersion": model.bundle.manifest.model_version,
            "calibrated": model.calibration is not None,
            "heads": heads,
        }
        prior = model.calibration_prior()
        if prior is not None:
            # Optional: the base rate the calibrated overall head is a posterior under. Older Java
            # clients ignore it; newer ones score logit(p) - logit(prior) instead of a guessed neutral.
            response["calibrationPrior"] = prior
        return response

    def _validate(self, payload: Mapping) -> tuple[LoadedModel, np.ndarray]:
        if not isinstance(payload, Mapping):
            raise ProtocolError(422, "request body must be a JSON object")
        for key in ("protocolVersion", "featureSchemaVersion", "requestId", "model",
                    "sequenceLength", "featureCount", "features"):
            if key not in payload:
                raise ProtocolError(422, f"missing {key}")
        for key in ("protocolVersion", "featureSchemaVersion", "requestId", "sequenceLength", "featureCount"):
            if type(payload[key]) is not int:
                raise ProtocolError(422, f"{key} must be an integer")
        if not 0 <= payload["requestId"] <= 2**63 - 1:
            raise ProtocolError(422, "requestId outside nonnegative Java long range")
        if int(payload["protocolVersion"]) != PROTOCOL_VERSION:
            raise ProtocolError(422, f"protocolVersion {payload['protocolVersion']} is not supported "
                                     f"(this service speaks {PROTOCOL_VERSION})")
        if int(payload["featureSchemaVersion"]) != self.schema.version:
            raise ProtocolError(422, f"featureSchemaVersion {payload['featureSchemaVersion']} does not match "
                                     f"the deployed schema {self.schema.version}; redeploy, do not reinterpret")
        if int(payload["featureCount"]) != self.schema.feature_count:
            raise ProtocolError(422, f"featureCount {payload['featureCount']} does not match "
                                     f"{self.schema.feature_count}")
        kind = str(payload["model"]).lower()
        model = self.models.get(kind)
        if model is None:
            raise ProtocolError(422, f"model {kind!r} is not loaded (available: {sorted(self.models)})")
        length = int(payload["sequenceLength"])
        if length < 1 or length > MAX_SEQUENCE_LENGTH:
            raise ProtocolError(422, f"sequenceLength {length} outside 1..{MAX_SEQUENCE_LENGTH}")
        if length != model.bundle.manifest.sequence_length:
            raise ProtocolError(422, f"model {kind} expects {model.bundle.manifest.sequence_length} samples, "
                                     f"request carries {length}")
        window = str(payload.get("window", model.bundle.manifest.window)).lower()
        if window != model.bundle.manifest.window:
            raise ProtocolError(422, f"model {kind} was trained on {model.bundle.manifest.window} windows, "
                                     f"request declares {window}")
        features = payload["features"]
        expected = length * self.schema.feature_count
        if not isinstance(features, (list, tuple, np.ndarray)) or len(features) != expected:
            raise ProtocolError(422, f"features must hold {expected} values, got "
                                     f"{len(features) if hasattr(features, '__len__') else 'none'}")
        try:
            if any(type(v) not in (int, float, np.float32, np.float64) for v in features):
                raise ValueError("non-numeric feature")
            array = np.asarray(features, dtype=np.float64)
        except (ValueError, TypeError, OverflowError):
            raise ProtocolError(422, "features must be a flat numeric array") from None
        if array.shape != (expected,) or not np.all(np.isfinite(array)) or np.any(np.abs(array) > np.finfo(np.float32).max):
            raise ProtocolError(422, "features must all be finite; unknown values encode as 0 with a 0 mask")
        return model, array.reshape(1, length, self.schema.feature_count).astype(np.float32)


def load_service(bundles: Mapping[str, Path | str], schema: FeatureSchema | None = None) -> InferenceService:
    schema = schema or default_schema()
    return InferenceService({kind: LoadedModel.load(path, schema) for kind, path in bundles.items()}, schema)


@dataclass
class ConstantBackend:
    """Fixed logits. For protocol tests and for bringing a deployment up before a model exists."""
    logits: np.ndarray

    def run(self, windows: np.ndarray) -> np.ndarray:
        batch = np.asarray(windows).shape[0]
        return np.tile(np.asarray(self.logits, dtype=np.float64), (batch, 1))

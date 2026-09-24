"""The deployable unit: one directory holding the model and everything needed to interpret it.

A bundle is self-describing on purpose. The service must be able to refuse a request built for a
different feature schema without consulting the repository, and an operator must be able to tell
from the bundle alone which dataset, split and commit produced the weights it is serving.

Reading a manifest needs nothing but the standard library and numpy; torch and onnxruntime are
only required to create or execute one.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

from ..schema import FeatureSchema, check_channels, default_schema
from ..reporting import write_json

MANIFEST_NAME = "manifest.json"
MODEL_NAME = "model.onnx"
BUNDLE_FORMAT = 1


@dataclass
class Provenance:
    """Everything needed to reproduce or to distrust a model."""
    dataset_version: str = "dataset-v1"
    dataset_root: str = ""
    git_commit: str = ""
    training_config: dict = field(default_factory=dict)
    split_manifest: dict = field(default_factory=dict)
    evaluation: dict = field(default_factory=dict)
    created: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat(timespec="seconds"))
    notes: str = ""

    def to_dict(self) -> dict:
        return {
            "datasetVersion": self.dataset_version,
            "datasetRoot": self.dataset_root,
            "gitCommit": self.git_commit,
            "trainingConfig": self.training_config,
            "splitManifest": self.split_manifest,
            "evaluation": self.evaluation,
            "created": self.created,
            "notes": self.notes,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "Provenance":
        return cls(
            dataset_version=data.get("datasetVersion", "dataset-v1"),
            dataset_root=data.get("datasetRoot", ""),
            git_commit=data.get("gitCommit", ""),
            training_config=data.get("trainingConfig", {}),
            split_manifest=data.get("splitManifest", {}),
            evaluation=data.get("evaluation", {}),
            created=data.get("created", ""),
            notes=data.get("notes", ""),
        )


@dataclass
class BundleManifest:
    model_version: str
    model_kind: str
    window: str
    sequence_length: int
    feature_schema_version: int
    channels: tuple[str, ...]
    heads: tuple[str, ...]
    normalization: dict
    calibration: dict | None
    provenance: Provenance
    bundle_format: int = BUNDLE_FORMAT

    @property
    def calibrated(self) -> bool:
        return bool(self.calibration)

    def to_dict(self) -> dict:
        return {
            "bundleFormat": self.bundle_format,
            "modelVersion": self.model_version,
            "modelKind": self.model_kind,
            "window": self.window,
            "sequenceLength": self.sequence_length,
            "featureSchemaVersion": self.feature_schema_version,
            "featureCount": len(self.channels),
            "channels": list(self.channels),
            "heads": list(self.heads),
            "normalization": self.normalization,
            "calibration": self.calibration,
            "provenance": self.provenance.to_dict(),
        }

    @classmethod
    def from_dict(cls, data: dict) -> "BundleManifest":
        if int(data.get("bundleFormat", 0)) != BUNDLE_FORMAT:
            raise ValueError(f"unsupported bundle format {data.get('bundleFormat')}")
        channels = tuple(data["channels"])
        if len(channels) != int(data["featureCount"]):
            raise ValueError("featureCount does not match the channel list")
        heads = tuple(data["heads"])
        if "overall" not in heads:
            raise ValueError("a bundle must publish an 'overall' head")
        return cls(
            model_version=data["modelVersion"],
            model_kind=data.get("modelKind", "flash"),
            window=data.get("window", "attack"),
            sequence_length=int(data["sequenceLength"]),
            feature_schema_version=int(data["featureSchemaVersion"]),
            channels=channels,
            heads=heads,
            normalization=data["normalization"],
            calibration=data.get("calibration"),
            provenance=Provenance.from_dict(data.get("provenance", {})),
        )

    def validate(self, schema: FeatureSchema | None = None) -> None:
        schema = schema or default_schema()
        if self.feature_schema_version != schema.version:
            raise ValueError(
                f"bundle was built for feature schema {self.feature_schema_version}, "
                f"this install uses {schema.version}"
            )
        check_channels(schema, self.channels)
        if not 1 <= self.sequence_length <= 512:
            raise ValueError("sequenceLength must be 1..512")
        if self.model_kind not in ("flash", "pro") or len(set(self.heads)) != len(self.heads) or any(h not in schema.heads for h in self.heads):
            raise ValueError("invalid model kind or heads")
        from ..dataset.normalize import Normalizer
        from ..evaluation.calibration import load_calibration
        Normalizer.from_dict(self.normalization, schema)
        load_calibration(self.calibration, self.heads)
        if self.window not in ("attack", "continuous"):
            raise ValueError(f"unknown window type {self.window!r}")


@dataclass
class Bundle:
    path: Path
    manifest: BundleManifest

    @classmethod
    def load(cls, path: Path | str, schema: FeatureSchema | None = None) -> "Bundle":
        path = Path(path)
        manifest_path = path / MANIFEST_NAME
        if not manifest_path.is_file():
            raise FileNotFoundError(f"{path} is not a model bundle (no {MANIFEST_NAME})")
        manifest = BundleManifest.from_dict(json.loads(manifest_path.read_text(encoding="utf-8")))
        manifest.validate(schema)
        return cls(path, manifest)

    @property
    def model_path(self) -> Path:
        return self.path / MODEL_NAME

    def normalization_arrays(self) -> tuple[np.ndarray, np.ndarray]:
        mean = np.asarray(self.manifest.normalization["mean"], dtype=np.float64)
        std = np.asarray(self.manifest.normalization["std"], dtype=np.float64)
        if mean.shape != std.shape or mean.size != len(self.manifest.channels):
            raise ValueError("normalisation arrays do not match the channel list")
        return mean, std


def write_manifest(path: Path | str, manifest: BundleManifest) -> Path:
    path = Path(path)
    path.mkdir(parents=True, exist_ok=True)
    manifest.validate()
    target = path / MANIFEST_NAME
    write_json(target, manifest.to_dict())
    return target


def model_version(prefix: str, git_commit: str = "", created: str | None = None) -> str:
    """Stable, readable and unique enough to appear in an alert and be traced back."""
    stamp = created or datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    suffix = f"-{git_commit[:8]}" if git_commit else ""
    return f"{prefix}-{stamp}{suffix}"

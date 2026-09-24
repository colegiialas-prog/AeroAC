"""Canonical model input contract.

The newest ``ml/schema/feature_schema_v*.json`` is the single source of truth. The Java
``ModelFeature`` enum is pinned against it by ``FeatureSchemaTest``; this module pins the Python
side. A model must never be trained against one ordering and served against another, so the
schema version and the channel names travel with every exported bundle and every request.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Sequence

MANIFEST_ENV = "AERO_FEATURE_SCHEMA"
MASK_SUFFIX = "_MASK"
SUPPORTED_TRANSFORMS = ("none", "log1p")


@dataclass(frozen=True)
class ValueChannel:
    name: str
    source: str
    nullable: bool
    low: float
    high: float
    unit: str
    transform: str = "none"

    @property
    def derived(self) -> bool:
        return self.source.startswith("derived.")

    def apply_transform(self, values):
        """Monotone per-channel transform applied BEFORE clipping, so bounds are in transformed units."""
        import numpy as np

        if self.transform == "none":
            return values
        if self.transform == "log1p":
            values = np.asarray(values, dtype=np.float64)
            # log1p is undefined below -1 and meaningless for a negative count: unknown stays unknown.
            return np.where(values >= 0, np.log1p(np.where(values >= 0, values, 0.0)), np.nan)
        raise ValueError(f"unsupported transform {self.transform!r} on channel {self.name}")


@dataclass(frozen=True)
class FeatureSchema:
    version: int
    raw_schema_version: int
    raw_fields: tuple[str, ...]
    values: tuple[ValueChannel, ...]
    heads: tuple[str, ...]
    required_heads: tuple[str, ...]

    @property
    def value_count(self) -> int:
        return len(self.values)

    @property
    def mask_count(self) -> int:
        return sum(1 for value in self.values if value.nullable)

    @property
    def feature_count(self) -> int:
        return self.value_count + self.mask_count

    @property
    def channel_names(self) -> tuple[str, ...]:
        masks = [value.name + MASK_SUFFIX for value in self.values if value.nullable]
        return tuple([value.name for value in self.values] + masks)

    def index(self, channel: str) -> int:
        return self.channel_names.index(channel)

    def mask_index(self, value_name: str) -> int:
        return self.index(value_name + MASK_SUFFIX)

    def raw_index(self, field: str) -> int:
        return self.raw_fields.index(field)

    def describe(self) -> str:
        return (
            f"featureSchemaVersion={self.version} rawSchemaVersion={self.raw_schema_version} "
            f"values={self.value_count} masks={self.mask_count} channels={self.feature_count}"
        )


def manifest_path() -> Path:
    """Environment override first so a deployed service can ship its own copy of the manifest."""
    override = os.environ.get(MANIFEST_ENV)
    if override:
        return Path(override)
    here = Path(__file__).resolve()
    for directory in (here.parent / "_schemas", here.parent.parent / "schema"):
        if not directory.is_dir():
            continue
        # Newest version wins. Older files stay on disk for provenance; a bundle built for one of
        # them is rejected by version, not silently reinterpreted against the current channels.
        versions = sorted(
            (int(match.group(1)), path)
            for path in directory.glob("feature_schema_v*.json")
            if (match := re.fullmatch(r"feature_schema_v(\d+)\.json", path.name))
        )
        if versions:
            return versions[-1][1]
    raise FileNotFoundError("no schema/feature_schema_v*.json found; set " + MANIFEST_ENV)


def load_schema(path: Path | str | None = None) -> FeatureSchema:
    source = Path(path) if path is not None else manifest_path()
    data = json.loads(source.read_text(encoding="utf-8"))
    values = tuple(
        ValueChannel(
            name=entry["name"],
            source=entry["source"],
            nullable=bool(entry["nullable"]),
            low=float(entry["clip"][0]),
            high=float(entry["clip"][1]),
            unit=entry.get("unit", ""),
            transform=entry.get("transform", "none"),
        )
        for entry in data["values"]
    )
    names = [value.name for value in values]
    if len(set(names)) != len(names):
        raise ValueError("duplicate value channel in the manifest")
    unsupported = sorted({value.transform for value in values} - set(SUPPORTED_TRANSFORMS))
    if unsupported:
        raise ValueError(f"manifest declares unsupported transforms {unsupported}")
    raw_fields = tuple(data["rawFields"])
    if len(set(raw_fields)) != len(raw_fields):
        raise ValueError("duplicate raw field in the manifest")
    schema = FeatureSchema(
        version=int(data["featureSchemaVersion"]),
        raw_schema_version=int(data["rawSchemaVersion"]),
        raw_fields=raw_fields,
        values=values,
        heads=tuple(data["heads"]),
        required_heads=tuple(data["requiredHeads"]),
    )
    missing = [value.source for value in values if not value.derived and value.source not in raw_fields]
    if missing:
        raise ValueError(f"value channels reference unknown raw fields: {missing}")
    unknown_required = [head for head in schema.required_heads if head not in schema.heads]
    if unknown_required:
        raise ValueError(f"required heads not declared: {unknown_required}")
    return schema


@lru_cache(maxsize=4)
def default_schema() -> FeatureSchema:
    return load_schema()


def check_channels(schema: FeatureSchema, channels: Sequence[str]) -> None:
    """Fail loudly when a bundle or request was built against a different channel order."""
    expected = schema.channel_names
    if tuple(channels) != expected:
        raise ValueError(
            "feature channel mismatch: a model built for a different schema would read the wrong "
            f"columns (expected {len(expected)} channels starting {expected[:3]}, "
            f"got {len(channels)} starting {tuple(channels)[:3]})"
        )

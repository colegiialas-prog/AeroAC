"""Per-channel normalisation fitted on the training fold only.

Two rules make this safe. Statistics are computed over known entries only, so a channel that is
usually unknown is not pulled towards zero by its own missing values. And an unknown entry stays
exactly zero after normalisation, so "unknown" never becomes a plausible-looking measurement.

Mask channels are indicators and are passed through untouched.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from ..schema import FeatureSchema, check_channels, default_schema

MIN_STD = 1.0e-6


@dataclass
class Normalizer:
    channels: tuple[str, ...]
    mean: np.ndarray
    std: np.ndarray
    gate: np.ndarray
    known_counts: np.ndarray
    feature_schema_version: int

    @classmethod
    def fit(cls, windows: np.ndarray, schema: FeatureSchema | None = None) -> "Normalizer":
        schema = schema or default_schema()
        windows = np.asarray(windows, dtype=np.float32)
        if windows.ndim != 3 or windows.shape[2] != schema.feature_count:
            raise ValueError(f"expected (N, T, {schema.feature_count}) windows")
        if windows.shape[0] == 0:
            raise ValueError("cannot fit normalisation on an empty training fold")
        if not np.all(np.isfinite(windows)):
            raise ValueError("encoded windows must be finite")
        flat = windows.reshape(-1, schema.feature_count).astype(np.float64)
        gate = _gate(flat, schema)
        counts = gate.sum(axis=0)
        mean = np.zeros(schema.feature_count)
        std = np.ones(schema.feature_count)
        for channel in range(schema.value_count):
            known = gate[:, channel] > 0
            if not np.any(known):
                # Never observed in training: leave it centred and unscaled rather than inventing a scale.
                continue
            column = flat[known, channel]
            mean[channel] = float(column.mean())
            std[channel] = max(float(column.std()), MIN_STD)
        return cls(
            channels=schema.channel_names,
            mean=mean,
            std=std,
            gate=np.concatenate([np.ones(schema.value_count), np.zeros(schema.mask_count)]),
            known_counts=counts,
            feature_schema_version=schema.version,
        )

    def apply(self, windows: np.ndarray, schema: FeatureSchema | None = None) -> np.ndarray:
        schema = schema or default_schema()
        check_channels(schema, self.channels)
        if self.feature_schema_version != schema.version:
            raise ValueError(
                f"normalisation was fitted for feature schema {self.feature_schema_version}, "
                f"this pipeline uses {schema.version}"
            )
        windows = np.asarray(windows, dtype=np.float32)
        if windows.ndim != 3 or windows.shape[2] != schema.feature_count:
            raise ValueError(f"expected (N, T, {schema.feature_count}) windows")
        flat = windows.reshape(-1, schema.feature_count).astype(np.float64)
        known = _gate(flat, schema)
        scaled = (flat - self.mean) / self.std
        # Mask channels keep their 0/1 meaning; unknown value entries stay exactly zero.
        out = np.where(self.gate > 0, scaled * known, flat)
        return out.reshape(windows.shape).astype(np.float32)

    def to_dict(self) -> dict:
        return {
            "featureSchemaVersion": self.feature_schema_version,
            "channels": list(self.channels),
            "mean": [float(value) for value in self.mean],
            "std": [float(value) for value in self.std],
            "knownCounts": [int(value) for value in self.known_counts],
        }

    @classmethod
    def from_dict(cls, data: dict, schema: FeatureSchema | None = None) -> "Normalizer":
        schema = schema or default_schema()
        channels = tuple(data["channels"])
        check_channels(schema, channels)
        mean = np.asarray(data["mean"], dtype=np.float64)
        std = np.asarray(data["std"], dtype=np.float64)
        if mean.shape != (len(channels),) or std.shape != mean.shape or not np.all(np.isfinite(mean)) or not np.all(np.isfinite(std)) or np.any(std <= 0):
            raise ValueError("invalid normalization arrays")
        if int(data["featureSchemaVersion"]) != schema.version:
            raise ValueError("normalization feature schema mismatch")
        if np.any(mean[schema.value_count:] != 0) or np.any(std[schema.value_count:] != 1):
            raise ValueError("normalization must leave masks unchanged")
        return cls(
            channels=channels,
            mean=np.asarray(data["mean"], dtype=np.float64),
            std=np.asarray(data["std"], dtype=np.float64),
            gate=np.concatenate([np.ones(schema.value_count), np.zeros(schema.mask_count)]),
            known_counts=np.asarray(data.get("knownCounts", [0] * len(channels)), dtype=np.float64),
            feature_schema_version=int(data["featureSchemaVersion"]),
        )

    def never_observed(self, schema: FeatureSchema | None = None) -> list[str]:
        """Channels a training fold never saw. Worth reading before trusting a model that uses them."""
        schema = schema or default_schema()
        return [self.channels[i] for i in range(schema.value_count) if self.known_counts[i] == 0]


def _gate(flat: np.ndarray, schema: FeatureSchema) -> np.ndarray:
    """1 where a channel's value is actually known: its own mask for nullables, always for the rest."""
    gate = np.ones_like(flat)
    mask_index = schema.value_count
    for channel, value in enumerate(schema.values):
        if value.nullable:
            gate[:, channel] = flat[:, mask_index]
            mask_index += 1
    gate[:, schema.value_count:] = 1.0
    return gate

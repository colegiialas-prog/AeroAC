"""Per-channel statistics, and the two things they are for.

First: finding a broken channel. A feature that is always missing, always the same value, or
constantly pinned to a clip bound is not teaching the model anything, and a feature whose range
suddenly changed means the recorder changed under us.

Second: finding a channel that encodes the label rather than the behaviour. The strongest form is
structural — a channel that is known in one class and missing in the other means the two classes
went through different recorder paths, and the model can read the label directly. A weaker form is
distributional separability, which is flagged for review only: a genuinely predictive feature looks
the same way, and deciding between the two needs a human who knows how the data was recorded.

Nothing here should be used to hand-write cheat thresholds. That is the failure mode this whole
project exists to avoid: a threshold on one number is one number for a cheat to stay under.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

import numpy as np

from ..evaluation.metrics import roc_auc
from ..schema import FeatureSchema, ValueChannel, default_schema
from ..dataset.features import encode_window
from ..dataset.windows import WindowIndex

SEPARABILITY_REVIEW = 0.99
STRUCTURAL_KNOWN_GAP = 0.95
CLIPPING_REVIEW = 0.10


@dataclass
class ChannelStats:
    name: str
    known: float
    missing: float
    count: int
    mean: float
    std: float
    minimum: float
    maximum: float
    p01: float
    p50: float
    p99: float
    clipping: float

    def to_dict(self) -> dict:
        return {
            "known": round(self.known, 4),
            "missing": round(self.missing, 4),
            "count": self.count,
            "mean": _round(self.mean),
            "std": _round(self.std),
            "min": _round(self.minimum),
            "max": _round(self.maximum),
            "p01": _round(self.p01),
            "p50": _round(self.p50),
            "p99": _round(self.p99),
            "clipping": round(self.clipping, 4),
        }


def _round(value: float) -> float | None:
    return None if value is None or not np.isfinite(value) else round(float(value), 5)


def _sample_rows(index: WindowIndex, limit: int) -> list[int]:
    if limit < 2:
        raise ValueError("feature sample must be at least 2")
    if len(index) <= limit:
        return list(range(len(index)))
    rng = np.random.default_rng(42)
    labels = index.labels()
    classes = np.unique(labels)
    return sorted(int(i) for label in classes for i in rng.choice(np.flatnonzero(labels == label),
                  min(limit // len(classes), int(np.sum(labels == label))), replace=False))


def channel_stats(encoded: np.ndarray, schema: FeatureSchema, unclipped: np.ndarray | None = None) -> dict[str, ChannelStats]:
    """``encoded`` is (N, T, channels) straight from the encoder, before normalisation."""
    if encoded.ndim != 3 or encoded.shape[2] != schema.feature_count:
        raise ValueError(f"expected (N, T, {schema.feature_count}) encoded windows")
    flat = encoded.reshape(-1, schema.feature_count).astype(np.float64)
    original = None if unclipped is None else unclipped.reshape(-1, schema.feature_count)
    total = max(flat.shape[0], 1)
    stats: dict[str, ChannelStats] = {}
    mask_index = schema.value_count
    values = [*schema.values, *(ValueChannel(n, n, False, 0, 1, "mask") for n in schema.channel_names[schema.value_count:])]
    for channel, value in enumerate(values):
        if value.nullable:
            known_mask = flat[:, mask_index] > 0
            mask_index += 1
        else:
            known_mask = np.ones(flat.shape[0], dtype=bool)
        column = flat[known_mask, channel]
        known = float(known_mask.sum()) / total
        if column.size == 0:
            stats[value.name] = ChannelStats(value.name, known, 1.0 - known, 0, np.nan, np.nan,
                                             np.nan, np.nan, np.nan, np.nan, np.nan, 0.0)
            continue
        raw_column = original[known_mask, channel] if original is not None else column
        at_bound = (raw_column < value.low) | (raw_column > value.high)
        stats[value.name] = ChannelStats(
            name=value.name,
            known=known,
            missing=1.0 - known,
            count=int(column.size),
            mean=float(column.mean()),
            std=float(column.std()),
            minimum=float(column.min()),
            maximum=float(column.max()),
            p01=float(np.percentile(column, 1)),
            p50=float(np.percentile(column, 50)),
            p99=float(np.percentile(column, 99)),
            clipping=float(at_bound.mean()),
        )
    return stats


def feature_report(index: WindowIndex, schema: FeatureSchema | None = None,
                   sample: int = 2048) -> dict:
    """Statistics over all windows, then per label, plus the findings that need a human."""
    schema = schema or default_schema()
    if len(index) == 0:
        return {"windows": 0, "note": "no windows to audit"}
    rows = _sample_rows(index, sample)
    encoded = index.encode(rows, schema)
    unclipped = np.stack([encode_window(index.raw(row), schema, clip=False) for row in rows])
    labels = index.labels()[rows]

    report = {
        "windows": len(index),
        "sampledWindows": len(rows),
        "all": {name: stats.to_dict() for name, stats in channel_stats(encoded, schema, unclipped).items()},
        "sampling": "seed=42, stratified by label; overlapping samples are not independent",
    }
    per_label: dict[str, dict[str, ChannelStats]] = {}
    for name, value in (("LEGIT", 0), ("CHEAT", 1)):
        selection = labels == value
        if not np.any(selection):
            continue
        per_label[name] = channel_stats(encoded[selection], schema, unclipped[selection])
        report[name] = {channel: stats.to_dict() for channel, stats in per_label[name].items()}

    report["findings"] = findings(per_label, encoded, labels, schema)
    for channel, stats in report["all"].items():
        if stats["clipping"] > CLIPPING_REVIEW:
            report["findings"].append(_finding("CLIPPED", channel, f"{stats['clipping']:.1%} of known pre-clip values exceeded bounds"))
    return report


def findings(per_label: dict[str, dict[str, ChannelStats]], encoded: np.ndarray,
             labels: np.ndarray, schema: FeatureSchema) -> list[dict]:
    """Broken channels first, then structural leakage, then separability for review."""
    results: list[dict] = []
    all_stats = channel_stats(encoded, schema)

    for value in schema.values:
        stats = all_stats[value.name]
        if stats.known == 0.0:
            results.append(_finding("ALWAYS_MISSING", value.name,
                                    "channel was never known in any sampled window"))
            continue
        if stats.count and stats.std == 0.0:
            results.append(_finding("CONSTANT", value.name,
                                    f"channel is always {stats.mean:g} where known"))
        if stats.clipping > CLIPPING_REVIEW:
            results.append(_finding("CLIPPED", value.name,
                                    f"{stats.clipping:.1%} of known values sit on a clip bound "
                                    f"[{value.low:g}, {value.high:g}]"))

    legit = per_label.get("LEGIT")
    cheat = per_label.get("CHEAT")
    if not legit or not cheat:
        results.append(_finding("SINGLE_CLASS", "*",
                                "only one label present; leakage between classes cannot be checked"))
        return results

    for value in schema.values:
        gap = abs(legit[value.name].known - cheat[value.name].known)
        if gap >= STRUCTURAL_KNOWN_GAP:
            results.append(_finding("STRUCTURAL_LEAKAGE", value.name,
                                    f"known in {legit[value.name].known:.1%} of LEGIT and "
                                    f"{cheat[value.name].known:.1%} of CHEAT windows: the two classes "
                                    f"may differ in recorder path or combat context. Human review is required"))

    for value in schema.values:
        channel = value.name
        index = schema.index(channel)
        pooled = encoded[:, :, index].mean(axis=1)
        if not np.any(np.isfinite(pooled)) or float(np.std(pooled)) == 0.0:
            continue
        auc = roc_auc(labels, pooled)
        if np.isnan(auc):
            continue
        separability = max(auc, 1.0 - auc)
        if separability >= SEPARABILITY_REVIEW:
            results.append(_finding("SEPARABLE", channel,
                                    f"window mean separates the classes with AUC {separability:.4f}. "
                                    f"A genuinely predictive feature looks like this too; confirm by "
                                    f"how the data was recorded, do not assume leakage or signal"))
    return results


def _finding(kind: str, channel: str, detail: str) -> dict:
    return {"kind": kind, "channel": channel, "detail": detail}


def blocking_findings(report: dict) -> list[dict]:
    """Findings that must stop a training run rather than be noted in a report."""
    return [item for item in report.get("findings", []) if item["kind"] == "STRUCTURAL_LEAKAGE"]


def separability_ranking(report: dict, top: int = 10) -> Sequence[dict]:
    return [item for item in report.get("findings", []) if item["kind"] == "SEPARABLE"][:top]

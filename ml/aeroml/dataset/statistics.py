"""Dataset statistics an operator should read before training anything.

The numbers that matter here are not accuracy-shaped. They are: how much legit combat time exists
(the false-positive budget is measured against it), how concentrated the data is in one player or
one client, and which channels are almost always unknown.
"""

from __future__ import annotations

from typing import Sequence

import numpy as np

from ..schema import FeatureSchema, default_schema
from .records import Session
from .windows import WindowIndex


def dataset_report(sessions: Sequence[Session], schema: FeatureSchema | None = None) -> dict:
    schema = schema or default_schema()
    by_label: dict[str, dict] = {}
    for session in sessions:
        bucket = by_label.setdefault(session.metadata.label, {
            "sessions": 0, "frames": 0, "durationHours": 0.0, "players": set(), "clients": set(),
            "cheatFamilies": set(), "protocols": set(),
        })
        bucket["sessions"] += 1
        bucket["frames"] += len(session)
        bucket["durationHours"] += session.metadata.duration_ms / 3_600_000.0
        bucket["players"].add(session.metadata.player_id)
        bucket["clients"].add(session.metadata.client_family or "unknown")
        if session.metadata.cheat_family:
            bucket["cheatFamilies"].add(session.metadata.cheat_family)
        bucket["protocols"].add(session.metadata.minecraft_protocol)
    for bucket in by_label.values():
        for key in ("players", "clients", "cheatFamilies", "protocols"):
            bucket[key] = sorted(str(value) for value in bucket[key])
    return {
        "sessions": len(sessions),
        "byLabel": by_label,
        "legitCombatHours": by_label.get("LEGIT", {}).get("durationHours", 0.0),
        "segments": sum(len(session.segments(schema)) for session in sessions),
    }


def missingness(index: WindowIndex, sample: int = 512, schema: FeatureSchema | None = None) -> dict[str, float]:
    """Fraction of known entries per nullable channel, estimated on a bounded sample of windows."""
    schema = schema or default_schema()
    if len(index) == 0:
        return {}
    step = max(1, len(index) // max(1, sample))
    chosen = list(range(0, len(index), step))[:sample]
    encoded = index.encode(chosen, schema)
    flat = encoded.reshape(-1, schema.feature_count)
    result: dict[str, float] = {}
    mask_index = schema.value_count
    for value in schema.values:
        if value.nullable:
            result[value.name] = float(flat[:, mask_index].mean())
            mask_index += 1
    return result


def clipping_rate(index: WindowIndex, sample: int = 512, schema: FeatureSchema | None = None) -> dict[str, float]:
    """How often a channel sits exactly on a clip bound; a high rate means the bound is too tight."""
    schema = schema or default_schema()
    if len(index) == 0:
        return {}
    step = max(1, len(index) // max(1, sample))
    chosen = list(range(0, len(index), step))[:sample]
    encoded = index.encode(chosen, schema)
    flat = encoded.reshape(-1, schema.feature_count)
    gate_index = schema.value_count
    result: dict[str, float] = {}
    for channel, value in enumerate(schema.values):
        if value.nullable:
            known = flat[:, gate_index] > 0
            gate_index += 1
        else:
            known = np.ones(flat.shape[0], dtype=bool)
        if not np.any(known):
            result[value.name] = 0.0
            continue
        column = flat[known, channel]
        at_bound = np.isclose(column, value.low) | np.isclose(column, value.high)
        result[value.name] = float(at_bound.mean())
    return result


def split_report(index: WindowIndex, indices_by_fold: dict[str, np.ndarray]) -> dict:
    """Per-fold window counts, label balance and the group values each fold contains."""
    report: dict[str, dict] = {}
    labels = index.labels()
    for fold, rows in indices_by_fold.items():
        if len(rows) == 0:
            report[fold] = {"windows": 0, "cheatFraction": 0.0, "sessions": 0, "clients": [], "players": 0}
            continue
        subset = index.subset(rows.tolist())
        report[fold] = {
            "windows": int(len(rows)),
            "cheatFraction": float(labels[rows].mean()),
            "sessions": len(set(subset.attribute("session").tolist())),
            "players": len(set(subset.attribute("player").tolist())),
            "clients": sorted(set(subset.attribute("client").tolist())),
            "cheatFamilies": sorted(set(subset.attribute("cheat_family").tolist())),
        }
    return report


def format_report(report: dict, indent: int = 0) -> str:
    lines = []
    pad = " " * indent
    for key, value in report.items():
        if isinstance(value, dict):
            lines.append(f"{pad}{key}:")
            lines.append(format_report(value, indent + 2))
        elif isinstance(value, float):
            lines.append(f"{pad}{key}: {value:.4f}")
        else:
            lines.append(f"{pad}{key}: {value}")
    return "\n".join(line for line in lines if line)

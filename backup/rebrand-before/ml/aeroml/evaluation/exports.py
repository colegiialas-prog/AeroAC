"""Prediction export and the two reports that drive the next training iteration.

Hard negatives are the honest sessions the model scored highest; hard positives are the cheating
sessions it scored lowest. Those two lists are worth more than any aggregate metric: they name the
specific recordings that would have to change for the model to improve, and they are what the next
collection round should try to produce more of.

Exports carry a pseudonymous player id at most. Usernames and UUIDs never leave the server.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

import numpy as np

from ..dataset.windows import WindowIndex
from ..schema import FeatureSchema, default_schema
from .sessions import SessionEvaluation
from ..reporting import write_json

PREDICTION_FIELDS = ("session", "window", "timestampSeconds", "label", "clientFamily",
                     "configuration", "cheatFamily", "modelVersion", "rawScore", "calibratedProbability",
                     "windowKind", "startFrame", "endFrame", "anchorFrame", "offsetNanos")


@dataclass
class PredictionTable:
    rows: list[dict]

    def __len__(self) -> int:
        return len(self.rows)

    def write_jsonl(self, path: Path | str) -> Path:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8") as handle:
            for row in self.rows:
                handle.write(json.dumps(row, separators=(",", ":"), allow_nan=False) + "\n")
        return path

    def write_npz(self, path: Path | str) -> Path:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        columns = {field: np.asarray([row[field] for row in self.rows]) for field in PREDICTION_FIELDS}
        np.savez_compressed(path, **columns)
        return path

    def write_parquet(self, path: Path | str) -> Path | None:
        """Parquet when pyarrow happens to be installed; JSONL is the format that always works."""
        try:
            import pyarrow  # noqa: F401
            import pyarrow.parquet as parquet
            from pyarrow import Table
        except ImportError:
            return None
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        table = Table.from_pydict({field: [row[field] for row in self.rows] for field in PREDICTION_FIELDS})
        parquet.write_table(table, path)
        return path

    def write(self, directory: Path | str, stem: str = "predictions") -> list[Path]:
        directory = Path(directory)
        written = [self.write_jsonl(directory / f"{stem}.jsonl")]
        parquet = self.write_parquet(directory / f"{stem}.parquet")
        if parquet is not None:
            written.append(parquet)
        return written


def build_predictions(index: WindowIndex, rows: Sequence[int], raw_scores: Sequence[float],
                      calibrated_scores: Sequence[float] | None, model_version: str) -> PredictionTable:
    rows = list(rows)
    raw_scores = np.asarray(raw_scores, dtype=np.float64)
    if calibrated_scores is None:
        calibrated = np.full(raw_scores.shape, np.nan)
    else:
        calibrated = np.asarray(calibrated_scores, dtype=np.float64)
    if not (len(rows) == raw_scores.size == calibrated.size):
        raise ValueError("rows, raw scores and calibrated scores must have the same length")
    if raw_scores.ndim != 1 or calibrated.ndim != 1 or not np.all(np.isfinite(raw_scores)):
        raise ValueError("raw scores must be a finite vector")
    if calibrated_scores is not None and (not np.all(np.isfinite(calibrated)) or np.any((calibrated < 0) | (calibrated > 1))):
        raise ValueError("calibrated scores must be finite probabilities")

    sessions = index.attribute("session")
    clients = index.attribute("client")
    configurations = index.attribute("configuration")
    families = index.attribute("cheat_family")
    offsets = index.offsets_seconds(rows)
    labels = index.labels()

    table = []
    for position, row in enumerate(rows):
        ref = index.refs[row]
        table.append({
            "session": str(sessions[row]),
            "window": int(row),
            "windowKind": index.kind, "startFrame": ref.start, "endFrame": ref.start + ref.length - 1,
            "anchorFrame": ref.anchor,
            "offsetNanos": int(index.session_of(row).offsets[ref.start + ref.length - 1]),
            "timestampSeconds": round(float(offsets[position]), 4),
            "label": "CHEAT" if labels[row] == 1 else "LEGIT",
            "clientFamily": str(clients[row]),
            "configuration": str(configurations[row]),
            "cheatFamily": str(families[row]),
            "modelVersion": model_version,
            "rawScore": float(raw_scores[position]),
            "calibratedProbability": None if np.isnan(calibrated[position]) else float(calibrated[position]),
        })
    return PredictionTable(table)


def _telemetry_summary(index: WindowIndex, rows: Sequence[int], schema: FeatureSchema) -> dict:
    """Cheap raw-frame summary so a reviewer can see what kind of combat this was."""
    if not rows:
        return {}
    values = np.concatenate([index.raw(row) for row in rows], axis=0)

    def stat(field: str) -> float | None:
        column = values[:, schema.raw_index(field)]
        column = column[np.isfinite(column)]
        return round(float(np.median(column)), 4) if column.size else None

    return {
        "medianPingMs": stat("PING_MS"),
        "medianRotationSpeed": stat("ROTATION_SPEED"),
        "medianAimErrorDegrees": stat("AIM_ERROR_TOTAL"),
        "medianTargetDistance": stat("DISTANCE_TO_TARGET"),
        "targetPresentRate": round(float(np.mean(values[:, schema.raw_index("TARGET_PRESENT")] == 1)), 4),
        "attackWindows": len(rows),
    }


def _session_rows(index: WindowIndex, rows: Sequence[int], session_id: str) -> list[int]:
    sessions = index.attribute("session")
    return [row for row in rows if str(sessions[row]) == session_id]


def hard_sessions(index: WindowIndex, rows: Sequence[int], scores: Sequence[float],
                  evaluations: Sequence[SessionEvaluation], *, label: str, top: int = 10,
                  worst_first: bool = True, schema: FeatureSchema | None = None) -> list[dict]:
    schema = schema or default_schema()
    rows = list(rows)
    scores = np.asarray(scores, dtype=np.float64)
    offsets = index.offsets_seconds(rows)
    by_session = {item.session_id: item for item in evaluations if item.label == label}
    if not by_session:
        return []
    ordered = sorted(
        by_session.values(),
        key=lambda item: -item.peak_probability if worst_first else item.peak_probability,
    )[:top]

    reports: list[dict] = []
    session_names = index.attribute("session")
    for evaluation in ordered:
        positions = [position for position, row in enumerate(rows)
                     if str(session_names[row]) == evaluation.session_id]
        if not positions:
            continue
        session_scores = scores[positions]
        ranking = np.argsort(session_scores)[::-1] if worst_first else np.argsort(session_scores)
        picked = [positions[i] for i in ranking[:5]]
        reports.append({
            "sessionId": evaluation.session_id,
            "playerId": evaluation.player_id,
            "label": evaluation.label,
            "clientFamily": evaluation.client_family,
            "configuration": evaluation.configuration,
            "cheatFamily": evaluation.cheat_family,
            "peakProbability": round(evaluation.peak_probability, 6),
            "meanTopK": round(evaluation.mean_top_k, 6),
            "peakRisk": round(evaluation.peak_risk, 4),
            "reachedSuspicious": evaluation.reached_suspicious,
            "maxConsecutiveAboveThreshold": evaluation.max_consecutive_above,
            "windows": [
                {
                    "window": int(rows[position]),
                    "timestampSeconds": round(float(offsets[position]), 3),
                    "score": round(float(scores[position]), 6),
                }
                for position in picked
            ],
            "telemetry": _telemetry_summary(index, [rows[position] for position in picked], schema),
        })
    return reports


def hard_negatives(index: WindowIndex, rows: Sequence[int], scores: Sequence[float],
                   evaluations: Sequence[SessionEvaluation], top: int = 10,
                   schema: FeatureSchema | None = None) -> list[dict]:
    """Honest sessions the model scored highest. The next collection round wants more of these."""
    return hard_sessions(index, rows, scores, evaluations, label="LEGIT", top=top,
                         worst_first=True, schema=schema)


def hard_positives(index: WindowIndex, rows: Sequence[int], scores: Sequence[float],
                   evaluations: Sequence[SessionEvaluation], top: int = 10,
                   schema: FeatureSchema | None = None) -> list[dict]:
    """Cheating sessions the model scored lowest: the configurations that currently get through."""
    return hard_sessions(index, rows, scores, evaluations, label="CHEAT", top=top,
                         worst_first=False, schema=schema)


def write_reports(directory: Path | str, *, predictions: PredictionTable | None = None,
                  negatives: Sequence[dict] = (), positives: Sequence[dict] = (),
                  simulation: dict | None = None) -> list[Path]:
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    if predictions is not None:
        written += predictions.write(directory)
    written.append(write_json(directory / "top_false_positive_sessions.json", list(negatives)))
    written.append(write_json(directory / "hard_positive_sessions.json", list(positives)))
    if simulation is not None:
        path = directory / "risk_simulation.json"
        write_json(path, simulation)
        written.append(path)
    return written

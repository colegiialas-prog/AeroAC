"""Session-level evaluation and risk simulation.

Window metrics measure a classifier. An anticheat is not a classifier — it is a process that
watches a player for minutes and accumulates. The questions that decide deployment are therefore:
how many honest players would reach SUSPICIOUS in an hour of ordinary combat, and how long a
cheating player survives before the same thing happens to them.

Everything here runs offline against a held-out fold, using the same accumulation rule the server
uses (``risk_sim`` is pinned against the Java engine by a shared fixture).
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Sequence

import numpy as np

from ..dataset.windows import WindowIndex
from ..dataset.exposure import combat_seconds, sample_seconds
from .risk_sim import CONFIRMED, Prediction, RiskConfig, SUSPICIOUS, Trace, WATCH, simulate


@dataclass
class SessionEvaluation:
    session_id: str
    label: str
    player_id: str
    client_family: str
    configuration: str
    cheat_family: str
    windows: int
    duration_hours: float
    peak_probability: float
    mean_top_k: float
    max_consecutive_above: int
    detected: bool
    time_to_detection_seconds: float
    peak_risk: float
    reached_watch: bool
    reached_suspicious: bool
    reached_confirmed: bool
    time_to_watch_seconds: float
    time_to_suspicious_seconds: float
    time_to_confirmed_seconds: float
    seconds_above_watch: float
    known_client: bool = True
    scenario: str = "unrecorded"
    assist_strength: str = "UNRECORDED"
    minecraft_protocol: int = 0
    median_ping_ms: float = float("nan")
    seconds_above_threshold: float = 0.0
    seconds_in_watch: float = 0.0
    risk_predictions: int = 0

    def to_dict(self) -> dict:
        return {
            "sessionId": self.session_id,
            "label": self.label,
            "playerId": self.player_id,
            "clientFamily": self.client_family,
            "configuration": self.configuration,
            "cheatFamily": self.cheat_family,
            "windows": self.windows,
            "durationHours": round(self.duration_hours, 5),
            "peakProbability": round(self.peak_probability, 6),
            "meanTopK": round(self.mean_top_k, 6),
            "maxConsecutiveAboveThreshold": self.max_consecutive_above,
            "detected": self.detected,
            "timeToDetectionSeconds": _finite_or_none(self.time_to_detection_seconds),
            "peakRisk": round(self.peak_risk, 5),
            "reachedWatch": self.reached_watch,
            "reachedSuspicious": self.reached_suspicious,
            "reachedConfirmed": self.reached_confirmed,
            "timeToWatchSeconds": _finite_or_none(self.time_to_watch_seconds),
            "timeToSuspiciousSeconds": _finite_or_none(self.time_to_suspicious_seconds),
            "timeToConfirmedSeconds": _finite_or_none(self.time_to_confirmed_seconds),
            "secondsAboveWatch": round(self.seconds_above_watch, 3),
            "knownClient": self.known_client,
            "scenario": self.scenario,
            "assistStrength": self.assist_strength,
            "minecraftProtocol": self.minecraft_protocol,
            "medianPingMs": _finite_or_none(self.median_ping_ms),
            "secondsAboveProbabilityThreshold": self.seconds_above_threshold,
            "secondsInWatch": self.seconds_in_watch,
            "riskPredictions": self.risk_predictions,
        }


def _finite_or_none(value: float) -> float | None:
    return None if not math.isfinite(value) else round(value, 3)


#: Ping buckets used for LEGIT breakdowns. A false positive rate that is fine at 30 ms and
#: terrible at 250 ms is two different results, not one average.
PING_BUCKETS = ((0, 30), (30, 60), (60, 100), (100, 150), (150, 250), (250, 10_000))


def ping_bucket(ping_ms: float) -> str:
    if not math.isfinite(ping_ms):
        return "unknown"
    for low, high in PING_BUCKETS:
        if low <= ping_ms < high:
            return f"{low}-{high if high < 10_000 else 'inf'}ms"
    return "unknown"


def _median_ping(session) -> float:
    column = session.column("PING_MS")
    finite = column[np.isfinite(column)]
    return float(np.median(finite)) if finite.size else float("nan")


def evaluate_sessions(index: WindowIndex, rows: Sequence[int], scores: Sequence[float], *,
                      threshold: float, risk_config: RiskConfig | None = None,
                      consecutive: int = 1, top_k: int = 10,
                      holdout_clients: Sequence[str] = (),
                      calibrated: bool = True, head_scores: dict[str, Sequence[float]] | None = None,
                      inference_interval_seconds: float = 0.5) -> list[SessionEvaluation]:
    """
    Groups windows by session, replays them in chronological order and simulates the risk engine.

    ``threshold`` is the window-level alarm level (usually taken from a TPR@FPR operating point);
    ``consecutive`` mirrors the risk engine's refusal to treat one spike as a detection.
    """
    rows = list(rows)
    scores = np.asarray(scores, dtype=np.float64)
    if scores.ndim != 1 or len(rows) != scores.size:
        raise ValueError("rows and scores must have the same length")
    if consecutive < 1 or top_k < 1 or inference_interval_seconds < 0 or not np.all(np.isfinite(scores)):
        raise ValueError("invalid session evaluation parameters")
    if head_scores and any(len(v) != len(rows) for v in head_scores.values()):
        raise ValueError("head scores must align with rows")
    offsets = index.offsets_seconds(rows)
    sessions = index.attribute("session")
    holdout = {client.strip().casefold() for client in holdout_clients}

    grouped: dict[str, list[int]] = {}
    for position, row in enumerate(rows):
        grouped.setdefault(str(sessions[row]), []).append(position)

    results: list[SessionEvaluation] = []
    for session_id, positions in grouped.items():
        order = sorted(positions, key=lambda position: offsets[position])
        session_scores = scores[order]
        session_offsets = offsets[order]
        session = index.session_of(rows[order[0]])
        metadata = session.metadata

        run = 0
        best_run = 0
        detection = math.inf
        previous_segment = None
        segments = session.segments()
        for position, score, offset in zip(order, session_scores, session_offsets):
            start = index.refs[rows[position]].start
            segment = next(a for a, b in segments if a <= start < b)
            if segment != previous_segment:
                run = 0
            previous_segment = segment
            run = run + 1 if score >= threshold else 0
            best_run = max(best_run, run)
            if run >= consecutive and not math.isfinite(detection):
                detection = float(offset)

        top = np.sort(session_scores)[::-1][:max(1, top_k)]
        predictions = []
        last_sent = -math.inf
        for position in order:
            if offsets[position] - last_sent + 1e-9 < inference_interval_seconds:
                continue
            last_sent = offsets[position]
            heads = {name: float(values[position]) for name, values in head_scores.items()} if head_scores else {"overall": float(scores[position])}
            predictions.append(Prediction(int(round(last_sent * 1e9)), heads, calibrated))
        trace = simulate(predictions, risk_config, start_nanos=0)
        end_nanos = metadata.duration_ms * 1_000_000
        above_watch = trace.seconds_at_or_above(WATCH, end_nanos)
        # Zero-order hold of observed predictions, counted only on observed combat frames.
        last_prediction = np.searchsorted(session_offsets, session.offsets / 1e9, side="right") - 1
        above = (last_prediction >= 0) & (session_scores[np.maximum(0, last_prediction)] >= threshold)
        active = (session.column("TARGET_PRESENT") == 1) | (session.column("ATTACK") == 1)
        probability_seconds = float(sample_seconds(session)[above & active].sum())
        results.append(SessionEvaluation(
            session_id=session_id,
            label=metadata.label,
            player_id=metadata.player_id,
            client_family=metadata.client_family or "unknown",
            configuration=metadata.configuration or "default",
            cheat_family=metadata.cheat_family or "none",
            scenario=metadata.scenario or "unrecorded",
            assist_strength=metadata.assist_strength,
            minecraft_protocol=metadata.minecraft_protocol,
            median_ping_ms=_median_ping(session),
            windows=len(order),
            duration_hours=combat_seconds(session) / 3600.0,
            peak_probability=float(session_scores.max()),
            mean_top_k=float(top.mean()),
            max_consecutive_above=int(best_run),
            detected=math.isfinite(detection),
            time_to_detection_seconds=detection,
            peak_risk=trace.peak_risk,
            reached_watch=trace.reached(WATCH),
            reached_suspicious=trace.reached(SUSPICIOUS),
            reached_confirmed=trace.reached(CONFIRMED),
            time_to_watch_seconds=trace.time_to(WATCH, 0),
            time_to_suspicious_seconds=trace.time_to(SUSPICIOUS, 0),
            time_to_confirmed_seconds=trace.time_to(CONFIRMED, 0),
            seconds_above_watch=above_watch,
            known_client=(metadata.client_family or "unknown").strip().casefold() not in holdout,
            seconds_above_threshold=probability_seconds,
            seconds_in_watch=max(0, above_watch - trace.seconds_at_or_above(SUSPICIOUS, end_nanos)),
            risk_predictions=len(predictions),
        ))
    return results


def false_positive_simulation(evaluations: Sequence[SessionEvaluation]) -> dict:
    """What an hour of honest combat would cost. CONFIRMED here is a measurement, not a ban."""
    legit = [item for item in evaluations if item.label == "LEGIT"]
    if not legit:
        return {"sessions": 0, "note": "no LEGIT sessions in this fold; false positives are unmeasured"}
    hours = sum(item.duration_hours for item in legit)
    players = {item.player_id for item in legit}
    reached = {
        WATCH: [item for item in legit if item.reached_watch],
        SUSPICIOUS: [item for item in legit if item.reached_suspicious],
        CONFIRMED: [item for item in legit if item.reached_confirmed],
    }
    watch_times = [item.seconds_in_watch for item in legit if item.reached_watch]
    return {
        "sessions": len(legit),
        "players": len(players),
        "combatHours": hours,
        "sessionsReaching": {state: len(items) for state, items in reached.items()},
        "playersReaching": {state: len({item.player_id for item in items}) for state, items in reached.items()},
        "perCombatHour": {
            state: (round(len(items) / hours, 4) if hours > 0 else None) for state, items in reached.items()
        },
        "playersPerCombatHour": {state: len({item.player_id for item in items}) / hours if hours > 0 else None for state, items in reached.items()},
        "medianSecondsInWatch": round(float(np.median(watch_times)), 2) if watch_times else None,
        "maxRisk": _describe([item.peak_risk for item in legit]),
        "maxConsecutiveWindows": _describe([float(item.max_consecutive_above) for item in legit]),
        "worstSessions": [item.session_id for item in sorted(legit, key=lambda x: -x.peak_risk)[:5]],
    }


def detection_simulation(evaluations: Sequence[SessionEvaluation]) -> dict:
    """Cheat detection rate and latency, reported separately for known and unknown clients."""
    cheat = [item for item in evaluations if item.label == "CHEAT"]
    if not cheat:
        return {"sessions": 0, "note": "no CHEAT sessions in this fold"}
    result = {"sessions": len(cheat), "overall": _detection_block(cheat)}
    known = [item for item in cheat if item.known_client]
    unknown = [item for item in cheat if not item.known_client]
    result["knownClient"] = _detection_block(known) if known else {
        "sessions": 0, "note": "no known-client cheat sessions in this fold"}
    if unknown:
        result["unknownClient"] = _detection_block(unknown)
    else:
        result["unknownClient"] = {
            "sessions": 0,
            "note": "UNKNOWN CLIENT GENERALIZATION NOT MEASURABLE: no held-out client family in this fold",
        }
    return result


def _detection_block(items: Sequence[SessionEvaluation]) -> dict:
    total = len(items)
    if total == 0:
        return {"sessions": 0}
    return {
        "sessions": total,
        "players": len({item.player_id for item in items}),
        "clientFamilies": sorted({item.client_family for item in items}),
        "configurations": sorted({item.configuration for item in items}),
        "reachedWatch": round(sum(item.reached_watch for item in items) / total, 4),
        "reachedSuspicious": round(sum(item.reached_suspicious for item in items) / total, 4),
        "reachedConfirmed": round(sum(item.reached_confirmed for item in items) / total, 4),
        "medianTimeToWatchSeconds": _median_finite([item.time_to_watch_seconds for item in items]),
        "medianTimeToSuspiciousSeconds": _median_finite([item.time_to_suspicious_seconds for item in items]),
        "medianTimeToConfirmedSeconds": _median_finite([item.time_to_confirmed_seconds for item in items]),
        "medianPeakProbability": round(float(np.median([item.peak_probability for item in items])), 4),
        "detectedFraction": sum(item.detected for item in items) / total,
        "medianDetectionSeconds": _median_finite([item.time_to_detection_seconds for item in items]),
        "peakRisk": _describe([item.peak_risk for item in items]),
    }


def _median_finite(values: Sequence[float]) -> float | None:
    finite = [value for value in values if math.isfinite(value)]
    return round(float(np.median(finite)), 2) if finite else None


def _describe(values: Sequence[float]) -> dict:
    array = np.asarray([value for value in values if math.isfinite(value)], dtype=float)
    if array.size == 0:
        return {"count": 0}
    return {
        "count": int(array.size),
        "mean": round(float(array.mean()), 4),
        "p50": round(float(np.percentile(array, 50)), 4),
        "p95": round(float(np.percentile(array, 95)), 4),
        "max": round(float(array.max()), 4),
    }


def simulation_report(index: WindowIndex, rows: Sequence[int], scores: Sequence[float], *,
                      threshold: float, risk_config: RiskConfig | None = None,
                      holdout_clients: Sequence[str] = (), consecutive: int = 1) -> dict:
    evaluations = evaluate_sessions(index, rows, scores, threshold=threshold, risk_config=risk_config,
                                    holdout_clients=holdout_clients, consecutive=consecutive)
    return {
        "threshold": threshold,
        "riskConfig": (risk_config or RiskConfig()).normalised().to_dict(),
        "falsePositives": false_positive_simulation(evaluations),
        "detection": detection_simulation(evaluations),
        "breakdowns": breakdowns(evaluations),
        "sessions": [item.to_dict() for item in evaluations],
    }


def _group_block(items: Sequence[SessionEvaluation]) -> dict:
    """One breakdown cell. Deliberately the same fields for every grouping, so cells compare."""
    hours = sum(item.duration_hours for item in items)
    return {
        "sessions": len(items),
        "players": len({item.player_id for item in items}),
        "combatHours": round(hours, 5),
        "windows": sum(item.windows for item in items),
        "reachedWatch": round(sum(item.reached_watch for item in items) / len(items), 4),
        "reachedSuspicious": round(sum(item.reached_suspicious for item in items) / len(items), 4),
        "reachedConfirmed": round(sum(item.reached_confirmed for item in items) / len(items), 4),
        "perCombatHour": {
            "WATCH": round(sum(item.reached_watch for item in items) / hours, 4) if hours > 0 else None,
            "SUSPICIOUS": round(sum(item.reached_suspicious for item in items) / hours, 4) if hours > 0 else None,
            "CONFIRMED": round(sum(item.reached_confirmed for item in items) / hours, 4) if hours > 0 else None,
        },
        "medianPeakProbability": round(float(np.median([item.peak_probability for item in items])), 4),
        "medianTimeToSuspiciousSeconds": _median_finite([item.time_to_suspicious_seconds for item in items]),
        "medianTimeToConfirmedSeconds": _median_finite([item.time_to_confirmed_seconds for item in items]),
        "peakRisk": _describe([item.peak_risk for item in items]),
    }


def _breakdown(items: Sequence[SessionEvaluation], key) -> dict:
    grouped: dict[str, list[SessionEvaluation]] = {}
    for item in items:
        grouped.setdefault(str(key(item)), []).append(item)
    return {name: _group_block(group) for name, group in sorted(grouped.items())}


def breakdowns(evaluations: Sequence[SessionEvaluation]) -> dict:
    """
    Analysis breakdowns, never model features.

    CHEAT is grouped by the four things that describe how the cheat was configured, because an
    aggregate detection rate hides the case that matters: strong settings detected and weak ones
    missed. LEGIT is grouped by scenario, protocol and ping, because a false positive rate that is
    fine on a local connection and terrible at 250 ms is two results, not one average.
    """
    cheat = [item for item in evaluations if item.label == "CHEAT"]
    legit = [item for item in evaluations if item.label == "LEGIT"]
    report: dict = {
        "note": "grouping metadata only; scenario, assistStrength, clientFamily and configuration "
                "are never model inputs",
    }
    if cheat:
        report["cheat"] = {
            "clientFamily": _breakdown(cheat, lambda item: item.client_family),
            "configuration": _breakdown(cheat, lambda item: f"{item.client_family}/{item.configuration}"),
            "assistStrength": _breakdown(cheat, lambda item: item.assist_strength),
            "scenario": _breakdown(cheat, lambda item: item.scenario),
            "cheatFamily": _breakdown(cheat, lambda item: item.cheat_family),
        }
        unrecorded = sum(1 for item in cheat if item.assist_strength == "UNRECORDED")
        if unrecorded:
            report["cheat"]["note"] = (f"{unrecorded} of {len(cheat)} CHEAT sessions predate the "
                                       f"assistStrength field; they group under UNRECORDED, not NONE")
    else:
        report["cheat"] = {"sessions": 0, "note": "no CHEAT sessions in this fold"}

    if legit:
        report["legit"] = {
            "scenario": _breakdown(legit, lambda item: item.scenario),
            "protocol": _breakdown(legit, lambda item: item.minecraft_protocol),
            "pingBucket": _breakdown(legit, lambda item: ping_bucket(item.median_ping_ms)),
        }
    else:
        report["legit"] = {"sessions": 0, "note": "no LEGIT sessions in this fold"}
    return report

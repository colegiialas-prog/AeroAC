"""What the dataset is actually made of.

The number that matters most here is not the total. It is the concentration: a corpus of forty
cheat sessions that are all one client on one configuration teaches a detector to recognise that
one build, and every aggregate metric will look excellent while it does. The report is arranged so
that this is visible at a glance rather than buried in a total.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Sequence

import numpy as np

from ..schema import FeatureSchema, default_schema
from ..dataset.records import Session
from ..dataset.exposure import combat_seconds
from ..dataset.windows import attack_windows, continuous_windows

PING_BUCKETS = ((0, 30), (30, 60), (60, 100), (100, 150), (150, 250), (250, 10_000))


@dataclass
class Group:
    sessions: int = 0
    players: set = field(default_factory=set)
    duration_seconds: float = 0.0
    combat_seconds: float = 0.0
    frames: int = 0
    attacks: int = 0
    windows: int = 0

    def add(self, session: Session, schema: FeatureSchema) -> None:
        self.sessions += 1
        self.players.add(session.metadata.player_id)
        self.duration_seconds += session.metadata.duration_ms / 1000.0
        self.combat_seconds += combat_seconds(session)
        self.frames += len(session)
        if len(session):
            self.attacks += int(np.count_nonzero(session.column("ATTACK", schema) == 1))

    def to_dict(self) -> dict:
        return {
            "sessions": self.sessions,
            "players": len(self.players),
            "durationMinutes": round(self.duration_seconds / 60.0, 2),
            "combatHours": self.combat_seconds / 3600.0,
            "frames": self.frames,
            "attacks": self.attacks,
            "windows": self.windows,
        }


def _window_counts(sessions: Sequence[Session], schema: FeatureSchema) -> dict[str, int]:
    """Attack windows per session id. Counted once so every grouping can reuse the same numbers."""
    counts: dict[str, int] = {session.metadata.session_id: 0 for session in sessions}
    for session in sessions:
        try:
            index = attack_windows([session], schema=schema)
        except ValueError:
            continue
        counts[session.metadata.session_id] = len(index)
    return counts


def cheat_distribution(sessions: Sequence[Session], schema: FeatureSchema | None = None) -> dict:
    """family -> clientFamily -> configuration, so a single-build corpus cannot hide in a total."""
    schema = schema or default_schema()
    cheat = [session for session in sessions if session.metadata.label == "CHEAT"]
    windows = _window_counts(cheat, schema)
    tree: dict[str, dict[str, dict[str, Group]]] = {}
    for session in cheat:
        family = (session.metadata.cheat_family or "unspecified").lower()
        client = session.metadata.client_family or "unknown"
        configuration = session.metadata.configuration or "default"
        group = tree.setdefault(family, {}).setdefault(client, {}).setdefault(configuration, Group())
        group.add(session, schema)
        group.windows += windows.get(session.metadata.session_id, 0)
    return {
        family: {
            client: {configuration: group.to_dict() for configuration, group in configurations.items()}
            for client, configurations in clients.items()
        }
        for family, clients in tree.items()
    }


def legit_distribution(sessions: Sequence[Session], schema: FeatureSchema | None = None) -> dict:
    """Distributions that decide whether a false positive rate measured here means anything."""
    schema = schema or default_schema()
    legit = [session for session in sessions if session.metadata.label == "LEGIT"]
    if not legit:
        return {"sessions": 0, "note": "no LEGIT sessions; false positive rate cannot be measured"}

    windows = _window_counts(legit, schema)
    ping_counts = {f"{low}-{high if high < 10_000 else 'inf'}ms": 0 for low, high in PING_BUCKETS}
    ping_counts["unknown"] = 0
    protocols: dict[str, int] = {}
    clients: dict[str, int] = {}
    per_player: dict[str, int] = {}
    durations: list[float] = []
    attack_rates: list[float] = []
    rotation: list[np.ndarray] = []
    aim_error: list[np.ndarray] = []
    distance: list[np.ndarray] = []
    missing_target: list[float] = []

    for session in legit:
        metadata = session.metadata
        protocols[str(metadata.minecraft_protocol)] = protocols.get(str(metadata.minecraft_protocol), 0) + 1
        clients[metadata.client_family or "unknown"] = clients.get(metadata.client_family or "unknown", 0) + 1
        per_player[metadata.player_id] = per_player.get(metadata.player_id, 0) + 1
        seconds = metadata.duration_ms / 1000.0
        durations.append(seconds)
        if not len(session):
            continue
        attacks = int(np.count_nonzero(session.column("ATTACK", schema) == 1))
        attack_rates.append(attacks / max(seconds / 60.0, 1e-9))
        rotation.append(_finite(session.column("ROTATION_SPEED", schema)))
        aim_error.append(_finite(session.column("AIM_ERROR_TOTAL", schema)))
        distance.append(_finite(session.column("DISTANCE_TO_TARGET", schema)))
        missing_target.append(1.0 - float(np.mean(session.column("TARGET_PRESENT", schema) == 1)))
        ping = _finite(session.column("PING_MS", schema))
        if ping.size:
            median = float(np.median(ping))
            for low, high in PING_BUCKETS:
                if low <= median and (median < high or high == 10_000):
                    ping_counts[f"{low}-{high if high < 10_000 else 'inf'}ms"] += 1
                    break
        else:
            ping_counts["unknown"] += 1

    return {
        "sessions": len(legit),
        "combatHours": sum(combat_seconds(s) for s in legit) / 3600.0,
        "wallHours": sum(durations) / 3600.0,
        "pingBucketUnit": "sessions, classified by median measured ping",
        "pseudonymousSessionsPerPlayer": dict(sorted(per_player.items())),
        "attackWindows": sum(windows.values()),
        "uniquePlayers": len(per_player),
        "sessionsPerPlayer": _describe(np.asarray(list(per_player.values()), dtype=float)),
        "pingBuckets": ping_counts,
        "protocolVersions": dict(sorted(protocols.items())),
        "clientFamilies": dict(sorted(clients.items())),
        "sessionDurationSeconds": _describe(np.asarray(durations, dtype=float)),
        "attacksPerMinute": _describe(np.asarray(attack_rates, dtype=float)),
        "rotationSpeedDegPerSample": _describe(np.concatenate(rotation) if rotation else np.zeros(0)),
        "aimErrorDegrees": _describe(np.concatenate(aim_error) if aim_error else np.zeros(0)),
        "targetDistanceBlocks": _describe(np.concatenate(distance) if distance else np.zeros(0)),
        "missingTargetTelemetryRate": _describe(np.asarray(missing_target, dtype=float)),
    }


def concentration(sessions: Sequence[Session]) -> dict:
    """Share of the largest client, configuration and player. High numbers invalidate generalisation."""
    cheat = [session for session in sessions if session.metadata.label == "CHEAT"]
    legit = [session for session in sessions if session.metadata.label == "LEGIT"]
    return {
        "cheat": _shares(cheat),
        "legit": _shares(legit),
    }


def concentration_warnings(sessions: Sequence[Session], threshold: float = 0.8) -> list[str]:
    warnings: list[str] = []
    report = concentration(sessions)
    for label, shares in report.items():
        if not shares:
            warnings.append(f"no {label.upper()} sessions at all")
            continue
        if shares["clientFamilies"] < 2 and label == "cheat":
            warnings.append("only one cheat client family: unknown-client generalisation is not measurable")
        for key in ("largestClientShare", "largestConfigurationShare", "largestPlayerShare"):
            if shares[key] >= threshold:
                warnings.append(f"{label}: {key} is {shares[key]:.0%}; the corpus is effectively one source")
    return warnings


def _shares(sessions: Sequence[Session]) -> dict:
    if not sessions:
        return {}
    clients: dict[str, int] = {}
    configurations: dict[str, int] = {}
    players: dict[str, int] = {}
    for session in sessions:
        metadata = session.metadata
        client = metadata.client_family or "unknown"
        clients[client] = clients.get(client, 0) + 1
        key = f"{client}/{metadata.configuration or 'default'}"
        configurations[key] = configurations.get(key, 0) + 1
        players[metadata.player_id] = players.get(metadata.player_id, 0) + 1
    total = len(sessions)
    return {
        "sessions": total,
        "clientFamilies": len(clients),
        "configurations": len(configurations),
        "players": len(players),
        "largestClientShare": max(clients.values()) / total,
        "largestConfigurationShare": max(configurations.values()) / total,
        "largestPlayerShare": max(players.values()) / total,
    }


def window_totals(sessions: Sequence[Session], continuous_length: int = 96,
                  stride: int = 8, schema: FeatureSchema | None = None) -> dict:
    schema = schema or default_schema()
    usable = [session for session in sessions if len(session)]
    if not usable:
        return {"attackWindows": 0, "continuousWindows": 0}
    try:
        attack = len(attack_windows(usable, schema=schema))
    except ValueError:
        # Sessions recorded with different window settings cannot share one attack window size.
        attack = sum(len(attack_windows([session], schema=schema)) for session in usable)
    return {
        "attackWindows": attack,
        "continuousWindows": len(continuous_windows(usable, continuous_length, stride=stride, schema=schema)),
        "continuousLength": continuous_length,
        "continuousStride": stride,
    }


def _finite(values: np.ndarray) -> np.ndarray:
    return values[np.isfinite(values)]


def _describe(values: np.ndarray) -> dict:
    values = np.asarray(values, dtype=float)
    values = values[np.isfinite(values)]
    if values.size == 0:
        return {"count": 0}
    return {
        "count": int(values.size),
        "mean": round(float(values.mean()), 4),
        "std": round(float(values.std()), 4),
        "min": round(float(values.min()), 4),
        "p01": round(float(np.percentile(values, 1)), 4),
        "p50": round(float(np.percentile(values, 50)), 4),
        "p99": round(float(np.percentile(values, 99)), 4),
        "max": round(float(values.max()), 4),
    }

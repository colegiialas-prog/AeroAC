"""Leakage-safe dataset splits.

Never split individual windows of one session at random. Windows from one session overlap, and a
player's own motion is a fingerprint: a random split measures how well the model memorised that
player, not whether it detects the behaviour. The unit of assignment here is always a whole group,
at minimum a session and by default a player.

``unknown_client_split`` answers the only question that matters for a new cheat: a client family
that never appeared anywhere in training, validation or calibration is held out entirely for test.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Mapping, Sequence

import numpy as np

from .windows import WindowIndex

# Unit separator: cannot appear in a pseudonym, client name or configuration string.
GROUP_SEPARATOR = chr(31)
FOLDS = ("train", "validation", "calibration", "test")
DEFAULT_FRACTIONS = {"train": 0.6, "validation": 0.15, "calibration": 0.1, "test": 0.15}


@dataclass
class Split:
    indices: dict[str, np.ndarray]
    manifest: dict = field(default_factory=dict)

    def __getitem__(self, fold: str) -> np.ndarray:
        return self.indices[fold]

    @property
    def train(self) -> np.ndarray:
        return self.indices["train"]

    @property
    def validation(self) -> np.ndarray:
        return self.indices["validation"]

    @property
    def calibration(self) -> np.ndarray:
        return self.indices["calibration"]

    @property
    def test(self) -> np.ndarray:
        return self.indices["test"]

    def sizes(self) -> dict[str, int]:
        return {fold: int(len(values)) for fold, values in self.indices.items()}


def group_keys(index: WindowIndex, group_by: Sequence[str]) -> np.ndarray:
    if not group_by:
        raise ValueError("group_by must name at least one attribute; window-level splits leak")
    if "session" not in group_by and "player" not in group_by:
        raise ValueError("group_by must include 'session' or 'player'; anything coarser leaks windows")
    # Connected components preserve EACH requested attribute, not just their Cartesian tuple.
    parent = list(range(len(index)))
    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    for name in dict.fromkeys(["session", *group_by]):
        seen = {}
        for row, value in enumerate(index.attribute(name)):
            value = str(value).strip().casefold() if name == "client" else str(value)
            if value in seen:
                left, right = find(row), find(seen[value])
                parent[max(left, right)] = min(left, right)
            else:
                seen[value] = row
    sessions = index.attribute("session")
    return np.asarray([str(sessions[find(i)]) for i in range(len(index))], dtype=object)


def group_split(index: WindowIndex, *, fractions: Mapping[str, float] | None = None,
                group_by: Sequence[str] = ("player",), seed: int = 0) -> Split:
    """Assigns whole groups to folds, greedily balancing both window count and label mix."""
    fractions = dict(fractions or DEFAULT_FRACTIONS)
    unknown = set(fractions) - set(FOLDS)
    if unknown:
        raise ValueError(f"unknown folds {sorted(unknown)}")
    if any(not np.isfinite(v) or v < 0 for v in fractions.values()):
        raise ValueError("fractions must be finite and nonnegative")
    fractions = {k: v for k, v in fractions.items() if v > 0}
    total_fraction = sum(fractions.values())
    if not 0.999 <= total_fraction <= 1.001:
        raise ValueError(f"fractions must sum to 1, got {total_fraction}")
    keys = group_keys(index, group_by)
    labels = index.labels()
    members: dict[str, list[int]] = {}
    for position, key in enumerate(keys):
        members.setdefault(key, []).append(position)

    if len(members) < len(fractions):
        raise ValueError(
            f"{len(members)} groups by {tuple(group_by)} cannot fill {len(fractions)} folds; "
            "record more independent players; do not weaken isolation to fill folds"
        )
    order = sorted(members, key=lambda key: (-len(members[key]), key))
    random.Random(seed).shuffle(order)
    order.sort(key=lambda key: -len(members[key]))

    assignment: dict[str, str] = {}
    counts = {fold: {"total": 0, "cheat": 0, "legit": 0} for fold in fractions}
    totals = {"total": len(index), "cheat": int(labels.sum()), "legit": int(len(index) - labels.sum())}
    # Seed each fold with one group before the greedy pass. Greedy alone can starve a small fold
    # when there are few groups, and an empty calibration fold only shows up much later.
    seeded = sorted(fractions, key=lambda fold: (-fractions[fold], fold))
    for fold, key in zip(seeded, order):
        assignment[key] = fold
        rows = members[key]
        cheat = int(labels[rows].sum())
        counts[fold]["total"] += len(rows)
        counts[fold]["cheat"] += cheat
        counts[fold]["legit"] += len(rows) - cheat
    for key in order[len(seeded):]:
        rows = members[key]
        cheat = int(labels[rows].sum())
        contribution = {"total": len(rows), "cheat": cheat, "legit": len(rows) - cheat}
        best_fold, best_score = None, None
        for fold, fraction in fractions.items():
            score = 0.0
            for metric, size in totals.items():
                if size == 0:
                    continue
                deficit = fraction * size - counts[fold][metric]
                score += deficit / size
            if best_score is None or score > best_score:
                best_fold, best_score = fold, score
        assignment[key] = best_fold
        for metric, value in contribution.items():
            counts[best_fold][metric] += value

    indices = {fold: np.asarray([], dtype=np.int64) for fold in FOLDS}
    for fold in fractions:
        rows = [position for position, key in enumerate(keys) if assignment[key] == fold]
        indices[fold] = np.asarray(sorted(rows), dtype=np.int64)
    split = Split(indices, {
        "strategy": "group",
        "groupBy": list(group_by),
        "fractions": fractions,
        "seed": seed,
        "groups": {fold: sorted(key for key, value in assignment.items() if value == fold) for fold in fractions},
        "sizes": {fold: int(len(values)) for fold, values in indices.items()},
    })
    verify(split, index, group_by)
    split.manifest["folds"] = membership(index, split)
    return split


def unknown_client_split(index: WindowIndex, holdout_clients: Sequence[str], *,
                         fractions: Mapping[str, float] | None = None,
                         group_by: Sequence[str] = ("player",), seed: int = 0) -> Split:
    """
    Every window of a held-out client family goes to test, including all of its configurations.

    When a group (by default a player) recorded on a held-out client also recorded on another one,
    the whole group follows into test. Keeping half of that player in training would leak their
    motion signature into the very benchmark that is supposed to measure generalisation, and the
    extra legit windows are useful negatives in test anyway. The manifest records how many windows
    were pulled across so the cost of the holdout is visible.
    """
    holdout = {client.strip().casefold() for client in holdout_clients}
    if not holdout:
        raise ValueError("name at least one client family to hold out")
    clients = np.asarray([str(value).strip().casefold() for value in index.attribute("client")], dtype=object)
    present = set(clients)
    missing = sorted(holdout - present)
    if missing:
        raise ValueError(f"client families not present in the dataset: {missing}")
    inner_fractions = dict(fractions or DEFAULT_FRACTIONS)

    keys = group_keys(index, group_by)
    direct = {position for position, client in enumerate(clients) if client in holdout}
    tainted_groups = {keys[position] for position in direct}
    held_rows = sorted(position for position, key in enumerate(keys) if key in tainted_groups)
    remaining = [position for position in range(len(index)) if keys[position] not in tainted_groups]
    if not remaining:
        raise ValueError("holding out these clients leaves nothing to train on")

    inner = index.subset(remaining)
    inner_split = group_split(inner, fractions=inner_fractions, group_by=group_by, seed=seed)

    mapping = np.asarray(remaining, dtype=np.int64)
    indices = {fold: mapping[inner_split[fold]] for fold in inner_fractions}
    indices["test"] = np.sort(np.r_[indices.get("test", []), held_rows]).astype(np.int64)
    for fold in FOLDS:
        indices.setdefault(fold, np.asarray([], dtype=np.int64))
    split = Split(indices, {
        "strategy": "unknown-client",
        "holdoutClients": sorted(holdout),
        "groupBy": list(group_by),
        "innerFractions": inner_fractions,
        "seed": seed,
        "holdoutWindows": len(direct),
        "windowsPulledInByGroup": len(held_rows) - len(direct),
        "sizes": {fold: int(len(values)) for fold, values in indices.items()},
    })
    verify(split, index, group_by)
    trained_clients = set(clients[np.concatenate([indices[f] for f in ("train", "validation", "calibration")])]) \
        if any(len(indices[f]) for f in ("train", "validation", "calibration")) else set()
    leaked = trained_clients & holdout
    if leaked:
        raise AssertionError(f"held-out clients appeared in training folds: {sorted(leaked)}")
    split.manifest["folds"] = membership(index, split)
    return split


def verify(split: Split, index: WindowIndex, group_by: Sequence[str] = ("player",)) -> None:
    """Raises when a group or a session straddles two folds, or when a window is used twice."""
    keys = group_keys(index, group_by)
    sessions = index.attribute("session")
    seen: dict[str, str] = {}
    seen_sessions: dict[str, str] = {}
    used: set[int] = set()
    for fold, rows in split.indices.items():
        for row in rows.tolist():
            if type(row) is not int or not 0 <= row < len(index):
                raise AssertionError(f"invalid window index {row}")
            if row in used:
                raise AssertionError(f"window {row} appears in more than one fold")
            used.add(row)
            key = keys[row]
            if seen.setdefault(key, fold) != fold:
                raise AssertionError(f"group {key!r} is in both {seen[key]} and {fold}")
            session = sessions[row]
            if seen_sessions.setdefault(session, fold) != fold:
                raise AssertionError(f"session {session} is in both {seen_sessions[session]} and {fold}")
    if len(used) != len(index):
        missing = len(index) - len(used)
        raise AssertionError(f"{missing} windows were not assigned to any fold")


def membership(index: WindowIndex, split: Split) -> dict:
    result = {}
    for fold, rows in split.indices.items():
        sessions = {index.session_of(int(row)).metadata.session_id: index.session_of(int(row)) for row in rows}
        result[fold] = {
            "sessions": [{"sessionId": sid, "playerId": s.metadata.player_id,
                          "clientFamily": (s.metadata.client_family or "unknown").strip().casefold(),
                          "configuration": s.metadata.configuration or "default", "label": s.metadata.label}
                         for sid, s in sorted(sessions.items())],
            "windowIndices": rows.tolist(),
        }
    return result

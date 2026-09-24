"""Window extraction with the same rules the server applies.

Windows are referenced, not materialised: a dataset of a hundred thousand windows would be gigabytes
if every overlapping copy were stored, so a window is a (session, start, length) reference and the
raw values are cut out of the session only when a batch needs them.

A window never crosses a segment boundary or a tick hole, and an attack window is emitted only when
the full before/after history exists — exactly the condition ``AttackWindowBuilder`` enforces in Java.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Sequence

import numpy as np

from ..schema import FeatureSchema, default_schema
from .features import encode_window
from .records import Session

LABEL_TO_INDEX = {"LEGIT": 0, "CHEAT": 1}


@dataclass(frozen=True)
class WindowRef:
    session_index: int
    start: int
    length: int
    anchor: int  # index of the attack sample inside the session, or -1 for a continuous window


@dataclass
class WindowIndex:
    sessions: list[Session]
    refs: list[WindowRef]
    length: int
    kind: str

    def __len__(self) -> int:
        return len(self.refs)

    def session_of(self, index: int) -> Session:
        return self.sessions[self.refs[index].session_index]

    def raw(self, index: int) -> np.ndarray:
        ref = self.refs[index]
        return self.sessions[ref.session_index].values[ref.start:ref.start + ref.length]

    def encode(self, indices: Sequence[int] | None = None, schema: FeatureSchema | None = None) -> np.ndarray:
        schema = schema or default_schema()
        chosen = range(len(self.refs)) if indices is None else indices
        chosen = list(chosen)
        out = np.zeros((len(chosen), self.length, schema.feature_count), dtype=np.float32)
        for position, index in enumerate(chosen):
            out[position] = encode_window(self.raw(index), schema)
        return out

    def labels(self) -> np.ndarray:
        """1 for CHEAT, 0 for LEGIT. UNLABELED sessions must never reach a supervised split."""
        values = np.empty(len(self.refs), dtype=np.int64)
        for position, ref in enumerate(self.refs):
            label = self.sessions[ref.session_index].metadata.label
            if label not in LABEL_TO_INDEX:
                raise ValueError(
                    f"session {self.sessions[ref.session_index].metadata.session_id} is {label}; "
                    "production predictions are not ground truth and cannot be used as labels"
                )
            values[position] = LABEL_TO_INDEX[label]
        return values

    def attribute(self, name: str) -> np.ndarray:
        values = []
        for ref in self.refs:
            metadata = self.sessions[ref.session_index].metadata
            if name == "session":
                values.append(metadata.session_id)
            elif name == "player":
                values.append(metadata.player_id)
            elif name == "client":
                values.append(metadata.client_family or "unknown")
            elif name == "configuration":
                values.append(metadata.configuration or "default")
            elif name == "cheat_family":
                values.append(metadata.cheat_family or "none")
            elif name == "protocol":
                values.append(str(metadata.minecraft_protocol))
            else:
                raise KeyError(name)
        return np.asarray(values, dtype=object)

    def offsets_seconds(self, indices: Sequence[int] | None = None) -> np.ndarray:
        """Seconds from the start of each window's own session to the window's last sample.

        Detection latency is measured against the session, not the wall clock: sessions are
        recorded at different times and only the elapsed time inside one of them is comparable.
        """
        chosen = range(len(self.refs)) if indices is None else indices
        out = []
        for index in chosen:
            ref = self.refs[index]
            session = self.sessions[ref.session_index]
            out.append(float(session.offsets[ref.start + ref.length - 1]) / 1e9)
        return np.asarray(out, dtype=np.float64)

    def subset(self, indices: Iterable[int]) -> "WindowIndex":
        return WindowIndex(self.sessions, [self.refs[i] for i in indices], self.length, self.kind)

    def describe(self) -> str:
        labels = [self.sessions[ref.session_index].metadata.label for ref in self.refs]
        cheat = sum(1 for label in labels if label == "CHEAT")
        return (
            f"{self.kind} windows={len(self.refs)} length={self.length} "
            f"cheat={cheat} legit={len(labels) - cheat} sessions={len({r.session_index for r in self.refs})}"
        )


def attack_windows(sessions: Sequence[Session], before: int | None = None, after: int | None = None,
                   schema: FeatureSchema | None = None) -> WindowIndex:
    """One window per attack that has complete history on both sides inside a single segment."""
    schema = schema or default_schema()
    sessions = list(sessions)
    if not sessions:
        return WindowIndex([], [], 0, "attack")
    before = sessions[0].metadata.attack_before if before is None else before
    after = sessions[0].metadata.attack_after if after is None else after
    length = before + after + 1
    if before < 0 or after < 0:
        raise ValueError("before and after must be nonnegative")
    refs: list[WindowRef] = []
    attack_column = schema.raw_index("ATTACK")
    for session_index, session in enumerate(sessions):
        # Raw recordings contain every sample; live ring settings do not limit offline history.
        attacks = np.flatnonzero(session.values[:, attack_column] == 1)
        for start, end in session.segments(schema):
            usable = attacks[(attacks >= start + before) & (attacks + after < end)]
            for anchor in usable:
                refs.append(WindowRef(session_index, int(anchor) - before, length, int(anchor)))
    return WindowIndex(sessions, refs, length, "attack")


def continuous_windows(sessions: Sequence[Session], length: int, stride: int = 1,
                       require_target: bool = False, schema: FeatureSchema | None = None) -> WindowIndex:
    """Sliding windows inside each segment. ``stride`` controls how much neighbouring windows overlap."""
    schema = schema or default_schema()
    if length < 1:
        raise ValueError("length must be positive")
    if stride < 1:
        raise ValueError("stride must be positive")
    sessions = list(sessions)
    refs: list[WindowRef] = []
    present_column = schema.raw_index("TARGET_PRESENT")
    for session_index, session in enumerate(sessions):
        for start, end in session.segments(schema):
            for begin in range(start, end - length + 1, stride):
                if require_target and not np.any(session.values[begin:begin + length, present_column] == 1):
                    continue
                refs.append(WindowRef(session_index, begin, length, -1))
    return WindowIndex(sessions, refs, length, "continuous")


def balance_report(index: WindowIndex) -> dict:
    """Windows per session and per client, so a single long session cannot dominate a split unnoticed."""
    sessions = index.attribute("session")
    clients = index.attribute("client")
    labels = index.labels()
    per_session: dict[str, int] = {}
    per_client: dict[str, int] = {}
    for session_id, client in zip(sessions, clients):
        per_session[session_id] = per_session.get(session_id, 0) + 1
        per_client[client] = per_client.get(client, 0) + 1
    largest = max(per_session.values()) if per_session else 0
    return {
        "windows": len(index),
        "cheatFraction": float(np.mean(labels)) if len(index) else 0.0,
        "sessions": len(per_session),
        "largestSessionShare": largest / len(index) if len(index) else 0.0,
        "windowsPerClient": per_client,
    }

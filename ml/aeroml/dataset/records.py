"""Reading Aero AC raw sessions.

A session is JSONL: one frame, event or observation per line, plus a metadata file. The loader is
deliberately strict about the schema and deliberately tolerant about a crash: a half-written last
line is dropped and reported, never silently interpolated.

Frame tick ids are consecutive while the recorder keeps up. A dropped record (the bounded queue
filled) leaves a hole, so a tick gap is treated exactly like an explicit segment boundary: it ends
the run. Nothing is ever reconstructed across a hole.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Iterator, Sequence

import numpy as np

from ..schema import FeatureSchema, default_schema

LABELS = ("LEGIT", "CHEAT", "UNLABELED")
LAB_LABEL_SOURCES = {"LEGIT": "LAB_LEGIT", "CHEAT": "LAB_CHEAT", "UNLABELED": "PRODUCTION_UNLABELED"}
FRAME_TYPE = "frame"
EVENT_TYPES = ("attack", "swing", "reachObservation")


@dataclass(frozen=True)
class SessionMetadata:
    session_id: str
    player_id: str
    start_timestamp: int
    label: str
    label_source: str
    cheat_family: str | None
    client_family: str | None
    configuration: str | None
    scenario: str | None
    assist_strength: str
    notes: str | None
    minecraft_protocol: int
    plugin_version: str
    continuous_size: int
    attack_before: int
    attack_after: int
    duration_ms: int
    declared_frames: int
    dropped_records: int
    complete: bool
    usable_without_review: bool
    close_reason: str | None
    failure: str | None
    schema_version: int
    dataset_version: str

    @property
    def is_lab_labelled(self) -> bool:
        return self.label_source in ("LAB_LEGIT", "LAB_CHEAT")

    @property
    def has_collection_metadata(self) -> bool:
        """False for sessions recorded before scenario/assistStrength existed.

        Absent metadata is reported as absent rather than defaulted to something plausible: a
        breakdown that silently files every old CHEAT session under NONE would be a lie about how
        the corpus was collected.
        """
        return self.scenario is not None or self.assist_strength != "UNRECORDED"

    @property
    def group_key(self) -> tuple[str, str, str]:
        """Grouping identity for leakage-safe splits: player, client family, configuration."""
        return (self.player_id, self.client_family or "unknown", self.configuration or "default")


@dataclass
class SessionQuality:
    truncated_last_line: bool = False
    malformed_lines: int = 0
    tick_gaps: int = 0
    unknown_record_types: int = 0
    frame_count_mismatch: bool = False

    @property
    def clean(self) -> bool:
        return (
            not self.truncated_last_line
            and self.malformed_lines == 0
            and self.tick_gaps == 0
            and self.unknown_record_types == 0
            and not self.frame_count_mismatch
        )

    def describe(self) -> str:
        return (
            f"truncated={self.truncated_last_line} malformed={self.malformed_lines} "
            f"tickGaps={self.tick_gaps} unknownTypes={self.unknown_record_types} countMismatch={self.frame_count_mismatch}"
        )


@dataclass
class Session:
    metadata: SessionMetadata
    ticks: np.ndarray
    offsets: np.ndarray
    values: np.ndarray
    events: list[dict] = field(default_factory=list)
    quality: SessionQuality = field(default_factory=SessionQuality)
    path: Path | None = None

    def __len__(self) -> int:
        return int(self.ticks.shape[0])

    @property
    def usable_for_training(self) -> bool:
        """Technical usability only. It says nothing about whether the label is true."""
        return (
            self.metadata.complete
            and self.metadata.dropped_records == 0
            and self.metadata.failure is None
            and self.quality.clean
            and len(self) > 0
        )

    def column(self, raw_field: str, schema: FeatureSchema | None = None) -> np.ndarray:
        schema = schema or default_schema()
        return self.values[:, schema.raw_index(raw_field)]

    def segments(self, schema: FeatureSchema | None = None) -> list[tuple[int, int]]:
        """Half-open [start, end) index ranges with no boundary and no tick hole inside."""
        schema = schema or default_schema()
        if len(self) == 0:
            return []
        starts = self.values[:, schema.raw_index("SEGMENT_START")] == 1
        breaks = np.zeros(len(self), dtype=bool)
        breaks[0] = True
        elapsed = np.diff(self.offsets)
        breaks[1:] = starts[1:] | (np.diff(self.ticks) != 1) | (elapsed <= 0) | (elapsed > 150_000_000)
        edges = list(np.flatnonzero(breaks)) + [len(self)]
        return [(int(edges[i]), int(edges[i + 1])) for i in range(len(edges) - 1)]


#: Written by the recorder. UNRECORDED is this loader's marker for a session from before the
#: field existed, and is deliberately distinct from the recorder's own UNKNOWN.
ASSIST_STRENGTHS = ("NONE", "VERY_LOW", "LOW", "MEDIUM", "HIGH", "UNKNOWN")
UNRECORDED = "UNRECORDED"


def _scenario(value) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError(f"scenario must be a string, got {type(value).__name__}")
    cleaned = value.strip().lower().replace(" ", "-").replace("_", "-")
    return cleaned or None


def _assist_strength(data: dict, label: str, path: Path) -> str:
    raw = data.get("assistStrength")
    if raw is None:
        return UNRECORDED
    if not isinstance(raw, str) or raw.upper() not in ASSIST_STRENGTHS:
        raise ValueError(f"{path}: assistStrength {raw!r} is not one of {ASSIST_STRENGTHS}")
    value = raw.upper()
    if label == "LEGIT" and value != "NONE":
        raise ValueError(f"{path}: LEGIT session declares assistStrength {value}")
    if label == "CHEAT" and value == "NONE":
        raise ValueError(f"{path}: CHEAT session declares assistStrength NONE")
    return value


def read_metadata(path: Path) -> SessionMetadata:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    for name in ("sessionId", "playerId"):
        if not isinstance(data.get(name), str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", data[name]):
            raise ValueError(f"{path}: invalid {name}")
    for name in ("complete", "usableWithoutReview"):
        if name in data and type(data[name]) is not bool:
            raise ValueError(f"{path}: {name} must be a boolean")
    for name in ("startTimestamp", "minecraftProtocol", "continuousSize", "attackBefore", "attackAfter", "durationMs", "frames", "droppedRecords", "schemaVersion"):
        if name in data and (type(data[name]) is not int or data[name] < 0):
            raise ValueError(f"{path}: {name} must be a nonnegative integer")
    if data.get("datasetVersion", "dataset-v1") != "dataset-v1":
        raise ValueError(f"{path}: unsupported dataset version")
    label = data["label"]
    if label not in LABELS:
        raise ValueError(f"{path}: unknown label {label!r}")
    if LAB_LABEL_SOURCES[label] != data["labelSource"]:
        raise ValueError(f"{path}: label {label} is incompatible with source {data['labelSource']}")
    return SessionMetadata(
        session_id=data["sessionId"],
        player_id=data["playerId"],
        start_timestamp=int(data["startTimestamp"]),
        label=label,
        label_source=data["labelSource"],
        cheat_family=data.get("cheatFamily") or None,
        client_family=data.get("clientFamily") or None,
        configuration=data.get("configuration") or None,
        scenario=_scenario(data.get("scenario")),
        assist_strength=_assist_strength(data, label, path),
        notes=data.get("notes") or None,
        minecraft_protocol=int(data["minecraftProtocol"]),
        plugin_version=data.get("pluginVersion") or "unknown",
        continuous_size=int(data["continuousSize"]),
        attack_before=int(data["attackBefore"]),
        attack_after=int(data["attackAfter"]),
        duration_ms=int(data.get("durationMs", 0)),
        declared_frames=int(data.get("frames", 0)),
        dropped_records=int(data.get("droppedRecords", 0)),
        complete=bool(data.get("complete", False)),
        usable_without_review=bool(data.get("usableWithoutReview", False)),
        close_reason=data.get("closeReason"),
        failure=data.get("failure"),
        schema_version=int(data["schemaVersion"]),
        dataset_version=data.get("datasetVersion", "dataset-v1"),
    )


def load_session(metadata_path: Path | str, raw_path: Path | str | None = None,
                 schema: FeatureSchema | None = None, keep_events: bool = True) -> Session:
    schema = schema or default_schema()
    metadata_path = Path(metadata_path)
    metadata = read_metadata(metadata_path)
    if not schema.readable_raw_version(metadata.schema_version):
        raise ValueError(
            f"{metadata_path}: raw schema {metadata.schema_version} cannot be read by this pipeline "
            f"(reads 1..{schema.raw_schema_version}); convert the session or use the matching version"
        )
    # Older recordings are read as they were written; fields added later load as unknown.
    recorded_fields = schema.raw_fields_for(metadata.schema_version)
    if raw_path is None:
        raw_path = metadata_path.parent.parent / "raw" / f"session-{metadata.session_id}.jsonl"
    raw_path = Path(raw_path)
    quality = SessionQuality()
    ticks: list[int] = []
    offsets: list[int] = []
    rows: list[np.ndarray] = []
    events: list[dict] = []
    # A one-line lookahead distinguishes a torn last line from corruption before a valid line.
    def records():
        with raw_path.open(encoding="utf-8") as stream:
            previous = None
            for line in stream:
                if not line.strip():
                    continue
                if previous is not None:
                    yield previous, False
                previous = line
            if previous is not None:
                yield previous, True
    last_offset = -1
    for line, is_last in records():
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            # Only the final line can be torn by a crash; anything earlier is real corruption.
            if is_last:
                quality.truncated_last_line = True
            else:
                quality.malformed_lines += 1
            continue
        kind = record.get("type")
        if record.get("schemaVersion") != metadata.schema_version or record.get("sessionId") != metadata.session_id:
            raise ValueError(f"{raw_path}: record has wrong schemaVersion or sessionId")
        offset = record.get("offsetNanos")
        tick = record.get("tick", record.get("precedingTick", 0))
        if type(offset) is not int or offset < 0 or type(tick) is not int or tick < 0:
            raise ValueError(f"{raw_path}: invalid tick or timestamp")
        # Events can share frame timestamps. Records must still be chronological.
        if offset < last_offset:
            raise ValueError(f"{raw_path}: chronology violation: timestamps are decreasing")
        last_offset = offset
        if kind == FRAME_TYPE:
            if record.get("schemaVersion") != metadata.schema_version:
                raise ValueError(f"{raw_path}: frame with schemaVersion {record.get('schemaVersion')}")
            if record.get("sessionId") != metadata.session_id:
                raise ValueError(f"{raw_path}: frame belongs to session {record.get('sessionId')}")
            ticks.append(int(record["tick"]))
            offsets.append(int(record["offsetNanos"]))
            rows.append(_frame_row(record["values"], schema, raw_path, recorded_fields))
        elif kind is None:
            quality.unknown_record_types += 1
        else:
            if kind not in EVENT_TYPES and not kind.startswith("flag:") and kind not in _MARKERS:
                quality.unknown_record_types += 1
            if keep_events:
                events.append(record)
    values = np.asarray(rows, dtype=np.float64) if rows else np.zeros((0, len(schema.raw_fields)))
    tick_array = np.asarray(ticks, dtype=np.int64)
    quality.frame_count_mismatch = len(ticks) != metadata.declared_frames
    if tick_array.size > 1:
        quality.tick_gaps = int(np.count_nonzero(np.diff(tick_array) != 1))
        if np.any(np.diff(tick_array) <= 0):
            raise ValueError(f"{raw_path}: frame ticks are not strictly increasing")
        if np.any(np.diff(offsets) <= 0):
            raise ValueError(f"{raw_path}: chronology violation: frame timestamps are not strictly increasing")
    return Session(
        metadata=metadata,
        ticks=tick_array,
        offsets=np.asarray(offsets, dtype=np.int64),
        values=values,
        events=events,
        quality=quality,
        path=raw_path,
    )


_MARKERS = (
    "teleport",
    "respawnOrWorldChange",
    "movementGap",
    "cancelledMovement",
    "invalidMovement",
    "CONFIG_RELOAD",
)


def _frame_row(values: dict, schema: FeatureSchema, path: Path,
               recorded: Sequence[str] | None = None) -> np.ndarray:
    recorded = tuple(schema.raw_fields if recorded is None else recorded)
    if len(values) != len(recorded):
        raise ValueError(
            f"{path}: frame carries {len(values)} fields, schema declares {len(recorded)}"
        )
    row = np.full(len(schema.raw_fields), np.nan, dtype=np.float64)
    present = set(recorded)
    for index, name in enumerate(schema.raw_fields):
        if name not in present:
            continue
        if name not in values:
            raise ValueError(f"{path}: frame is missing field {name}")
        raw = values[name]
        if raw is not None and (type(raw) not in (int, float) or not np.isfinite(raw)):
            raise ValueError(f"{path}: field {name} must be a finite number or null")
        row[index] = np.nan if raw is None else float(raw)
    return row


def iter_session_paths(root: Path | str) -> Iterator[Path]:
    metadata_dir = Path(root) / "metadata"
    if not metadata_dir.is_dir():
        raise FileNotFoundError(f"{root} does not look like a dataset root (no metadata/)")
    yield from sorted(metadata_dir.glob("session-*.json"))


def load_dataset(root: Path | str, schema: FeatureSchema | None = None, *,
                 labels: Sequence[str] | None = None, require_usable: bool = True,
                 keep_events: bool = False) -> list[Session]:
    """Loads every session under ``root``. Unusable sessions are skipped, never silently repaired."""
    schema = schema or default_schema()
    sessions: list[Session] = []
    seen = set()
    for path in iter_session_paths(root):
        session = load_session(path, schema=schema, keep_events=keep_events)
        if session.metadata.session_id in seen:
            raise ValueError(f"duplicate sessionId {session.metadata.session_id}")
        seen.add(session.metadata.session_id)
        if labels is not None and session.metadata.label not in labels:
            continue
        if require_usable and not session.usable_for_training:
            continue
        sessions.append(session)
    return sessions


def unusable_sessions(root: Path | str, schema: FeatureSchema | None = None) -> list[tuple[Session, str]]:
    """Everything ``load_dataset`` would skip, with the reason, so the operator can review it."""
    schema = schema or default_schema()
    rejected: list[tuple[Session, str]] = []
    for path in iter_session_paths(root):
        session = load_session(path, schema=schema, keep_events=False)
        if session.usable_for_training:
            continue
        reasons = []
        if not session.metadata.complete:
            reasons.append("incomplete")
        if session.metadata.dropped_records:
            reasons.append(f"dropped={session.metadata.dropped_records}")
        if session.metadata.failure:
            reasons.append(f"failure={session.metadata.failure}")
        if not session.quality.clean:
            reasons.append(session.quality.describe())
        if len(session) == 0:
            reasons.append("no frames")
        rejected.append((session, ", ".join(reasons)))
    return rejected


def attack_ticks(session: Session, schema: FeatureSchema | None = None) -> np.ndarray:
    schema = schema or default_schema()
    attacks = session.column("ATTACK", schema) == 1
    return session.ticks[attacks]


def summarise(sessions: Iterable[Session]) -> dict:
    sessions = list(sessions)
    by_label: dict[str, int] = {}
    frames = 0
    duration_ms = 0
    for session in sessions:
        by_label[session.metadata.label] = by_label.get(session.metadata.label, 0) + 1
        frames += len(session)
        duration_ms += session.metadata.duration_ms
    return {
        "sessions": len(sessions),
        "byLabel": by_label,
        "frames": frames,
        "durationHours": duration_ms / 3_600_000.0,
        "players": len({session.metadata.player_id for session in sessions}),
        "clients": sorted({session.metadata.client_family or "unknown" for session in sessions}),
    }

"""Staff verdicts on evidence snapshots, as a weak and optional source of training labels.

A moderator who watched a snapshot replay and pressed CHEAT or LEGIT in the server GUI wrote
``datasets/reviews/<eventId>.json`` next to the snapshot. That is weaker evidence than a lab
recording, where the label is known because the recorder chose the client: a moderator can be wrong,
and the snapshot exists only because the model already fired, so it is biased towards what the model
already sees. Hence the rules here:

* label source ``STAFF_REVIEWED``, never mistaken for ``LAB_*``;
* nothing uses these unless training is run with ``--include-staff-reviews``;
* their windows go to the train fold only, never validation, calibration or test, and a reviewed
  player who also appears in any of those folds is dropped rather than leaked across the split.
"""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

import numpy as np

from ..schema import FeatureSchema, default_schema
from .records import Session, SessionMetadata, SessionQuality, _frame_row

REVIEW_SOURCE = "STAFF_REVIEWED"
VERDICTS = ("CHEAT", "LEGIT")
#: A staff CHEAT verdict in this phase means aim assistance: it is what the replay shows.
STAFF_CHEAT_FAMILY = "aim-assist-staff-verdict"


def load_reviewed_snapshots(root: Path | str, schema: FeatureSchema | None = None) -> list[Session]:
    """Every reviewed snapshot under a dataset root, as a one-segment session. Bad files are skipped."""
    schema = schema or default_schema()
    root = Path(root)
    reviews = root / "reviews"
    if not reviews.is_dir():
        return []
    sessions: list[Session] = []
    for path in sorted(reviews.glob("*.json")):
        try:
            session = _session(path, root / "snapshots", schema)
        except (OSError, ValueError, KeyError, TypeError):
            continue
        if session is not None:
            sessions.append(session)
    return sessions


def _session(review_path: Path, snapshots: Path, schema: FeatureSchema) -> Session | None:
    review = json.loads(review_path.read_text(encoding="utf-8"))
    verdict = review.get("verdict")
    name = str(review.get("snapshotFile", ""))
    if verdict not in VERDICTS or not name or "/" in name or "\\" in name or ".." in name:
        return None
    snapshot = json.loads((snapshots / name).read_text(encoding="utf-8"))
    if snapshot.get("kind") != "evidenceSnapshot" or snapshot.get("eventId") != review.get("eventId"):
        return None
    version = int(snapshot["schemaVersion"])
    fields = schema.raw_fields_for(version)
    frames = snapshot.get("frames") or []
    if not frames:
        return None
    rows = np.asarray([_frame_row(frame["values"], schema, review_path, fields) for frame in frames])
    ticks = np.asarray([int(frame["tick"]) for frame in frames], dtype=np.int64)
    offsets = np.asarray([int(frame["offsetNanos"]) for frame in frames], dtype=np.int64)
    offsets = offsets - offsets.min()
    if np.any(np.diff(ticks) <= 0) or np.any(np.diff(offsets) <= 0):
        return None
    cheat = verdict == "CHEAT"
    metadata = SessionMetadata(
        session_id="review-" + str(snapshot["eventId"]),
        player_id=str(snapshot["playerId"]),
        start_timestamp=int(snapshot["timestamp"]),
        label=verdict,
        label_source=REVIEW_SOURCE,
        cheat_family=STAFF_CHEAT_FAMILY if cheat else None,
        client_family=None,
        configuration=None,
        scenario=None,
        assist_strength="UNKNOWN" if cheat else "NONE",
        notes=f"staff review by {review.get('reviewer', '?')} at {review.get('reviewedAt', '?')}",
        minecraft_protocol=int(snapshot.get("minecraftProtocol") or 0),
        plugin_version="evidence-snapshot",
        continuous_size=len(frames),
        attack_before=int(snapshot.get("framesBefore", 0)),
        attack_after=int(snapshot.get("framesAfter", 0)),
        duration_ms=int(offsets[-1] // 1_000_000),
        declared_frames=len(frames),
        dropped_records=0,
        complete=True,
        usable_without_review=False,
        close_reason="snapshot",
        failure=None,
        schema_version=version,
        dataset_version="dataset-v1",
    )
    quality = SessionQuality(tick_gaps=int(np.count_nonzero(np.diff(ticks) != 1)))
    # A gap inside a snapshot is a segment boundary, like any recording; windows never span it.
    return Session(metadata=metadata, ticks=ticks, offsets=offsets, values=rows, quality=replace(quality, tick_gaps=0),
                   path=review_path)


def train_only(index, split, reviewed_ids: set[str]) -> dict:
    """Moves reviewed windows into the train fold, dropping any whose player sits in another fold.

    Mutates ``split.indices`` and returns counts for the provenance record.
    """
    if not reviewed_ids:
        return {"added": 0, "droppedForLeakage": 0}
    sessions = index.attribute("session")
    players = index.attribute("player")
    is_reviewed = np.asarray([str(session) in reviewed_ids for session in sessions])
    reviewed_rows = np.flatnonzero(is_reviewed)
    held_players = set()
    for fold in ("validation", "calibration", "test"):
        rows = np.asarray(split.indices[fold], dtype=np.int64)
        held_players |= {str(players[row]) for row in rows if not is_reviewed[row]}
    keep = np.asarray([row for row in reviewed_rows if str(players[row]) not in held_players], dtype=np.int64)
    for fold in list(split.indices):
        rows = np.asarray(split.indices[fold], dtype=np.int64)
        split.indices[fold] = rows[~is_reviewed[rows]] if rows.size else rows
    split.indices["train"] = np.sort(np.concatenate([split.indices["train"], keep]).astype(np.int64))
    return {"added": int(keep.size), "droppedForLeakage": int(reviewed_rows.size - keep.size)}

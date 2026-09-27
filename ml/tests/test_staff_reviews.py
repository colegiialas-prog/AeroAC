"""Moderator verdicts on evidence snapshots: loaded only on request, and only ever into train."""

from __future__ import annotations

import json
import uuid

import numpy as np

from aeroml.dataset.records import load_dataset
from aeroml.dataset.reviews import REVIEW_SOURCE, load_reviewed_snapshots, train_only
from aeroml.dataset.splits import group_split
from aeroml.dataset.windows import attack_windows
from aeroml.tools.make_synthetic import generate_dataset


def _snapshot_from(root, session_path, player_id, verdict):
    """Wraps real recorded frames the way the server writes a snapshot, plus a review of it."""
    lines = [json.loads(line) for line in session_path.read_text(encoding="utf-8").splitlines()]
    frames = [{"tick": r["tick"], "offsetNanos": r["offsetNanos"], "values": r["values"]}
              for r in lines if r.get("type") == "frame"][:96]
    event = str(uuid.uuid4())
    name = f"1000-{event}.json"
    (root / "snapshots").mkdir(exist_ok=True)
    (root / "snapshots" / name).write_text(json.dumps({
        "schemaVersion": lines[0]["schemaVersion"], "kind": "evidenceSnapshot", "label": "UNLABELED",
        "labelSource": "PRODUCTION_UNLABELED", "eventId": event, "playerId": player_id, "timestamp": 1000,
        "minecraftProtocol": 767, "framesBefore": 64, "framesAfter": len(frames) - 64, "frames": frames}))
    (root / "reviews").mkdir(exist_ok=True)
    (root / "reviews" / f"{event}.json").write_text(json.dumps({
        "kind": "snapshotReview", "eventId": event, "snapshotFile": name, "verdict": verdict,
        "labelSource": REVIEW_SOURCE, "reviewer": "mod", "reviewedAt": 2000}))
    return event


def test_reviewed_snapshots_become_weak_sessions_and_land_in_train_only(tmp_path, schema):
    root = tmp_path / "data"
    generate_dataset(root, players=8, seconds=14.0, seed=5)
    lab = load_dataset(root, schema)
    raw = sorted((root / "raw").glob("session-*.jsonl"))
    outsider = _snapshot_from(root, raw[0], "staff-only-player", "CHEAT")
    insider_player = lab[1].metadata.player_id
    _snapshot_from(root, raw[1], insider_player, "LEGIT")
    (root / "reviews" / "junk.json").write_text("{ nope")

    reviewed = load_reviewed_snapshots(root, schema)
    assert len(reviewed) == 2
    by_id = {s.metadata.session_id: s for s in reviewed}
    cheat = by_id["review-" + outsider]
    assert cheat.metadata.label == "CHEAT" and cheat.metadata.label_source == REVIEW_SOURCE
    assert not cheat.metadata.is_lab_labelled
    assert cheat.usable_for_training and len(cheat) > 0

    index = attack_windows(lab + reviewed)
    split = group_split(index, group_by=("player",), seed=0)
    reviewed_ids = {s.metadata.session_id for s in reviewed}
    placement = train_only(index, split, reviewed_ids)
    sessions = index.attribute("session")
    for fold in ("validation", "calibration", "test"):
        assert not any(str(sessions[row]) in reviewed_ids for row in split.indices[fold])
    assert placement["added"] > 0
    train_sessions = {str(sessions[row]) for row in split.train}
    assert "review-" + outsider in train_sessions
    players = index.attribute("player")
    held = {str(players[row]) for fold in ("validation", "calibration", "test") for row in split.indices[fold]}
    if insider_player in held:
        assert placement["droppedForLeakage"] > 0
        assert not any(str(sessions[row]).startswith("review-") and str(players[row]) == insider_player
                       for row in split.train)
    assert len(np.unique(split.train)) == len(split.train)


def test_nothing_is_loaded_without_a_reviews_directory(tmp_path, schema):
    assert load_reviewed_snapshots(tmp_path, schema) == []

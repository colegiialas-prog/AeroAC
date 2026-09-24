from __future__ import annotations

import json

import numpy as np
import pytest

from aeroml.dataset.records import (
    attack_ticks,
    load_dataset,
    load_session,
    read_metadata,
    summarise,
    unusable_sessions,
)
from aeroml.tools.make_synthetic import generate_session


def _metadata_path(root, session_id):
    return root / "metadata" / f"session-{session_id}.json"


def _raw_path(root, session_id):
    return root / "raw" / f"session-{session_id}.jsonl"


def test_loads_a_synthetic_corpus(sessions):
    # The generator writes one LEGIT and one CHEAT session per player. Asserting the relationship
    # rather than a literal count keeps this honest when the fixture is widened for a split that
    # needs more players, while still catching a generator that quietly produces fewer sessions.
    report = summarise(sessions)
    players = report["players"]
    assert players >= 6
    assert report["byLabel"] == {"CHEAT": players, "LEGIT": players}
    assert len(sessions) == 2 * players
    for session in sessions:
        assert len(session) > 0
        assert session.usable_for_training
        assert session.quality.clean


def test_frames_carry_every_declared_field(sessions, schema):
    session = sessions[0]
    assert session.values.shape[1] == len(schema.raw_fields)
    assert np.all(np.diff(session.ticks) == 1)
    assert session.column("TARGET_PRESENT", schema).max() == 1


def test_attack_ticks_come_from_the_frame_flag(sessions, schema):
    ticks = attack_ticks(sessions[0], schema)
    assert ticks.size > 0
    assert np.all(np.isin(ticks, sessions[0].ticks))


def test_segments_split_on_boundaries_and_on_tick_holes(tmp_path, schema):
    root = tmp_path / "ds"
    session_id = generate_session(root, label="LEGIT", client="vanilla", configuration="default",
                                  player_id="p", seconds=6.0, seed=1)
    raw = _raw_path(root, session_id)
    lines = raw.read_text(encoding="utf-8").strip().split("\n")
    # Drop a frame to simulate a record the bounded queue had to discard.
    frames = [index for index, line in enumerate(lines) if json.loads(line).get("type") == "frame"]
    removed = frames[len(frames) // 2]
    raw.write_text("\n".join(lines[:removed] + lines[removed + 1:]) + "\n", encoding="utf-8")
    session = load_session(_metadata_path(root, session_id), schema=schema)
    assert session.quality.tick_gaps == 1
    assert not session.usable_for_training
    segments = session.segments(schema)
    assert len(segments) == 2
    for start, end in segments:
        assert np.all(np.diff(session.ticks[start:end]) == 1)


def test_a_torn_last_line_is_reported_not_repaired(tmp_path, schema):
    root = tmp_path / "ds"
    session_id = generate_session(root, label="LEGIT", client="vanilla", configuration="default",
                                  player_id="p", seconds=4.0, seed=2)
    raw = _raw_path(root, session_id)
    text = raw.read_text(encoding="utf-8")
    raw.write_text(text[:-40], encoding="utf-8")
    session = load_session(_metadata_path(root, session_id), schema=schema)
    assert session.quality.truncated_last_line
    assert not session.usable_for_training
    assert len(session) > 0


def test_corruption_in_the_middle_is_counted_separately(tmp_path, schema):
    root = tmp_path / "ds"
    session_id = generate_session(root, label="LEGIT", client="vanilla", configuration="default",
                                  player_id="p", seconds=4.0, seed=3)
    raw = _raw_path(root, session_id)
    lines = raw.read_text(encoding="utf-8").strip().split("\n")
    lines[3] = "{not json"
    raw.write_text("\n".join(lines) + "\n", encoding="utf-8")
    session = load_session(_metadata_path(root, session_id), schema=schema)
    assert session.quality.malformed_lines == 1
    assert not session.quality.truncated_last_line


def test_a_frame_missing_a_field_is_an_error_not_a_zero(tmp_path, schema):
    root = tmp_path / "ds"
    session_id = generate_session(root, label="LEGIT", client="vanilla", configuration="default",
                                  player_id="p", seconds=3.0, seed=4)
    raw = _raw_path(root, session_id)
    lines = raw.read_text(encoding="utf-8").strip().split("\n")
    record = json.loads(lines[0])
    record["values"].pop("YAW")
    lines[0] = json.dumps(record)
    raw.write_text("\n".join(lines) + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="fields"):
        load_session(_metadata_path(root, session_id), schema=schema)


def test_an_incompatible_raw_schema_is_refused(tmp_path, schema):
    root = tmp_path / "ds"
    session_id = generate_session(root, label="LEGIT", client="vanilla", configuration="default",
                                  player_id="p", seconds=3.0, seed=5)
    path = _metadata_path(root, session_id)
    data = json.loads(path.read_text(encoding="utf-8"))
    data["schemaVersion"] = 99
    path.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(ValueError, match="cannot be read"):
        load_session(path, schema=schema)


def test_a_label_that_contradicts_its_source_is_refused(tmp_path):
    root = tmp_path / "ds"
    session_id = generate_session(root, label="CHEAT", client="x", configuration="c",
                                  player_id="p", seconds=3.0, assist=0.5, seed=6)
    path = _metadata_path(root, session_id)
    data = json.loads(path.read_text(encoding="utf-8"))
    data["labelSource"] = "LAB_LEGIT"
    path.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(ValueError, match="incompatible"):
        read_metadata(path)


def test_incomplete_sessions_are_skipped_and_explained(tmp_path, schema):
    root = tmp_path / "ds"
    good = generate_session(root, label="LEGIT", client="vanilla", configuration="default",
                            player_id="p1", seconds=4.0, seed=7)
    bad = generate_session(root, label="CHEAT", client="x", configuration="c",
                           player_id="p2", seconds=4.0, assist=0.5, seed=8)
    path = _metadata_path(root, bad)
    data = json.loads(path.read_text(encoding="utf-8"))
    data["complete"] = False
    data["droppedRecords"] = 12
    path.write_text(json.dumps(data), encoding="utf-8")
    loaded = load_dataset(root, schema)
    assert [session.metadata.session_id for session in loaded] == [good]
    rejected = unusable_sessions(root, schema)
    assert len(rejected) == 1
    assert "incomplete" in rejected[0][1] and "dropped=12" in rejected[0][1]


def test_group_key_identifies_player_client_and_configuration(sessions):
    keys = {session.metadata.group_key for session in sessions}
    assert len(keys) == len(sessions)
    for player, client, configuration in keys:
        assert player and client and configuration

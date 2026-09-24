import copy
import json
from dataclasses import replace
from pathlib import Path
import numpy as np
import pytest
from aeroml.audit.sessions import audit_session, AuditPolicy
from aeroml.audit.report import inspect_dataset
from aeroml.audit.leakage import check_feature_schema, check_encoder_invariance, check_split, check_normalization, check_provenance
from aeroml.audit.features import feature_report, channel_stats
from aeroml.dataset.features import encode_window
from aeroml.dataset.records import load_session
from aeroml.dataset.exposure import combat_seconds
from aeroml.dataset.windows import attack_windows
from aeroml.dataset.splits import group_split, Split
from aeroml.dataset.normalize import Normalizer
from aeroml.dataset.lineage import bind_manifest, restore_index


def test_quality_verdicts_have_reasons(sessions):
    session = copy.deepcopy(sessions[0])
    policy = AuditPolicy(min_duration_seconds=1, min_frames=1, min_attacks=1, min_segment_frames=1)
    assert audit_session(session, policy).verdict == "GOOD"
    session.metadata = replace(session.metadata, complete=False, dropped_records=3)
    result = audit_session(session, policy)
    assert result.verdict == "REVIEW"
    assert "incomplete metadata" in result.reasons
    session.quality.frame_count_mismatch = True
    assert audit_session(session, policy).verdict == "UNUSABLE"


def test_real_combat_hours_exclude_idle_and_gaps(sessions):
    s = copy.deepcopy(sessions[0])
    s.values[:, 0] = 0
    s.values[:, s.values.shape[1] - 1] = 0
    from aeroml.schema import default_schema
    schema = default_schema()
    s.values[:, schema.raw_index("TARGET_PRESENT")] = 0
    s.values[:, schema.raw_index("ATTACK")] = 0
    assert combat_seconds(s) == 0
    s.values[0, schema.raw_index("ATTACK")] = 1
    s.offsets[1:] += 10_000_000_000
    assert combat_seconds(s) == pytest.approx(0.05)


@pytest.mark.parametrize("name", ["username", "UUID", "pseudonym", "playerId", "sessionId", "entityId", "transactionId", "datasetLabel", "clientFamily", "configurationName", "GrimFlag", "REACH_EVIDENCE"])
def test_blacklist_catches_aliases_and_derived_identity(schema, name):
    bad = replace(schema, values=(replace(schema.values[0], name=name), *schema.values[1:]))
    assert check_feature_schema(bad)
    derived = replace(schema, values=(replace(schema.values[0], name="harmless", source="derived." + name), *schema.values[1:]))
    assert check_feature_schema(derived)


@pytest.mark.parametrize("source", ["PLAYER_X", "PLAYER_Y", "TARGET_Z", "TARGET_MIN_X", "AIM_POINT_X", "SERVER_TICK", "REACH_EVIDENCE"])
def test_blacklist_rejects_absolute_and_check_sources(schema, source):
    bad = replace(schema, values=(replace(schema.values[0], source=source), *schema.values[1:]))
    assert check_feature_schema(bad)


def test_encoder_ignores_identity_world_translation_and_flag_context(schema):
    assert not check_feature_schema(schema)
    assert not check_encoder_invariance(schema)


def test_same_raw_input_is_identical_for_legit_and_cheat_labels(sessions, schema):
    s = copy.deepcopy(sessions[0])
    before = encode_window(s.values[:31], schema)
    s.metadata = replace(s.metadata, label="CHEAT", client_family="secret", configuration="label-copy", player_id="other-player")
    np.testing.assert_array_equal(before, encode_window(s.values[:31], schema))


def test_feature_report_covers_masks_and_does_not_call_binary_endpoints_clipping(sessions, schema):
    index = attack_windows(sessions)
    report = feature_report(index, sample=64)
    assert set(report["all"]) == set(schema.channel_names)
    assert report["all"]["ATTACK"]["clipping"] == 0
    assert "LEGIT" in report and "CHEAT" in report
    raw = index.raw(0).copy()
    raw[:, schema.raw_index("PING_MS")] = 1e7
    encoded = encode_window(raw, schema)[None]
    original = encode_window(raw, schema, clip=False)[None]
    assert channel_stats(encoded, schema, original)["PING_MS"].clipping == 1


def test_composite_grouping_still_isolates_each_player(sessions):
    index = attack_windows(sessions)
    split = group_split(index, group_by=("player", "session"))
    assert not [f for f in check_split(index, split, ("player", "session")) if f.severity == "ERROR"]
    seen = {}
    for fold, rows in split.indices.items():
        for player in index.attribute("player")[rows]:
            assert seen.setdefault(player, fold) == fold


def test_invalid_window_indices_fail_closed(sessions):
    index = attack_windows(sessions)
    split = group_split(index)
    split.indices["test"][0] = -1
    assert check_split(index, split)[0].severity == "ERROR"


def test_normalizer_really_refits_train_only_and_manifest_binds_content(sessions):
    index = attack_windows(sessions)
    split = group_split(index)
    norm = Normalizer.fit(index.encode(split.train))
    assert not check_normalization(norm, index, split)
    norm.mean[0] += 10
    assert check_normalization(norm, index, split)
    bind_manifest(index, split)
    restored, _ = restore_index(sessions, split.manifest)
    assert len(restored) == len(index)
    corrupted = copy.deepcopy(sessions)
    corrupted[0].metadata = replace(corrupted[0].metadata, notes="changed after split")
    with pytest.raises(ValueError, match="changed"):
        restore_index(corrupted, split.manifest)


def test_provenance_refuses_calibration_for_epoch_selection(sessions):
    index = attack_windows(sessions)
    split = group_split(index)
    p = {"splitManifest": split.manifest, "evaluation": {"lineage": {"normalizationFold": "train", "epochSelectionFold": "calibration", "calibrationFold": "calibration"}}}
    assert any(f.check == "provenance/lineage" for f in check_provenance(p))
    p["splitManifest"]["folds"]["test"]["sessions"][0]["playerId"] = p["splitManifest"]["folds"]["train"]["sessions"][0]["playerId"]
    assert any(f.check == "provenance/membership" for f in check_provenance(p))


def test_audit_report_lists_required_totals(synthetic_root):
    report, _ = inspect_dataset(synthetic_root, feature_sample=32)
    assert report["sessionsTotal"] == 24
    assert report["sessionsByLabel"]["LEGIT"] == 12
    assert report["combatHours"]["LEGIT"] > 0
    assert report["attackWindows"] > 0 and report["continuousWindows"] > 0
    assert not report["leakage"]


def test_loader_rejects_backward_timestamps_and_wrong_event_session(tmp_path, sessions):
    original = sessions[0]
    metadata = original.path.parent.parent / "metadata" / (original.path.stem + ".json")
    records = [json.loads(line) for line in original.path.read_text().splitlines()]
    records[-1]["offsetNanos"] = 0
    raw = tmp_path / "bad.jsonl"
    raw.write_text("\n".join(json.dumps(r) for r in records))
    with pytest.raises(ValueError, match="chronology"):
        load_session(metadata, raw)
    records = [json.loads(line) for line in original.path.read_text().splitlines()]
    event = next(r for r in records if r["type"] != "frame")
    event["sessionId"] = "wrong"
    raw.write_text("\n".join(json.dumps(r) for r in records))
    with pytest.raises(ValueError, match="sessionId"):
        load_session(metadata, raw)


def test_empty_golden_never_passes_as_human_reviewed(sessions):
    from aeroml.tools.review_session import validate_golden
    with pytest.raises(ValueError, match="human reviews"):
        validate_golden(sessions, {"manifestVersion": 1, "reviewStatus": "PENDING_REAL_DATA", "sessions": []})


def test_the_shipped_golden_manifest_loads_and_is_honestly_pending():
    """The file in datasets/manifests must be readable by the code that verifies it."""
    from aeroml.dataset.manifest import GoldenManifest

    path = Path(__file__).resolve().parents[2] / "datasets" / "manifests" / "golden-v1.json"
    manifest = GoldenManifest.load(path)
    assert manifest.name == "golden-v1"
    assert len(manifest) == 0
    assert not manifest.reviewed
    # Round-tripping must not change the shape the review CLI writes and reads.
    assert GoldenManifest.from_dict(manifest.to_dict()).to_dict() == manifest.to_dict()


def test_review_session_and_the_manifest_reader_share_one_format(tmp_path, sessions):
    """A manifest written by the review CLI must load through the same parser that verifies it."""
    import json
    import subprocess
    import sys

    from aeroml.dataset.lineage import session_digest
    from aeroml.dataset.manifest import GoldenManifest, validate_golden

    session = sessions[0]
    entry = {
        "sessionId": session.metadata.session_id,
        "expectedLabel": session.metadata.label,
        "sha256": session_digest(session),
        "reviewer": "operator-1",
        "reviewedAt": "2026-01-01T00:00:00+00:00",
        "reviewNotes": "watched the recording end to end",
        "audit": {},
    }
    written = {"manifestVersion": 1, "name": "round-trip", "reviewStatus": "HUMAN_REVIEWED",
               "purpose": "test", "sessions": [entry]}
    path = tmp_path / "golden.json"
    path.write_text(json.dumps(written), encoding="utf-8")

    manifest = GoldenManifest.load(path)
    assert manifest.reviewed
    assert manifest.labels == {session.metadata.session_id: session.metadata.label}
    # Synthetic sessions are refused even with complete human provenance.
    with pytest.raises(ValueError, match="synthetic"):
        validate_golden(sessions, written)


def test_a_recording_edited_after_review_stops_matching(sessions):
    from aeroml.dataset.manifest import validate_golden

    session = sessions[0]
    written = {
        "manifestVersion": 1, "name": "digest", "reviewStatus": "HUMAN_REVIEWED",
        "sessions": [{
            "sessionId": session.metadata.session_id,
            "expectedLabel": session.metadata.label,
            "sha256": "0" * 64,
            "reviewer": "operator-1",
            "reviewedAt": "2026-01-01T00:00:00+00:00",
            "reviewNotes": "watched it",
        }],
    }
    with pytest.raises(ValueError, match="changed since it was reviewed"):
        validate_golden(sessions, written)


def test_a_listed_session_with_a_wrong_label_is_refused(sessions):
    from aeroml.dataset.lineage import session_digest
    from aeroml.dataset.manifest import validate_golden

    session = next(item for item in sessions if item.metadata.label == "LEGIT")
    written = {
        "manifestVersion": 1, "name": "label", "reviewStatus": "HUMAN_REVIEWED",
        "sessions": [{
            "sessionId": session.metadata.session_id,
            "expectedLabel": "CHEAT",
            "sha256": session_digest(session),
            "reviewer": "operator-1",
            "reviewedAt": "2026-01-01T00:00:00+00:00",
            "reviewNotes": "watched it",
        }],
    }
    with pytest.raises(ValueError, match="golden label changed"):
        validate_golden(sessions, written)


def test_audit_review_candidates_are_not_a_manifest(sessions):
    """An audit verdict is a judgement about a file, never a human confirming what a player did."""
    from aeroml.audit.sessions import audit_dataset as run_audit
    from aeroml.dataset.manifest import GoldenManifest, review_candidates

    audits = [type("A", (), {"session_id": s.metadata.session_id, "verdict": "GOOD", "reasons": []})()
              for s in sessions]
    candidates = review_candidates(sessions, audits)
    assert candidates["kind"] == "reviewCandidates"
    assert len(candidates["candidates"]) == len(sessions)
    for candidate in candidates["candidates"]:
        assert "reviewer" not in candidate and "reviewedAt" not in candidate
        assert candidate["sha256"]
        assert candidate["synthetic"] is True
    # It must not be loadable as a golden manifest, so it can never be mistaken for one.
    with pytest.raises((ValueError, KeyError)):
        GoldenManifest.from_dict(candidates)


def test_session_sanity_report_covers_the_requested_fields(sessions, synthetic_root, schema):
    """inspect_session is what an operator reads right after a recording, so it must be complete."""
    from aeroml.tools.inspect_session import find_session, inspect, render

    session = next(item for item in sessions if item.metadata.label == "CHEAT")
    found = find_session(synthetic_root, session.metadata.session_id, schema)
    assert found.metadata.session_id == session.metadata.session_id

    report = inspect(found, schema)
    for field in ("durationSeconds", "frames", "attacks", "swings", "targetAcquisitions",
                  "buildableAttackWindows", "sampleIntervalMs", "movementGapMarkers",
                  "droppedRecords", "aimErrorKnown", "targetGeometryKnown", "lineOfSightKnown",
                  "segments", "modelChannels", "verdict", "scenario", "assistStrength"):
        assert field in report, field
    for field in ("p50", "p95", "p99"):
        assert field in report["sampleIntervalMs"], field
    for field in ("missingness", "clipping", "alwaysMissing", "constant"):
        assert field in report["modelChannels"], field

    assert report["frames"] > 0
    assert report["attacks"] > 0
    assert report["buildableAttackWindows"] > 0
    assert report["segments"] >= 1
    assert 0.0 <= report["aimErrorKnown"] <= 1.0
    # The synthetic recorder never observes line of sight, and the report must say so rather than
    # quietly reporting a rate of zero as if it were a measurement of zero visibility.
    assert report["lineOfSightKnown"] == 0.0
    assert "LINE_OF_SIGHT" not in report["modelChannels"]["alwaysMissing"], "LOS is not a model channel"

    text = render(report)
    assert "Known values" in text and "Model channels" in text


def test_session_lookup_accepts_a_prefix_and_refuses_an_ambiguous_one(sessions, synthetic_root, schema):
    from aeroml.tools.inspect_session import find_session

    session = sessions[0]
    assert find_session(synthetic_root, session.metadata.session_id[:12], schema).metadata.session_id \
        == session.metadata.session_id
    with pytest.raises(SystemExit, match="no session"):
        find_session(synthetic_root, "does-not-exist", schema)
    with pytest.raises(SystemExit, match="matches"):
        find_session(synthetic_root, "", schema)

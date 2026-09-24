"""scenario / assistStrength: recording metadata, and the guards that keep it out of the model.

These two fields describe how a session was produced. They correlate with the label perfectly by
construction — every CHEAT session has an assist strength and no LEGIT session does — so a model
that could read either of them would be reading the answer. They exist only to group results and
to plan what still needs recording.
"""

from __future__ import annotations

import json

import numpy as np
import pytest

from aeroml.audit.leakage import check_feature_schema
from aeroml.dataset.records import ASSIST_STRENGTHS, UNRECORDED, read_metadata
from aeroml.evaluation.risk_sim import RiskConfig
from aeroml.evaluation.sessions import breakdowns, evaluate_sessions, ping_bucket
from aeroml.dataset.windows import attack_windows
from aeroml.schema import ValueChannel


def _metadata(tmp_path, **overrides) -> dict:
    base = {
        "sessionId": "abc123", "playerId": "pseudonym", "startTimestamp": 0,
        "label": "CHEAT", "labelSource": "LAB_CHEAT", "cheatFamily": "aimassist",
        "clientFamily": "clientA", "configuration": "smooth-low",
        "minecraftProtocol": 47, "continuousSize": 96, "attackBefore": 20, "attackAfter": 10,
        "durationMs": 60_000, "frames": 1000, "droppedRecords": 0,
        "complete": True, "usableWithoutReview": True, "schemaVersion": 1,
    }
    base.update(overrides)
    path = tmp_path / "metadata.json"
    path.write_text(json.dumps(base), encoding="utf-8")
    return path


def test_a_session_recorded_before_these_fields_still_loads(tmp_path):
    """Backward compatibility: absent is reported as absent, never defaulted to something plausible."""
    metadata = read_metadata(_metadata(tmp_path))
    assert metadata.scenario is None
    assert metadata.assist_strength == UNRECORDED
    assert metadata.has_collection_metadata is False
    # UNRECORDED must be distinguishable from the recorder's own UNKNOWN.
    assert UNRECORDED not in ASSIST_STRENGTHS


def test_a_new_session_carries_and_normalises_both_fields(tmp_path):
    metadata = read_metadata(_metadata(tmp_path, scenario="Box PvP", assistStrength="very_low"))
    assert metadata.scenario == "box-pvp"
    assert metadata.assist_strength == "VERY_LOW"
    assert metadata.has_collection_metadata is True


@pytest.mark.parametrize("value", ["NONE", "VERY_LOW", "LOW", "MEDIUM", "HIGH", "UNKNOWN"])
def test_every_declared_strength_is_accepted(tmp_path, value):
    label = {"label": "LEGIT", "labelSource": "LAB_LEGIT", "cheatFamily": None} if value == "NONE" else {}
    metadata = read_metadata(_metadata(tmp_path, assistStrength=value, **label))
    assert metadata.assist_strength == value


def test_a_strength_that_contradicts_the_label_is_refused(tmp_path):
    with pytest.raises(ValueError, match="LEGIT session declares"):
        read_metadata(_metadata(tmp_path, label="LEGIT", labelSource="LAB_LEGIT",
                                cheatFamily=None, assistStrength="HIGH"))
    with pytest.raises(ValueError, match="CHEAT session declares"):
        read_metadata(_metadata(tmp_path, assistStrength="NONE"))


def test_an_unknown_strength_is_refused_rather_than_coerced(tmp_path):
    with pytest.raises(ValueError, match="assistStrength"):
        read_metadata(_metadata(tmp_path, assistStrength="EXTREME"))
    with pytest.raises(ValueError, match="scenario must be a string"):
        read_metadata(_metadata(tmp_path, scenario=17))


def test_collection_metadata_cannot_enter_the_feature_schema(schema):
    """The blacklist must bite for each of the four fields, whatever they are named."""
    from dataclasses import replace

    assert check_feature_schema(schema) == []
    for name in ("SCENARIO", "ASSIST_STRENGTH", "ASSISTSTRENGTH", "CLIENT_FAMILY",
                 "CLIENTFAMILY", "CONFIGURATION", "CHEAT_FAMILY"):
        poisoned = replace(schema, values=schema.values + (ValueChannel(name, "PING_MS", False, 0, 1, "flag"),))
        findings = check_feature_schema(poisoned)
        assert findings, f"{name} was not rejected"
        assert all(finding.severity == "ERROR" for finding in findings)


def test_a_channel_reading_such_a_field_is_also_rejected(schema):
    from dataclasses import replace

    poisoned = replace(schema, values=schema.values + (
        ValueChannel("HARMLESS_NAME", "CLIENT_FAMILY", False, 0, 1, "flag"),))
    findings = check_feature_schema(poisoned)
    assert findings and "forbidden" in findings[0].detail


def test_breakdowns_group_cheat_by_every_requested_dimension(sessions, schema):
    index = attack_windows(sessions, schema=schema)
    rows = list(range(len(index)))
    labels = index.labels()
    rng = np.random.default_rng(11)
    scores = np.where(labels == 1, rng.uniform(0.6, 0.99, len(rows)), rng.uniform(0.01, 0.4, len(rows)))
    evaluations = evaluate_sessions(index, rows, scores, threshold=0.8, risk_config=RiskConfig())

    report = breakdowns(evaluations)
    assert set(report["cheat"]) >= {"clientFamily", "configuration", "assistStrength", "scenario"}
    assert set(report["legit"]) >= {"scenario", "protocol", "pingBucket"}
    # Every session lands in exactly one cell of each grouping.
    cheat_sessions = sum(1 for item in evaluations if item.label == "CHEAT")
    for grouping in ("clientFamily", "configuration", "assistStrength", "scenario"):
        assert sum(cell["sessions"] for cell in report["cheat"][grouping].values()) == cheat_sessions
    legit_sessions = sum(1 for item in evaluations if item.label == "LEGIT")
    for grouping in ("scenario", "protocol", "pingBucket"):
        assert sum(cell["sessions"] for cell in report["legit"][grouping].values()) == legit_sessions
    assert "never model inputs" in report["note"]


def test_breakdowns_say_so_when_the_metadata_predates_the_field(sessions, schema):
    index = attack_windows(sessions, schema=schema)
    rows = list(range(len(index)))
    scores = np.full(len(rows), 0.5)
    evaluations = evaluate_sessions(index, rows, scores, threshold=0.8, risk_config=RiskConfig())
    for item in evaluations:
        if item.label == "CHEAT":
            item.assist_strength = "UNRECORDED"
    report = breakdowns(evaluations)
    assert "UNRECORDED" in report["cheat"]["assistStrength"]
    assert "predate" in report["cheat"]["note"]


def test_ping_buckets_cover_the_range_and_name_the_unknown():
    assert ping_bucket(10) == "0-30ms"
    assert ping_bucket(45) == "30-60ms"
    assert ping_bucket(300) == "250-infms"
    assert ping_bucket(float("nan")) == "unknown"

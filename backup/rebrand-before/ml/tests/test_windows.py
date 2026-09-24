from __future__ import annotations

import numpy as np
import pytest

from aeroml.dataset.windows import attack_windows, balance_report, continuous_windows


def test_every_attack_window_is_complete_and_anchored(sessions, schema):
    index = attack_windows(sessions, schema=schema)
    assert len(index) > 0
    before = sessions[0].metadata.attack_before
    assert index.length == before + sessions[0].metadata.attack_after + 1
    attack_column = schema.raw_index("ATTACK")
    for position in range(len(index)):
        raw = index.raw(position)
        assert raw.shape == (index.length, len(schema.raw_fields))
        assert raw[before, attack_column] == 1


def test_no_window_crosses_a_segment_boundary(sessions, schema):
    for index in (attack_windows(sessions, schema=schema),
                  continuous_windows(sessions, 32, stride=5, schema=schema)):
        boundary = schema.raw_index("SEGMENT_START")
        for position in range(len(index)):
            raw = index.raw(position)
            assert not np.any(raw[1:, boundary] == 1)


def test_windows_stay_inside_one_session(sessions, schema):
    index = continuous_windows(sessions, 24, stride=7, schema=schema)
    for position in range(len(index)):
        ref = index.refs[position]
        session = index.sessions[ref.session_index]
        assert ref.start + ref.length <= len(session)
        assert np.all(np.diff(session.ticks[ref.start:ref.start + ref.length]) == 1)


def test_offline_history_uses_recorded_frames_not_live_ring_capacity(sessions, schema):
    index = attack_windows(sessions, before=200, after=5, schema=schema)
    assert len(index) > 0
    assert index.length == 206
    for ref in index.refs:
        assert ref.start >= 0 and ref.start + ref.length <= len(index.sessions[ref.session_index])


def test_stride_controls_overlap(sessions, schema):
    dense = continuous_windows(sessions, 32, stride=1, schema=schema)
    sparse = continuous_windows(sessions, 32, stride=8, schema=schema)
    assert len(sparse) < len(dense)
    assert len(sparse) >= 1


def test_require_target_drops_windows_with_nothing_to_track(sessions, schema):
    everything = continuous_windows(sessions, 32, stride=4, require_target=False, schema=schema)
    tracked = continuous_windows(sessions, 32, stride=4, require_target=True, schema=schema)
    assert len(tracked) <= len(everything)


def test_labels_refuse_unlabelled_sessions(sessions, schema, monkeypatch):
    index = attack_windows(sessions, schema=schema)
    labels = index.labels()
    assert set(labels.tolist()) == {0, 1}
    victim = index.sessions[index.refs[0].session_index]
    object.__setattr__(victim.metadata, "label", "UNLABELED")
    try:
        with pytest.raises(ValueError, match="not ground truth"):
            index.labels()
    finally:
        object.__setattr__(victim.metadata, "label", "CHEAT" if labels[0] == 1 else "LEGIT")


def test_balance_report_exposes_session_dominance(sessions, schema):
    index = attack_windows(sessions, schema=schema)
    report = balance_report(index)
    assert report["windows"] == len(index)
    assert 0.0 <= report["cheatFraction"] <= 1.0
    assert 0.0 < report["largestSessionShare"] <= 1.0
    assert sum(report["windowsPerClient"].values()) == len(index)


def test_subset_keeps_reference_semantics(sessions, schema):
    index = attack_windows(sessions, schema=schema)
    subset = index.subset([0, 2, 4])
    assert len(subset) == 3
    np.testing.assert_array_equal(subset.raw(0), index.raw(0))
    assert subset.length == index.length


def test_empty_session_list_yields_an_empty_index(schema):
    index = attack_windows([], schema=schema)
    assert len(index) == 0
    assert index.encode([], schema).shape[0] == 0

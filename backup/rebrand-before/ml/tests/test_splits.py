from __future__ import annotations

import numpy as np
import pytest

from aeroml.dataset.splits import group_split, unknown_client_split, verify
from aeroml.dataset.windows import attack_windows


@pytest.fixture(scope="module")
def index(sessions, schema):
    return attack_windows(sessions, schema=schema)


def test_every_window_lands_in_exactly_one_fold(index):
    split = group_split(index, seed=1)
    total = sum(len(rows) for rows in split.indices.values())
    assert total == len(index)
    everything = np.concatenate([rows for rows in split.indices.values() if len(rows)])
    assert len(set(everything.tolist())) == len(index)


def test_no_session_or_player_straddles_two_folds(index):
    split = group_split(index, group_by=("player",), seed=2)
    sessions = index.attribute("session")
    players = index.attribute("player")
    for attribute in (sessions, players):
        seen: dict[str, str] = {}
        for fold, rows in split.indices.items():
            for row in rows.tolist():
                key = attribute[row]
                assert seen.setdefault(key, fold) == fold


def test_window_level_splitting_is_impossible_by_construction(index):
    with pytest.raises(ValueError, match="must include"):
        group_split(index, group_by=("client",), seed=3)
    with pytest.raises(ValueError, match="at least one attribute"):
        group_split(index, group_by=(), seed=3)


def test_fractions_must_describe_a_whole_dataset(index):
    with pytest.raises(ValueError, match="sum to 1"):
        group_split(index, fractions={"train": 0.5, "test": 0.2}, seed=4)
    with pytest.raises(ValueError, match="unknown folds"):
        group_split(index, fractions={"train": 0.5, "holdout": 0.5}, seed=4)


def test_the_split_is_deterministic_for_a_seed(index):
    first = group_split(index, seed=5)
    second = group_split(index, seed=5)
    for fold in first.indices:
        np.testing.assert_array_equal(first[fold], second[fold])


def test_calibration_is_disjoint_from_validation_and_test(index):
    split = group_split(index, seed=6)
    calibration = set(split.calibration.tolist())
    assert not calibration & set(split.validation.tolist())
    assert not calibration & set(split.test.tolist())
    assert not calibration & set(split.train.tolist())


def test_unknown_client_split_keeps_the_held_out_family_out_of_training(index):
    clients = sorted({str(value) for value in index.attribute("client")})
    holdout = [client for client in clients if client.startswith("client")][:1]
    split = unknown_client_split(index, holdout, seed=7)
    client_of = index.attribute("client")
    lowered = {client.lower() for client in holdout}
    for fold in ("train", "validation", "calibration"):
        for row in split[fold].tolist():
            assert str(client_of[row]).lower() not in lowered
    held = {str(client_of[row]).lower() for row in split.test.tolist()}
    assert lowered <= held
    assert split.manifest["holdoutWindows"] > 0


def test_a_group_that_touches_a_held_out_client_follows_it_into_test(index):
    clients = sorted({str(value) for value in index.attribute("client")})
    holdout = [client for client in clients if client.startswith("client")][:1]
    split = unknown_client_split(index, holdout, group_by=("player",), seed=7)
    players = index.attribute("player")
    tainted = {players[row] for row in split.test.tolist()}
    for fold in ("train", "validation", "calibration"):
        for row in split[fold].tolist():
            assert players[row] not in tainted
    assert split.manifest["windowsPulledInByGroup"] >= 0


def test_grouping_by_session_keeps_more_data_available_for_training(index):
    clients = sorted({str(value) for value in index.attribute("client")})
    holdout = [client for client in clients if client.startswith("client")][:1]
    by_player = unknown_client_split(index, holdout, group_by=("player",), seed=7)
    by_session = unknown_client_split(index, holdout, group_by=("session",), seed=7)
    assert len(by_session.test) <= len(by_player.test)


def test_holding_out_an_absent_client_is_an_error(index):
    with pytest.raises(ValueError, match="not present"):
        unknown_client_split(index, ["never-seen-client"], seed=8)


def test_holding_out_everything_is_an_error(index):
    clients = sorted({str(value) for value in index.attribute("client")})
    with pytest.raises(ValueError, match="nothing to train"):
        unknown_client_split(index, clients, seed=9)


def test_unknown_client_split_also_reserves_known_client_test(index):
    clients = sorted({str(value) for value in index.attribute("client")})
    holdout = [c for c in clients if c.startswith("client")][:1]
    split = unknown_client_split(index, holdout, seed=10)
    test_clients = set(index.attribute("client")[split.test])
    assert set(holdout) <= test_clients
    assert any(c.startswith("client") and c not in holdout for c in test_clients)


def test_verify_catches_a_hand_made_leak(index):
    split = group_split(index, seed=11)
    leaked = dict(split.indices)
    if len(leaked["train"]) and len(leaked["test"]):
        leaked["test"] = np.concatenate([leaked["test"], leaked["train"][:1]])
        split.indices = leaked
        with pytest.raises(AssertionError):
            verify(split, index)


def test_split_manifest_records_what_defined_it(index):
    split = group_split(index, group_by=("player", "session"), seed=12)
    assert split.manifest["strategy"] == "group"
    assert split.manifest["groupBy"] == ["player", "session"]
    assert split.manifest["seed"] == 12
    assert sum(split.manifest["sizes"].values()) == len(index)

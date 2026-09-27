"""Python half of the risk-engine parity contract.

Java ``RiskSimulationGoldenTest`` replays the same fixture. If these two ever disagree, every
offline false-positive number describes a program nobody is running: the simulator decides whether
a threshold is safe, the Java engine decides what actually happens to a player.

Regenerate the fixture with ``python -m aeroml.tools.make_risk_golden``, then run BOTH suites.
Regenerating to make one side go green removes the only thing that catches the drift.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import pytest

from aeroml.evaluation.risk_sim import (
    CONFIRMED,
    LOG_ODDS,
    THRESHOLD,
    Prediction,
    RiskConfig,
    RiskSimulator,
    SUSPICIOUS,
    WATCH,
    overlap_share,
    simulate,
    simulate_scores,
)

DATA = Path(__file__).resolve().parent / "data"
FIXTURE = DATA / "risk_golden.json"
SECOND = 1_000_000_000


def legacy(**overrides) -> RiskConfig:
    """The threshold rule these older tests describe; log-odds has its own tests below."""
    return RiskConfig(ai_scoring=THRESHOLD, **overrides)


@pytest.fixture(scope="module", params=["risk_golden.json", "risk_golden_log_odds.json"])
def golden_risk(request) -> dict:
    path = DATA / request.param
    if not path.is_file():
        pytest.skip("risk fixture missing; run python -m aeroml.tools.make_risk_golden")
    return json.loads(path.read_text(encoding="utf-8"))


def test_the_simulator_reproduces_every_step_of_the_fixture(golden_risk):
    config = RiskConfig.from_dict(golden_risk["config"])
    predictions = [Prediction.from_dict(item) for item in golden_risk["predictions"]]
    trace = simulate(predictions, config)
    assert len(trace.steps) == len(golden_risk["steps"])
    for index, (step, expected) in enumerate(zip(trace.steps, golden_risk["steps"])):
        assert step.nano_time == expected["nanoTime"], index
        assert step.risk == pytest.approx(expected["risk"], abs=1e-12), index
        assert step.state == expected["state"], index
        assert step.evidence == expected["evidence"], index
        assert step.strength == pytest.approx(expected["strength"], abs=1e-12), index
        assert step.transitioned == expected["transitioned"], index
    assert trace.peak_risk == pytest.approx(golden_risk["peakRisk"], abs=1e-12)
    assert trace.counts() == golden_risk["counts"]


def test_the_fixture_still_exercises_every_evidence_branch(golden_risk):
    kinds = {step["evidence"] for step in golden_risk["steps"]}
    assert {"AI_AIM", "AI_KILLAURA", "AI_OVERALL", "AI_RELIEF", None} <= kinds
    assert golden_risk["counts"]["CONFIRMED"] >= 1


def test_one_maximal_prediction_cannot_reach_confirmed():
    simulator = RiskSimulator(legacy())
    simulator.step(Prediction(SECOND, {"overall": 1.0, "aimAssist": 1.0}))
    assert simulator.state == "CLEAN"
    assert simulator.risk <= simulator.config.ai_weight + 1e-12


def test_decay_composes_so_sampling_rate_does_not_change_the_result():
    """The server decays every sample; this decays only at predictions. Both must agree exactly."""
    config = RiskConfig(decay_per_second=0.05)
    coarse = RiskSimulator(config)
    coarse.accept(5.0, 0)
    coarse.decay(10 * SECOND)

    fine = RiskSimulator(config)
    fine.accept(5.0, 0)
    for tick in range(1, 201):
        fine.decay(tick * SECOND // 20)
    assert fine.risk == pytest.approx(coarse.risk, rel=1e-12)


def test_an_uncalibrated_prediction_produces_no_evidence_by_default():
    simulator = RiskSimulator(RiskConfig())
    assert simulator.evidence_for(Prediction(SECOND, {"overall": 0.99}, calibrated=False)) is None
    allowed = RiskSimulator(RiskConfig(accept_uncalibrated=True))
    assert allowed.evidence_for(Prediction(SECOND, {"overall": 0.99}, calibrated=False)) is not None


def test_a_head_the_model_does_not_publish_is_not_read_as_zero():
    simulator = RiskSimulator(legacy())
    # Only overall is present; the absent aimAssist must not count as a low score.
    evidence = simulator.evidence_for(Prediction(SECOND, {"overall": 0.95}))
    assert evidence is not None and evidence[0] == "AI_OVERALL"


def test_relief_lowers_risk_but_never_below_zero():
    config = legacy(decay_per_second=0.0, ai_relief=1.0)
    simulator = RiskSimulator(config)
    simulator.accept(3.0, 0)
    for index in range(50):
        simulator.step(Prediction((index + 1) * SECOND, {"overall": 0.0}))
    assert simulator.risk == pytest.approx(0.0)
    assert simulator.state == "CLEAN"


def test_time_to_state_and_time_above_state_are_reported_in_seconds():
    config = RiskConfig(decay_per_second=0.0)
    predictions = [Prediction(index * SECOND, {"overall": 1.0, "aimAssist": 1.0}) for index in range(40)]
    trace = simulate(predictions, config)
    assert trace.reached(WATCH) and trace.reached(SUSPICIOUS) and trace.reached(CONFIRMED)
    assert 0 < trace.time_to(WATCH) < trace.time_to(SUSPICIOUS) < trace.time_to(CONFIRMED)
    assert trace.seconds_at_or_above(WATCH) > trace.seconds_at_or_above(CONFIRMED)


def test_an_unreached_state_has_infinite_time_not_zero():
    trace = simulate([Prediction(index * SECOND, {"overall": 0.5}) for index in range(10)], RiskConfig())
    assert not trace.reached(WATCH)
    assert math.isinf(trace.time_to(WATCH))


def test_config_is_clamped_the_same_way_java_clamps_it():
    config = RiskConfig(watch=9.0, suspicious=1.0, confirmed=2.0, max_risk=0.5,
                        ai_threshold=0.4, ai_clear_threshold=0.9).normalised()
    assert config.watch == config.suspicious == config.confirmed == 9.0
    assert config.max_risk == 9.0
    assert config.ai_clear_threshold <= config.ai_threshold


def test_a_non_finite_configuration_is_refused():
    with pytest.raises(ValueError):
        RiskConfig(decay_per_second=float("nan")).normalised()


def test_log_odds_is_the_default_and_matches_the_likelihood_ratio():
    simulator = RiskSimulator(RiskConfig(decay_per_second=0.0))
    assert simulator.config.ai_scoring == LOG_ODDS
    assert simulator.evidence_for(Prediction(SECOND, {"overall": 0.5})) is None
    kind, strength = simulator.evidence_for(Prediction(SECOND, {"overall": 0.7}))
    assert kind == "AI_OVERALL" and strength == pytest.approx(0.5 * math.log(0.7 / 0.3))


def test_a_cheat_parked_in_the_old_dead_band_now_accumulates():
    times = [index * 0.5 for index in range(240)]
    scores = [0.7] * len(times)
    old = simulate_scores(times, scores, legacy(), window_seconds=1.55)
    new = simulate_scores(times, scores, RiskConfig(), window_seconds=1.55)
    assert not old.reached(WATCH)
    assert new.reached(SUSPICIOUS)


def test_honest_windows_pull_risk_down_in_log_odds_mode():
    simulator = RiskSimulator(RiskConfig(decay_per_second=0.0))
    simulator.accept(5.0, 0)
    for index in range(40):
        simulator.step(Prediction((index + 1) * SECOND, {"overall": 0.05}, share=1 / 3))
    assert simulator.risk == pytest.approx(0.0)


def test_overlap_share_counts_each_sample_once():
    assert overlap_share(None, 1.0, 1.55) == 1.0
    assert overlap_share(1.0, 1.5, 1.55) == pytest.approx(0.5 / 1.55)
    assert overlap_share(1.0, 9.0, 1.55) == 1.0
    assert overlap_share(1.0, 1.5, None) == 1.0


def test_a_reported_prior_replaces_the_configured_neutral():
    simulator = RiskSimulator(RiskConfig())
    assert simulator.evidence_for(Prediction(SECOND, {"overall": 0.2}, prior=0.2)) is None
    kind, strength = simulator.evidence_for(Prediction(SECOND, {"overall": 0.2}))
    assert kind == "AI_RELIEF" and strength < 0

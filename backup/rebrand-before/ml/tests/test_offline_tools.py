"""Threshold search, hard-example selection and model comparison.

These are the tools an operator uses to decide whether a model may be switched on. What is
asserted here is their discipline rather than their output: the search must refuse to propose a
configuration the data cannot support, hard-example selection must surface the sessions that
actually drive the next collection round, and a comparison must refuse to compare two models on
data one of them trained on.
"""

from __future__ import annotations

import math

import numpy as np
import pytest

from aeroml.dataset.windows import attack_windows
from aeroml.evaluation.exports import build_predictions, hard_negatives, hard_positives
from aeroml.evaluation.risk_sim import RiskConfig
from aeroml.evaluation.sessions import (
    detection_simulation,
    evaluate_sessions,
    false_positive_simulation,
)
from aeroml.tools.compare_models import compare_reports
from aeroml.tools.search_thresholds import DEFAULT_GRID, poisson_upper95, search


@pytest.fixture(scope="module")
def index(sessions, schema):
    return attack_windows(sessions, schema=schema)


@pytest.fixture(scope="module")
def scored(index):
    """A deliberately good-but-imperfect scorer, so both hard negatives and hard positives exist."""
    rows = list(range(len(index)))
    labels = index.labels()
    rng = np.random.default_rng(3)
    scores = np.where(labels == 1, rng.uniform(0.70, 0.999, len(rows)), rng.uniform(0.001, 0.30, len(rows)))
    # One honest session scored high and one cheating session scored low: the interesting cases.
    session_of = index.attribute("session")
    legit_session = next(str(session_of[row]) for row in rows if labels[row] == 0)
    cheat_session = next(str(session_of[row]) for row in rows if labels[row] == 1)
    for row in rows:
        if str(session_of[row]) == legit_session:
            scores[row] = 0.97
        elif str(session_of[row]) == cheat_session:
            scores[row] = 0.05
    return rows, scores, legit_session, cheat_session


def test_threshold_search_refuses_a_configuration_the_data_cannot_support(index, scored):
    """Zero observed false positives in eleven minutes does not certify one per hundred hours."""
    rows, scores, _, _ = scored
    strict = search(index, rows, scores, grid=DEFAULT_GRID, max_confirmed_per_100h=1.0,
                    require_upper95=True)
    empirical = search(index, rows, scores, grid=DEFAULT_GRID, max_confirmed_per_100h=1.0,
                       require_upper95=False)
    assert strict["constraint"]["requirePoissonUpper95"] is True
    assert strict["feasibleCandidates"] == 0
    # The same grid is feasible once the uncertainty requirement is waived, which proves the
    # refusal comes from the confidence bound and not from a broken search.
    assert empirical["feasibleCandidates"] > 0
    for proposal in strict["proposals"]:
        assert proposal["poissonUpper95Per100Hours"] >= proposal["confirmedSessionsPer100LegitCombatHours"]


def test_threshold_search_optimises_detection_under_the_constraint_not_accuracy(index, scored):
    rows, scores, _, _ = scored
    result = search(index, rows, scores, grid=DEFAULT_GRID, max_confirmed_per_100h=1.0,
                    require_upper95=False)
    feasible = [item for item in result["proposals"] if item["feasible"]]
    assert feasible, "expected at least one feasible configuration"
    # Feasible proposals come first, then more detection, then lower latency. No accuracy anywhere.
    assert all(item["feasible"] for item in result["proposals"][:len(feasible)])
    fractions = [item["confirmedCheatFraction"] for item in feasible]
    assert fractions == sorted(fractions, reverse=True)
    assert "accuracy" not in str(result).lower()
    assert "no server config changed" in result["purpose"]


def test_threshold_search_rejects_a_malformed_or_oversized_grid(index, scored):
    rows, scores, _, _ = scored
    with pytest.raises(ValueError, match="grid must contain"):
        search(index, rows, scores, grid={"notAParameter": [1]}, max_confirmed_per_100h=1.0)
    with pytest.raises(ValueError, match="grid must contain"):
        search(index, rows, scores, grid={"aiThreshold": []}, max_confirmed_per_100h=1.0)
    with pytest.raises(ValueError, match="constraint must be"):
        search(index, rows, scores, grid=DEFAULT_GRID, max_confirmed_per_100h=-1.0)
    with pytest.raises(ValueError, match="max-candidates"):
        search(index, rows, scores, grid={"aiThreshold": [0.8, 0.85, 0.9]}, max_confirmed_per_100h=1.0,
               max_candidates=2)


def test_threshold_search_refuses_uncalibrated_predictions(index, scored):
    rows, scores, _, _ = scored
    with pytest.raises(ValueError, match="calibrated"):
        search(index, rows, scores, grid=DEFAULT_GRID, max_confirmed_per_100h=1.0, calibrated=False)


def test_poisson_bound_is_above_the_observed_count_and_finite_at_zero():
    assert poisson_upper95(0) > 0
    for count in (0, 1, 5, 20):
        assert poisson_upper95(count) >= count
        assert math.isfinite(poisson_upper95(count))


def test_hard_negatives_surface_the_honest_sessions_scored_highest(index, scored, schema):
    rows, scores, legit_session, _ = scored
    evaluations = evaluate_sessions(index, rows, scores, threshold=0.8, risk_config=RiskConfig())
    negatives = hard_negatives(index, rows, scores, evaluations, top=3, schema=schema)
    assert negatives, "expected at least one hard negative"
    assert negatives[0]["sessionId"] == legit_session
    assert negatives[0]["label"] == "LEGIT"
    assert negatives[0]["peakProbability"] == pytest.approx(0.97)
    # Sorted worst first, so the top of the file is what a reviewer should look at.
    peaks = [item["peakProbability"] for item in negatives]
    assert peaks == sorted(peaks, reverse=True)
    # A reviewer needs enough context to find the moment in the recording.
    assert negatives[0]["windows"] and negatives[0]["telemetry"]
    assert "playerId" in negatives[0] and "username" not in negatives[0]


def test_hard_positives_surface_the_cheating_sessions_scored_lowest(index, scored, schema):
    rows, scores, _, cheat_session = scored
    evaluations = evaluate_sessions(index, rows, scores, threshold=0.8, risk_config=RiskConfig())
    positives = hard_positives(index, rows, scores, evaluations, top=3, schema=schema)
    assert positives, "expected at least one hard positive"
    assert positives[0]["sessionId"] == cheat_session
    assert positives[0]["label"] == "CHEAT"
    assert positives[0]["peakProbability"] == pytest.approx(0.05)
    peaks = [item["peakProbability"] for item in positives]
    assert peaks == sorted(peaks)
    assert positives[0]["configuration"] and positives[0]["cheatFamily"]


def test_prediction_export_carries_no_identity(index, scored):
    rows, scores, _, _ = scored
    table = build_predictions(index, rows[:20], scores[:20], scores[:20], "test-model-v1")
    assert len(table) == 20
    row = table.rows[0]
    assert set(row) >= {"session", "window", "timestampSeconds", "label", "clientFamily",
                        "configuration", "modelVersion", "rawScore", "calibratedProbability"}
    for forbidden in ("username", "uuid", "playerId", "player"):
        assert forbidden not in row


def test_session_detection_time_is_measured_and_needs_a_run(index, scored):
    rows, scores, _, _ = scored
    single = evaluate_sessions(index, rows, scores, threshold=0.8, risk_config=RiskConfig(), consecutive=1)
    patient = evaluate_sessions(index, rows, scores, threshold=0.8, risk_config=RiskConfig(), consecutive=8)
    by_id = {item.session_id: item for item in single}
    for item in patient:
        other = by_id[item.session_id]
        if item.detected and other.detected:
            # Requiring a run can only delay a detection, never make it earlier.
            assert item.time_to_detection_seconds >= other.time_to_detection_seconds - 1e-9
        assert item.max_consecutive_above == other.max_consecutive_above
    detected = [item for item in single if item.label == "CHEAT" and item.detected]
    assert detected, "expected the scorer to detect some cheat sessions"
    assert all(math.isfinite(item.time_to_detection_seconds) for item in detected)


def test_simulation_separates_known_and_unknown_clients(index, scored):
    rows, scores, _, _ = scored
    clients = sorted({str(value) for value in index.attribute("client")})
    holdout = [client for client in clients if client.startswith("client")][:1]
    evaluations = evaluate_sessions(index, rows, scores, threshold=0.8, risk_config=RiskConfig(),
                                    holdout_clients=holdout)
    detection = detection_simulation(evaluations)
    assert detection["knownClient"]["sessions"] > 0
    assert detection["unknownClient"]["sessions"] > 0
    assert holdout[0] in detection["unknownClient"]["clientFamilies"]
    assert holdout[0] not in detection["knownClient"]["clientFamilies"]

    false_positives = false_positive_simulation(evaluations)
    assert false_positives["combatHours"] > 0
    assert set(false_positives["sessionsReaching"]) == {"WATCH", "SUSPICIOUS", "CONFIRMED"}


def test_a_single_client_corpus_says_generalisation_is_not_measurable(index, scored):
    rows, scores, _, _ = scored
    evaluations = evaluate_sessions(index, rows, scores, threshold=0.8, risk_config=RiskConfig())
    detection = detection_simulation(evaluations)
    assert detection["unknownClient"]["sessions"] == 0
    assert "UNKNOWN CLIENT GENERALIZATION NOT MEASURABLE" in detection["unknownClient"]["note"]


def _report(roc: float, pr: float, fp_per_hour: float, confirmed: float, latency: float,
            sessions: list[dict]) -> dict:
    return {
        "windowMetrics": {"rocAuc": roc, "prAuc": pr},
        "operatingPoint": {"falsePositiveWindowsPerCombatHour": fp_per_hour},
        "riskSimulation": {
            "sessions": sessions,
            "detection": {
                "knownClient": {"reachedConfirmed": confirmed, "detectedFraction": confirmed,
                                "medianDetectionSeconds": latency},
                "unknownClient": {"reachedConfirmed": confirmed, "detectedFraction": confirmed,
                                  "medianDetectionSeconds": latency},
            },
            "falsePositives": {"perCombatHour": {"WATCH": 0.0, "SUSPICIOUS": 0.0, "CONFIRMED": 0.0}},
        },
    }


def test_model_comparison_names_regressions_and_improvements():
    cohort = [{"sessionId": "s1", "label": "CHEAT", "knownClient": True},
              {"sessionId": "s2", "label": "CHEAT", "knownClient": False}]
    left = _report(0.90, 0.90, 1.0, 0.80, 30.0, cohort)
    right = _report(0.95, 0.93, 2.0, 0.90, 20.0, cohort)
    result = compare_reports(left, right)
    assert "rocAuc" in result["improvements"] and "prAuc" in result["improvements"]
    # More false positives per hour is a regression even though every other number improved.
    assert "falsePositiveWindowsPerCombatHour" in result["regressions"]
    assert "knownClient/medianDetectionSeconds" in result["improvements"]
    assert "significance" not in result["interpretation"] or "not statistical" in result["interpretation"]


def test_model_comparison_refuses_to_compare_different_populations():
    left = _report(0.90, 0.90, 1.0, 0.80, 30.0,
                   [{"sessionId": "s1", "label": "CHEAT", "knownClient": False}])
    right = _report(0.95, 0.93, 1.0, 0.99, 10.0,
                    [{"sessionId": "s2", "label": "CHEAT", "knownClient": False}])
    result = compare_reports(left, right)
    for field in ("reachedConfirmed", "detectedFraction", "medianDetectionSeconds"):
        assert result["changes"][f"unknownClient/{field}"]["status"] == "UNMEASURABLE"


def test_model_comparison_marks_a_missing_measurement_rather_than_scoring_it():
    cohort = [{"sessionId": "s1", "label": "CHEAT", "knownClient": True}]
    left = _report(0.90, 0.90, 1.0, 0.80, 30.0, cohort)
    right = _report(0.95, 0.93, 1.0, 0.90, float("nan"), cohort)
    result = compare_reports(left, right)
    assert result["changes"]["knownClient/medianDetectionSeconds"]["status"] == "UNMEASURABLE"
    assert "knownClient/medianDetectionSeconds" not in result["regressions"]
    assert "knownClient/medianDetectionSeconds" not in result["improvements"]

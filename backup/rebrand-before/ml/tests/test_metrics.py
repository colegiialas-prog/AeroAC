from __future__ import annotations

import numpy as np
import pytest

from aeroml.evaluation.calibration import (
    TemperatureScaler,
    expected_calibration_error,
    probability_to_logit,
    reliability_table,
    sigmoid,
)
from aeroml.evaluation.metrics import (
    detection_times,
    evaluate,
    false_positives_per_hour,
    negatives_needed,
    pr_auc,
    roc_auc,
    roc_curve,
    tpr_at_fpr,
    wilson_interval,
)


def test_roc_auc_matches_hand_computed_values():
    assert roc_auc([0, 0, 1, 1], [0.1, 0.2, 0.3, 0.4]) == pytest.approx(1.0)
    assert roc_auc([1, 1, 0, 0], [0.1, 0.2, 0.3, 0.4]) == pytest.approx(0.0)
    assert roc_auc([0, 1, 0, 1], [0.1, 0.2, 0.3, 0.4]) == pytest.approx(0.75)


def test_a_constant_scorer_is_exactly_half_not_perfect():
    assert roc_auc([0, 1, 0, 1], [0.5, 0.5, 0.5, 0.5]) == pytest.approx(0.5)
    assert pr_auc([0, 1, 0, 1], [0.5, 0.5, 0.5, 0.5]) == pytest.approx(0.5)


def test_pr_auc_is_average_precision():
    # Ranked 1,1,0,1: precision at each positive is 1, 1, 0.75.
    assert pr_auc([1, 1, 0, 1], [0.9, 0.8, 0.7, 0.6]) == pytest.approx((1 + 1 + 0.75) / 3)


def test_one_class_input_is_undefined_not_perfect():
    assert np.isnan(roc_auc([1, 1, 1], [0.1, 0.2, 0.3]))
    assert np.isnan(pr_auc([0, 0, 0], [0.1, 0.2, 0.3]))


def test_roc_curve_starts_at_the_origin_and_is_monotonic():
    fpr, tpr, thresholds = roc_curve([0, 1, 0, 1, 1], [0.1, 0.9, 0.4, 0.8, 0.3])
    assert fpr[0] == 0.0 and tpr[0] == 0.0
    assert np.all(np.diff(fpr) >= -1e-12)
    assert np.all(np.diff(tpr) >= -1e-12)
    assert thresholds[0] == np.inf


def test_tpr_at_fpr_never_exceeds_the_budget():
    labels = np.asarray([0] * 1000 + [1] * 100)
    scores = np.concatenate([np.linspace(0.0, 0.5, 1000), np.linspace(0.6, 1.0, 100)])
    tpr, threshold = tpr_at_fpr(labels, scores, 0.001)
    assert tpr == pytest.approx(1.0)
    fpr_curve, _, _ = roc_curve(labels, scores)
    assert float(np.count_nonzero(scores[labels == 0] >= threshold)) / 1000 <= 0.001 + 1e-12


def test_an_unreachable_budget_gives_zero_not_an_error():
    labels = np.asarray([0, 1])
    scores = np.asarray([0.9, 0.9])
    tpr, threshold = tpr_at_fpr(labels, scores, 0.0)
    assert tpr == 0.0
    assert np.isinf(threshold)


def test_rare_rate_claims_need_a_stated_sample_size():
    assert negatives_needed(0.001) == 10_000
    assert negatives_needed(0.00001) == 1_000_000
    low, high = wilson_interval(1, 10)
    assert 0.0 < low < 0.1 < high < 1.0
    assert wilson_interval(0, 0) == (0.0, 1.0)


def test_evaluate_flags_an_estimate_the_data_cannot_support():
    labels = np.asarray([0] * 50 + [1] * 10)
    scores = np.concatenate([np.linspace(0, 0.4, 50), np.linspace(0.6, 1.0, 10)])
    report = evaluate(labels, scores, legit_hours=0.5)
    assert report.roc_auc == pytest.approx(1.0)
    assert report.tpr_at_fpr["0.001"]["reliable"] == 0.0
    assert any("legit windows" in note for note in report.notes)
    assert report.false_positives_per_hour["0.001"] >= 0.0


def test_false_positives_per_hour_counts_alarms_not_windows():
    scores = np.asarray([0.1, 0.95, 0.99, 0.2])
    assert false_positives_per_hour(scores, 0.9, legit_hours=2.0) == pytest.approx(1.0)
    assert np.isnan(false_positives_per_hour(scores, 0.9, legit_hours=0.0))


def test_detection_time_needs_a_run_not_a_single_spike():
    scores = np.asarray([0.1, 0.99, 0.1, 0.99, 0.99, 0.99])
    offsets = np.asarray([0.0, 1.0, 2.0, 3.0, 4.0, 5.0])
    groups = ["s"] * 6
    assert detection_times(scores, groups, offsets, 0.9, consecutive=1)[0] == pytest.approx(1.0)
    assert detection_times(scores, groups, offsets, 0.9, consecutive=3)[0] == pytest.approx(5.0)
    assert np.isinf(detection_times(scores, groups, offsets, 0.9, consecutive=4)[0])


def test_detection_time_is_measured_per_session(sessions):
    scores = np.asarray([0.99, 0.99, 0.1, 0.99])
    offsets = np.asarray([10.0, 11.0, 20.0, 24.0])
    groups = ["a", "a", "b", "b"]
    times = detection_times(scores, groups, offsets, 0.9)
    assert times[0] == pytest.approx(10.0)
    assert times[1] == pytest.approx(24.0)


def test_temperature_recovers_a_known_overconfidence():
    rng = np.random.default_rng(4)
    truth = rng.normal(size=4000)
    labels = (rng.uniform(size=4000) < sigmoid(truth)).astype(float)
    overconfident = truth * 3.0
    scaler = TemperatureScaler.fit(overconfident, labels)
    assert scaler.temperature == pytest.approx(3.0, rel=0.2)
    assert scaler.nll_after < scaler.nll_before
    assert scaler.ece_after < scaler.ece_before
    assert scaler.improved


def test_calibration_never_changes_ranking():
    rng = np.random.default_rng(5)
    logits = rng.normal(size=500)
    labels = (rng.uniform(size=500) < sigmoid(logits)).astype(float)
    scaler = TemperatureScaler.fit(logits * 2.0, labels)
    before = roc_auc(labels, sigmoid(logits * 2.0))
    after = roc_auc(labels, scaler.apply_logits(logits * 2.0))
    assert before == pytest.approx(after, abs=1e-9)


def test_calibration_round_trips_through_a_manifest():
    rng = np.random.default_rng(6)
    logits = rng.normal(size=200)
    labels = (rng.uniform(size=200) < sigmoid(logits)).astype(float)
    scaler = TemperatureScaler.fit(logits, labels)
    restored = TemperatureScaler.from_dict(scaler.to_dict())
    assert restored.temperature == pytest.approx(scaler.temperature)
    np.testing.assert_allclose(restored.apply_logits(logits), scaler.apply_logits(logits))


def test_a_one_class_calibration_fold_is_refused():
    with pytest.raises(ValueError, match="both classes"):
        TemperatureScaler.fit(np.zeros(10), np.zeros(10))
    with pytest.raises(ValueError, match="empty"):
        TemperatureScaler.fit(np.zeros(0), np.zeros(0))


def test_an_unsupported_calibration_method_is_refused():
    with pytest.raises(ValueError, match="unsupported"):
        TemperatureScaler.from_dict({"method": "isotonic", "temperature": 1.0})


def test_probability_and_logit_round_trip():
    probabilities = np.asarray([0.001, 0.5, 0.999])
    np.testing.assert_allclose(sigmoid(probability_to_logit(probabilities)), probabilities, atol=1e-9)
    # Saturated inputs are clipped rather than producing an infinite logit.
    assert np.all(np.isfinite(probability_to_logit(np.asarray([0.0, 1.0]))))


def test_expected_calibration_error_is_zero_for_a_perfect_forecast():
    probabilities = np.concatenate([np.full(500, 0.2), np.full(500, 0.8)])
    labels = np.concatenate([np.zeros(400), np.ones(100), np.zeros(100), np.ones(400)])
    assert expected_calibration_error(probabilities, labels) == pytest.approx(0.0, abs=1e-9)
    table = reliability_table(probabilities, labels, bins=5)
    assert sum(row["count"] for row in table) == 1000


def test_evaluate_rejects_malformed_inputs():
    with pytest.raises(ValueError, match="same length"):
        evaluate([0, 1], [0.5])
    with pytest.raises(ValueError, match="0 .legit. or 1"):
        evaluate([0, 2], [0.5, 0.5])
    with pytest.raises(ValueError, match="finite"):
        evaluate([0, 1], [0.5, np.nan])
    with pytest.raises(ValueError, match="no samples"):
        evaluate([], [])

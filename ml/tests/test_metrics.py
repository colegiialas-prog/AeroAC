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


# --- Platt scaling and the low-FPR selection metric -------------------------------------------------

from aeroml.evaluation.calibration import (  # noqa: E402
    HeadCalibration,
    PlattScaler,
    fit_scaler,
    load_calibration,
)
from aeroml.evaluation.metrics import partial_auc  # noqa: E402


def _shifted(seed: int = 0, size: int = 20000):
    """True log-odds z; the model reports 2z + 3, i.e. overconfident AND prior-shifted, which is
    what a pos_weight-trained network does to a legit-heavy population."""
    rng = np.random.default_rng(seed)
    z = rng.normal(-3.0, 2.0, size)
    labels = (rng.random(size) < sigmoid(z)).astype(float)
    return 2.0 * z + 3.0, labels


def test_platt_removes_the_prior_shift_a_temperature_cannot():
    logits, labels = _shifted()
    platt = PlattScaler.fit(logits, labels)
    temperature = TemperatureScaler.fit(logits, labels)
    assert platt.slope == pytest.approx(0.5, rel=0.1)
    assert platt.bias == pytest.approx(-1.5, abs=0.25)
    assert platt.nll_after < temperature.nll_after
    assert platt.ece_after < 0.02 < temperature.ece_after


def test_platt_preserves_ranking_exactly():
    logits, labels = _shifted(seed=3, size=3000)
    platt = PlattScaler.fit(logits, labels)
    assert roc_auc(labels, platt.apply_logits(logits)) == pytest.approx(roc_auc(labels, logits))
    order = np.argsort(logits)
    assert np.all(np.diff(platt.apply_logits(logits)[order]) >= 0)


def test_platt_stays_finite_on_a_separable_fold():
    logits = np.array([-4.0, -3.0, -2.0, 2.0, 3.0, 4.0])
    labels = np.array([0, 0, 0, 1, 1, 1])
    platt = PlattScaler.fit(logits, labels)
    assert np.isfinite(platt.slope) and np.isfinite(platt.bias) and platt.slope > 0


def test_platt_refuses_one_class_and_bad_dicts():
    with pytest.raises(ValueError):
        PlattScaler.fit(np.zeros(10), np.zeros(10))
    with pytest.raises(ValueError):
        PlattScaler.from_dict({"method": "platt", "slope": -1.0, "bias": 0.0})
    with pytest.raises(ValueError):
        PlattScaler.from_dict({"method": "temperature", "temperature": 1.0})


def test_fit_scaler_falls_back_to_temperature_when_asked():
    logits, labels = _shifted(size=2000)
    assert isinstance(fit_scaler(logits, labels, "platt"), PlattScaler)
    assert isinstance(fit_scaler(logits, labels, "temperature"), TemperatureScaler)
    with pytest.raises(ValueError):
        fit_scaler(logits, labels, "isotonic")


def test_head_calibration_round_trips_new_and_legacy_manifests():
    logits, labels = _shifted(size=4000)
    stacked = np.stack([logits, logits * 0.5], axis=1)
    targets = np.stack([labels, labels], axis=1)
    heads = ("overall", "aimAssist")
    fitted = HeadCalibration.fit(stacked, targets, heads)
    manifest = fitted.to_dict()
    assert manifest["method"] == "per-head"
    restored = load_calibration(manifest, heads)
    assert np.allclose(restored.apply_logits(stacked), fitted.apply_logits(stacked))
    legacy = HeadCalibration.fit(stacked, targets, heads, method="temperature").to_dict()
    assert legacy["method"] == "per-head-temperature"
    assert np.allclose(load_calibration(legacy, heads).apply_logits(stacked),
                       HeadCalibration.fit(stacked, targets, heads, method="temperature").apply_logits(stacked))
    single = load_calibration(PlattScaler.fit(logits, labels).to_dict(), heads)
    assert isinstance(single, PlattScaler)


def test_partial_auc_reference_points():
    labels = np.array([0, 0, 0, 0, 1, 1])
    assert partial_auc(labels, np.array([0.1, 0.2, 0.3, 0.4, 0.8, 0.9]), 0.1) == pytest.approx(1.0)
    assert partial_auc(labels, np.array([0.9, 0.8, 0.7, 0.6, 0.1, 0.2]), 0.1) < 0.5
    assert partial_auc(labels, np.full(6, 0.5), 0.1) == pytest.approx(0.5)
    scores = np.array([0.1, 0.9, 0.3, 0.4, 0.8, 0.2])
    assert partial_auc(labels, scores, 1.0) == pytest.approx(roc_auc(labels, scores))
    assert np.isnan(partial_auc(np.zeros(4), np.arange(4.0), 0.1))
    with pytest.raises(ValueError):
        partial_auc(labels, scores, 0.0)


def test_partial_auc_prefers_the_scorer_that_is_clean_at_low_fpr():
    # Same ROC-AUC, different corner: A is perfect for its top scores, B pays at low FPR.
    rng = np.random.default_rng(5)
    negatives, positives = rng.normal(0, 1, 4000), rng.normal(1.5, 1, 400)
    labels = np.r_[np.zeros(4000), np.ones(400)]
    clean_top = np.r_[negatives, np.where(positives > 2.0, positives + 10.0, positives)]
    noisy_top = np.r_[np.where(negatives > 2.5, negatives + 10.0, negatives), positives]
    assert partial_auc(labels, clean_top, 0.01) > partial_auc(labels, noisy_top, 0.01)

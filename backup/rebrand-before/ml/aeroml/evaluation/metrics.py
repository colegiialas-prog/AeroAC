"""Evaluation that matches how the detector is actually used.

Accuracy is not reported anywhere here on purpose. A detector runs against a stream that is
overwhelmingly legitimate, so the only numbers that decide whether it can be deployed are the true
positive rate at a very low false positive rate, and the false positives an hour of ordinary combat
would produce.

Everything is numpy only, so evaluation runs wherever the dataset pipeline runs.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Mapping, Sequence

import numpy as np

DEFAULT_FPR_TARGETS = (0.001, 0.0001, 0.00001)


def _check(labels: np.ndarray, scores: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    labels = np.asarray(labels).ravel()
    scores = np.asarray(scores, dtype=np.float64).ravel()
    if labels.shape != scores.shape:
        raise ValueError("labels and scores must have the same length")
    if labels.size == 0:
        raise ValueError("no samples to evaluate")
    if not np.all(np.isin(labels, (0, 1))):
        raise ValueError("labels must be 0 (legit) or 1 (cheat)")
    if not np.all(np.isfinite(scores)):
        raise ValueError("scores must be finite")
    return labels.astype(np.int64), scores


def roc_auc(labels: np.ndarray, scores: np.ndarray) -> float:
    """Rank based, so ties count as half — an all-constant scorer gets 0.5, not 1.0."""
    labels, scores = _check(labels, scores)
    positives = int(labels.sum())
    negatives = int(labels.size - positives)
    if positives == 0 or negatives == 0:
        return float("nan")
    order = np.argsort(scores, kind="mergesort")
    ranks = np.empty(scores.size, dtype=np.float64)
    sorted_scores = scores[order]
    index = 0
    while index < sorted_scores.size:
        end = index
        while end + 1 < sorted_scores.size and sorted_scores[end + 1] == sorted_scores[index]:
            end += 1
        ranks[order[index:end + 1]] = (index + end) / 2.0 + 1.0
        index = end + 1
    positive_rank_sum = ranks[labels == 1].sum()
    return float((positive_rank_sum - positives * (positives + 1) / 2.0) / (positives * negatives))


def roc_curve(labels: np.ndarray, scores: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    labels, scores = _check(labels, scores)
    order = np.argsort(-scores, kind="mergesort")
    sorted_labels = labels[order]
    sorted_scores = scores[order]
    true_positives = np.cumsum(sorted_labels)
    false_positives = np.cumsum(1 - sorted_labels)
    distinct = np.flatnonzero(np.diff(sorted_scores)) if sorted_scores.size > 1 else np.asarray([], dtype=np.int64)
    keep = np.concatenate([distinct, [sorted_scores.size - 1]])
    positives = max(int(labels.sum()), 1)
    negatives = max(int(labels.size - labels.sum()), 1)
    tpr = np.concatenate([[0.0], true_positives[keep] / positives])
    fpr = np.concatenate([[0.0], false_positives[keep] / negatives])
    thresholds = np.concatenate([[np.inf], sorted_scores[keep]])
    return fpr, tpr, thresholds


def pr_auc(labels: np.ndarray, scores: np.ndarray) -> float:
    """Average precision: the step-wise sum used for ranking tasks, not a trapezoid over a curve."""
    labels, scores = _check(labels, scores)
    positives = int(labels.sum())
    if positives == 0:
        return float("nan")
    order = np.argsort(-scores, kind="mergesort")
    sorted_labels = labels[order]
    true_positives = np.cumsum(sorted_labels)
    ends = np.r_[np.flatnonzero(np.diff(scores[order])), len(scores) - 1]
    tp = true_positives[ends]
    precision = tp / (ends + 1)
    return float(np.sum(precision * np.diff(np.r_[0, tp])) / positives)


def tpr_at_fpr(labels: np.ndarray, scores: np.ndarray, target_fpr: float) -> tuple[float, float]:
    """Highest TPR whose FPR stays at or below the target, with the threshold that achieves it."""
    if not np.isfinite(target_fpr) or not 0 <= target_fpr <= 1:
        raise ValueError("target_fpr must be in [0, 1]")
    labels, scores = _check(labels, scores)
    if len(np.unique(labels)) != 2:
        return float("nan"), float("inf")
    fpr, tpr, thresholds = roc_curve(labels, scores)
    allowed = fpr <= target_fpr + 1e-12
    if not np.any(allowed):
        return 0.0, float("inf")
    index = int(np.flatnonzero(allowed)[-1])
    return float(tpr[index]), float(thresholds[index])


def negatives_needed(target_fpr: float, expected_false_positives: int = 10) -> int:
    """How many legit windows a claim at this FPR needs before it means anything."""
    if not np.isfinite(target_fpr) or not 0 < target_fpr <= 1 or expected_false_positives < 1:
        raise ValueError("target_fpr must be positive")
    return int(np.ceil(expected_false_positives / target_fpr))


def wilson_interval(successes: int, trials: int, z: float = 1.96) -> tuple[float, float]:
    """Wilson score interval; usable at the tiny rates where a normal approximation breaks down."""
    if trials <= 0:
        return (0.0, 1.0)
    phat = successes / trials
    denominator = 1 + z * z / trials
    centre = (phat + z * z / (2 * trials)) / denominator
    spread = z * np.sqrt(phat * (1 - phat) / trials + z * z / (4 * trials * trials)) / denominator
    return (float(max(0.0, centre - spread)), float(min(1.0, centre + spread)))


def false_positives_per_hour(scores: np.ndarray, threshold: float, legit_hours: float) -> float:
    """Alarms an hour of ordinary combat would raise. The number an operator actually feels."""
    scores = np.asarray(scores, dtype=np.float64).ravel()
    if legit_hours <= 0:
        return float("nan")
    return float(np.count_nonzero(scores >= threshold) / legit_hours)


def detection_times(scores: np.ndarray, groups: Sequence, offsets_seconds: np.ndarray,
                    threshold: float, consecutive: int = 1) -> np.ndarray:
    """
    Seconds from the start of each cheating session until the alarm condition first holds.
    ``consecutive`` mirrors the risk engine: one spike is not a detection.
    """
    scores = np.asarray(scores, dtype=np.float64).ravel()
    offsets = np.asarray(offsets_seconds, dtype=np.float64).ravel()
    groups = np.asarray(groups, dtype=object).ravel()
    if not (scores.shape == offsets.shape == groups.shape):
        raise ValueError("scores, groups and offsets must have the same length")
    if consecutive < 1 or not np.all(np.isfinite(scores)) or not np.all(np.isfinite(offsets)) or np.any(offsets < 0):
        raise ValueError("finite scores/nonnegative offsets and consecutive >= 1 are required")
    times: list[float] = []
    for group in dict.fromkeys(groups.tolist()):
        selection = groups == group
        order = np.argsort(offsets[selection], kind="mergesort")
        group_scores = scores[selection][order]
        group_offsets = offsets[selection][order]
        run = 0
        detected = np.inf
        for score, offset in zip(group_scores, group_offsets):
            run = run + 1 if score >= threshold else 0
            if run >= consecutive:
                detected = float(offset)
                break
        times.append(detected)
    return np.asarray(times, dtype=np.float64)


@dataclass
class EvaluationReport:
    roc_auc: float
    pr_auc: float
    positives: int
    negatives: int
    tpr_at_fpr: dict[str, dict[str, float]] = field(default_factory=dict)
    false_positives_per_hour: dict[str, float] = field(default_factory=dict)
    median_detection_seconds: float = float("nan")
    detected_fraction: float = float("nan")
    notes: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "rocAuc": self.roc_auc,
            "prAuc": self.pr_auc,
            "positives": self.positives,
            "negatives": self.negatives,
            "tprAtFpr": self.tpr_at_fpr,
            "falsePositivesPerLegitHour": self.false_positives_per_hour,
            "medianDetectionSeconds": self.median_detection_seconds,
            "detectedFraction": self.detected_fraction,
            "notes": self.notes,
        }


def evaluate(labels: np.ndarray, scores: np.ndarray, *, legit_hours: float = 0.0,
             groups: Sequence | None = None, offsets_seconds: np.ndarray | None = None,
             fpr_targets: Sequence[float] = DEFAULT_FPR_TARGETS,
             consecutive: int = 1) -> EvaluationReport:
    labels, scores = _check(labels, scores)
    positives = int(labels.sum())
    negatives = int(labels.size - positives)
    report = EvaluationReport(
        roc_auc=roc_auc(labels, scores),
        pr_auc=pr_auc(labels, scores),
        positives=positives,
        negatives=negatives,
    )
    report.notes.append("TPR@FPR is a descriptive ROC curve, not a deployable threshold. Overlapping windows are correlated; window intervals do not establish player-level confidence.")
    for target in fpr_targets:
        tpr, threshold = tpr_at_fpr(labels, scores, target)
        required = negatives_needed(target)
        entry = {"tpr": tpr, "threshold": threshold, "availableLegitNegatives": negatives,
                 "requiredApproximateNegatives": required, "requestedFpr": target,
                 "status": "INSUFFICIENT DATA TO CLAIM THIS FPR" if negatives < required else "WINDOW COUNT SUFFICIENT; INDEPENDENCE STILL REQUIRES REVIEW"}
        if negatives < required:
            # Below this many legit windows the estimate is dominated by sampling noise.
            entry["reliable"] = 0.0
            report.notes.append(
                f"FPR {target:g} needs about {required} legit windows for a stable estimate; this "
                f"evaluation has {negatives}."
            )
        else:
            entry["reliable"] = 1.0
        low, high = wilson_interval(int(round(tpr * positives)) if np.isfinite(tpr) else 0, positives)
        entry["tprCi95Low"], entry["tprCi95High"] = low, high
        report.tpr_at_fpr[f"{target:g}"] = entry
        if legit_hours > 0 and np.isfinite(threshold):
            report.false_positives_per_hour[f"{target:g}"] = false_positives_per_hour(
                scores[labels == 0], threshold, legit_hours)

    if groups is not None and offsets_seconds is not None and positives > 0:
        target = fpr_targets[0]
        _, threshold = tpr_at_fpr(labels, scores, target)
        cheat = labels == 1
        times = detection_times(scores[cheat], np.asarray(groups, dtype=object)[cheat],
                                np.asarray(offsets_seconds)[cheat], threshold, consecutive)
        finite = times[np.isfinite(times)]
        report.detected_fraction = float(finite.size / times.size) if times.size else float("nan")
        report.median_detection_seconds = float(np.median(finite)) if finite.size else float("inf")
    return report


def compare(reports: Mapping[str, EvaluationReport]) -> dict:
    """Known-client and unknown-client results side by side; the gap is the generalisation claim."""
    return {name: report.to_dict() for name, report in reports.items()}

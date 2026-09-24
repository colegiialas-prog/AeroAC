"""Compare actual ONNX bundles on exactly the same held-out recordings."""
import argparse
from pathlib import Path
from ..dataset.records import load_dataset
from ..evaluation.runner import eligible_external, windows_for, evaluate_index
from ..service.runtime import LoadedModel
from ..reporting import write_json, dumps


def compare_reports(a, b):
    metrics = {
        "rocAuc": (a["windowMetrics"]["rocAuc"], b["windowMetrics"]["rocAuc"], True),
        "prAuc": (a["windowMetrics"]["prAuc"], b["windowMetrics"]["prAuc"], True),
        "falsePositiveWindowsPerCombatHour": (a["operatingPoint"]["falsePositiveWindowsPerCombatHour"], b["operatingPoint"]["falsePositiveWindowsPerCombatHour"], False),
    }
    for group in ("knownClient", "unknownClient"):
        left = a["riskSimulation"]["detection"].get(group, {})
        right = b["riskSimulation"]["detection"].get(group, {})
        expected_known = group == "knownClient"
        cohort_a = {s["sessionId"] for s in a["riskSimulation"].get("sessions", []) if s["label"] == "CHEAT" and s["knownClient"] == expected_known}
        cohort_b = {s["sessionId"] for s in b["riskSimulation"].get("sessions", []) if s["label"] == "CHEAT" and s["knownClient"] == expected_known}
        if cohort_a != cohort_b:
            left = right = {}  # Different populations cannot establish a model regression.
        for field, higher in (("reachedConfirmed", True), ("detectedFraction", True), ("medianDetectionSeconds", False)):
            metrics[f"{group}/{field}"] = (left.get(field), right.get(field), higher)
    for state in ("WATCH", "SUSPICIOUS", "CONFIRMED"):
        metrics[f"legit/{state}/sessionsPerCombatHour"] = (
            a["riskSimulation"]["falsePositives"].get("perCombatHour", {}).get(state),
            b["riskSimulation"]["falsePositives"].get("perCombatHour", {}).get(state), False)
    changes, regressions, improvements = {}, [], []
    import math
    for name, (left, right, higher) in metrics.items():
        if left is None or right is None or not math.isfinite(left) or not math.isfinite(right):
            changes[name] = {"a": left, "b": right, "delta": None, "status": "UNMEASURABLE"}
            continue
        delta = right - left
        changes[name] = {"a": left, "b": right, "delta": delta}
        if abs(delta) > 1e-9:
            (improvements if (delta > 0) == higher else regressions).append(name)
    return {"changes": changes, "regressions": regressions, "improvements": improvements,
            "interpretation": "descriptive paired changes, not statistical significance or automatic promotion"}


def compare_models(bundle_a, bundle_b, dataset, output, threshold=0.8):
    models = [LoadedModel.load(path) for path in (bundle_a, bundle_b)]
    manifests = [m.bundle.manifest for m in models]
    if (manifests[0].window, manifests[0].sequence_length, manifests[0].channels) != (manifests[1].window, manifests[1].sequence_length, manifests[1].channels):
        raise ValueError("paired window comparison requires identical window length/type and feature channels")
    sessions = load_dataset(dataset, labels=("LEGIT", "CHEAT"), require_usable=False)
    eligible = eligible_external(sessions, [m.bundle for m in models])
    if not eligible:
        raise ValueError("no test sessions/players disjoint from both models' train/validation/calibration sets")
    if any(not s.usable_for_training for s in eligible):
        raise ValueError("held-out recordings contain corruption; audit and review them first")
    index = windows_for(eligible, manifests[0])
    reports = [evaluate_index(model, index, range(len(index)), Path(output) / name, threshold=threshold) for model, name in zip(models, ("A", "B"))]
    common_unknown = sorted(set(s["sessionId"] for s in reports[0]["riskSimulation"]["sessions"] if s["label"] == "CHEAT" and not s["knownClient"]) &
                            set(s["sessionId"] for s in reports[1]["riskSimulation"]["sessions"] if s["label"] == "CHEAT" and not s["knownClient"]))
    result = {"A": reports[0], "B": reports[1], **compare_reports(*reports),
              "sharedTestSessions": sorted(s.metadata.session_id for s in eligible),
              "excludedBecauseSeenByEitherModel": sorted({s.metadata.session_id for s in sessions} - {s.metadata.session_id for s in eligible}),
              "unknownToBothSessionIds": common_unknown,
              "unknownComparisonNote": "Unknown/known groups can differ by model. Only compare their rates directly when session sets match; full per-session results are included."}
    write_json(Path(output) / "comparison.json", result)
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("bundle_a", type=Path)
    p.add_argument("bundle_b", type=Path)
    p.add_argument("dataset", type=Path)
    p.add_argument("--output", type=Path, default=Path("reports/comparison"))
    p.add_argument("--threshold", type=float, default=0.8)
    a = p.parse_args()
    result = compare_models(a.bundle_a, a.bundle_b, a.dataset, a.output, a.threshold)
    print(dumps({k: v for k, v in result.items() if k not in ("A", "B")}))


if __name__ == "__main__":
    main()

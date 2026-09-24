"""Export actual offline evaluation results for Aero's read-only Training Center."""
import argparse
import json
import math
import time
from pathlib import Path
from ..reporting import write_json


def export(report, fpr=0.001):
    if not math.isfinite(fpr) or not 0 < fpr <= 1:
        raise ValueError("FPR must be in (0,1]")
    cohort = report.get("cohortId", "")
    if len(cohort) != 64 or any(char not in "0123456789abcdef" for char in cohort):
        raise ValueError("evaluation lacks a cohort identity; rerun evaluate_model with the current tooling")
    if report.get("fold") not in ("test", "external-test"):
        raise ValueError("the admin evaluation screen requires a held-out test report")
    metrics = report.get("windowMetrics", {})
    point = metrics.get("tprAtFpr", {}).get(f"{fpr:g}", {})
    simulation = report.get("riskSimulation", {})
    detection = simulation.get("detection", {})
    groups = simulation.get("breakdowns", {}).get("cheat", {})

    def rate(group):
        return group.get("reachedSuspicious") if group.get("sessions", 0) > 0 else None

    def breakdown(key):
        return {name: rate(group) for name, group in groups.get(key, {}).items() if isinstance(group, dict)}

    known, unknown = detection.get("knownClient", {}), detection.get("unknownClient", {})
    population = "MIXED" if bool(known.get("sessions")) == bool(unknown.get("sessions")) else (
        "KNOWN_CLIENT" if known.get("sessions") else "UNKNOWN_CLIENT")
    caveats = list(report.get("limitations", []))
    caveats += [point.get("status", "INSUFFICIENT DATA: requested FPR was not evaluated"),
                "Session FP/h, latency and breakdowns use SUSPICIOUS in offline risk replay; TPR@FPR is a window ROC statistic.",
                "These reports are imported; they do not establish the server's active model or authorize deployment."]
    return {
        "reportVersion": 1, "modelVersion": report.get("modelVersion"), "datasetVersion": report.get("datasetVersion"),
        "featureSchemaVersion": report.get("featureSchemaVersion"), "cohortId": cohort,
        "rocAuc": metrics.get("rocAuc"), "prAuc": metrics.get("prAuc"), "configuredFpr": fpr,
        "tprAtFpr": point.get("tpr") if point.get("reliable") == 1 and metrics.get("positives", 0) > 0 else None,
        "falsePositivesPerCombatHour": simulation.get("falsePositives", {}).get("perCombatHour", {}).get("SUSPICIOUS"),
        "medianDetectionSeconds": detection.get("overall", {}).get("medianTimeToSuspiciousSeconds"),
        "calibration": "CALIBRATED" if report.get("calibrated") is True else "UNCALIBRATED",
        "population": population,
        "detectionByPopulation": {"KNOWN_CLIENT": rate(known), "UNKNOWN_CLIENT": rate(unknown)},
        "detectionByAssist": breakdown("assistStrength"), "detectionByClient": breakdown("clientFamily"),
        "detectionByScenario": breakdown("scenario"), "caveats": caveats,
        "producedAtMillis": int(time.time() * 1000),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="evaluate_model's report.json")
    parser.add_argument("output", type=Path, help="configured report directory/current.json or candidate.json")
    parser.add_argument("--fpr", type=float, default=0.001)
    args = parser.parse_args()
    result = export(json.loads(args.report.read_text(encoding="utf-8")), args.fpr)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    write_json(temporary, result)
    temporary.replace(args.output)
    print(f"Exported {args.output}; no model was deployed")


if __name__ == "__main__":
    main()

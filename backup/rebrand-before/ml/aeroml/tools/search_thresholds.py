"""Offline constraint search. Reads validation only and never changes server configuration."""
import argparse
import itertools
import json
import math
from pathlib import Path
from ..evaluation.risk_sim import RiskConfig
from ..evaluation.sessions import evaluate_sessions, false_positive_simulation, detection_simulation
from ..evaluation.runner import predict_index
from ..service.runtime import LoadedModel
from ..dataset.records import load_dataset
from ..dataset.lineage import restore_index
from ..reporting import write_json, dumps

GRID_KEYS = {"aiThreshold", "aiClearThreshold", "aiWeight", "decayPerSecond", "watch", "suspicious", "confirmed"}
DEFAULT_GRID = {"aiThreshold": [0.8, 0.9], "aiClearThreshold": [0.1, 0.2], "aiWeight": [0.25, 0.5],
                "decayPerSecond": [0.01, 0.05], "watch": [2.0], "suspicious": [6.0], "confirmed": [12.0]}


def poisson_upper95(events):
    """One-sided Poisson upper rate numerator; sessions must be independent for this interpretation."""
    if events < 0:
        raise ValueError("negative event count")
    def cdf(mean):
        logs = [-mean + k * math.log(mean) - math.lgamma(k + 1) for k in range(events + 1)]
        maximum = max(logs)
        return math.exp(maximum) * sum(math.exp(x - maximum) for x in logs)
    low, high = 1e-12, max(10.0, events * 2 + 10)
    while cdf(high) > 0.05:
        high *= 2
    for _ in range(60):
        middle = (low + high) / 2
        if cdf(middle) > 0.05:
            low = middle
        else:
            high = middle
    return high


def search(index, rows, scores, *, grid, max_confirmed_per_100h, calibrated=True, head_scores=None,
           require_upper95=True, max_candidates=256):
    if set(grid) - GRID_KEYS or not grid or any(not isinstance(v, list) or not v for v in grid.values()):
        raise ValueError("grid must contain nonempty lists of supported risk parameters")
    if not math.isfinite(max_confirmed_per_100h) or max_confirmed_per_100h < 0:
        raise ValueError("constraint must be finite and nonnegative")
    if math.prod(map(len, grid.values())) > max_candidates:
        raise ValueError("grid exceeds max-candidates; narrow the search")
    if not calibrated:
        raise ValueError("risk threshold search requires calibrated predictions")
    proposals = []
    for values in itertools.product(*grid.values()):
        candidate = {**RiskConfig().to_dict(), **dict(zip(grid, values))}
        cfg = RiskConfig.from_dict(candidate)
        if cfg.to_dict() != cfg.normalised().to_dict():
            raise ValueError("invalid grid point: thresholds must be ordered and parameters in range")
        evaluations = evaluate_sessions(index, rows, scores, threshold=cfg.ai_threshold, risk_config=cfg,
                                       calibrated=calibrated, head_scores=head_scores)
        fp = false_positive_simulation(evaluations)
        detection = detection_simulation(evaluations)
        hours = fp.get("combatHours", 0)
        if hours <= 0 or detection.get("sessions", 0) == 0:
            raise ValueError("validation search needs measured LEGIT combat exposure and CHEAT sessions")
        count = fp["sessionsReaching"]["CONFIRMED"]
        rate = 100 * count / hours
        upper = 100 * poisson_upper95(count) / hours
        cheats = [s for s in evaluations if s.label == "CHEAT"]
        reached = [s for s in cheats if s.reached_confirmed]
        latency = sorted(s.time_to_confirmed_seconds for s in reached)
        median = float(__import__("numpy").median(latency)) if latency else None
        proposals.append({"riskConfig": cfg.to_dict(), "confirmedSessionsPer100LegitCombatHours": rate,
                          "poissonUpper95Per100Hours": upper,
                          "feasible": rate <= max_confirmed_per_100h and (not require_upper95 or upper <= max_confirmed_per_100h),
                          "confirmedCheatFraction": len(reached) / len(cheats), "medianConfirmedSeconds": median,
                          "legitCombatHours": hours, "falsePositives": fp, "detection": detection})
    proposals.sort(key=lambda p: (not p["feasible"], -p["confirmedCheatFraction"], p["medianConfirmedSeconds"] if p["medianConfirmedSeconds"] is not None else math.inf))
    return {"constraint": {"confirmedPer100LegitCombatHours": max_confirmed_per_100h, "requirePoissonUpper95": require_upper95},
            "feasibleCandidates": sum(p["feasible"] for p in proposals), "proposals": proposals,
            "purpose": "offline suggestions only; no server config changed",
            "limitations": "Validation-only tuning requires a fresh untouched test afterward. Poisson intervals assume independent session events; repeated players and sequential samples need clustered review."}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("bundle", type=Path)
    p.add_argument("dataset", type=Path)
    p.add_argument("--grid", type=Path)
    p.add_argument("--max-confirmed-per-100h", type=float, required=True)
    p.add_argument("--empirical-only", action="store_true", help="exploration only, omits uncertainty constraint")
    p.add_argument("--output", type=Path, default=Path("reports/threshold_proposals.json"))
    a = p.parse_args()
    model = LoadedModel.load(a.bundle)
    index, split = restore_index(load_dataset(a.dataset, require_usable=False), model.bundle.manifest.provenance.split_manifest)
    rows = split.validation.tolist()  # No option to select calibration or test.
    _, scores = predict_index(model, index, rows)
    grid = json.loads(a.grid.read_text()) if a.grid else DEFAULT_GRID
    result = search(index, rows, scores[:, model.bundle.manifest.heads.index("overall")], grid=grid,
                    max_confirmed_per_100h=a.max_confirmed_per_100h, calibrated=model.calibration is not None,
                    head_scores={h: scores[:, i] for i, h in enumerate(model.bundle.manifest.heads)}, require_upper95=not a.empirical_only)
    result["fold"] = "validation"
    result["modelVersion"] = model.bundle.manifest.model_version
    write_json(a.output, result)
    print(dumps({"feasibleCandidates": result["feasibleCandidates"], "report": a.output}))


if __name__ == "__main__":
    main()

"""Regenerates the cross-language risk fixture.

The offline simulator decides whether mitigation thresholds are safe; the Java engine decides what
actually happens to a player. If the two drift, every offline false-positive number becomes a
statement about a program nobody is running.

The fixture is one prediction sequence and the exact risk trace it must produce. Java
``RiskSimulationGoldenTest`` and Python ``tests/test_risk_sim.py`` both replay it.

    python -m aeroml.tools.make_risk_golden
"""

from __future__ import annotations

import json
from pathlib import Path

from ..console import use_utf8_console
from ..evaluation.risk_sim import Prediction, RiskConfig, simulate

SECOND = 1_000_000_000


def build_predictions() -> list[Prediction]:
    """Covers every branch of fromPrediction plus decay over a long quiet gap."""
    predictions: list[Prediction] = []

    def add(second: float, heads: dict[str, float], calibrated: bool = True) -> None:
        predictions.append(Prediction(int(round(second * SECOND)), heads, calibrated))

    # Nothing notable: between the clear threshold and the alarm threshold.
    add(0.0, {"overall": 0.50, "aimAssist": 0.40})
    add(0.5, {"overall": 0.79, "aimAssist": 0.79})
    # Exactly on the alarm threshold contributes zero strength but is still evidence.
    add(1.0, {"overall": 0.80, "aimAssist": 0.80})
    # The specific head wins over overall.
    add(1.5, {"overall": 0.99, "aimAssist": 0.90})
    add(2.0, {"overall": 0.95, "aimAssist": 0.10, "killAura": 0.93})
    add(2.5, {"overall": 0.95, "aimAssist": 0.10})
    # An uncalibrated answer must be ignored entirely.
    add(3.0, {"overall": 1.0, "aimAssist": 1.0}, calibrated=False)
    # A run of maximal predictions, enough to cross every boundary.
    for index in range(60):
        add(3.5 + index * 0.5, {"overall": 1.0, "aimAssist": 1.0})
    # Quiet: decay only, no evidence either way.
    add(120.0, {"overall": 0.5})
    add(300.0, {"overall": 0.5})
    # Relief: sustained very low probability walks the risk back down.
    for index in range(40):
        add(310.0 + index * 2.0, {"overall": 0.0, "aimAssist": 0.0})
    # A head the model does not publish must not be read as zero.
    add(400.0, {"overall": 0.85})
    return predictions


def build_fixture() -> dict:
    config = RiskConfig()
    predictions = build_predictions()
    trace = simulate(predictions, config)
    return {
        "note": "Canonical risk trace. Java RiskSimulationGoldenTest and the Python simulator must "
                "both reproduce every step from the same predictions and the same config.",
        "config": trace.config.to_dict(),
        "predictions": [prediction.to_dict() for prediction in predictions],
        "steps": [step.to_dict() for step in trace.steps],
        "peakRisk": trace.peak_risk,
        "counts": trace.counts(),
        "timeToWatchSeconds": trace.time_to("WATCH"),
        "timeToSuspiciousSeconds": trace.time_to("SUSPICIOUS"),
        "timeToConfirmedSeconds": trace.time_to("CONFIRMED"),
    }


def main() -> None:
    use_utf8_console()
    target = Path(__file__).resolve().parents[2] / "tests" / "data" / "risk_golden.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(build_fixture(), indent=1) + "\n", encoding="utf-8")
    fixture = build_fixture()
    print(f"wrote {target}: {len(fixture['steps'])} steps, peak risk {fixture['peakRisk']:.4f}, "
          f"counts {fixture['counts']}")


if __name__ == "__main__":
    main()

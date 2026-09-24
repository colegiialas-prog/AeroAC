import argparse
import json
from pathlib import Path
from ..evaluation.runner import evaluate_bundle
from ..evaluation.risk_sim import RiskConfig
from ..reporting import dumps


def main():
    p = argparse.ArgumentParser(description="Evaluate a saved ONNX bundle on its test fold or independent recordings")
    p.add_argument("bundle", type=Path)
    p.add_argument("dataset", type=Path)
    p.add_argument("--output", type=Path, default=Path("reports/evaluation"))
    p.add_argument("--fold", choices=("test", "validation", "external-test"), default="test")
    p.add_argument("--threshold", type=float, default=0.8)
    p.add_argument("--risk-config", type=Path)
    p.add_argument("--inference-interval", type=float, default=0.5)
    a = p.parse_args()
    risk = RiskConfig.from_dict(json.loads(a.risk_config.read_text())) if a.risk_config else None
    report = evaluate_bundle(a.bundle, a.dataset, a.output, fold=a.fold, threshold=a.threshold, risk_config=risk, inference_interval_seconds=a.inference_interval)
    print(dumps({"modelVersion": report["modelVersion"], "metrics": report["windowMetrics"], "operatingPoint": report["operatingPoint"], "report": a.output / "report.json"}))


if __name__ == "__main__":
    main()

"""Fail closed when a saved model cannot prove its split/normalization lineage."""
import argparse
from pathlib import Path
from ..audit.leakage import (Finding, ERROR, check_feature_schema, check_encoder_invariance,
    check_split, check_normalization, check_provenance, exit_code)
from ..dataset.records import load_dataset
from ..dataset.lineage import restore_index
from ..dataset.normalize import Normalizer
from ..export.bundle import Bundle
from ..reporting import dumps, write_json


def verify_bundle(dataset, bundle_path):
    findings = check_feature_schema() + check_encoder_invariance()
    try:
        bundle = Bundle.load(bundle_path)
        provenance = bundle.manifest.provenance.to_dict()
        findings += check_provenance(provenance)
        sessions = load_dataset(dataset, require_usable=False)
        manifest = bundle.manifest.provenance.split_manifest
        index, split = restore_index(sessions, manifest)
        findings += check_split(index, split, manifest["groupBy"], manifest.get("holdoutClients", []))
        findings += check_normalization(Normalizer.from_dict(bundle.manifest.normalization), index, split)
    except (ValueError, KeyError, OSError, AssertionError, TypeError) as error:
        findings.append(Finding(ERROR, "verification", str(error)))
    return findings


def main():
    parser = argparse.ArgumentParser(description="Verify content-bound fold isolation and train-only normalization")
    parser.add_argument("dataset", type=Path)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    findings = verify_bundle(args.dataset, args.bundle)
    report = {"passed": not exit_code(findings), "findings": [vars(f) for f in findings]}
    if args.output:
        write_json(args.output, report)
    print(dumps(report))
    raise SystemExit(exit_code(findings))


if __name__ == "__main__":
    main()

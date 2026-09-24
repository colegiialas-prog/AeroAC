"""Reproducible offline evaluation of the actual ONNX artifact, with fixed operating thresholds."""
from pathlib import Path
import hashlib
import json
import numpy as np
from ..dataset.records import load_dataset
from ..dataset.lineage import restore_index, session_digest
from ..dataset.windows import attack_windows, continuous_windows
from ..dataset.exposure import combat_seconds
from ..audit.leakage import check_provenance, exit_code
from ..export.bundle import Bundle
from ..service.runtime import LoadedModel
from ..reporting import write_json
from .metrics import evaluate
from .calibration import sigmoid, reliability_table, expected_calibration_error
from .sessions import breakdowns, evaluate_sessions, false_positive_simulation, detection_simulation
from .risk_sim import RiskConfig
from .exports import build_predictions, hard_negatives, hard_positives, write_reports


def excluded_identities(bundle):
    manifest = bundle.manifest.provenance.split_manifest
    findings = check_provenance(bundle.manifest.provenance.to_dict())
    if exit_code(findings):
        raise ValueError("bundle lacks verifiable lineage: " + "; ".join(str(f) for f in findings))
    items = [s for fold in ("train", "validation", "calibration") for s in manifest["folds"][fold]["sessions"]]
    return {s["sessionId"] for s in items}, {s["playerId"] for s in items}


def eligible_external(sessions, bundles):
    blocked_sessions, blocked_players = set(), set()
    for bundle in bundles:
        for session in sessions:
            expected = bundle.manifest.provenance.split_manifest.get("sessionDigests", {}).get(session.metadata.session_id)
            if expected is not None and session_digest(session) != expected:
                raise ValueError(f"recording/label changed since model split: {session.metadata.session_id}")
        sids, players = excluded_identities(bundle)
        blocked_sessions |= sids
        blocked_players |= players
    return [s for s in sessions if s.metadata.session_id not in blocked_sessions and s.metadata.player_id not in blocked_players]


def windows_for(sessions, manifest):
    if manifest.window == "attack":
        after = min(10, manifest.sequence_length - 1)
        return attack_windows(sessions, before=manifest.sequence_length - 1 - after, after=after)
    return continuous_windows(sessions, manifest.sequence_length, stride=manifest.provenance.training_config.get("stride", 4), require_target=True)


def predict_index(model, index, rows, batch_size=256):
    raw, calibrated = [], []
    from ..schema import default_schema
    schema = default_schema()
    for start in range(0, len(rows), batch_size):
        encoded = index.encode(rows[start:start + batch_size], schema)
        logits = np.asarray(model.backend.run(model.normalize(encoded, schema)))
        if logits.shape != (len(encoded), len(model.bundle.manifest.heads)) or not np.all(np.isfinite(logits)):
            raise ValueError("model produced invalid logits")
        raw.append(sigmoid(logits))
        calibrated.append(model.calibration.apply_logits(logits) if model.calibration else sigmoid(logits))
    shape = (0, len(model.bundle.manifest.heads))
    return (np.concatenate(raw) if raw else np.zeros(shape), np.concatenate(calibrated) if calibrated else np.zeros(shape))


def evaluate_index(model, index, rows, output, *, fold="external-test", threshold=0.8,
                   risk_config=None, inference_interval_seconds=0.5):
    rows = list(rows)
    if not rows:
        raise ValueError("no complete evaluation windows")
    if not np.isfinite(threshold) or not 0 <= threshold <= 1:
        raise ValueError("threshold must be a finite probability, fixed before test")
    config = risk_config or RiskConfig()
    raw, scores = predict_index(model, index, rows)
    manifest = model.bundle.manifest
    column = manifest.heads.index("overall")
    selected = {index.refs[r].session_index for r in rows}
    hours = sum(combat_seconds(index.sessions[i]) for i in selected if index.sessions[i].metadata.label == "LEGIT") / 3600
    labels = index.labels()[rows]
    metrics = evaluate(labels, scores[:, column]).to_dict()
    known_cheat_clients = {s["clientFamily"].strip().casefold() for fold_name in ("train", "validation", "calibration")
                          for s in manifest.provenance.split_manifest["folds"][fold_name]["sessions"] if s["label"] == "CHEAT"}
    unknown_clients = {(s.metadata.client_family or "unknown").strip().casefold() for s in index.sessions
                       if s.metadata.label == "CHEAT"} - known_cheat_clients
    evaluations = evaluate_sessions(index, rows, scores[:, column], threshold=threshold,
        holdout_clients=sorted(unknown_clients),
        risk_config=config, calibrated=model.calibration is not None,
        head_scores={h: scores[:, i] for i, h in enumerate(manifest.heads)},
        inference_interval_seconds=inference_interval_seconds)
    simulated = {"riskConfig": config.to_dict(), "inferenceIntervalSeconds": inference_interval_seconds,
                 "falsePositives": false_positive_simulation(evaluations), "detection": detection_simulation(evaluations),
                 "breakdowns": breakdowns(evaluations),
                 "sessions": [s.to_dict() for s in evaluations]}
    probabilities = scores[:, column]
    operating = {"threshold": threshold, "thresholdSource": "operator-fixed before evaluation; never selected on test",
                 "legitCombatHours": hours,
                 "falsePositiveWindowsPerCombatHour": float(np.sum((labels == 0) & (probabilities >= threshold))) / hours if hours else None,
                 "windowFpr": float(np.mean(probabilities[labels == 0] >= threshold)) if np.any(labels == 0) else None,
                 "windowTpr": float(np.mean(probabilities[labels == 1] >= threshold)) if np.any(labels == 1) else None}
    report = {"modelVersion": manifest.model_version, "fold": fold, "windowMetrics": metrics,
              "operatingPoint": operating, "riskSimulation": simulated,
              "calibrated": model.calibration is not None,
              "calibration": {h: {"ece": expected_calibration_error(scores[:, i], labels), "reliability": reliability_table(scores[:, i], labels)} for i, h in enumerate(manifest.heads)},
              "sessionDigests": {index.sessions[i].metadata.session_id: session_digest(index.sessions[i]) for i in selected},
              "sessionsWithoutWindows": [s.metadata.session_id for i, s in enumerate(index.sessions) if i not in selected],
              "limitations": ["Risk replay uses window-end timestamps, zero transport delay, configured minimum request interval and no Grim evidence. It resets per recorded session; it is not a live server benchmark.",
                              "Window FPR and ROC points are descriptive and correlated; production promotion requires independent players and human review."]}
    report["featureSchemaVersion"] = manifest.feature_schema_version
    report["datasetVersion"] = ",".join(sorted({index.sessions[i].metadata.dataset_version for i in selected}))
    report["cohortId"] = hashlib.sha256(json.dumps({
        "sessions": report["sessionDigests"],
        "windows": sorted((index.sessions[index.refs[r].session_index].metadata.session_id,
                           index.refs[r].start, index.refs[r].length, index.refs[r].anchor) for r in rows),
        "threshold": threshold, "riskConfig": config.to_dict(), "inferenceIntervalSeconds": inference_interval_seconds,
    }, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()
    report["promotion"] = {"status": "BLOCKED_PENDING_HUMAN_REVIEW", "automaticDeployment": False,
        "blockers": [reason for reason, blocked in (
            ("synthetic pipeline-test data", manifest.provenance.evaluation.get("synthetic", False)),
            ("calibration invalid or missing", model.calibration is None),
            ("test fold too small for requested FPR", any(p["reliable"] == 0 for p in metrics["tprAtFpr"].values())),
            ("unknown-client evaluation missing", not unknown_clients),
            ("not an untouched test fold", fold == "validation"),
            ("human-reviewed golden and independent server validation still required", True)) if blocked]}
    write_reports(output, predictions=build_predictions(index, rows, raw[:, column], scores[:, column] if model.calibration else None, manifest.model_version),
        negatives=hard_negatives(index, rows, probabilities, evaluations), positives=hard_positives(index, rows, probabilities, evaluations), simulation=simulated)
    report["predictionHeads"] = list(manifest.heads)
    # Full head tensors are required to replay Java head precedence in threshold exploration.
    np.savez_compressed(Path(output) / "prediction_heads.npz", raw=raw, scores=scores, heads=np.asarray(manifest.heads),
                        rows=np.asarray(rows), calibrated=np.asarray(model.calibration is not None))
    write_json(Path(output) / "report.json", report)
    return report


def evaluate_bundle(bundle_path, dataset, output, *, fold="test", threshold=0.8, risk_config=None,
                    inference_interval_seconds=0.5):
    model = LoadedModel.load(bundle_path)
    sessions = load_dataset(dataset, labels=("LEGIT", "CHEAT"), require_usable=False)
    manifest = model.bundle.manifest.provenance.split_manifest
    if fold in ("test", "validation"):
        index, split = restore_index(sessions, manifest)
        # Keep only selected sessions in evaluation reporting; no train session enters reports.
        rows = split[fold].tolist()
        selected = {index.refs[r].session_index for r in rows}
        selected_sessions = [s for i, s in enumerate(index.sessions) if i in selected]
        remap = {old: new for new, old in enumerate(sorted(selected))}
        from ..dataset.windows import WindowIndex, WindowRef
        subset = WindowIndex(selected_sessions, [WindowRef(remap[index.refs[r].session_index], index.refs[r].start, index.refs[r].length, index.refs[r].anchor) for r in rows], index.length, index.kind)
        index, rows = subset, list(range(len(subset)))
    elif fold == "external-test":
        chosen = eligible_external(sessions, [model.bundle])
        if len(chosen) != len(sessions):
            raise ValueError("external evaluation contains training/validation/calibration sessions or players")
        if any(not s.usable_for_training for s in chosen):
            raise ValueError("external test contains technically unusable recordings; audit before evaluation")
        index = windows_for(chosen, model.bundle.manifest)
        rows = list(range(len(index)))
    else:
        raise ValueError("evaluation fold must be test, validation, or external-test")
    return evaluate_index(model, index, rows, output, fold=fold, threshold=threshold, risk_config=risk_config,
                          inference_interval_seconds=inference_interval_seconds)

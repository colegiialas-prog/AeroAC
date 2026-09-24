"""Phase 3 training run: dataset in, verified model bundle out.

The order of operations is the part that matters. Split by group first, then fit normalisation on
the training fold only, then train, then calibrate on a fold that training never saw, then evaluate
on a test fold that neither training nor calibration saw. Doing any of these out of order produces
numbers that look better and mean less.

A head with no positive examples in training is not trained. A head that always answers "no"
because it never saw a yes would still be published as a confident judgement.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from ..console import use_utf8_console
from ..dataset.features import encode_window
from ..dataset.normalize import Normalizer
from ..dataset.records import load_dataset, unusable_sessions
from ..dataset.splits import Split, group_split, unknown_client_split
from ..dataset.statistics import dataset_report, missingness, split_report
from ..dataset.windows import WindowIndex, attack_windows, balance_report, continuous_windows
from ..evaluation.calibration import HeadCalibration, probability_to_logit
from ..evaluation.metrics import evaluate
from ..export.bundle import Provenance, model_version
from ..schema import FeatureSchema, default_schema
from .config import TrainingConfig, git_commit, head_positive
from ..audit.report import inspect_dataset
from ..audit.leakage import check_split, check_feature_schema, check_encoder_invariance, exit_code
from ..audit.features import blocking_findings
from ..dataset.lineage import bind_manifest
from ..dataset.exposure import combat_seconds
from ..reporting import write_json, dumps

LOGGER = logging.getLogger("aeroml.training")


@dataclass
class HeadLabels:
    names: tuple[str, ...]
    values: np.ndarray  # (N, heads) of 0/1
    trainable: tuple[bool, ...]


def build_head_labels(index: WindowIndex, heads: tuple[str, ...], train_rows: np.ndarray) -> HeadLabels:
    labels = np.zeros((len(index), len(heads)), dtype=np.float32)
    for position, ref in enumerate(index.refs):
        metadata = index.sessions[ref.session_index].metadata
        for column, head in enumerate(heads):
            labels[position, column] = 1.0 if head_positive(head, metadata.label, metadata.cheat_family) else 0.0
    trainable = []
    for column, head in enumerate(heads):
        positives = int(labels[train_rows, column].sum()) if len(train_rows) else 0
        negatives = len(train_rows) - positives
        usable = positives > 0 and negatives > 0
        if not usable:
            LOGGER.warning(
                "head %s has %d positive and %d negative training windows; it will not be trained "
                "and will not be published", head, positives, negatives)
        trainable.append(usable)
    return HeadLabels(heads, labels, tuple(trainable))


def encode_all(index: WindowIndex, rows: np.ndarray, schema: FeatureSchema) -> np.ndarray:
    out = np.zeros((len(rows), index.length, schema.feature_count), dtype=np.float32)
    for position, row in enumerate(rows.tolist()):
        out[position] = encode_window(index.raw(row), schema)
    return out


def build_windows(sessions, config: TrainingConfig, schema: FeatureSchema) -> WindowIndex:
    if config.window == "attack":
        before = config.sequence_length - 1 - min(10, config.sequence_length - 1)
        after = config.sequence_length - 1 - before
        index = attack_windows(sessions, before=before, after=after, schema=schema)
        if index.length != config.sequence_length:
            LOGGER.warning("attack windows are %d samples; sequence_length adjusted from %d",
                           index.length, config.sequence_length)
            config.sequence_length = index.length
        return index
    return continuous_windows(sessions, config.sequence_length, stride=config.stride,
                              require_target=True, schema=schema)


def make_split(index: WindowIndex, config: TrainingConfig) -> Split:
    clients = sorted({(s.metadata.client_family or "unknown").strip().casefold()
                      for s in index.sessions if s.metadata.label == "CHEAT"})
    if not config.holdout_clients and len(clients) >= 2:
        # Precommitted deterministic holdout, chosen without looking at model scores.
        config.holdout_clients = (clients[config.seed % len(clients)],)
    if len(clients) < 2:
        LOGGER.warning("UNKNOWN CLIENT GENERALIZATION NOT MEASURABLE")
    if config.holdout_clients:
        return unknown_client_split(index, config.holdout_clients, group_by=config.group_by, seed=config.seed)
    return group_split(index, group_by=config.group_by, seed=config.seed)


def run(config: TrainingConfig, schema: FeatureSchema | None = None) -> dict:
    import torch
    from torch import nn

    from ..export.onnx_export import export
    from ..models.tcn import FLASH, PRO, ModelConfig, AeroTemporalNet

    schema = schema or default_schema()
    torch.set_num_threads(config.torch_threads)
    torch.manual_seed(config.seed)
    np.random.seed(config.seed)

    if (config.output / "manifest.json").exists() or (config.output / "model.onnx").exists():
        raise ValueError("output already contains a model; use a new bundle directory")
    audit, all_sessions = inspect_dataset(config.dataset)
    write_json(config.output / "dataset_audit.json", audit)
    schema_findings = check_feature_schema(schema) + check_encoder_invariance(schema)
    if exit_code(schema_findings):
        raise ValueError("feature leakage/schema checks failed: " + str(schema_findings))
    verdicts = {a["sessionId"]: a["verdict"] for a in audit["sessions"]}
    sessions = [s for s in all_sessions if s.metadata.label in ("LEGIT", "CHEAT") and s.usable_for_training
                and (verdicts[s.metadata.session_id] == "GOOD" or ((config.include_review or config.allow_synthetic) and verdicts[s.metadata.session_id] == "REVIEW"))]
    golden_path = config.golden_manifest or Path(__file__).resolve().parents[3] / "datasets/manifests/golden-v1.json"
    golden_ids = set()
    if golden_path.is_file():
        golden = json.loads(golden_path.read_text(encoding="utf-8"))
        if golden.get("sessions"):
            from ..tools.review_session import validate_golden
            reviewed = validate_golden(all_sessions, golden)
            golden_players = {s.metadata.player_id for s in reviewed}
            golden_ids = {s.metadata.session_id for s in sessions if s.metadata.player_id in golden_players}
            sessions = [s for s in sessions if s.metadata.player_id not in golden_players]
    elif config.golden_manifest:
        raise ValueError("requested golden manifest is missing")
    synthetic = [s for s in sessions if "synthetic" in (s.metadata.notes or "").lower() or "synthetic" in s.metadata.plugin_version.lower()]
    if synthetic and not config.allow_synthetic:
        raise ValueError("synthetic data is only allowed with --allow-synthetic for pipeline smoke tests")
    if any(s.metadata.label == "CHEAT" and not head_positive("aimAssist", "CHEAT", s.metadata.cheat_family) for s in sessions):
        raise ValueError("this phase trains only LEGIT vs AIM_ASSIST; separate other cheat families")
    if blocking_findings(audit["features"]):
        raise ValueError("possible structural label leakage; inspect dataset_audit.json and review recorder paths")
    if not sessions:
        raise SystemExit(f"no usable labelled sessions under {config.dataset}")
    LOGGER.info("dataset: %s", json.dumps(dataset_report(sessions, schema), default=str))
    LOGGER.warning("audit accepted %d of %d readable sessions; exclusions are recorded in dataset_audit.json", len(sessions), len(all_sessions))

    index = build_windows(sessions, config, schema)
    LOGGER.info("%s | %s", index.describe(), json.dumps(balance_report(index), default=str))
    if len(index) == 0:
        raise SystemExit("no complete windows; record longer sessions or shorten the window")

    split = make_split(index, config)
    findings = check_split(index, split, config.group_by, config.holdout_clients)
    if exit_code(findings):
        raise ValueError("split leakage: " + str(findings))
    bind_manifest(index, split)
    write_json(config.output / "split_manifest.json", split.manifest)
    LOGGER.info("split sizes: %s", split.sizes())
    for fold in ("train", "validation", "calibration", "test"):
        if len(split[fold]) == 0:
            raise SystemExit(f"the {fold} fold is empty; more players or sessions are needed")
        if len(np.unique(index.labels()[split[fold]])) != 2:
            raise ValueError(f"{fold} needs both independent LEGIT and AIM_ASSIST examples; collect more players")
    heads = build_head_labels(index, tuple(config.heads), split.train)
    published = tuple(head for head, usable in zip(config.heads, heads.trainable) if usable)
    if "overall" not in published:
        raise SystemExit("the overall head has no usable training labels; the dataset needs both classes")

    estimated_bytes = len(index) * index.length * schema.feature_count * 4 * 3
    if estimated_bytes > config.max_cached_windows_bytes:
        raise ValueError(f"encoded cache requires about {estimated_bytes} bytes, above configured budget; use a smaller curated corpus")
    train_windows = encode_all(index, split.train, schema)
    normalizer = Normalizer.fit(train_windows, schema)
    never_seen = normalizer.never_observed(schema)
    if never_seen:
        LOGGER.warning("channels never observed in training: %s", never_seen)

    preset = PRO if config.preset == "pro" else FLASH
    model = AeroTemporalNet(ModelConfig(
        feature_count=schema.feature_count,
        sequence_length=index.length,
        heads=published,
        width=preset.width,
        blocks=preset.blocks,
        dropout=config.dropout,
    )).to(config.device)
    LOGGER.info("model %s: %d parameters, receptive field %d of %d samples",
                config.preset, model.parameter_count(), model.config.receptive_field, index.length)

    columns = [list(config.heads).index(head) for head in published]
    tensors = {
        fold: torch.as_tensor(normalizer.apply(train_windows if fold == "train" else encode_all(index, split[fold], schema), schema))
        for fold in ("train", "validation", "calibration", "test") if len(split[fold])
    }
    del train_windows
    targets = {
        fold: torch.as_tensor(heads.values[split[fold]][:, columns])
        for fold in tensors
    }
    positives = targets["train"].sum(dim=0).clamp(min=1.0)
    negatives = targets["train"].shape[0] - positives
    pos_weight = (negatives / positives).to(config.device)
    criterion = nn.BCEWithLogitsLoss(pos_weight=pos_weight)
    optimizer = torch.optim.AdamW(model.parameters(), lr=config.learning_rate, weight_decay=config.weight_decay)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max(1, config.epochs))

    best_state, best_score, patience = None, -np.inf, 0
    history = []
    generator = torch.Generator().manual_seed(config.seed)
    for epoch in range(config.epochs):
        model.train()
        order = torch.randperm(tensors["train"].shape[0], generator=generator)
        total = 0.0
        for start in range(0, order.numel(), config.batch_size):
            rows = order[start:start + config.batch_size]
            optimizer.zero_grad(set_to_none=True)
            logits = model(tensors["train"][rows].to(config.device))
            loss = criterion(logits, targets["train"][rows].to(config.device))
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            optimizer.step()
            total += float(loss.item()) * rows.numel()
        scheduler.step()
        validation = _score(model, tensors["validation"], config)
        overall_column = published.index("overall")
        report = evaluate(targets["validation"][:, overall_column].numpy().astype(int), validation[:, overall_column])
        history.append({"epoch": epoch, "loss": total / max(1, order.numel()), "validationRocAuc": report.roc_auc})
        LOGGER.info("epoch %d loss=%.4f validation ROC-AUC=%.4f", epoch, history[-1]["loss"], report.roc_auc)
        if report.roc_auc > best_score:
            best_score, patience = report.roc_auc, 0
            best_state = {key: value.detach().clone() for key, value in model.state_dict().items()}
        else:
            patience += 1
            if patience >= config.early_stopping_patience:
                LOGGER.info("early stop at epoch %d", epoch)
                break
    if best_state is None:
        raise ValueError("no finite validation score; no model can be selected")
    model.load_state_dict(best_state)

    overall_column = published.index("overall")
    calibration_scores = _score(model, tensors["calibration"], config)
    calibration_labels = targets["calibration"].numpy()
    scaler = None
    try:
        scaler = HeadCalibration.fit(probability_to_logit(calibration_scores), calibration_labels, published)
        if not scaler.improved:
            LOGGER.warning("temperature scaling did not improve likelihood; publishing uncalibrated")
            scaler = None
    except ValueError as error:
        LOGGER.warning("calibration skipped: %s", error)

    results = {}
    for fold in ("validation", "test"):
        if fold not in tensors:
            continue
        scores = _score(model, tensors[fold], config)
        if scaler is not None:
            scores = scaler.apply(scores)
        scores = scores[:, overall_column]
        legit_hours = _legit_hours(index, split[fold])
        results[fold] = evaluate(targets[fold][:, overall_column].numpy().astype(int), scores,
                                 legit_hours=legit_hours).to_dict()
        LOGGER.info("%s: %s", fold, json.dumps(results[fold], default=str))

    provenance = Provenance(
        dataset_root=str(config.dataset),
        git_commit=git_commit(Path(__file__).resolve().parents[3]),
        training_config=config.to_dict(),
        split_manifest=split.manifest,
        evaluation={
            "folds": results,
            "history": history,
            "splitReport": split_report(index, split.indices),
            "missingness": missingness(index, schema=schema),
            "headsNotTrained": [head for head, usable in zip(config.heads, heads.trainable) if not usable],
            "channelsNeverObserved": never_seen,
            "lineage": {"normalizationFold": "train", "epochSelectionFold": "validation", "calibrationFold": "calibration"},
            "synthetic": bool(synthetic),
            "excludedGoldenSessionsAndPlayers": sorted(golden_ids),
            "purpose": "pipeline-smoke-only" if synthetic else "candidate-awaiting-human-promotion-review",
            "unknownClientBenchmark": "held-out" if config.holdout_clients else "UNKNOWN CLIENT GENERALIZATION NOT MEASURABLE",
        },
        notes=config.notes,
    )
    bundle = export(
        model.cpu(),
        config.output,
        model_version=model_version(config.model_version_prefix, provenance.git_commit),
        model_kind=config.preset,
        window=config.window,
        normalizer=normalizer,
        calibration=scaler,
        provenance=provenance,
        sample_windows=tensors["validation"][:8].numpy(),
        schema=schema,
    )
    LOGGER.info("wrote bundle %s (%s)", bundle.path, bundle.manifest.model_version)
    from ..evaluation.runner import evaluate_bundle
    evaluate_bundle(bundle.path, config.dataset, config.output / "evaluation", fold="test")
    return {"bundle": str(bundle.path), "modelVersion": bundle.manifest.model_version, "evaluation": results}


def _score(model, windows, config: TrainingConfig) -> np.ndarray:
    import torch

    model.eval()
    outputs = []
    with torch.no_grad():
        for start in range(0, windows.shape[0], 512):
            batch = windows[start:start + 512].to(config.device)
            outputs.append(torch.sigmoid(model(batch)).cpu().numpy())
    return np.concatenate(outputs) if outputs else np.zeros((0, len(model.config.heads)))


def _legit_hours(index: WindowIndex, rows: np.ndarray) -> float:
    seen: set[str] = set()
    hours = 0.0
    for row in rows.tolist():
        metadata = index.session_of(row).metadata
        if metadata.label != "LEGIT" or metadata.session_id in seen:
            continue
        seen.add(metadata.session_id)
        hours += combat_seconds(index.session_of(row)) / 3600.0
    return hours


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(description="Train an Aero AC combat model")
    parser.add_argument("dataset", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--window", choices=("attack", "continuous"), default="attack")
    parser.add_argument("--sequence-length", type=int, default=31)
    parser.add_argument("--stride", type=int, default=4)
    parser.add_argument("--preset", choices=("flash", "pro"), default="flash")
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--heads", nargs="+", choices=("overall", "aimAssist"), default=["overall", "aimAssist"])
    parser.add_argument("--split", choices=("player", "session"), default=None)
    parser.add_argument("--group-by", nargs="+", default=["player"])
    parser.add_argument("--holdout-clients", nargs="*", default=[])
    parser.add_argument("--notes", default="")
    parser.add_argument("--allow-synthetic", action="store_true", help="pipeline tests only; blocks production promotion")
    parser.add_argument("--include-review", action="store_true", help="explicitly include readable REVIEW sessions; never UNUSABLE")
    parser.add_argument("--golden", type=Path, help="reserve all reviewed golden players outside every training fold")
    parser.add_argument("--log-level", default="INFO")
    arguments = parser.parse_args()
    use_utf8_console()
    logging.basicConfig(level=arguments.log_level.upper(), format="%(asctime)s %(levelname)s %(message)s")
    config = TrainingConfig(
        dataset=arguments.dataset,
        output=arguments.output,
        window=arguments.window,
        sequence_length=arguments.sequence_length,
        stride=arguments.stride,
        preset=arguments.preset,
        epochs=arguments.epochs,
        batch_size=arguments.batch_size,
        seed=arguments.seed,
        device=arguments.device,
        heads=tuple(arguments.heads),
        group_by=(arguments.split,) if arguments.split else tuple(arguments.group_by),
        holdout_clients=tuple(arguments.holdout_clients),
        notes=arguments.notes,
        allow_synthetic=arguments.allow_synthetic,
        include_review=arguments.include_review,
        golden_manifest=arguments.golden,
    )
    print(dumps(run(config)))


if __name__ == "__main__":
    main()

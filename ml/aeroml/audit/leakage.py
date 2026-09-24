"""Leakage checks that fail a build rather than print a warning.

Two kinds are covered.

*Label leakage* asks whether the model can read the answer off its own input. A name blacklist
catches the obvious cases, but names are easy to get wrong, so the real check is empirical: the
encoder output must be invariant to translating the whole world, to renumbering entities, and to
renumbering transactions and ticks. If any of those changes a single channel, something absolute
or identifying reached the model.

*Split leakage* asks whether the evaluation is honest: whether a session or a player straddles two
folds, whether a held-out client family appears anywhere it trained, whether normalisation was
fitted on more than the training fold, and whether the calibration fold was also used to pick an
epoch.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

import numpy as np

from ..dataset.features import encode_window
from ..dataset.normalize import Normalizer
from ..dataset.splits import Split, group_keys
from ..dataset.windows import WindowIndex
from ..schema import FeatureSchema, default_schema

ERROR = "ERROR"
WARNING = "WARNING"

#: Raw fields a model input must never expose. Identity and absolute world state on one side,
#: deterministic check output on the other: the first lets a model learn who someone is, the
#: second lets it copy thresholds the anticheat already enforces.
FORBIDDEN_SOURCE_PATTERNS = (
    "ENTITY_ID",
    "TRANSACTION",
    "SESSION",
    "EVIDENCE",
    "PLAYER_X", "PLAYER_Y", "PLAYER_Z",
    "TARGET_X", "TARGET_Y", "TARGET_Z",
    "TARGET_MIN", "TARGET_MAX",
    "AIM_POINT",
    "SERVER_TICK",
    "CLIENT_PROTOCOL_VERSION",
    "HELD_ITEM_TYPE",
    "TARGET_TYPE",
    "REACH_",
    "LINE_OF_SIGHT",
    "CANCELLED_ATTACK_COUNT",
)

#: Concepts that must never appear as a channel name, whatever raw field backs them.
FORBIDDEN_CHANNEL_TOKENS = (
    "USERNAME", "UUID", "PSEUDONYM", "PLAYER_ID", "SESSION_ID",
    "LABEL", "CHEAT_FAMILY", "CLIENT_FAMILY", "CONFIGURATION",
    "ENTITY_ID", "TRANSACTION", "GRIM", "EVIDENCE", "FLAG", "ABSOLUTE_POSITION",
    # Dataset collection metadata: how a recording was made, not what the player did. A model that
    # reads any of this learns the recording protocol, and the label comes along with it.
    "SCENARIO", "ASSIST_STRENGTH", "REVIEWER",
)
DERIVED_SOURCES = {"targetAngularVelocityYaw", "targetAngularVelocityPitch", "rotationTargetAlignment",
                   "targetRadialSpeed", "targetSpeed", "targetAngularRadius", "aimErrorRatio", "playerSpeedHorizontal"}

#: Absolute world coordinates in the raw frame. Translating all of them must change nothing.
ABSOLUTE_FIELDS = (
    "PLAYER_X", "PLAYER_Y", "PLAYER_Z",
    "TARGET_X", "TARGET_Y", "TARGET_Z",
    "TARGET_MIN_X", "TARGET_MIN_Y", "TARGET_MIN_Z",
    "TARGET_MAX_X", "TARGET_MAX_Y", "TARGET_MAX_Z",
    "AIM_POINT_X", "AIM_POINT_Y", "AIM_POINT_Z",
)

IDENTIFIER_FIELDS = ("TARGET_ENTITY_ID", "PREVIOUS_TARGET_ENTITY_ID", "REACH_TARGET_ENTITY_ID",
                     "TRANSACTION_ID", "SERVER_TICK")


@dataclass(frozen=True)
class Finding:
    severity: str
    check: str
    detail: str

    def __str__(self) -> str:
        return f"[{self.severity}] {self.check}: {self.detail}"


def check_feature_schema(schema: FeatureSchema | None = None) -> list[Finding]:
    """Name-level blacklist. Cheap, and it catches a field added to the schema by habit."""
    schema = schema or default_schema()
    findings: list[Finding] = []
    for value in schema.values:
        upper = value.name.upper()
        for token in FORBIDDEN_CHANNEL_TOKENS:
            if token.replace("_", "") in upper.replace("_", ""):
                findings.append(Finding(ERROR, "feature-schema",
                                        f"channel {value.name} names a forbidden concept ({token})"))
        if value.derived:
            if value.source.split(".", 1)[1] not in DERIVED_SOURCES:
                findings.append(Finding(ERROR, "feature-schema", f"unreviewed derived source {value.source}"))
            continue
        source = value.source.upper()
        for pattern in (*FORBIDDEN_SOURCE_PATTERNS, *FORBIDDEN_CHANNEL_TOKENS):
            if pattern.replace("_", "") in source.replace("_", ""):
                findings.append(Finding(ERROR, "feature-schema",
                                        f"channel {value.name} reads forbidden raw field {value.source}"))
    return findings


def check_encoder_invariance(schema: FeatureSchema | None = None, seed: int = 0) -> list[Finding]:
    """
    Empirical label-leakage check. Build a window, then transform the parts of the world that must
    not matter. Any change in the encoded output means something absolute or identifying got in.
    """
    schema = schema or default_schema()
    rng = np.random.default_rng(seed)
    raw = _synthetic_window(schema, rng)
    baseline = encode_window(raw, schema)
    findings: list[Finding] = []

    translated = raw.copy()
    for field in ABSOLUTE_FIELDS:
        translated[:, schema.raw_index(field)] += 1024.0
    findings += _compare(baseline, encode_window(translated, schema), schema,
                         "world translation", "absolute position reached the model")

    renumbered = raw.copy()
    for field in IDENTIFIER_FIELDS:
        column = renumbered[:, schema.raw_index(field)]
        renumbered[:, schema.raw_index(field)] = np.where(np.isfinite(column), column + 7919.0, column)
    findings += _compare(baseline, encode_window(renumbered, schema), schema,
                         "identifier renumbering", "an entity, transaction or tick id reached the model")

    context = raw.copy()
    for field in ("CLIENT_PROTOCOL_VERSION", "HELD_ITEM_TYPE", "TARGET_TYPE"):
        column = context[:, schema.raw_index(field)]
        context[:, schema.raw_index(field)] = np.where(np.isfinite(column), column + 13.0, column)
    findings += _compare(baseline, encode_window(context, schema), schema,
                         "client context change", "protocol, item or entity type reached the model")

    evidence = raw.copy()
    for field in ("REACH_EVIDENCE", "WALL_HIT_EVIDENCE", "ENTITY_PIERCE_EVIDENCE",
                  "PACKET_ORDER_EVIDENCE", "CANCELLED_ATTACK_COUNT", "REACH_DISTANCE", "LINE_OF_SIGHT"):
        evidence[:, schema.raw_index(field)] = 1.0
    findings += _compare(baseline, encode_window(evidence, schema), schema,
                         "deterministic check evidence", "a Grim check result reached the model")
    return findings


def _compare(baseline: np.ndarray, transformed: np.ndarray, schema: FeatureSchema,
             check: str, detail: str) -> list[Finding]:
    difference = np.abs(baseline - transformed)
    changed = np.flatnonzero((difference.max(axis=0) > 1e-6) | ~np.all(np.isfinite(difference), axis=0))
    if changed.size == 0:
        return []
    names = [schema.channel_names[index] for index in changed.tolist()]
    return [Finding(ERROR, f"encoder-invariance/{check}", f"{detail}: {', '.join(names)}")]


def _synthetic_window(schema: FeatureSchema, rng: np.random.Generator) -> np.ndarray:
    """A window with every field populated, so a leak cannot hide behind an unknown value."""
    length = 8
    raw = np.zeros((length, len(schema.raw_fields)), dtype=np.float64)
    for index, name in enumerate(schema.raw_fields):
        raw[:, index] = rng.uniform(0.5, 2.5, size=length)
    raw[:, schema.raw_index("TARGET_PRESENT")] = 1.0
    raw[:, schema.raw_index("TARGET_SWITCH")] = 0.0
    raw[:, schema.raw_index("SEGMENT_START")] = 0.0
    raw[:, schema.raw_index("TARGET_ENTITY_ID")] = 55.0
    raw[:, schema.raw_index("PREVIOUS_TARGET_ENTITY_ID")] = 54.0
    raw[:, schema.raw_index("TICKS_SINCE_TARGET_SWITCH")] = np.arange(length, dtype=float)
    raw[:, schema.raw_index("DISTANCE_TO_TARGET")] = np.linspace(3.0, 4.0, length)
    raw[:, schema.raw_index("TARGET_YAW")] = np.linspace(-10.0, 10.0, length)
    raw[:, schema.raw_index("TARGET_PITCH")] = np.linspace(-2.0, 2.0, length)
    for axis in ("X", "Z"):
        raw[:, schema.raw_index(f"TARGET_MIN_{axis}")] = 0.0
        raw[:, schema.raw_index(f"TARGET_MAX_{axis}")] = 0.6
    raw[:, schema.raw_index("TARGET_MIN_Y")] = 64.0
    raw[:, schema.raw_index("TARGET_MAX_Y")] = 65.8
    for axis in ("X", "Y", "Z"):
        raw[:, schema.raw_index(f"AIM_POINT_{axis}")] = 64.0
        raw[:, schema.raw_index(f"PLAYER_{axis}")] = 64.0
        raw[:, schema.raw_index(f"TARGET_{axis}")] = 64.0
    return raw


def check_split(index: WindowIndex, split: Split, group_by: Sequence[str] = ("player",),
                holdout_clients: Sequence[str] = ()) -> list[Finding]:
    """Every split invariant, reported rather than raised, so one run can list them all."""
    findings: list[Finding] = []
    sessions = index.attribute("session")
    if any(np.asarray(rows).dtype.kind not in "iu" or np.any(np.asarray(rows) < 0) or np.any(np.asarray(rows) >= len(index)) for rows in split.indices.values()):
        return [Finding(ERROR, "split/indices", "invalid window index")]
    players = index.attribute("player")
    clients = np.asarray([str(value).lower() for value in index.attribute("client")], dtype=object)
    configurations = index.attribute("configuration")
    keys = group_keys(index, group_by)

    seen_window: dict[int, str] = {}
    for fold, rows in split.indices.items():
        for row in rows.tolist():
            if row in seen_window:
                findings.append(Finding(ERROR, "split/duplicate-window",
                                        f"window {row} is in both {seen_window[row]} and {fold}"))
            seen_window[row] = fold

    for name, attribute in (("session", sessions), ("player", players), ("group", keys)):
        if name == "player" and "player" not in group_by and "session" in group_by:
            # A session-level split deliberately allows one player in several folds; say so once.
            findings.append(Finding(WARNING, "split/player",
                                    "grouped by session only: one player may appear in several folds, "
                                    "which measures memorisation of that player as if it were detection"))
            continue
        placement: dict[str, str] = {}
        for fold, rows in split.indices.items():
            for row in rows.tolist():
                value = str(attribute[row])
                if placement.setdefault(value, fold) != fold:
                    findings.append(Finding(ERROR, f"split/{name}",
                                            f"{name} {value!r} is in both {placement[value]} and {fold}"))
                    break

    assigned = sum(len(rows) for rows in split.indices.values())
    if assigned != len(index):
        findings.append(Finding(ERROR, "split/coverage",
                                f"{len(index) - assigned} windows were not assigned to any fold"))

    for fold in ("train", "validation", "calibration"):
        if len(split.indices.get(fold, [])) == 0:
            findings.append(Finding(ERROR, "split/empty-fold", f"the {fold} fold is empty"))
    if len(split.indices.get("test", [])) == 0:
        findings.append(Finding(WARNING, "split/empty-fold", "the test fold is empty"))

    holdout = {client.lower() for client in holdout_clients}
    if holdout:
        for fold in ("train", "validation", "calibration"):
            rows = split.indices.get(fold, np.asarray([], dtype=np.int64)).tolist()
            leaked_clients = {clients[row] for row in rows} & holdout
            if leaked_clients:
                findings.append(Finding(ERROR, "split/unknown-client",
                                        f"held-out client(s) {sorted(leaked_clients)} appear in {fold}"))
            held_configurations = {
                f"{clients[row]}/{configurations[row]}"
                for row in split.indices.get("test", np.asarray([], dtype=np.int64)).tolist()
                if clients[row] in holdout
            }
            fold_configurations = {f"{clients[row]}/{configurations[row]}" for row in rows}
            overlap = held_configurations & fold_configurations
            if overlap:
                findings.append(Finding(ERROR, "split/unknown-client-configuration",
                                        f"configurations of a held-out client appear in {fold}: {sorted(overlap)}"))
    return findings


def check_normalization(normalizer: Normalizer, index: WindowIndex, split: Split,
                        schema: FeatureSchema | None = None, tolerance: float = 1e-4) -> list[Finding]:
    """
    Refits normalisation on the recorded training fold and compares. A bundle whose statistics do
    not match its own training fold was fitted on something else — usually the whole dataset.
    """
    schema = schema or default_schema()
    train_rows = split.indices.get("train")
    if train_rows is None or len(train_rows) == 0:
        return [Finding(ERROR, "normalization", "no training fold to verify against")]
    expected = Normalizer.fit(index.encode(train_rows.tolist(), schema), schema)
    findings: list[Finding] = []
    if tuple(normalizer.channels) != tuple(expected.channels):
        findings.append(Finding(ERROR, "normalization", "channel list does not match the current schema"))
        return findings
    drift = np.max(np.abs(np.asarray(normalizer.mean) - expected.mean)) if len(expected.mean) else 0.0
    scale = np.max(np.abs(np.asarray(normalizer.std) - expected.std)) if len(expected.std) else 0.0
    if drift > tolerance or scale > tolerance:
        findings.append(Finding(ERROR, "normalization",
                                f"statistics differ from a refit on the training fold "
                                f"(max mean drift {drift:.2e}, max std drift {scale:.2e}); "
                                f"they were probably fitted on more than train"))
    return findings


def check_provenance(provenance: dict) -> list[Finding]:
    """What can be checked from a bundle alone, without the dataset it was trained on."""
    findings: list[Finding] = []
    split = provenance.get("splitManifest") or {}
    evaluation = provenance.get("evaluation") or {}
    groups = split.get("groups") or {}

    if not split:
        findings.append(Finding(ERROR, "provenance/split", "bundle records no split manifest"))
    strategy = split.get("strategy")
    if strategy == "group":
        group_by = split.get("groupBy") or []
        if "player" not in group_by and "session" not in group_by:
            findings.append(Finding(ERROR, "provenance/split",
                                    f"grouped by {group_by}, which does not isolate sessions or players"))
        for left in ("train", "validation", "calibration", "test"):
            for right in ("train", "validation", "calibration", "test"):
                if left >= right:
                    continue
                overlap = set(groups.get(left, [])) & set(groups.get(right, []))
                if overlap:
                    findings.append(Finding(ERROR, "provenance/split",
                                            f"groups shared by {left} and {right}: {sorted(overlap)[:3]}"))
    elif strategy == "unknown-client":
        if not split.get("holdoutClients"):
            findings.append(Finding(ERROR, "provenance/split", "unknown-client split names no held-out client"))
    else:
        findings.append(Finding(WARNING, "provenance/split", f"unrecognised split strategy {strategy!r}"))

    lineage = evaluation.get("lineage", {})
    for key, expected in (("normalizationFold", "train"), ("epochSelectionFold", "validation"), ("calibrationFold", "calibration")):
        if lineage.get(key) != expected:
            findings.append(Finding(ERROR, "provenance/lineage", f"{key} must be explicitly recorded as {expected}"))
    memberships = split.get("folds", {})
    if set(memberships) != {"train", "validation", "calibration", "test"}:
        findings.append(Finding(ERROR, "provenance/membership", "explicit membership of all four folds is required"))
    else:
        seen = {"sessionId": {}, "playerId": {}}
        holdout = {str(c).strip().casefold() for c in split.get("holdoutClients", [])}
        for fold, block in memberships.items():
            for item in block.get("sessions", []):
                for key in seen:
                    if key == "playerId" and "player" not in split.get("groupBy", []):
                        continue
                    value = item.get(key)
                    if not value or seen[key].setdefault(value, fold) != fold:
                        findings.append(Finding(ERROR, "provenance/membership", f"missing or shared {key}: {value}"))
                if fold != "test" and str(item.get("clientFamily", "")).strip().casefold() in holdout:
                    findings.append(Finding(ERROR, "provenance/unknown-client", f"held-out client/configuration in {fold}"))
    history = evaluation.get("history") or []
    if history and any("validationRocAuc" not in entry for entry in history):
        findings.append(Finding(ERROR, "provenance/calibration",
                                "training history does not record a validation score; the epoch may "
                                "have been selected on the calibration or test fold"))
    if not history:
        findings.append(Finding(WARNING, "provenance/calibration", "bundle records no training history"))

    sizes = split.get("sizes") or {}
    if sizes.get("calibration", 0) == 0:
        findings.append(Finding(ERROR, "provenance/calibration", "no calibration fold was used"))
    return findings


def worst_severity(findings: Sequence[Finding]) -> str | None:
    if any(finding.severity == ERROR for finding in findings):
        return ERROR
    if findings:
        return WARNING
    return None


def exit_code(findings: Sequence[Finding]) -> int:
    return 1 if any(finding.severity == ERROR for finding in findings) else 0


def load_provenance(bundle_path: Path | str) -> dict:
    from ..export.bundle import Bundle

    return Bundle.load(bundle_path).manifest.provenance.to_dict()

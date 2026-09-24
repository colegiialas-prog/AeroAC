"""One read-only entry point for dataset audits and training preflight."""
from collections import Counter
from pathlib import Path
from .sessions import AuditPolicy, SessionAudit, audit_session, load_safely, summarise_audits
from .distribution import cheat_distribution, legit_distribution, concentration, concentration_warnings, window_totals
from .features import feature_report
from .leakage import check_feature_schema, check_encoder_invariance
from ..dataset.records import iter_session_paths
from ..dataset.exposure import combat_seconds
from ..dataset.windows import attack_windows
from ..schema import default_schema


def inspect_dataset(root, *, policy=None, feature_sample=2048):
    schema = default_schema()
    policy = policy or AuditPolicy()
    sessions, audits, seen = [], [], set()
    for path in iter_session_paths(root):
        session, error = load_safely(path, schema)
        if session is None:
            audits.append(SessionAudit(path.stem.removeprefix("session-"), "UNKNOWN", "UNUSABLE", [f"cannot read: {error}"], path=str(path)))
            continue
        audit = audit_session(session, policy, schema)
        if session.metadata.session_id in seen:
            audit.verdict = "UNUSABLE"
            audit.reasons.append("duplicate sessionId")
        else:
            seen.add(session.metadata.session_id)
            sessions.append(session)
        audits.append(audit)
    raw_ids = {p.stem.removeprefix("session-") for p in (Path(root) / "raw").glob("session-*.jsonl")}
    labelled = [s for s in sessions if s.metadata.label in ("LEGIT", "CHEAT")]
    # Feature statistics include readable REVIEW recordings; they must not silently disappear.
    features = feature_report(attack_windows(labelled, before=20, after=10), sample=feature_sample) if labelled else {"windows": 0}
    counts = Counter(a.label for a in audits)
    reasons = [" ".join(a.reasons).lower() for a in audits]
    report = {
        "datasetVersions": sorted({s.metadata.dataset_version for s in sessions}),
        "schemaVersions": sorted({s.metadata.schema_version for s in sessions}),
        "featureSchemaVersion": schema.version,
        "sessionsTotal": len(audits), "sessionsByLabel": {k: counts[k] for k in ("LEGIT", "CHEAT", "UNLABELED", "UNKNOWN")},
        "players": len({s.metadata.player_id for s in sessions}),
        "clientFamilies": sorted({s.metadata.client_family or "unknown" for s in sessions}),
        "cheatFamilies": sorted({s.metadata.cheat_family for s in sessions if s.metadata.cheat_family}),
        "configurations": sorted({(s.metadata.client_family or "unknown") + "/" + (s.metadata.configuration or "default") for s in sessions}),
        "protocolVersions": sorted({s.metadata.minecraft_protocol for s in sessions}),
        "combatHours": {label: sum(combat_seconds(s) for s in sessions if s.metadata.label == label) / 3600 for label in ("LEGIT", "CHEAT", "UNLABELED")},
        "combatExposureDefinition": "up to 50ms per observed TARGET_PRESENT/ATTACK frame, bounded by next timestamp/session end; idle and missing intervals excluded",
        "totalFrames": sum(len(s) for s in sessions), **window_totals(sessions),
        "incompleteSessions": sum(not s.metadata.complete for s in sessions),
        "sessionsWithDroppedRecords": sum(s.metadata.dropped_records > 0 for s in sessions),
        "sessionsWithChronologyErrors": sum(any(t in r for t in ("chronology", "strictly increasing", "timestamp")) for r in reasons),
        "sessionsMissingCriticalTelemetry": sum("missing critical" in r or "missing target geometry" in r for r in reasons),
        "unreadableSessions": sum(a.label == "UNKNOWN" for a in audits),
        "orphanRawSessions": sorted(raw_ids - {a.session_id for a in audits}),
        "quality": summarise_audits(audits), "policy": policy.describe(),
        "sessions": [a.to_dict() for a in audits],
        "cheatDistribution": cheat_distribution(sessions), "legitDistribution": legit_distribution(sessions),
        "concentration": concentration(sessions), "warnings": concentration_warnings(sessions),
        "features": features,
        "leakage": [vars(f) for f in check_feature_schema() + check_encoder_invariance()],
    }
    return report, sessions

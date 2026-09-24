"""Per-session quality verdicts.

Three outcomes, and the reasons are always explicit:

* ``GOOD``     — nothing found; usable as recorded.
* ``REVIEW``   — a human has to decide. The session is readable, but something about it would
                 distort training or evaluation if used without knowing about it.
* ``UNUSABLE`` — cannot be read or cannot be trusted at all.

Nothing here deletes a session. A dataset cleaner that silently drops the awkward recordings
removes exactly the sessions a detector most needs to be measured against, and it does it without
leaving a trace of what was thrown away.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterator

import numpy as np

from ..schema import FeatureSchema, default_schema
from ..dataset.records import Session, iter_session_paths, load_session
from ..dataset.exposure import combat_seconds

GOOD = "GOOD"
REVIEW = "REVIEW"
UNUSABLE = "UNUSABLE"


@dataclass(frozen=True)
class AuditPolicy:
    """Thresholds an operator can argue with. Defaults are conservative, not tuned to any dataset."""

    min_frames: int = 200
    min_attacks: int = 5
    min_duration_seconds: float = 20.0
    min_target_present_rate: float = 0.25
    min_aim_error_known_rate: float = 0.20
    max_sampling_gap_ms: float = 150.0
    max_sampling_gap_rate: float = 0.02
    max_dropped_records: int = 0
    min_segment_frames: int = 32

    def describe(self) -> dict:
        return {
            "minFrames": self.min_frames,
            "minAttacks": self.min_attacks,
            "minDurationSeconds": self.min_duration_seconds,
            "minTargetPresentRate": self.min_target_present_rate,
            "minAimErrorKnownRate": self.min_aim_error_known_rate,
            "maxSamplingGapMs": self.max_sampling_gap_ms,
            "maxSamplingGapRate": self.max_sampling_gap_rate,
            "maxDroppedRecords": self.max_dropped_records,
            "minSegmentFrames": self.min_segment_frames,
        }


@dataclass
class SessionAudit:
    session_id: str
    label: str
    verdict: str
    reasons: list[str] = field(default_factory=list)
    metrics: dict = field(default_factory=dict)
    path: str | None = None

    @property
    def usable(self) -> bool:
        return self.verdict == GOOD

    def to_dict(self) -> dict:
        return {
            "sessionId": self.session_id,
            "label": self.label,
            "verdict": self.verdict,
            "reasons": self.reasons,
            "metrics": self.metrics,
            "path": self.path,
        }


def load_safely(path: Path, schema: FeatureSchema | None = None) -> tuple[Session | None, str | None]:
    """Reading a broken session must produce a verdict, not a stack trace that stops the audit."""
    try:
        return load_session(path, schema=schema, keep_events=False), None
    except Exception as error:  # noqa: BLE001 - any read failure is a finding, not a crash
        return None, f"{type(error).__name__}: {error}"


def audit_session(session: Session, policy: AuditPolicy | None = None,
                  schema: FeatureSchema | None = None) -> SessionAudit:
    policy = policy or AuditPolicy()
    schema = schema or default_schema()
    metadata = session.metadata
    reasons: list[str] = []
    unusable: list[str] = []
    frames = len(session)

    if frames == 0:
        unusable.append("no frames")

    if metadata.schema_version != schema.raw_schema_version:
        unusable.append(f"unsupported schema {metadata.schema_version}")
    if session.quality.malformed_lines:
        unusable.append(f"corrupt records: {session.quality.malformed_lines} malformed lines")
    if session.quality.frame_count_mismatch:
        unusable.append("metadata frame count mismatch")
    if frames > 1 and (np.any(np.diff(session.offsets) <= 0) or np.any(np.diff(session.ticks) <= 0)):
        unusable.append("chronology violation")

    if not metadata.complete:
        reasons.append("incomplete metadata")
    if metadata.failure:
        reasons.append(f"recorder failure: {metadata.failure}")
    if metadata.dropped_records > policy.max_dropped_records:
        reasons.append(f"dropped records: {metadata.dropped_records}")
    if session.quality.truncated_last_line:
        reasons.append("truncated last line")
    if session.quality.tick_gaps:
        reasons.append(f"tick holes: {session.quality.tick_gaps}")
    if session.quality.unknown_record_types:
        reasons.append(f"unknown record types: {session.quality.unknown_record_types}")

    duration_seconds = metadata.duration_ms / 1000.0
    if frames and frames < policy.min_frames:
        reasons.append(f"too short: {frames} frames < {policy.min_frames}")
    if duration_seconds < policy.min_duration_seconds:
        reasons.append(f"too short: {duration_seconds:.1f}s < {policy.min_duration_seconds:.0f}s")

    metrics: dict = {
        "frames": frames,
        "durationSeconds": round(duration_seconds, 2),
        "segments": len(session.segments(schema)),
        "droppedRecords": metadata.dropped_records,
        "tickGaps": session.quality.tick_gaps,
        "combatSeconds": combat_seconds(session),
    }

    if frames:
        if not np.all(np.isfinite(session.column("YAW"))) or not np.all(np.isfinite(session.column("PITCH"))):
            unusable.append("missing critical telemetry: rotation")
        attacks = int(np.count_nonzero(session.column("ATTACK", schema) == 1))
        target_rate = float(np.mean(session.column("TARGET_PRESENT", schema) == 1))
        aim_error = session.column("AIM_ERROR_TOTAL", schema)
        aim_known = float(np.mean(np.isfinite(aim_error)))
        intervals = session.column("SAMPLE_INTERVAL_MS", schema)
        finite_intervals = intervals[np.isfinite(intervals)]
        gap_rate = float(np.mean(finite_intervals > policy.max_sampling_gap_ms)) if finite_intervals.size else 0.0
        ping = session.column("PING_MS", schema)
        longest_segment = max((end - start for start, end in session.segments(schema)), default=0)

        metrics.update({
            "attacks": attacks,
            "attacksPerMinute": round(attacks / max(duration_seconds / 60.0, 1e-9), 2),
            "targetPresentRate": round(target_rate, 4),
            "aimErrorKnownRate": round(aim_known, 4),
            "pingKnownRate": round(float(np.mean(np.isfinite(ping))), 4),
            "medianPingMs": round(float(np.median(ping[np.isfinite(ping)])), 1) if np.any(np.isfinite(ping)) else None,
            "samplingGapRate": round(gap_rate, 4),
            "medianSampleIntervalMs": round(float(np.median(finite_intervals)), 2) if finite_intervals.size else None,
            "longestSegmentFrames": int(longest_segment),
        })

        if attacks == 0:
            reasons.append("no combat: zero attacks recorded")
        elif attacks < policy.min_attacks:
            reasons.append(f"too few attacks: {attacks} < {policy.min_attacks}")
        if target_rate < policy.min_target_present_rate:
            reasons.append(f"missing target geometry: target present {target_rate:.1%}")
        if aim_known < policy.min_aim_error_known_rate:
            reasons.append(f"missing target geometry: aim error known {aim_known:.1%}")
        if gap_rate > policy.max_sampling_gap_rate:
            reasons.append(f"large sampling gaps: {gap_rate:.1%} of samples over {policy.max_sampling_gap_ms:.0f}ms")
        if longest_segment < policy.min_segment_frames:
            reasons.append(f"no usable segment: longest run {longest_segment} frames")

    if metadata.label == "CHEAT" and not metadata.cheat_family:
        unusable.append("CHEAT session without a cheat family")
    if metadata.label == "CHEAT" and (not metadata.client_family or not metadata.configuration):
        reasons.append("incomplete metadata: CHEAT client family or configuration missing")
    if metadata.label == "UNLABELED":
        reasons.append("unlabelled: production recording, not ground truth")

    verdict = UNUSABLE if unusable else (REVIEW if reasons else GOOD)
    return SessionAudit(
        session_id=metadata.session_id,
        label=metadata.label,
        verdict=verdict,
        reasons=unusable + reasons,
        metrics=metrics,
        path=str(session.path) if session.path else None,
    )


def audit_dataset(root: Path | str, policy: AuditPolicy | None = None,
                  schema: FeatureSchema | None = None) -> list[SessionAudit]:
    schema = schema or default_schema()
    results: list[SessionAudit] = []
    for path in iter_session_paths(root):
        session, error = load_safely(path, schema)
        if session is None:
            results.append(SessionAudit(
                session_id=path.stem.replace("session-", ""),
                label="UNKNOWN",
                verdict=UNUSABLE,
                reasons=[f"cannot read: {error}"],
                path=str(path),
            ))
            continue
        results.append(audit_session(session, policy, schema))
    return results


def usable_sessions(root: Path | str, policy: AuditPolicy | None = None,
                    schema: FeatureSchema | None = None,
                    accept: tuple[str, ...] = (GOOD,)) -> Iterator[Session]:
    """Yields sessions whose verdict is in ``accept``. Training uses (GOOD,) unless told otherwise."""
    schema = schema or default_schema()
    for path in iter_session_paths(root):
        session, error = load_safely(path, schema)
        if session is None:
            continue
        if audit_session(session, policy, schema).verdict in accept:
            yield session


def summarise_audits(audits: list[SessionAudit]) -> dict:
    counts = {GOOD: 0, REVIEW: 0, UNUSABLE: 0}
    reasons: dict[str, int] = {}
    for audit in audits:
        counts[audit.verdict] = counts.get(audit.verdict, 0) + 1
        for reason in audit.reasons:
            key = reason.split(":")[0]
            reasons[key] = reasons.get(key, 0) + 1
    return {"verdicts": counts, "reasonCounts": dict(sorted(reasons.items(), key=lambda item: -item[1]))}

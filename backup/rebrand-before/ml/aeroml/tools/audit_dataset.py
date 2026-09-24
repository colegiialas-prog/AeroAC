"""Dataset audit CLI.

    python -m aeroml.tools.audit_dataset /path/to/datasets
    python -m aeroml.tools.audit_dataset /path/to/datasets --features --json report.json
    python -m aeroml.tools.audit_dataset /path/to/datasets --write-review-candidates candidates.json

Reports, never repairs. Every verdict comes with the reasons behind it, and nothing is deleted or
relabelled: a tool that quietly drops the awkward recordings removes exactly the sessions a
detector most needs to be measured against.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from ..audit.distribution import (
    cheat_distribution,
    concentration,
    concentration_warnings,
    legit_distribution,
    window_totals,
)
from ..audit.features import feature_report
from ..audit.sessions import GOOD, REVIEW, UNUSABLE, AuditPolicy, audit_dataset, load_safely, summarise_audits
from ..console import use_utf8_console
from ..dataset.manifest import GoldenManifest, review_candidates, verify
from ..dataset.records import Session, iter_session_paths
from ..dataset.windows import attack_windows
from ..schema import default_schema
from ..dataset.exposure import combat_seconds
from ..reporting import write_json
from ..audit.admin_export import enrich_admin_report


def collect(root: Path, schema) -> tuple[list[Session], list[str]]:
    sessions: list[Session] = []
    unreadable: list[str] = []
    for path in iter_session_paths(root):
        session, error = load_safely(path, schema)
        if session is None:
            unreadable.append(f"{path.name}: {error}")
        else:
            sessions.append(session)
    return sessions, unreadable


def overview(sessions: list[Session], audits, unreadable: list[str], schema,
             continuous_length: int, stride: int) -> dict:
    by_label: dict[str, list[Session]] = {}
    for session in sessions:
        by_label.setdefault(session.metadata.label, []).append(session)

    def hours(label: str) -> float:
        return sum(combat_seconds(item) for item in by_label.get(label, [])) / 3600.0

    verdicts = {audit.session_id: audit for audit in audits}
    incomplete = [item for item in sessions if not item.metadata.complete]
    dropped = [item for item in sessions if item.metadata.dropped_records > 0]
    chronology = [item for item in sessions
                  if item.quality.malformed_lines or item.quality.tick_gaps or item.quality.truncated_last_line]
    missing_telemetry = [
        audit.session_id for audit in audits
        if any(reason.startswith("missing target geometry") for reason in audit.reasons)
    ]

    return {
        "datasetVersion": sorted({item.metadata.dataset_version for item in sessions}) or ["unknown"],
        "rawSchemaVersion": sorted({item.metadata.schema_version for item in sessions}) or [schema.raw_schema_version],
        "featureSchemaVersion": schema.version,
        "sessionsTotal": len(sessions) + len(unreadable),
        "sessionsUnreadable": len(unreadable),
        "sessionsByLabel": {label: len(items) for label, items in sorted(by_label.items())},
        "players": len({item.metadata.player_id for item in sessions}),
        "clientFamilies": sorted({item.metadata.client_family or "unknown" for item in sessions}),
        "cheatFamilies": sorted({item.metadata.cheat_family for item in sessions if item.metadata.cheat_family}),
        "configurations": sorted({(item.metadata.client_family or "unknown") + "/" + (item.metadata.configuration or "default") for item in sessions}),
        "protocolVersions": sorted({item.metadata.minecraft_protocol for item in sessions}),
        "combatHours": {label: round(hours(label), 4) for label in sorted(by_label)},
        "totalFrames": sum(len(item) for item in sessions),
        **window_totals(sessions, continuous_length, stride, schema),
        "incompleteSessions": len(incomplete),
        "sessionsWithDroppedRecords": len(dropped),
        "sessionsWithChronologyErrors": len(chronology),
        "sessionsMissingCriticalTelemetry": len(missing_telemetry),
        "verdicts": summarise_audits(audits)["verdicts"],
        "reasonCounts": summarise_audits(audits)["reasonCounts"],
        "unreadable": unreadable,
        "_audits": verdicts,
    }


def render(report: dict) -> str:
    lines: list[str] = []

    def section(title: str) -> None:
        lines.append("")
        lines.append(title)
        lines.append("-" * len(title))

    head = report["overview"]
    section("Dataset")
    lines.append(f"  dataset version        {', '.join(head['datasetVersion'])}")
    lines.append(f"  raw schema version     {', '.join(str(v) for v in head['rawSchemaVersion'])}")
    lines.append(f"  feature schema version {head['featureSchemaVersion']}")

    section("Sessions")
    lines.append(f"  total                  {head['sessionsTotal']}")
    for label in ("LEGIT", "CHEAT", "UNLABELED"):
        lines.append(f"  {label:<22} {head['sessionsByLabel'].get(label, 0)}")
    if head["sessionsUnreadable"]:
        lines.append(f"  unreadable             {head['sessionsUnreadable']}")

    section("Coverage")
    lines.append(f"  players                {head['players']}")
    lines.append(f"  client families        {', '.join(head['clientFamilies']) or '-'}")
    lines.append(f"  cheat families         {', '.join(head['cheatFamilies']) or '-'}")
    lines.append(f"  configurations         {len(head['configurations'])}")
    lines.append(f"  protocol versions      {', '.join(str(v) for v in head['protocolVersions']) or '-'}")

    section("Volume")
    for label in ("LEGIT", "CHEAT"):
        lines.append(f"  combat hours {label:<9} {head['combatHours'].get(label, 0.0)}")
    lines.append(f"  total frames           {head['totalFrames']}")
    lines.append(f"  attack windows         {head['attackWindows']}")
    lines.append(f"  continuous windows     {head['continuousWindows']} "
                 f"(length {head['continuousLength']}, stride {head['continuousStride']})")

    section("Quality")
    lines.append(f"  incomplete             {head['incompleteSessions']}")
    lines.append(f"  dropped records        {head['sessionsWithDroppedRecords']}")
    lines.append(f"  chronology errors      {head['sessionsWithChronologyErrors']}")
    lines.append(f"  missing telemetry      {head['sessionsMissingCriticalTelemetry']}")
    for verdict in (GOOD, REVIEW, UNUSABLE):
        lines.append(f"  {verdict:<22} {head['verdicts'].get(verdict, 0)}")
    for reason, count in head["reasonCounts"].items():
        lines.append(f"      {count:>4}x {reason}")

    section("CHEAT distribution")
    cheat = report["cheatDistribution"]
    if not cheat:
        lines.append("  (no CHEAT sessions)")
    for family, clients in sorted(cheat.items()):
        lines.append(f"  {family.upper()}")
        for client, configurations in sorted(clients.items()):
            lines.append(f"    {client}")
            for configuration, group in sorted(configurations.items()):
                lines.append(f"      {configuration:<24} sessions={group['sessions']:<4} "
                             f"players={group['players']:<4} minutes={group['durationMinutes']:<8} "
                             f"windows={group['windows']}")

    section("LEGIT distribution")
    legit = report["legitDistribution"]
    if legit.get("sessions", 0) == 0:
        lines.append(f"  {legit.get('note', 'no LEGIT sessions')}")
    else:
        lines.append(f"  sessions {legit['sessions']}  players {legit['uniquePlayers']}  "
                     f"combat hours {legit['combatHours']}  attack windows {legit['attackWindows']}")
        lines.append(f"  ping buckets           {legit['pingBuckets']}")
        lines.append(f"  protocol versions      {legit['protocolVersions']}")
        lines.append(f"  client families        {legit['clientFamilies']}")
        for key in ("sessionsPerPlayer", "sessionDurationSeconds", "attacksPerMinute",
                    "rotationSpeedDegPerSample", "aimErrorDegrees", "targetDistanceBlocks",
                    "missingTargetTelemetryRate"):
            stats = legit[key]
            if stats.get("count"):
                lines.append(f"  {key:<26} p01={stats['p01']:<10} p50={stats['p50']:<10} "
                             f"p99={stats['p99']:<10} mean={stats['mean']}")

    if report.get("featureAudit"):
        section("Feature audit findings")
        findings = report["featureAudit"].get("findings", [])
        if not findings:
            lines.append("  none")
        for finding in findings:
            lines.append(f"  [{finding['kind']}] {finding['channel']}: {finding['detail']}")

    warnings = report["warnings"]
    if warnings:
        section("Warnings")
        for warning in warnings:
            lines.append(f"  ! {warning}")

    if report.get("manifest"):
        section("Golden manifest")
        for key, value in report["manifest"].items():
            lines.append(f"  {key:<22} {value}")
        if not report["manifest"].get("reviewed"):
            lines.append("  ! no completed human reviews: this manifest cannot support a regression claim")
    if report.get("reviewCandidates"):
        section("Review candidates")
        for key, value in report["reviewCandidates"].items():
            lines.append(f"  {key:<22} {value}")
        lines.append("  (a candidate is not a review; promote one with aeroml.tools.review_session)")

    section("Sessions needing review")
    review = [audit for audit in report["sessions"] if audit["verdict"] != GOOD]
    if not review:
        lines.append("  none")
    for audit in review:
        lines.append(f"  {audit['verdict']:<9} {audit['sessionId']} [{audit['label']}]")
        for reason in audit["reasons"]:
            lines.append(f"      - {reason}")
    return "\n".join(lines).lstrip("\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Audit an Aero AC dataset; report only, never repair")
    parser.add_argument("root", type=Path, help="dataset root containing raw/ and metadata/")
    parser.add_argument("--json", type=Path, help="also write the full report as JSON")
    parser.add_argument("--features", action="store_true", help="include the per-channel feature audit")
    parser.add_argument("--sample", type=int, default=2048, help="windows sampled for the feature audit")
    parser.add_argument("--continuous-length", type=int, default=96)
    parser.add_argument("--stride", type=int, default=8)
    parser.add_argument("--manifest", type=Path, help="verify a golden manifest against this dataset")
    parser.add_argument("--write-review-candidates", type=Path,
                        help="list sessions a human could review next; this is NOT a golden manifest")
    parser.add_argument("--fail-on-unusable", action="store_true",
                        help="exit non-zero when any session is UNUSABLE")
    arguments = parser.parse_args()
    use_utf8_console()

    schema = default_schema()
    sessions, unreadable = collect(arguments.root, schema)
    audits = audit_dataset(arguments.root, AuditPolicy(), schema)
    head = overview(sessions, audits, unreadable, schema, arguments.continuous_length, arguments.stride)
    head.pop("_audits", None)

    report = {
        "root": str(arguments.root),
        "overview": head,
        "cheatDistribution": cheat_distribution(sessions, schema),
        "legitDistribution": legit_distribution(sessions, schema),
        "concentration": concentration(sessions),
        "warnings": concentration_warnings(sessions),
        "sessions": [audit.to_dict() for audit in audits],
    }

    labelled = [session for session in sessions if session.metadata.label in ("LEGIT", "CHEAT")]
    if arguments.features and labelled:
        try:
            index = attack_windows(labelled, schema=schema)
            report["featureAudit"] = feature_report(index, schema, sample=arguments.sample)
        except ValueError as error:
            report["featureAudit"] = {"error": str(error)}

    manifest = None
    if arguments.manifest:
        manifest = GoldenManifest.load(arguments.manifest)
        verification = verify(manifest, arguments.root, schema)
        report["manifest"] = {
            "name": manifest.name,
            "listed": len(manifest),
            "counts": manifest.counts(),
            **verification.to_dict(),
        }

    if arguments.write_review_candidates:
        # Deliberately not a manifest: an audit verdict is a technical judgement about a file, not
        # a human confirming what the player was doing. Only review_session can promote one.
        candidates = review_candidates(sessions, audits)
        arguments.write_review_candidates.parent.mkdir(parents=True, exist_ok=True)
        arguments.write_review_candidates.write_text(
            json.dumps(candidates, indent=2) + "\n", encoding="utf-8")
        report["reviewCandidates"] = {
            "path": str(arguments.write_review_candidates),
            "candidates": len(candidates["candidates"]),
            "good": sum(1 for item in candidates["candidates"] if item["auditVerdict"] == GOOD),
            "synthetic": sum(1 for item in candidates["candidates"] if item["synthetic"]),
        }

    enrich_admin_report(report, arguments.root, sessions, manifest)
    print(render(report))
    if arguments.json:
        arguments.json.parent.mkdir(parents=True, exist_ok=True)
        write_json(arguments.json, report)
        print(f"\nwrote {arguments.json}")

    unusable = head["verdicts"].get(UNUSABLE, 0) + head["sessionsUnreadable"]
    if arguments.fail_on_unusable and unusable:
        raise SystemExit(f"{unusable} session(s) are UNUSABLE")


if __name__ == "__main__":
    main()

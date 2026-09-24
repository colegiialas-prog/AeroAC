"""Telemetry sanity report for one session.

    python -m aeroml.tools.inspect_session <dataset> <sessionId>
    python -m aeroml.tools.inspect_session <dataset> <sessionId> --json reports/session.json

Meant to be run right after a recording, while the player is still there and the session can be
redone. It answers one question: is this recording worth keeping. It does not judge the player,
does not score anything, and does not change the file.

The numbers that usually explain a bad recording are the sample interval percentiles (a p99 far
above 50 ms means the client or the server was stalling), the known-value percentages (a session
where aim geometry is mostly unknown has little combat in it) and the segment count (many short
segments mean the recording kept breaking).
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from ..audit.features import channel_stats
from ..audit.sessions import AuditPolicy, audit_session, load_safely
from ..console import use_utf8_console
from ..dataset.exposure import combat_seconds
from ..dataset.features import encode_window
from ..dataset.records import Session, iter_session_paths
from ..dataset.windows import attack_windows
from ..schema import FeatureSchema, default_schema

MARKERS = ("teleport", "respawnOrWorldChange", "movementGap", "cancelledMovement", "invalidMovement")


def find_session(root: Path, session_id: str, schema: FeatureSchema) -> Session:
    """Accepts a full session id or an unambiguous prefix, so an operator need not paste a UUID."""
    matches = []
    for path in iter_session_paths(root):
        stem = path.stem.replace("session-", "")
        if stem == session_id or stem.startswith(session_id):
            matches.append(path)
    if not matches:
        raise SystemExit(f"no session under {root} matches {session_id!r}")
    if len(matches) > 1:
        raise SystemExit(f"{session_id!r} matches {len(matches)} sessions; use a longer prefix")
    session, error = load_safely(matches[0], schema)
    if session is None:
        raise SystemExit(f"cannot read {matches[0].name}: {error}")
    return session


def _percentiles(values: np.ndarray) -> dict:
    finite = values[np.isfinite(values)]
    if finite.size == 0:
        return {"count": 0}
    return {
        "count": int(finite.size),
        "p50": round(float(np.percentile(finite, 50)), 3),
        "p95": round(float(np.percentile(finite, 95)), 3),
        "p99": round(float(np.percentile(finite, 99)), 3),
        "max": round(float(finite.max()), 3),
    }


def _known_rate(values: np.ndarray) -> float:
    return round(float(np.mean(np.isfinite(values))), 4) if values.size else 0.0


def inspect(session: Session, schema: FeatureSchema | None = None,
            policy: AuditPolicy | None = None) -> dict:
    schema = schema or default_schema()
    metadata = session.metadata
    frames = len(session)
    audit = audit_session(session, policy, schema)

    if frames == 0:
        return {"sessionId": metadata.session_id, "frames": 0, "verdict": audit.verdict,
                "reasons": audit.reasons, "note": "session contains no frames"}

    attacks = session.column("ATTACK_COUNT", schema)
    swings = session.column("SWING_COUNT", schema)
    present = session.column("TARGET_PRESENT", schema) == 1
    switches = session.column("TARGET_SWITCH", schema) == 1
    # An acquisition is 0 -> 1 on target presence; a switch is a replacement of a live target.
    acquisitions = int(np.count_nonzero(present[1:] & ~present[:-1])) + int(present[0])
    segments = session.segments(schema)

    try:
        windows = len(attack_windows([session], schema=schema))
    except ValueError as error:
        windows = f"unavailable: {error}"

    report = {
        "sessionId": metadata.session_id,
        "label": metadata.label,
        "labelSource": metadata.label_source,
        "clientFamily": metadata.client_family,
        "configuration": metadata.configuration,
        "cheatFamily": metadata.cheat_family,
        "scenario": metadata.scenario,
        "assistStrength": metadata.assist_strength,
        "collectionMetadataPresent": metadata.has_collection_metadata,
        "minecraftProtocol": metadata.minecraft_protocol,
        "verdict": audit.verdict,
        "reasons": audit.reasons,

        "durationSeconds": round(metadata.duration_ms / 1000.0, 2),
        "combatSeconds": round(combat_seconds(session), 2),
        "frames": frames,
        "declaredFrames": metadata.declared_frames,
        "attacks": int(np.nansum(attacks)),
        "swings": int(np.nansum(swings)),
        "targetAcquisitions": acquisitions,
        "targetSwitches": int(np.count_nonzero(switches)),
        "buildableAttackWindows": windows,

        "sampleIntervalMs": _percentiles(session.column("SAMPLE_INTERVAL_MS", schema)),
        "segments": len(segments),
        "longestSegmentFrames": max((end - start for start, end in segments), default=0),
        "movementGapMarkers": sum(1 for event in session.events if event.get("type") in MARKERS),
        "tickHoles": session.quality.tick_gaps,
        "droppedRecords": metadata.dropped_records,
        "truncatedLastLine": session.quality.truncated_last_line,
        "malformedLines": session.quality.malformed_lines,

        "aimErrorKnown": _known_rate(session.column("AIM_ERROR_TOTAL", schema)),
        "targetGeometryKnown": _known_rate(session.column("DISTANCE_TO_TARGET", schema)),
        "lineOfSightKnown": _known_rate(session.column("LINE_OF_SIGHT", schema)),
        "pingKnown": _known_rate(session.column("PING_MS", schema)),
        "targetPresentRate": round(float(np.mean(present)), 4),
    }

    # Model-channel view: encode the whole session as one window so missingness and clipping are
    # measured on exactly the channels a model would receive.
    encoded = encode_window(session.values, schema)[None, :, :]
    unclipped = encode_window(session.values, schema, clip=False)[None, :, :]
    stats = channel_stats(encoded, schema, unclipped)
    report["modelChannels"] = {
        "missingness": {name: round(1.0 - item.known, 4) for name, item in stats.items()
                        if name in {value.name for value in schema.values} and item.known < 1.0},
        "clipping": {name: round(item.clipping, 4) for name, item in stats.items()
                     if getattr(item, "clipping", 0) > 0.001},
        "alwaysMissing": sorted(name for name, item in stats.items()
                                if name in {value.name for value in schema.values} and item.known == 0.0),
        "constant": sorted(name for name, item in stats.items()
                           if name in {value.name for value in schema.values}
                           and item.count and item.std == 0.0),
    }
    return report


def render(report: dict) -> str:
    lines: list[str] = []

    def section(title: str) -> None:
        lines.append("")
        lines.append(title)
        lines.append("-" * len(title))

    lines.append(f"session {report['sessionId']}  [{report.get('label', '?')}]  verdict {report['verdict']}")
    for reason in report.get("reasons", []):
        lines.append(f"  ! {reason}")
    if report.get("frames", 0) == 0:
        lines.append(f"  {report.get('note', 'no frames')}")
        return "\n".join(lines)

    section("Recording")
    lines.append(f"  client/configuration   {report['clientFamily']} / {report['configuration']}")
    lines.append(f"  cheat family           {report['cheatFamily']}")
    lines.append(f"  scenario               {report['scenario']}")
    lines.append(f"  assist strength        {report['assistStrength']}"
                 + ("" if report["collectionMetadataPresent"] else "  (recorded before these fields existed)"))
    lines.append(f"  protocol               {report['minecraftProtocol']}")

    section("Volume")
    lines.append(f"  duration               {report['durationSeconds']}s "
                 f"(combat {report['combatSeconds']}s)")
    lines.append(f"  frames                 {report['frames']} (metadata declared {report['declaredFrames']})")
    lines.append(f"  attacks / swings       {report['attacks']} / {report['swings']}")
    lines.append(f"  target acquisitions    {report['targetAcquisitions']} (switches {report['targetSwitches']})")
    lines.append(f"  attack windows         {report['buildableAttackWindows']}")

    section("Continuity")
    interval = report["sampleIntervalMs"]
    if interval.get("count"):
        lines.append(f"  sample interval ms     p50={interval['p50']} p95={interval['p95']} "
                     f"p99={interval['p99']} max={interval['max']}")
    else:
        lines.append("  sample interval ms     (no measured intervals)")
    lines.append(f"  segments               {report['segments']} "
                 f"(longest {report['longestSegmentFrames']} frames)")
    lines.append(f"  gap markers            {report['movementGapMarkers']}")
    lines.append(f"  tick holes             {report['tickHoles']}")
    lines.append(f"  dropped records        {report['droppedRecords']}")
    if report["truncatedLastLine"] or report["malformedLines"]:
        lines.append(f"  damaged lines          truncated={report['truncatedLastLine']} "
                     f"malformed={report['malformedLines']}")

    section("Known values")
    lines.append(f"  target present         {report['targetPresentRate']:.1%}")
    lines.append(f"  aim error              {report['aimErrorKnown']:.1%}")
    lines.append(f"  target geometry        {report['targetGeometryKnown']:.1%}")
    lines.append(f"  line of sight          {report['lineOfSightKnown']:.1%}")
    lines.append(f"  ping                   {report['pingKnown']:.1%}")

    channels = report["modelChannels"]
    section("Model channels")
    if channels["alwaysMissing"]:
        lines.append(f"  always missing         {', '.join(channels['alwaysMissing'])}")
    if channels["constant"]:
        lines.append(f"  constant               {', '.join(channels['constant'])}")
    if channels["clipping"]:
        for name, rate in sorted(channels["clipping"].items(), key=lambda item: -item[1]):
            lines.append(f"  clipped {rate:6.1%}         {name}")
    missing = {name: rate for name, rate in channels["missingness"].items() if rate > 0.5}
    for name, rate in sorted(missing.items(), key=lambda item: -item[1])[:10]:
        lines.append(f"  missing {rate:6.1%}         {name}")
    if not any((channels["alwaysMissing"], channels["constant"], channels["clipping"], missing)):
        lines.append("  nothing notable")
    return "\n".join(lines).lstrip("\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Telemetry sanity report for one recorded session")
    parser.add_argument("dataset", type=Path)
    parser.add_argument("session", help="session id or an unambiguous prefix")
    parser.add_argument("--json", type=Path, help="also write the report as JSON")
    arguments = parser.parse_args()
    use_utf8_console()

    schema = default_schema()
    session = find_session(arguments.dataset, arguments.session, schema)
    report = inspect(session, schema)
    print(render(report))
    if arguments.json:
        arguments.json.parent.mkdir(parents=True, exist_ok=True)
        arguments.json.write_text(json.dumps(report, indent=2, default=str) + "\n", encoding="utf-8")
        print(f"\nwrote {arguments.json}")


if __name__ == "__main__":
    main()

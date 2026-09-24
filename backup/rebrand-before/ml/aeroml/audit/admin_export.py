"""Bind imported GUI facts to recorded files; no labels or telemetry are changed."""
import hashlib
from pathlib import Path
from ..dataset.windows import attack_windows
from ..dataset.manifest import validate_golden


def enrich_admin_report(report, root, sessions, manifest=None):
    root = Path(root)
    by_id = {s.metadata.session_id: s for s in sessions}
    reviewed = {}
    if manifest is not None and manifest.reviewed:
        try:
            validate_golden(sessions, manifest)
        except ValueError:
            pass  # the audit already reports the failed verification; do not import a review
        else:
            reviewed = {entry.session_id: entry.to_dict() for entry in manifest.entries}
    report["adminImportVersion"] = 1
    for row in report["sessions"]:
        session = by_id.get(row["sessionId"])
        if session is None:
            continue
        path = root / "metadata" / f"session-{session.metadata.session_id}.json"
        if not path.is_file() or session.path is None or not session.path.is_file():
            continue
        stat = session.path.stat()
        row["metadataSha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        row["rawBytes"] = stat.st_size
        row["rawModifiedNs"] = str(stat.st_mtime_ns)
        # These count every readable labelled recording, including REVIEW, just like the audit.
        row["attackWindows"] = len(attack_windows([session]))
        if row["sessionId"] in reviewed:
            row["humanReview"] = reviewed[row["sessionId"]]

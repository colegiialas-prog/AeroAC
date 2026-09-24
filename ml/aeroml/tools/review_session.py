"""Record an operator's completed human review. This command does not infer ground truth."""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from ..dataset.records import load_dataset
from ..dataset.lineage import session_digest
from ..dataset.manifest import validate_golden
from ..audit.sessions import audit_session
from ..reporting import write_json


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("dataset", type=Path)
    p.add_argument("manifest", type=Path)
    p.add_argument("--session")
    p.add_argument("--expected-label", choices=("LEGIT", "CHEAT"))
    p.add_argument("--reviewer", help="reviewer pseudonym")
    p.add_argument("--notes", help="independent observation supporting the label; never model scores")
    a = p.parse_args()
    sessions = load_dataset(a.dataset, require_usable=False)
    data = json.loads(a.manifest.read_text(encoding="utf-8"))
    if a.session:
        if not all((a.expected_label, a.reviewer, a.notes)):
            p.error("--session requires --expected-label, --reviewer and --notes after human review")
        session = next((s for s in sessions if s.metadata.session_id == a.session), None)
        if session is None:
            p.error("session not found")
        entry = {"sessionId": a.session, "expectedLabel": a.expected_label, "sha256": session_digest(session),
                 "reviewer": a.reviewer, "reviewedAt": datetime.now(timezone.utc).isoformat(), "reviewNotes": a.notes,
                 "audit": audit_session(session).to_dict()}
        data["sessions"] = [e for e in data.get("sessions", []) if e["sessionId"] != a.session] + [entry]
        data["reviewStatus"] = "HUMAN_REVIEWED"
        data.pop("note", None)
        validate_golden(sessions, data)
        write_json(a.manifest, data)
    else:
        validate_golden(sessions, data)
    print(f"Verified {len(data['sessions'])} human-reviewed sessions")


if __name__ == "__main__":
    main()

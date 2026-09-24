"""Golden dataset manifests.

A manifest names sessions a human reviewed and the label that human assigned. It holds no
telemetry, so it can live in version control while the recordings themselves never do — raw combat
data is personal data about the players who produced it.

What it buys: every model is scored on the same reviewed sessions, so "the new model is better"
becomes a statement about the same evidence rather than about a different sample.

Three rules make it trustworthy, and all three are enforced here rather than trusted:

* every entry carries a content digest, so a session edited after review stops matching;
* every entry carries a reviewer, a timestamp and a note, so an automated verdict cannot pose as
  a review — no tool in this repository can add an entry on its own;
* synthetic sessions are refused outright. A regression set exists to measure behaviour against
  real players, and a generator's output would make that measurement meaningless.

This module is the single definition of the format. ``aeroml.tools.review_session`` writes it and
``aeroml.tools.audit_dataset`` verifies it; neither implements its own rules.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

from ..schema import FeatureSchema, default_schema
from .lineage import session_digest
from .records import Session, iter_session_paths, load_session

MANIFEST_VERSION = 1
REVIEWED = "HUMAN_REVIEWED"
PENDING = "PENDING_REAL_DATA"
REVIEWED_SOURCES = ("LAB_LEGIT", "LAB_CHEAT")
DEFAULT_PURPOSE = ("Independent regression evaluation only; never use these sessions or players "
                   "to fit training, calibration, or thresholds.")


@dataclass
class GoldenEntry:
    session_id: str
    expected_label: str
    sha256: str
    reviewer: str
    reviewed_at: str
    review_notes: str
    audit: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return {
            "sessionId": self.session_id,
            "expectedLabel": self.expected_label,
            "sha256": self.sha256,
            "reviewer": self.reviewer,
            "reviewedAt": self.reviewed_at,
            "reviewNotes": self.review_notes,
            "audit": self.audit,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "GoldenEntry":
        return cls(
            session_id=data["sessionId"],
            expected_label=data["expectedLabel"],
            sha256=data.get("sha256", ""),
            reviewer=data.get("reviewer", ""),
            reviewed_at=data.get("reviewedAt", ""),
            review_notes=data.get("reviewNotes", ""),
            audit=data.get("audit", {}),
        )


@dataclass
class GoldenManifest:
    name: str = ""
    entries: list[GoldenEntry] = field(default_factory=list)
    review_status: str = PENDING
    purpose: str = DEFAULT_PURPOSE
    notes: str = ""
    manifest_version: int = MANIFEST_VERSION

    def __len__(self) -> int:
        return len(self.entries)

    @property
    def reviewed(self) -> bool:
        return self.review_status == REVIEWED and bool(self.entries)

    @property
    def labels(self) -> dict[str, str]:
        return {entry.session_id: entry.expected_label for entry in self.entries}

    def counts(self) -> dict[str, int]:
        result: dict[str, int] = {}
        for entry in self.entries:
            result[entry.expected_label] = result.get(entry.expected_label, 0) + 1
        return result

    def to_dict(self) -> dict:
        data = {
            "manifestVersion": self.manifest_version,
            "name": self.name,
            "reviewStatus": self.review_status,
            "purpose": self.purpose,
            "sessions": [entry.to_dict() for entry in self.entries],
        }
        if self.notes:
            data["note"] = self.notes
        return data

    @classmethod
    def from_dict(cls, data: dict) -> "GoldenManifest":
        version = data.get("manifestVersion")
        if version is None or int(version) != MANIFEST_VERSION:
            raise ValueError(f"unsupported manifest version {version!r}; this build reads {MANIFEST_VERSION}")
        entries = [GoldenEntry.from_dict(item) for item in data.get("sessions", [])]
        ids = [entry.session_id for entry in entries]
        if len(set(ids)) != len(ids):
            raise ValueError("manifest lists the same session twice")
        for entry in entries:
            if entry.expected_label not in ("LEGIT", "CHEAT"):
                raise ValueError(f"session {entry.session_id} has expectedLabel {entry.expected_label!r}; "
                                 f"a golden manifest holds reviewed ground truth only")
        status = data.get("reviewStatus", PENDING)
        if entries and status != REVIEWED:
            raise ValueError(f"manifest lists {len(entries)} sessions but reviewStatus is {status!r}")
        return cls(
            name=data.get("name", ""),
            entries=entries,
            review_status=status,
            purpose=data.get("purpose", DEFAULT_PURPOSE),
            notes=data.get("note", data.get("notes", "")),
        )

    @classmethod
    def load(cls, path: Path | str) -> "GoldenManifest":
        return cls.from_dict(json.loads(Path(path).read_text(encoding="utf-8")))

    def save(self, path: Path | str) -> Path:
        from ..reporting import write_json

        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        write_json(path, self.to_dict())
        return path


def is_synthetic(session: Session) -> bool:
    """A generator's output can never be a reviewed recording of a real player."""
    notes = (session.metadata.notes or "").lower()
    return "synthetic" in notes or "synthetic" in (session.metadata.plugin_version or "").lower()


def validate_golden(sessions: Sequence[Session], manifest: dict | GoldenManifest) -> list[Session]:
    """
    The single definition of what makes a golden manifest usable. Raises on the first problem.

    Returns the listed sessions in a stable order so a caller can score exactly those recordings.
    """
    data = manifest.to_dict() if isinstance(manifest, GoldenManifest) else manifest
    if data.get("reviewStatus") != REVIEWED or not data.get("sessions"):
        raise ValueError("golden dataset has no completed human reviews")
    parsed = GoldenManifest.from_dict(data)
    if not parsed.reviewed:
        raise ValueError("golden dataset has no completed human reviews")
    by_id = {session.metadata.session_id: session for session in sessions}
    seen: set[str] = set()
    for entry in parsed.entries:
        session_id = entry.session_id
        if session_id in seen or session_id not in by_id:
            raise ValueError(f"duplicate or absent golden session {session_id}")
        seen.add(session_id)
        session = by_id[session_id]
        if entry.expected_label != session.metadata.label:
            raise ValueError(f"golden label changed for {session_id}: manifest says {entry.expected_label}, "
                             f"the recording says {session.metadata.label}")
        if entry.sha256 != session_digest(session):
            raise ValueError(f"recording {session_id} changed since it was reviewed")
        if not (entry.reviewer and entry.reviewed_at and entry.review_notes):
            raise ValueError(f"golden entry {session_id} lacks human provenance")
        if is_synthetic(session):
            raise ValueError(f"{session_id} is synthetic; a golden set measures behaviour against real players")
        if session.metadata.label == "UNLABELED" or not session.usable_for_training:
            raise ValueError(f"{session_id} must be technically complete and independently labelled")
        if session.metadata.label_source not in REVIEWED_SOURCES:
            raise ValueError(f"{session_id} has labelSource {session.metadata.label_source}, not a lab label")
    return [by_id[session_id] for session_id in sorted(seen)]


@dataclass
class ManifestVerification:
    present: list[str] = field(default_factory=list)
    missing: list[str] = field(default_factory=list)
    mismatched: list[dict] = field(default_factory=list)
    unlisted: int = 0
    reviewed: bool = False

    @property
    def ok(self) -> bool:
        return not self.missing and not self.mismatched

    def to_dict(self) -> dict:
        return {
            "reviewed": self.reviewed,
            "present": len(self.present),
            "missing": self.missing,
            "mismatched": self.mismatched,
            "unlistedOnDisk": self.unlisted,
            "ok": self.ok,
        }


def verify(manifest: GoldenManifest, root: Path | str,
           schema: FeatureSchema | None = None) -> ManifestVerification:
    """Reports every problem instead of raising on the first, for an audit that lists them all."""
    schema = schema or default_schema()
    result = ManifestVerification(reviewed=manifest.reviewed)
    on_disk: dict[str, Session] = {}
    for path in iter_session_paths(root):
        try:
            session = load_session(path, schema=schema, keep_events=False)
        except Exception:  # noqa: BLE001 - a broken session is the audit's finding, not this one's
            continue
        on_disk[session.metadata.session_id] = session

    for entry in manifest.entries:
        session = on_disk.get(entry.session_id)
        if session is None:
            result.missing.append(entry.session_id)
            continue
        problems: list[str] = []
        if entry.expected_label != session.metadata.label:
            problems.append(f"label on disk is {session.metadata.label}, manifest says {entry.expected_label}")
        if entry.sha256 and entry.sha256 != session_digest(session):
            problems.append("recording changed since it was reviewed")
        if session.metadata.label_source not in REVIEWED_SOURCES:
            problems.append(f"labelSource {session.metadata.label_source} is not a reviewed lab label")
        if session.metadata.schema_version != schema.raw_schema_version:
            problems.append(f"raw schema {session.metadata.schema_version} != {schema.raw_schema_version}")
        if is_synthetic(session):
            problems.append("synthetic session cannot be part of a golden set")
        if not (entry.reviewer and entry.reviewed_at and entry.review_notes):
            problems.append("entry lacks human provenance (reviewer, reviewedAt, reviewNotes)")
        if problems:
            result.mismatched.append({"sessionId": entry.session_id, "problems": problems})
        else:
            result.present.append(entry.session_id)

    result.unlisted = len(set(on_disk) - set(manifest.labels))
    return result


def filter_sessions(sessions: Sequence[Session], manifest: GoldenManifest) -> list[Session]:
    listed = manifest.labels
    return [session for session in sessions if session.metadata.session_id in listed]


def review_candidates(sessions: Sequence[Session], audits: Sequence) -> dict:
    """
    A list of sessions a human could review next. Deliberately NOT a manifest: it carries no
    reviewer, no timestamp and no review note, because no review has happened. Only
    ``aeroml.tools.review_session`` can turn one of these into a golden entry.
    """
    verdicts = {audit.session_id: audit for audit in audits}
    candidates = []
    for session in sessions:
        metadata = session.metadata
        audit = verdicts.get(metadata.session_id)
        if metadata.label not in ("LEGIT", "CHEAT") or metadata.label_source not in REVIEWED_SOURCES:
            continue
        candidates.append({
            "sessionId": metadata.session_id,
            "recordedLabel": metadata.label,
            "clientFamily": metadata.client_family,
            "configuration": metadata.configuration,
            "cheatFamily": metadata.cheat_family,
            "sha256": session_digest(session),
            "auditVerdict": audit.verdict if audit else "UNKNOWN",
            "auditReasons": audit.reasons if audit else [],
            "synthetic": is_synthetic(session),
        })
    return {
        "kind": "reviewCandidates",
        "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "note": "NOT a golden manifest. Nothing here has been reviewed. Use "
                "'python -m aeroml.tools.review_session <dataset> <manifest> --session <id> "
                "--expected-label <LEGIT|CHEAT> --reviewer <name> --notes <observation>' "
                "once a human has actually watched the recording.",
        "candidates": candidates,
    }

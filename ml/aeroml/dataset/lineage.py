"""Content-bound, reproducible fold manifests. No raw identity is added."""
import hashlib
import json
from dataclasses import asdict
from .splits import Split, FOLDS, verify
import numpy as np


def session_digest(session):
    digest = hashlib.sha256()
    digest.update(json.dumps(asdict(session.metadata), sort_keys=True, separators=(",", ":")).encode())
    for array, dtype in ((session.ticks, "<i8"), (session.offsets, "<i8"), (session.values, "<f8")):
        digest.update(np.ascontiguousarray(array, dtype=dtype).tobytes())
    return digest.hexdigest()


def bind_manifest(index, split):
    split.manifest["window"] = index.kind
    split.manifest["sequenceLength"] = index.length
    split.manifest["sessionDigests"] = {s.metadata.session_id: session_digest(s) for s in index.sessions}
    # Includes exact start/anchor references; CLI verification does not guess extraction settings.
    split.manifest["windows"] = [{"sessionId": index.sessions[r.session_index].metadata.session_id,
                                 "start": r.start, "length": r.length, "anchor": r.anchor} for r in index.refs]


def restore_index(sessions, manifest):
    from .windows import WindowIndex, WindowRef
    by_id = {s.metadata.session_id: i for i, s in enumerate(sessions)}
    for sid, digest in manifest.get("sessionDigests", {}).items():
        if sid not in by_id or session_digest(sessions[by_id[sid]]) != digest:
            raise ValueError(f"session missing or changed since split: {sid}")
    if not manifest.get("sessionDigests") or not manifest.get("windows"):
        raise ValueError("content-bound split manifest is missing")
    refs = []
    for row in manifest["windows"]:
        if row["sessionId"] not in by_id:
            raise ValueError("manifest references absent session")
        ref = WindowRef(by_id[row["sessionId"]], row["start"], row["length"], row["anchor"])
        if type(ref.start) is not int or ref.start < 0 or ref.length != manifest["sequenceLength"] or ref.start + ref.length > len(sessions[ref.session_index]):
            raise ValueError("invalid window reference")
        refs.append(ref)
    index = WindowIndex(sessions, refs, manifest["sequenceLength"], manifest["window"])
    split = Split({fold: np.asarray(manifest["folds"][fold]["windowIndices"], dtype=np.int64) for fold in FOLDS}, manifest)
    verify(split, index, manifest["groupBy"])
    from .splits import membership
    if membership(index, split) != manifest["folds"]:
        raise ValueError("declared session membership does not match actual window membership")
    return index, split

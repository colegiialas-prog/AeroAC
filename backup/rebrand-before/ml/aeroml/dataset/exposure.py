"""Conservative observed combat exposure, excluding idle time and sampling gaps.

Count at most 50 ms after a TARGET_PRESENT or ATTACK sample, bounded by the next
timestamp and session end. Audit and evaluation share this denominator.
"""
import numpy as np


def sample_seconds(session):
    if not len(session):
        return np.zeros(0)
    end = max(0, session.metadata.duration_ms) * 1e6
    following = np.r_[session.offsets[1:], end]
    return np.maximum(0, np.minimum(50_000_000, following - session.offsets)) / 1e9


def combat_seconds(session):
    active = (session.column("TARGET_PRESENT") == 1) | (session.column("ATTACK") == 1)
    return float(sample_seconds(session)[active].sum())

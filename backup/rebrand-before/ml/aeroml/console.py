from __future__ import annotations

import sys


def use_utf8_console() -> None:
    """
    Windows consoles still default to a legacy codepage. Library progress output can contain
    characters it cannot encode, and a training run must not die printing its own status.
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except (ValueError, OSError):
            pass

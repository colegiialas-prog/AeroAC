"""Regenerates the cross-language inference request fixture.

The encoder fixture pins what the model reads; this one pins the envelope around it. The Java test
builds the same request, encodes it and compares field by field; the Python test feeds this exact
object to the service and expects it to be served. Between them, a change to either side of the
wire format cannot pass unnoticed.

Values are compared numerically, not as text: Java and Python format floats differently and an
exact string match would fail for reasons that have nothing to do with the protocol.

    python -m aeroml.tools.make_request_golden
"""

from __future__ import annotations

import json
from pathlib import Path

from ..schema import default_schema

SEQUENCE_LENGTH = 2
REQUEST_ID = 4242


def build_fixture() -> dict:
    schema = default_schema()
    count = SEQUENCE_LENGTH * schema.feature_count
    # A deterministic ramp with both signs and a zero, so a transposed or truncated payload shows up.
    features = [round((index % 19) * 0.25 - 2.0, 6) for index in range(count)]
    return {
        "note": "Canonical inference request. Java InferenceJson.encode must produce these fields and "
                "these feature values; the Python service must accept the result.",
        "protocolVersion": 1,
        "featureSchemaVersion": schema.version,
        "requestId": REQUEST_ID,
        "model": "flash",
        "window": "attack",
        "sequenceLength": SEQUENCE_LENGTH,
        "featureCount": schema.feature_count,
        "features": features,
    }


def main() -> None:
    target = Path(__file__).resolve().parents[2] / "tests" / "data" / "request_golden.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(build_fixture(), indent=1) + "\n", encoding="utf-8")
    print(f"wrote {target}")


if __name__ == "__main__":
    main()

"""Regenerates the cross-language encoder fixture.

The fixture is the only thing that keeps Java ``FeatureEncoder`` and Python ``encode_window``
honest about each other. Both sides load it and must reproduce every channel; if they drift, a
model trained offline would read different columns than the server sends, and nothing else in the
pipeline would notice.

Run after any change to the schema or to either encoder, then run both test suites:
    python -m aeroml.tools.make_encoder_golden
"""

from __future__ import annotations

import json
import math
import random
from pathlib import Path

import numpy as np

from ..dataset.features import encode_window
from ..schema import default_schema

CASES = ("all_unknown", "tracking_run", "target_switch", "segment_boundary", "clipping", "yaw_wrap", "no_target")


def _row(schema, **values) -> list[float | None]:
    row: list[float | None] = [None] * len(schema.raw_fields)
    for name, value in values.items():
        row[schema.raw_index(name)] = None if value is None else float(value)
    return row


def build_cases() -> list[dict]:
    schema = default_schema()
    rng = random.Random(20240513)
    cases: list[dict] = []

    cases.append({"name": "all_unknown", "raw": [_row(schema)]})

    tracking = []
    yaw, pitch = 12.0, 3.0
    for step in range(6):
        want_yaw = 20.0 + step * 4.0
        want_pitch = 2.0 + step * 0.75
        yaw += rng.uniform(1.0, 5.0)
        pitch += rng.uniform(-0.6, 0.9)
        tracking.append(_row(
            schema,
            TARGET_PRESENT=1, TARGET_ENTITY_ID=42, TARGET_SWITCH=0, SEGMENT_START=1 if step == 0 else 0,
            YAW=yaw, PITCH=pitch,
            DELTA_YAW=None if step == 0 else rng.uniform(-6, 6),
            DELTA_PITCH=None if step == 0 else rng.uniform(-3, 3),
            DELTA2_YAW=None if step < 2 else rng.uniform(-4, 4),
            DELTA2_PITCH=None if step < 2 else rng.uniform(-2, 2),
            ROTATION_SPEED=None if step == 0 else rng.uniform(0, 9),
            ROTATION_ACCELERATION=None if step < 2 else rng.uniform(0, 5),
            ROTATION_JERK=None if step < 3 else rng.uniform(-5, 5),
            TARGET_YAW=want_yaw, TARGET_PITCH=want_pitch,
            AIM_ERROR_YAW=yaw - want_yaw, AIM_ERROR_PITCH=pitch - want_pitch,
            AIM_ERROR_TOTAL=math.hypot(yaw - want_yaw, pitch - want_pitch),
            AIM_ERROR_DELTA=None if step == 0 else rng.uniform(-2, 2),
            DISTANCE_TO_TARGET=4.5 - step * 0.2,
            TARGET_MIN_X=0.0, TARGET_MAX_X=0.6, TARGET_MIN_Z=0.0, TARGET_MAX_Z=0.6,
            TARGET_VELOCITY_X=0.12, TARGET_VELOCITY_Y=-0.01, TARGET_VELOCITY_Z=0.05,
            VELOCITY_X=0.21, VELOCITY_Y=-0.08, VELOCITY_Z=-0.13,
            ON_GROUND=1, SPRINTING=1, SNEAKING=0,
            ATTACK=1 if step == 3 else 0, ATTACK_COUNT=1 if step == 3 else 0, SWING_COUNT=1 if step == 3 else 0,
            TICKS_SINCE_ATTACK=None if step < 3 else step - 3,
            ATTACK_INTERVAL_MS=None if step < 3 else 550.0,
            TICKS_SINCE_TARGET_SWITCH=step,
            PING_MS=41.5, ESTIMATED_JITTER_MS=3.25, SAMPLE_INTERVAL_MS=None if step == 0 else 50.0,
        ))
    cases.append({"name": "tracking_run", "raw": tracking})

    switch = [
        _row(schema, TARGET_PRESENT=1, TARGET_ENTITY_ID=7, TARGET_SWITCH=0, SEGMENT_START=0,
             TARGET_YAW=10.0, TARGET_PITCH=1.0, DISTANCE_TO_TARGET=3.0,
             DELTA_YAW=2.0, DELTA_PITCH=0.5, TICKS_SINCE_TARGET_SWITCH=9),
        _row(schema, TARGET_PRESENT=1, TARGET_ENTITY_ID=7, TARGET_SWITCH=0, SEGMENT_START=0,
             TARGET_YAW=13.0, TARGET_PITCH=1.5, DISTANCE_TO_TARGET=2.5,
             DELTA_YAW=2.0, DELTA_PITCH=0.5, TICKS_SINCE_TARGET_SWITCH=10),
        _row(schema, TARGET_PRESENT=1, TARGET_ENTITY_ID=9, TARGET_SWITCH=1, SEGMENT_START=0,
             TARGET_YAW=-40.0, TARGET_PITCH=-2.0, DISTANCE_TO_TARGET=6.0,
             DELTA_YAW=-8.0, DELTA_PITCH=-1.0, TICKS_SINCE_TARGET_SWITCH=0),
        _row(schema, TARGET_PRESENT=1, TARGET_ENTITY_ID=9, TARGET_SWITCH=0, SEGMENT_START=0,
             TARGET_YAW=-37.0, TARGET_PITCH=-1.5, DISTANCE_TO_TARGET=5.5,
             DELTA_YAW=3.0, DELTA_PITCH=0.5, TICKS_SINCE_TARGET_SWITCH=1),
    ]
    cases.append({"name": "target_switch", "raw": switch})

    boundary = [
        _row(schema, TARGET_PRESENT=1, TARGET_ENTITY_ID=3, SEGMENT_START=0, TARGET_SWITCH=0,
             TARGET_YAW=5.0, TARGET_PITCH=0.0, DISTANCE_TO_TARGET=4.0, DELTA_YAW=1.0, DELTA_PITCH=0.2),
        _row(schema, TARGET_PRESENT=1, TARGET_ENTITY_ID=3, SEGMENT_START=1, TARGET_SWITCH=0,
             TARGET_YAW=8.0, TARGET_PITCH=0.5, DISTANCE_TO_TARGET=3.5, DELTA_YAW=1.0, DELTA_PITCH=0.2),
    ]
    cases.append({"name": "segment_boundary", "raw": boundary})

    clipping = [_row(
        schema,
        TARGET_PRESENT=1, TARGET_ENTITY_ID=1, TARGET_SWITCH=0, SEGMENT_START=0,
        DELTA_YAW=5000.0, DELTA_PITCH=-5000.0, ROTATION_SPEED=99999.0, ROTATION_JERK=-99999.0,
        AIM_ERROR_TOTAL=9999.0, AIM_ERROR_DELTA=-9999.0, DISTANCE_TO_TARGET=900.0,
        TARGET_MIN_X=0.0, TARGET_MAX_X=12.0, TARGET_MIN_Z=0.0, TARGET_MAX_Z=1.0,
        VELOCITY_X=90.0, VELOCITY_Y=-90.0, VELOCITY_Z=90.0,
        TARGET_VELOCITY_X=50.0, TARGET_VELOCITY_Y=50.0, TARGET_VELOCITY_Z=50.0,
        ATTACK_COUNT=99, SWING_COUNT=99, TICKS_SINCE_ATTACK=9999, ATTACK_INTERVAL_MS=999999,
        TICKS_SINCE_TARGET_SWITCH=-1, PING_MS=99999, ESTIMATED_JITTER_MS=99999, SAMPLE_INTERVAL_MS=99999,
        ON_GROUND=1, SPRINTING=0, SNEAKING=1,
    )]
    cases.append({"name": "clipping", "raw": clipping})

    wrap = [
        _row(schema, TARGET_PRESENT=1, TARGET_ENTITY_ID=5, TARGET_SWITCH=0, SEGMENT_START=0,
             TARGET_YAW=179.5, TARGET_PITCH=10.0, DISTANCE_TO_TARGET=3.0, DELTA_YAW=1.0, DELTA_PITCH=0.1),
        _row(schema, TARGET_PRESENT=1, TARGET_ENTITY_ID=5, TARGET_SWITCH=0, SEGMENT_START=0,
             TARGET_YAW=-179.5, TARGET_PITCH=10.5, DISTANCE_TO_TARGET=3.0, DELTA_YAW=1.0, DELTA_PITCH=0.1),
        _row(schema, TARGET_PRESENT=1, TARGET_ENTITY_ID=5, TARGET_SWITCH=0, SEGMENT_START=0,
             TARGET_YAW=179.5, TARGET_PITCH=11.0, DISTANCE_TO_TARGET=3.0, DELTA_YAW=-1.0, DELTA_PITCH=0.1),
    ]
    cases.append({"name": "yaw_wrap", "raw": wrap})

    absent = [
        _row(schema, TARGET_PRESENT=0, TARGET_ENTITY_ID=-1, TARGET_SWITCH=0, SEGMENT_START=1,
             TICKS_SINCE_TARGET_SWITCH=-1, DELTA_YAW=0.5, DELTA_PITCH=0.1, ON_GROUND=0, SPRINTING=0, SNEAKING=0),
        _row(schema, TARGET_PRESENT=0, TARGET_ENTITY_ID=-1, TARGET_SWITCH=0, SEGMENT_START=0,
             TICKS_SINCE_TARGET_SWITCH=-1, DELTA_YAW=0.4, DELTA_PITCH=0.2, ON_GROUND=1, SPRINTING=0, SNEAKING=0),
    ]
    cases.append({"name": "no_target", "raw": absent})
    return cases


def build_fixture() -> dict:
    schema = default_schema()
    cases = build_cases()
    if {case["name"] for case in cases} != set(CASES):
        raise AssertionError("fixture case list changed; update CASES so both suites stay in step")
    encoded_cases = []
    for case in cases:
        raw = np.asarray([[np.nan if value is None else value for value in row] for row in case["raw"]], dtype=np.float64)
        encoded = encode_window(raw, schema)
        encoded_cases.append({
            "name": case["name"],
            "raw": case["raw"],
            "encoded": [[float(value) for value in row] for row in encoded],
        })
    return {
        "featureSchemaVersion": schema.version,
        "rawSchemaVersion": schema.raw_schema_version,
        "rawFields": list(schema.raw_fields),
        "channels": list(schema.channel_names),
        "cases": encoded_cases,
    }


def main() -> None:
    target = Path(__file__).resolve().parents[2] / "tests" / "data" / "encoder_golden.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(build_fixture(), indent=1) + "\n", encoding="utf-8")
    print(f"wrote {target}")


if __name__ == "__main__":
    main()

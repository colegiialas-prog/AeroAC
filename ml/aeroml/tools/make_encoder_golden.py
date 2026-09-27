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

CASES = ("all_unknown", "tracking_run", "target_switch", "segment_boundary", "clipping", "yaw_wrap", "no_target",
         "error_frame", "crosshair")


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

    # Error-frame channels: an unwrapped yaw many turns around, the 1 degree floor, a missing delta,
    # a correction far past the clip bound and a target switch that must break the frame.
    def framed(entity, switch, yaw, pitch, target_yaw, target_pitch, delta_yaw, delta_pitch):
        return _row(schema, TARGET_PRESENT=1, TARGET_ENTITY_ID=entity, TARGET_SWITCH=switch, SEGMENT_START=0,
                    YAW=yaw, PITCH=pitch, TARGET_YAW=target_yaw, TARGET_PITCH=target_pitch,
                    DELTA_YAW=delta_yaw, DELTA_PITCH=delta_pitch, DISTANCE_TO_TARGET=3.0)
    frame = [
        framed(11, 0, 1090.0, 4.0, 5.0, 1.0, None, None),     # error (5, 3): 1090 - 5 wraps to 5
        framed(11, 0, 1087.5, 2.5, 5.0, 1.0, -2.5, -1.5),    # removes half of it, straight at it: 0.5, 0
        framed(11, 0, 1087.2, 2.2, 5.0, 1.5, -0.3, -0.3),    # vs error (2.5, 1.5): partly off-axis
        framed(11, 0, 1087.0, 2.0, 5.2, 1.6, -0.2, None),    # pitch delta unknown -> both unknown
        framed(11, 0, 1080.0, 1.8, 0.3, 1.6, -7.0, -0.2),    # vs error (1.8, 0.4): gain 3.7 clips to 3
        framed(11, 0, 1080.1, 1.7, 0.0, 1.6, 0.1, -0.1),     # vs error (-0.3, 0.2), under 1 degree: unknown
        framed(12, 1, 1070.0, 1.0, -8.0, 2.0, -10.1, -0.7),  # target switch: no frame across it
        framed(12, 0, 1069.0, 1.5, -8.0, 2.0, -1.0, 0.5),    # vs error (-2, -1) on the new target: -0.3, 0.4
    ]
    cases.append({"name": "error_frame", "raw": frame})

    # Crosshair geometry: the player stands at the origin (eye 1.62) and the target box is
    # [2.7, 3.3] x [0, 1.8] x [-0.3, 0.3], three blocks along +x. Yaw -90 looks along +x.
    def aimed(yaw, pitch, eye_height=1.62, present=1, player_x=0.0):
        return _row(schema, TARGET_PRESENT=present, TARGET_ENTITY_ID=21, TARGET_SWITCH=0, SEGMENT_START=0,
                    YAW=yaw, PITCH=pitch, PLAYER_X=player_x, PLAYER_Y=0.0, PLAYER_Z=0.0, EYE_HEIGHT=eye_height,
                    TARGET_MIN_X=2.7, TARGET_MIN_Y=0.0, TARGET_MIN_Z=-0.3,
                    TARGET_MAX_X=3.3, TARGET_MAX_Y=1.8, TARGET_MAX_Z=0.3, DISTANCE_TO_TARGET=2.7)
    crosshair = [
        aimed(-90.0, 0.0),            # level, straight at it: hit at eye height 1.62 / 1.8 = 0.9
        aimed(-90.0, 12.0),           # looking down into the chest: hit lower on the box
        aimed(-80.0, 0.0),            # 10 degrees off: the ray passes beside the box, a miss
        aimed(90.0, 0.0),             # facing away: box behind the eye is not a hit
        aimed(-90.0, -60.0),          # looking steeply up: passes over the box
        aimed(-90.0, 0.0, player_x=3.0),  # eye inside the box: entry clamps to 0
        aimed(-90.0, 0.0, present=0),     # no target: all four unknown
        aimed(0.0, 90.0),             # straight down: x and z parallel to their slabs, a miss
    ]
    cases.append({"name": "crosshair", "raw": crosshair})
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

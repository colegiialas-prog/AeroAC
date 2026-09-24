from __future__ import annotations

import math

import numpy as np
import pytest

from aeroml.dataset.features import encode_window, encode_windows, same_target, wrap180


def _blank(schema, rows: int = 1) -> np.ndarray:
    raw = np.full((rows, len(schema.raw_fields)), np.nan)
    for name in ("SEGMENT_START", "TARGET_PRESENT", "TARGET_SWITCH"):
        raw[:, schema.raw_index(name)] = 0.0
    return raw


def test_matches_the_java_golden_fixture(golden, schema):
    assert golden["featureSchemaVersion"] == schema.version
    for case in golden["cases"]:
        raw = np.asarray([[np.nan if value is None else value for value in row] for row in case["raw"]],
                         dtype=np.float64)
        encoded = encode_window(raw, schema)
        expected = np.asarray(case["encoded"], dtype=np.float32)
        assert encoded.shape == expected.shape, case["name"]
        np.testing.assert_allclose(encoded, expected, rtol=1e-5, atol=1e-5,
                                   err_msg=f"case {case['name']} drifted from the fixture")


def test_unknown_encodes_as_zero_with_a_cleared_mask(schema):
    encoded = encode_window(_blank(schema), schema)
    assert encoded.shape == (1, schema.feature_count)
    for value in schema.values:
        if value.nullable:
            assert encoded[0, schema.index(value.name)] == 0.0
            assert encoded[0, schema.mask_index(value.name)] == 0.0


def test_values_are_clipped_but_stay_known(schema):
    raw = _blank(schema)
    raw[0, schema.raw_index("DELTA_YAW")] = 5000.0
    raw[0, schema.raw_index("PING_MS")] = -5.0
    encoded = encode_window(raw, schema)
    assert encoded[0, schema.index("DELTA_YAW")] == pytest.approx(180.0)
    assert encoded[0, schema.mask_index("DELTA_YAW")] == 1.0
    assert encoded[0, schema.index("PING_MS")] == pytest.approx(0.0)
    assert encoded[0, schema.mask_index("PING_MS")] == 1.0


def test_target_switch_sentinel_is_unknown_not_zero(schema):
    raw = _blank(schema, 2)
    raw[0, schema.raw_index("TICKS_SINCE_TARGET_SWITCH")] = -1.0
    raw[1, schema.raw_index("TICKS_SINCE_TARGET_SWITCH")] = 0.0
    encoded = encode_window(raw, schema)
    assert encoded[0, schema.mask_index("TICKS_SINCE_TARGET_SWITCH")] == 0.0
    assert encoded[1, schema.mask_index("TICKS_SINCE_TARGET_SWITCH")] == 1.0


def test_derived_deltas_are_window_local_and_target_scoped(schema):
    raw = _blank(schema, 3)
    for row, (entity, want_yaw, switch) in enumerate([(7, 10.0, 0), (7, 14.0, 0), (9, 30.0, 1)]):
        raw[row, schema.raw_index("TARGET_PRESENT")] = 1
        raw[row, schema.raw_index("TARGET_ENTITY_ID")] = entity
        raw[row, schema.raw_index("TARGET_YAW")] = want_yaw
        raw[row, schema.raw_index("TARGET_PITCH")] = 1.0
        raw[row, schema.raw_index("DISTANCE_TO_TARGET")] = 4.0
        raw[row, schema.raw_index("TARGET_SWITCH")] = switch
    continuity = same_target(raw, schema)
    assert list(continuity) == [False, True, False]
    encoded = encode_window(raw, schema)
    assert encoded[0, schema.mask_index("TARGET_ANGULAR_VELOCITY_YAW")] == 0.0
    assert encoded[1, schema.index("TARGET_ANGULAR_VELOCITY_YAW")] == pytest.approx(4.0)
    assert encoded[2, schema.mask_index("TARGET_ANGULAR_VELOCITY_YAW")] == 0.0


def test_segment_boundary_breaks_the_derivation(schema):
    raw = _blank(schema, 2)
    for row in (0, 1):
        raw[row, schema.raw_index("TARGET_PRESENT")] = 1
        raw[row, schema.raw_index("TARGET_ENTITY_ID")] = 3
        raw[row, schema.raw_index("TARGET_YAW")] = 5.0 + row * 3
        raw[row, schema.raw_index("DISTANCE_TO_TARGET")] = 4.0
    raw[1, schema.raw_index("SEGMENT_START")] = 1
    encoded = encode_window(raw, schema)
    assert encoded[1, schema.mask_index("TARGET_ANGULAR_VELOCITY_YAW")] == 0.0
    assert encoded[1, schema.mask_index("TARGET_RADIAL_SPEED")] == 0.0


def test_angular_radius_and_ratio_use_the_compensated_box(schema):
    raw = _blank(schema)
    raw[0, schema.raw_index("TARGET_PRESENT")] = 1
    raw[0, schema.raw_index("DISTANCE_TO_TARGET")] = 3.0
    raw[0, schema.raw_index("TARGET_MIN_X")] = 0.0
    raw[0, schema.raw_index("TARGET_MAX_X")] = 0.6
    raw[0, schema.raw_index("TARGET_MIN_Z")] = 0.0
    raw[0, schema.raw_index("TARGET_MAX_Z")] = 0.6
    raw[0, schema.raw_index("AIM_ERROR_TOTAL")] = math.degrees(math.atan2(0.3, 3.0))
    encoded = encode_window(raw, schema)
    assert encoded[0, schema.index("TARGET_ANGULAR_RADIUS")] == pytest.approx(math.degrees(math.atan2(0.3, 3.0)), rel=1e-5)
    assert encoded[0, schema.index("AIM_ERROR_RATIO")] == pytest.approx(1.0, rel=1e-4)


def test_wrap_matches_the_java_normaliser():
    cases = {0: 0, 180: -180, -180: -180, 181: -179, -181: 179, 540: -180, -540: -180, 1081: 1}
    for value, expected in cases.items():
        assert float(wrap180(np.asarray([value]))[0]) == pytest.approx(expected)


def test_alignment_is_unknown_when_a_rotation_is_still(schema):
    raw = _blank(schema, 2)
    for row in (0, 1):
        raw[row, schema.raw_index("TARGET_PRESENT")] = 1
        raw[row, schema.raw_index("TARGET_ENTITY_ID")] = 1
        raw[row, schema.raw_index("TARGET_YAW")] = 10.0
        raw[row, schema.raw_index("TARGET_PITCH")] = 0.0
        raw[row, schema.raw_index("DELTA_YAW")] = 0.0
        raw[row, schema.raw_index("DELTA_PITCH")] = 0.0
        raw[row, schema.raw_index("DISTANCE_TO_TARGET")] = 4.0
    encoded = encode_window(raw, schema)
    assert encoded[1, schema.mask_index("ROTATION_TARGET_ALIGNMENT")] == 0.0


def test_encoded_output_is_always_finite_float32(sessions, schema):
    from aeroml.dataset.windows import attack_windows

    index = attack_windows(sessions, schema=schema)
    encoded = index.encode(list(range(min(12, len(index)))), schema)
    assert encoded.dtype == np.float32
    assert np.isfinite(encoded).all()
    masks = encoded[:, :, schema.value_count:]
    assert np.all((masks == 0) | (masks == 1))


def test_malformed_windows_are_refused(schema):
    with pytest.raises(ValueError):
        encode_window(np.zeros((0, len(schema.raw_fields))), schema)
    with pytest.raises(ValueError):
        encode_window(np.zeros((2, 3)), schema)
    with pytest.raises(ValueError):
        encode_windows(np.zeros((2, 3)), schema)

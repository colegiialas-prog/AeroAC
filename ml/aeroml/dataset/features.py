"""Model channel encoding — the exact mirror of Java ``FeatureEncoder``.

Every derivation here is window-local: index 0 has no predecessor inside the window, so its derived
deltas are unknown and masked. The Java encoder does the same, and ``tests/test_features.py`` pins
both against the shared manifest. If the two ever diverge, a model trained offline would read
different columns than the ones the server sends.

Unknown values encode as 0 with a 0 mask. That is a deliberate pair: a zero on its own would be a
plausible measurement, and the model must be able to tell "no target" from "perfectly on target".
"""

from __future__ import annotations

import numpy as np

from ..schema import FeatureSchema, default_schema

EPSILON = 1.0e-6
# Below this aim error (degrees) the crosshair is on or next to the target and "what fraction of the
# error did the rotation remove" is dominated by noise; the error-frame channels are unknown there.
MIN_ERROR_FRAME_DEGREES = 1.0


def wrap180(degrees: np.ndarray) -> np.ndarray:
    """Java ``AimErrorCalculator.normalizeYaw``: truncated modulo, then two one-sided corrections."""
    wrapped = np.fmod(np.asarray(degrees, dtype=np.float64), 360.0)
    wrapped = np.where(wrapped >= 180.0, wrapped - 360.0, wrapped)
    wrapped = np.where(wrapped < -180.0, wrapped + 360.0, wrapped)
    return wrapped


def same_target(raw: np.ndarray, schema: FeatureSchema) -> np.ndarray:
    """True where sample t continues tracking the same entity as t-1 inside this window."""
    length = raw.shape[0]
    result = np.zeros(length, dtype=bool)
    if length < 2:
        return result
    present = raw[:, schema.raw_index("TARGET_PRESENT")] == 1
    identity = raw[:, schema.raw_index("TARGET_ENTITY_ID")]
    switched = raw[:, schema.raw_index("TARGET_SWITCH")] == 1
    boundary = raw[:, schema.raw_index("SEGMENT_START")] == 1
    result[1:] = present[1:] & present[:-1] & (identity[1:] == identity[:-1]) & ~switched[1:] & ~boundary[1:]
    return result


def _shift(column: np.ndarray) -> np.ndarray:
    previous = np.empty_like(column)
    previous[0] = np.nan
    previous[1:] = column[:-1]
    return previous


def derive(raw: np.ndarray, schema: FeatureSchema) -> dict[str, np.ndarray]:
    """All derived channels for one window, keyed by the manifest's ``derived.<name>`` suffix."""
    continuous = same_target(raw, schema)
    unknown = np.full(raw.shape[0], np.nan)

    target_yaw = raw[:, schema.raw_index("TARGET_YAW")]
    target_pitch = raw[:, schema.raw_index("TARGET_PITCH")]
    distance = raw[:, schema.raw_index("DISTANCE_TO_TARGET")]

    angular_yaw = np.where(continuous, wrap180(target_yaw - _shift(target_yaw)), unknown)
    angular_pitch = np.where(continuous, target_pitch - _shift(target_pitch), unknown)
    radial = np.where(continuous, distance - _shift(distance), unknown)

    delta_yaw = raw[:, schema.raw_index("DELTA_YAW")]
    delta_pitch = raw[:, schema.raw_index("DELTA_PITCH")]
    target_norm = np.hypot(angular_yaw, angular_pitch)
    player_norm = np.hypot(delta_yaw, delta_pitch)
    usable = continuous & (target_norm > EPSILON) & (player_norm > EPSILON)
    with np.errstate(invalid="ignore", divide="ignore"):
        alignment = np.where(
            usable,
            (delta_yaw * angular_yaw + delta_pitch * angular_pitch) / np.where(usable, target_norm * player_norm, 1.0),
            unknown,
        )

    velocity = np.stack([
        raw[:, schema.raw_index("TARGET_VELOCITY_X")],
        raw[:, schema.raw_index("TARGET_VELOCITY_Y")],
        raw[:, schema.raw_index("TARGET_VELOCITY_Z")],
    ], axis=1)
    target_speed = np.sqrt(np.sum(velocity * velocity, axis=1))

    width = np.maximum(
        raw[:, schema.raw_index("TARGET_MAX_X")] - raw[:, schema.raw_index("TARGET_MIN_X")],
        raw[:, schema.raw_index("TARGET_MAX_Z")] - raw[:, schema.raw_index("TARGET_MIN_Z")],
    )
    with np.errstate(invalid="ignore"):
        measurable = distance > EPSILON
        radius = np.where(measurable, np.degrees(np.arctan2(0.5 * width, np.where(measurable, distance, 1.0))), unknown)
        error_total = raw[:, schema.raw_index("AIM_ERROR_TOTAL")]
        ratio_usable = radius > EPSILON
        ratio = np.where(ratio_usable, error_total / np.where(ratio_usable, radius, 1.0), unknown)

    horizontal = np.hypot(raw[:, schema.raw_index("VELOCITY_X")], raw[:, schema.raw_index("VELOCITY_Z")])

    # The rotation of sample t in the frame of the aim error it responded to (sample t-1's). A
    # smoothing assist — yaw += (target - yaw) * k — removes a near-constant fraction k of the error
    # and moves straight at the target; a hand overshoots, undershoots and curves.
    error_yaw = _shift(wrap180(raw[:, schema.raw_index("YAW")] - target_yaw))
    error_pitch = _shift(raw[:, schema.raw_index("PITCH")] - target_pitch)
    error_norm2 = error_yaw * error_yaw + error_pitch * error_pitch
    with np.errstate(invalid="ignore"):
        framed = continuous & (error_norm2 >= MIN_ERROR_FRAME_DEGREES * MIN_ERROR_FRAME_DEGREES)
    safe_norm2 = np.where(framed, error_norm2, 1.0)
    with np.errstate(invalid="ignore"):
        correction_gain = np.where(framed, -(delta_yaw * error_yaw + delta_pitch * error_pitch) / safe_norm2, unknown)
        off_axis = np.where(framed, (delta_yaw * error_pitch - delta_pitch * error_yaw) / safe_norm2, unknown)

    return {
        "targetAngularVelocityYaw": angular_yaw,
        "targetAngularVelocityPitch": angular_pitch,
        "rotationTargetAlignment": alignment,
        "targetRadialSpeed": radial,
        "targetSpeed": target_speed,
        "targetAngularRadius": radius,
        "aimErrorRatio": ratio,
        "playerSpeedHorizontal": horizontal,
        "rotationCorrectionGain": correction_gain,
        "rotationOffAxis": off_axis,
    }


def encode_window(raw: np.ndarray, schema: FeatureSchema | None = None, *, clip: bool = True) -> np.ndarray:
    """(T, rawFields) with NaN for unknown -> (T, channels) float32 with paired mask channels."""
    schema = schema or default_schema()
    raw = np.asarray(raw, dtype=np.float64)
    if raw.ndim != 2 or raw.shape[0] == 0:
        raise ValueError("window must be a non-empty (T, rawFields) array")
    if raw.shape[1] != len(schema.raw_fields):
        raise ValueError(f"window has {raw.shape[1]} raw fields, schema declares {len(schema.raw_fields)}")

    derived = derive(raw, schema)
    length = raw.shape[0]
    out = np.zeros((length, schema.feature_count), dtype=np.float32 if clip else np.float64)
    mask_index = schema.value_count
    for channel_index, value in enumerate(schema.values):
        if value.derived:
            column = derived[value.source.split(".", 1)[1]]
        else:
            column = raw[:, schema.raw_index(value.source)]
        column = np.asarray(column, dtype=np.float64).copy()
        if value.name == "TICKS_SINCE_TARGET_SWITCH":
            # -1 is the recorder's "no target tracked" sentinel, not a measurement of zero ticks.
            column[column < 0] = np.nan
        # Transform first, clip second: the schema states bounds in transformed units.
        column = np.asarray(value.apply_transform(column), dtype=np.float64)
        known = np.isfinite(column)
        clipped = np.where(known, np.clip(column, value.low, value.high) if clip else column, 0.0)
        out[:, channel_index] = clipped
        if value.nullable:
            out[:, mask_index] = known.astype(np.float32)
            mask_index += 1
    return out


def encode_windows(windows: np.ndarray, schema: FeatureSchema | None = None) -> np.ndarray:
    """(N, T, rawFields) -> (N, T, channels). Windows are encoded independently, as on the server."""
    schema = schema or default_schema()
    windows = np.asarray(windows, dtype=np.float64)
    if windows.ndim != 3:
        raise ValueError("expected a (N, T, rawFields) array")
    if windows.shape[0] == 0:
        return np.zeros((0, windows.shape[1], schema.feature_count), dtype=np.float32)
    return np.stack([encode_window(window, schema) for window in windows])

from __future__ import annotations

import numpy as np
import pytest

torch = pytest.importorskip("torch")

from aeroml.dataset.features import encode_window  # noqa: E402
from aeroml.dataset.normalize import Normalizer  # noqa: E402
from aeroml.training.augment import (  # noqa: E402
    YAW_ODD_CHANNELS,
    AugmentConfig,
    Augmenter,
    group_balance_weights,
)


def _normalised(sessions, schema, count: int = 24):
    from aeroml.dataset.windows import attack_windows

    index = attack_windows(sessions, schema=schema)
    rows = np.arange(min(count, len(index)))
    encoded = index.encode(rows.tolist(), schema=schema)
    normalizer = Normalizer.fit(encoded, schema)
    return encoded, normalizer, torch.as_tensor(normalizer.apply(encoded, schema))


def test_mirror_equals_encoding_the_mirrored_fight(sessions, schema):
    """Mirroring in normalised space is exactly normalising the encoded, reflected window."""
    encoded, normalizer, batch = _normalised(sessions, schema)
    augmenter = Augmenter(schema, normalizer.mean, normalizer.std, AugmentConfig(1.0, 0.0))
    mirrored = augmenter(batch).numpy()
    reflected = encoded.copy()
    names = [value.name for value in schema.values]
    for name in YAW_ODD_CHANNELS:
        if name in names:
            reflected[..., names.index(name)] *= -1.0
    expected = normalizer.apply(reflected, schema)
    assert np.allclose(mirrored, expected, atol=1e-5)


def test_mirror_twice_is_identity_and_keeps_unknowns_zero(sessions, schema):
    _, normalizer, batch = _normalised(sessions, schema)
    augmenter = Augmenter(schema, normalizer.mean, normalizer.std, AugmentConfig(1.0, 0.0))
    once = augmenter(batch)
    assert torch.allclose(augmenter(once), batch, atol=1e-5)
    masks = batch[:, :, schema.value_count:]
    assert torch.equal(once[:, :, schema.value_count:], masks)
    mask_index = schema.value_count
    for channel, value in enumerate(schema.values):
        if value.nullable:
            unknown = batch[:, :, mask_index] == 0
            assert torch.all(once[:, :, channel][unknown] == 0)
            mask_index += 1


def test_channel_dropout_writes_unknown_the_way_the_encoder_does(sessions, schema):
    _, normalizer, batch = _normalised(sessions, schema)
    augmenter = Augmenter(schema, normalizer.mean, normalizer.std, AugmentConfig(0.0, 0.5))
    out = augmenter(batch, torch.Generator().manual_seed(1))
    mask_index = schema.value_count
    dropped_any = False
    for channel, value in enumerate(schema.values):
        if not value.nullable:
            assert torch.equal(out[:, :, channel], batch[:, :, channel])
            continue
        mask = out[:, :, mask_index]
        assert torch.all(out[:, :, channel][mask == 0] == 0)
        dropped_any |= bool(((batch[:, :, mask_index] == 1) & (mask == 0)).any())
        mask_index += 1
    assert dropped_any
    assert not torch.equal(batch, out) and torch.equal(batch, batch.clone())


def test_asymmetric_odd_channel_is_refused(schema):
    from dataclasses import replace

    names = [value.name for value in schema.values]
    values = list(schema.values)
    index = names.index("DELTA_YAW")
    values[index] = replace(values[index], low=-10.0, high=20.0)
    broken = replace(schema, values=tuple(values))
    with pytest.raises(ValueError):
        Augmenter(broken, np.zeros(schema.feature_count), np.ones(schema.feature_count), AugmentConfig())


def test_group_balance_weights():
    groups = np.array(["a"] * 90 + ["b"] * 10)
    flat = group_balance_weights(groups, 0.0)
    assert np.allclose(flat, 1.0)
    equal = group_balance_weights(groups, 1.0)
    assert equal[:90].sum() == pytest.approx(equal[90:].sum())
    assert equal.mean() == pytest.approx(1.0)
    half = group_balance_weights(groups, 0.5)
    assert equal[90:].sum() > half[90:].sum() > flat[90:].sum()
    with pytest.raises(ValueError):
        group_balance_weights(groups, 1.5)
    assert group_balance_weights(np.array([]), 0.5).size == 0


def test_encoder_is_mirror_consistent_on_raw_data(schema):
    """The augmentation's premise: reflecting the raw fight negates exactly the yaw-odd channels."""
    rng = np.random.default_rng(2)
    raw = np.full((6, len(schema.raw_fields)), np.nan)
    def put(name, values):
        raw[:, schema.raw_index(name)] = values
    yaw = np.cumsum(rng.uniform(-4, 4, 6)) + 30.0
    pitch = rng.uniform(-5, 5, 6)
    target_yaw = yaw + rng.uniform(-6, 6, 6)
    target_pitch = pitch + rng.uniform(-3, 3, 6)
    put("TARGET_PRESENT", 1); put("TARGET_ENTITY_ID", 4); put("TARGET_SWITCH", 0); put("SEGMENT_START", 0)
    put("YAW", yaw); put("PITCH", pitch); put("TARGET_YAW", target_yaw); put("TARGET_PITCH", target_pitch)
    put("DELTA_YAW", np.r_[np.nan, np.diff(yaw)]); put("DELTA_PITCH", np.r_[np.nan, np.diff(pitch)])
    put("AIM_ERROR_YAW", yaw - target_yaw); put("AIM_ERROR_PITCH", pitch - target_pitch)
    put("DISTANCE_TO_TARGET", rng.uniform(2, 4, 6))
    mirrored = raw.copy()
    for name in ("YAW", "TARGET_YAW", "DELTA_YAW", "AIM_ERROR_YAW"):
        mirrored[:, schema.raw_index(name)] *= -1.0
    names = [value.name for value in schema.values]
    plain, flipped = encode_window(raw, schema), encode_window(mirrored, schema)
    for channel, name in enumerate(names):
        expected = -plain[:, channel] if name in YAW_ODD_CHANNELS else plain[:, channel]
        assert np.allclose(flipped[:, channel], expected, atol=1e-5), name

from __future__ import annotations

import numpy as np
import pytest

from aeroml.dataset.normalize import Normalizer
from aeroml.dataset.splits import group_split
from aeroml.dataset.windows import attack_windows


@pytest.fixture(scope="module")
def index(sessions, schema):
    return attack_windows(sessions, schema=schema)


def test_unknown_entries_stay_exactly_zero(index, schema):
    windows = index.encode(list(range(min(40, len(index)))), schema)
    normalizer = Normalizer.fit(windows, schema)
    scaled = normalizer.apply(windows, schema)
    mask_index = schema.value_count
    for channel, value in enumerate(schema.values):
        if not value.nullable:
            continue
        unknown = windows[:, :, mask_index] == 0
        assert np.all(scaled[:, :, channel][unknown] == 0.0), value.name
        mask_index += 1


def test_mask_channels_are_passed_through_untouched(index, schema):
    windows = index.encode(list(range(min(40, len(index)))), schema)
    scaled = Normalizer.fit(windows, schema).apply(windows, schema)
    np.testing.assert_array_equal(scaled[:, :, schema.value_count:], windows[:, :, schema.value_count:])


def test_known_entries_are_centred_and_scaled(index, schema):
    windows = index.encode(list(range(len(index))), schema)
    normalizer = Normalizer.fit(windows, schema)
    scaled = normalizer.apply(windows, schema)
    channel = schema.index("DISTANCE_TO_TARGET")
    known = windows[:, :, schema.mask_index("DISTANCE_TO_TARGET")] == 1
    values = scaled[:, :, channel][known]
    assert abs(float(values.mean())) < 1e-4
    assert abs(float(values.std()) - 1.0) < 1e-3


def test_statistics_ignore_missing_values(schema):
    windows = np.zeros((4, 3, schema.feature_count), dtype=np.float32)
    channel = schema.index("PING_MS")
    mask = schema.mask_index("PING_MS")
    windows[:, :, channel] = 0.0
    windows[0, 0, channel], windows[0, 0, mask] = 100.0, 1.0
    windows[0, 1, channel], windows[0, 1, mask] = 200.0, 1.0
    normalizer = Normalizer.fit(windows, schema)
    # The ten unknown entries must not drag the mean towards zero.
    assert normalizer.mean[channel] == pytest.approx(150.0)


def test_a_channel_never_seen_in_training_is_reported(schema):
    windows = np.zeros((2, 4, schema.feature_count), dtype=np.float32)
    normalizer = Normalizer.fit(windows, schema)
    never = normalizer.never_observed(schema)
    assert "PING_MS" in never
    assert "ON_GROUND" not in never  # non-nullable channels are always observed


def test_normalisation_is_fitted_on_training_only(index, schema):
    split = group_split(index, seed=3)
    train = index.encode(split.train.tolist(), schema)
    test = index.encode(split.test.tolist(), schema)
    from_train = Normalizer.fit(train, schema)
    from_everything = Normalizer.fit(np.concatenate([train, test]), schema)
    channel = schema.index("AIM_ERROR_TOTAL")
    assert from_train.mean[channel] != pytest.approx(from_everything.mean[channel], abs=1e-9)


def test_round_trip_through_a_manifest(index, schema):
    windows = index.encode(list(range(min(20, len(index)))), schema)
    normalizer = Normalizer.fit(windows, schema)
    restored = Normalizer.from_dict(normalizer.to_dict(), schema)
    np.testing.assert_allclose(restored.mean, normalizer.mean)
    np.testing.assert_allclose(restored.std, normalizer.std)
    np.testing.assert_allclose(restored.apply(windows, schema), normalizer.apply(windows, schema))


def test_a_foreign_channel_list_is_refused(index, schema):
    windows = index.encode([0], schema)
    data = Normalizer.fit(windows, schema).to_dict()
    data["channels"] = data["channels"][:-1]
    with pytest.raises(ValueError, match="channel mismatch"):
        Normalizer.from_dict(data, schema)


def test_a_foreign_schema_version_is_refused(index, schema):
    windows = index.encode([0], schema)
    normalizer = Normalizer.fit(windows, schema)
    normalizer.feature_schema_version = 99
    with pytest.raises(ValueError, match="feature schema"):
        normalizer.apply(windows, schema)


def test_fitting_on_nothing_is_an_error(schema):
    with pytest.raises(ValueError, match="empty training fold"):
        Normalizer.fit(np.zeros((0, 4, schema.feature_count), dtype=np.float32), schema)
    with pytest.raises(ValueError, match="expected"):
        Normalizer.fit(np.zeros((2, 4, 3), dtype=np.float32), schema)

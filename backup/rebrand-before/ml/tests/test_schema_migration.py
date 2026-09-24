"""Feature schema v1 -> v2, and what must break when a model is built for the wrong one.

The channel ``TICKS_SINCE_TARGET_SWITCH`` changed meaning: it used to be a raw sample count clipped
at 200, and at that bound it was constant for roughly three quarters of sampled windows. It is now
log1p(samples) clipped at 12. A bundle trained against v1 would read a different quantity out of
the same column, so it must be refused rather than served.
"""

from __future__ import annotations

import json

import numpy as np
import pytest

from aeroml.dataset.features import encode_window
from aeroml.export.bundle import Bundle, BundleManifest, Provenance, write_manifest
from aeroml.schema import default_schema, load_schema, manifest_path


def _manifest_dict(schema, *, version: int, channels=None) -> dict:
    count = len(channels) if channels is not None else schema.feature_count
    return {
        "bundleFormat": 1,
        "modelVersion": "test-v1",
        "modelKind": "flash",
        "window": "attack",
        "sequenceLength": 31,
        "featureSchemaVersion": version,
        "featureCount": count,
        "channels": list(channels if channels is not None else schema.channel_names),
        "heads": ["overall", "aimAssist"],
        "normalization": {
            "featureSchemaVersion": version,
            "channels": list(channels if channels is not None else schema.channel_names),
            "mean": [0.0] * count,
            "std": [1.0] * count,
            "knownCounts": [1] * count,
        },
        "calibration": None,
        "provenance": Provenance().to_dict(),
    }


def test_the_current_schema_is_version_two(schema):
    assert schema.version == 2
    assert manifest_path().name == "feature_schema_v2.json"


def test_a_bundle_built_for_schema_v1_is_refused(tmp_path, schema):
    path = tmp_path / "old-bundle"
    path.mkdir()
    (path / "manifest.json").write_text(json.dumps(_manifest_dict(schema, version=1)), encoding="utf-8")
    with pytest.raises(ValueError, match="feature schema"):
        Bundle.load(path, schema)


def test_a_bundle_for_the_current_schema_loads(tmp_path, schema):
    path = tmp_path / "current-bundle"
    manifest = BundleManifest.from_dict(_manifest_dict(schema, version=schema.version))
    write_manifest(path, manifest)
    bundle = Bundle.load(path, schema)
    assert bundle.manifest.feature_schema_version == schema.version
    assert tuple(bundle.manifest.channels) == schema.channel_names


def test_a_bundle_whose_channel_list_drifted_is_refused(tmp_path, schema):
    path = tmp_path / "drifted"
    path.mkdir()
    channels = list(schema.channel_names)
    channels[0], channels[1] = channels[1], channels[0]
    (path / "manifest.json").write_text(
        json.dumps(_manifest_dict(schema, version=schema.version, channels=channels)), encoding="utf-8")
    with pytest.raises(ValueError, match="channel mismatch"):
        Bundle.load(path, schema)


def test_the_previous_schema_file_is_still_readable_for_provenance():
    """v1 stays on disk so an old bundle can be explained, but it is not what the pipeline uses."""
    previous = manifest_path().with_name("feature_schema_v1.json")
    if not previous.is_file():
        pytest.skip("v1 manifest not retained")
    old = load_schema(previous)
    current = default_schema()
    assert old.version == 1 and current.version == 2
    assert old.channel_names == current.channel_names, "channel order must not change silently"
    old_switch = next(v for v in old.values if v.name == "TICKS_SINCE_TARGET_SWITCH")
    new_switch = next(v for v in current.values if v.name == "TICKS_SINCE_TARGET_SWITCH")
    assert old_switch.transform == "none" and (old_switch.low, old_switch.high) == (0.0, 200.0)
    assert new_switch.transform == "log1p" and (new_switch.low, new_switch.high) == (0.0, 12.0)


def test_the_changed_channel_no_longer_saturates(schema):
    """The whole realistic range now fits inside the bound instead of piling up on it."""
    raw = np.full((5, len(schema.raw_fields)), np.nan)
    for name in ("SEGMENT_START", "TARGET_PRESENT", "TARGET_SWITCH"):
        raw[:, schema.raw_index(name)] = 0.0
    samples = np.asarray([0.0, 20.0, 200.0, 2_000.0, 40_000.0])
    raw[:, schema.raw_index("TICKS_SINCE_TARGET_SWITCH")] = samples

    encoded = encode_window(raw, schema)
    channel = encoded[:, schema.index("TICKS_SINCE_TARGET_SWITCH")]
    np.testing.assert_allclose(channel, np.log1p(samples).astype(np.float32), rtol=1e-6)
    # Strictly increasing: every one of these five counts is still distinguishable.
    assert np.all(np.diff(channel) > 0)
    assert channel.max() < 12.0
    assert np.all(encoded[:, schema.mask_index("TICKS_SINCE_TARGET_SWITCH")] == 1.0)


def test_the_sentinel_still_means_unknown_after_the_change(schema):
    raw = np.full((2, len(schema.raw_fields)), np.nan)
    for name in ("SEGMENT_START", "TARGET_PRESENT", "TARGET_SWITCH"):
        raw[:, schema.raw_index(name)] = 0.0
    raw[0, schema.raw_index("TICKS_SINCE_TARGET_SWITCH")] = -1.0
    raw[1, schema.raw_index("TICKS_SINCE_TARGET_SWITCH")] = 0.0
    encoded = encode_window(raw, schema)
    assert encoded[0, schema.mask_index("TICKS_SINCE_TARGET_SWITCH")] == 0.0
    assert encoded[0, schema.index("TICKS_SINCE_TARGET_SWITCH")] == 0.0
    # Zero samples since a switch is a real measurement and stays distinguishable from unknown.
    assert encoded[1, schema.mask_index("TICKS_SINCE_TARGET_SWITCH")] == 1.0
    assert encoded[1, schema.index("TICKS_SINCE_TARGET_SWITCH")] == 0.0


def test_clipping_on_the_changed_channel_is_now_negligible(sessions, schema):
    """The finding that prompted the change must not come back."""
    from aeroml.audit.features import feature_report
    from aeroml.dataset.windows import attack_windows

    report = feature_report(attack_windows(sessions, schema=schema), schema, sample=256)
    clipping = report["all"]["TICKS_SINCE_TARGET_SWITCH"]["clipping"]
    assert clipping < 0.01, f"channel still saturates: {clipping:.1%}"
    assert not [item for item in report["findings"]
                if item["kind"] == "CLIPPED" and item["channel"] == "TICKS_SINCE_TARGET_SWITCH"]

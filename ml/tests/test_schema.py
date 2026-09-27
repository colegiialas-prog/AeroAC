from __future__ import annotations

import json

import pytest

from aeroml.schema import MASK_SUFFIX, check_channels, load_schema, manifest_path


def test_manifest_is_self_consistent(schema):
    # The feature schema and the raw dataset schema version independently: a model input change
    # does not invalidate recordings, and a recorder change does not silently reinterpret channels.
    assert schema.version == 5
    assert schema.raw_schema_version == 2
    assert schema.value_count + schema.mask_count == schema.feature_count
    assert len(schema.channel_names) == schema.feature_count
    assert len(set(schema.channel_names)) == schema.feature_count


def test_masks_follow_every_value_in_declaration_order(schema):
    values = [value.name for value in schema.values]
    masks = [value.name + MASK_SUFFIX for value in schema.values if value.nullable]
    assert list(schema.channel_names) == values + masks
    for value in schema.values:
        if value.nullable:
            assert schema.mask_index(value.name) >= schema.value_count


def test_model_never_receives_identity_position_or_check_output(schema):
    forbidden = ("ENTITY_ID", "TRANSACTION", "EVIDENCE", "SESSION", "PLAYER_X", "PLAYER_Y", "PLAYER_Z",
                 "SERVER_TICK", "CLIENT_PROTOCOL_VERSION", "HELD_ITEM_TYPE", "TARGET_TYPE")
    for value in schema.values:
        if value.derived:
            continue
        assert not any(token in value.source for token in forbidden), value.source


def test_every_raw_source_exists_and_clip_bounds_are_ordered(schema):
    for value in schema.values:
        if not value.derived:
            assert value.source in schema.raw_fields
        assert value.low < value.high, value.name


def test_required_heads_are_declared(schema):
    assert "overall" in schema.required_heads
    assert set(schema.required_heads) <= set(schema.heads)


def test_channel_mismatch_is_rejected_loudly(schema):
    check_channels(schema, schema.channel_names)
    with pytest.raises(ValueError, match="channel mismatch"):
        check_channels(schema, schema.channel_names[:-1])
    with pytest.raises(ValueError, match="channel mismatch"):
        check_channels(schema, tuple(reversed(schema.channel_names)))


def test_a_manifest_with_an_unknown_raw_source_is_refused(tmp_path, schema):
    data = json.loads(manifest_path().read_text(encoding="utf-8"))
    data["values"][0]["source"] = "NOT_A_FIELD"
    broken = tmp_path / "broken.json"
    broken.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(ValueError, match="unknown raw fields"):
        load_schema(broken)


def test_java_and_python_agree_on_the_channel_list(golden, schema):
    assert tuple(golden["channels"]) == schema.channel_names
    assert tuple(golden["rawFields"]) == schema.raw_fields
    assert golden["featureSchemaVersion"] == schema.version


def test_declared_transforms_are_supported_and_applied_before_clipping(schema):
    """Clip bounds are stated in transformed units, so the order matters and is pinned here."""
    import numpy as np

    from aeroml.schema import SUPPORTED_TRANSFORMS

    for value in schema.values:
        assert value.transform in SUPPORTED_TRANSFORMS, value.name

    switch = next(v for v in schema.values if v.name == "TICKS_SINCE_TARGET_SWITCH")
    assert switch.transform == "log1p"
    assert (switch.low, switch.high) == (0.0, 12.0)
    transformed = switch.apply_transform(np.asarray([0.0, 9.0, 200.0, 20_000.0]))
    np.testing.assert_allclose(transformed, np.log1p([0.0, 9.0, 200.0, 20_000.0]))
    # The whole realistic range now fits inside the bound instead of saturating at it.
    assert transformed.max() < switch.high
    # A negative count is the recorder's sentinel, not a measurement: it stays unknown.
    assert np.isnan(switch.apply_transform(np.asarray([-1.0]))[0])


def test_an_unsupported_transform_is_refused(tmp_path, schema):
    import json

    from aeroml.schema import load_schema, manifest_path

    data = json.loads(manifest_path().read_text(encoding="utf-8"))
    data["values"][0]["transform"] = "sqrt"
    broken = tmp_path / "broken.json"
    broken.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(ValueError, match="unsupported transforms"):
        load_schema(broken)


def test_collection_metadata_can_never_become_a_model_channel(schema):
    """scenario/assistStrength/clientFamily/configuration describe the recording, not the player."""
    forbidden = ("scenario", "assiststrength", "clientfamily", "configuration",
                 "cheatfamily", "label", "labelsource", "playerid", "sessionid")
    for value in schema.values:
        lowered = value.name.lower().replace("_", "")
        source = value.source.lower().replace("_", "")
        for token in forbidden:
            assert token not in lowered, f"{value.name} names collection metadata"
            assert token not in source, f"{value.name} reads collection metadata from {value.source}"
    # The manifest states the rule as data too, so a reviewer sees it without reading the code.
    import json

    from aeroml.schema import manifest_path

    declared = json.loads(manifest_path().read_text(encoding="utf-8")).get("forbiddenModelInputs", [])
    assert {"scenario", "assistStrength", "clientFamily", "configuration"} <= set(declared)

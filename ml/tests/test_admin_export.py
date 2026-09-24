import pytest
from aeroml.tools.export_admin_evaluation import export
from aeroml.schema import default_schema


def report():
    return {"cohortId": "a" * 64, "fold": "test", "modelVersion": "fixture", "featureSchemaVersion": 2,
            "windowMetrics": {"positives": 100, "rocAuc": 0.8, "tprAtFpr": {"0.001": {"tpr": 0.7, "reliable": 0}}},
            "riskSimulation": {"detection": {"knownClient": {"sessions": 2, "reachedSuspicious": 0},
                                             "unknownClient": {"sessions": 0}},
                               "falsePositives": {"perCombatHour": {"SUSPICIOUS": 0}}}}


def test_export_preserves_missing_vs_measured_zero_and_fpr_shortage():
    result = export(report())
    assert result["tprAtFpr"] is None
    assert result["falsePositivesPerCombatHour"] == 0
    assert result["detectionByPopulation"] == {"KNOWN_CLIENT": 0, "UNKNOWN_CLIENT": None}
    assert result["medianDetectionSeconds"] is None
    assert result["population"] == "KNOWN_CLIENT"


def test_export_requires_identified_held_out_cohort():
    data = report()
    data["fold"] = "validation"
    with pytest.raises(ValueError, match="held-out"):
        export(data)
    with pytest.raises(ValueError, match="cohort"):
        export({})


def test_export_uses_available_roc_point_without_claiming_player_confidence():
    data = report()
    data["windowMetrics"]["tprAtFpr"]["0.001"]["reliable"] = 1
    result = export(data)
    assert result["tprAtFpr"] == 0.7
    assert any("window ROC" in warning for warning in result["caveats"])


def test_schema_installed_resource_lookup_is_not_cwd_dependent(tmp_path, monkeypatch):
    from aeroml.schema import manifest_path
    monkeypatch.chdir(tmp_path)
    assert manifest_path().is_file()
    assert default_schema().version == 3

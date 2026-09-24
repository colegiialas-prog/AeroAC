from __future__ import annotations

import sys
import urllib.request
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from aeroml.dataset.records import load_dataset  # noqa: E402
from aeroml.schema import default_schema  # noqa: E402
from aeroml.tools.make_synthetic import generate_dataset  # noqa: E402


@pytest.fixture(scope="session")
def schema():
    return default_schema()


@pytest.fixture(scope="session")
def synthetic_root(tmp_path_factory) -> Path:
    root = tmp_path_factory.mktemp("synthetic") / "dataset"
    generate_dataset(root, players=12, seconds=14.0, seed=11)
    return root


@pytest.fixture(scope="session")
def sessions(synthetic_root, schema):
    return load_dataset(synthetic_root, schema)


@pytest.fixture(scope="session")
def loopback_opener():
    """An opener that ignores the system proxy.

    On Windows ``urllib.request`` reads the WinINET proxy out of the registry, so on a machine with a
    configured proxy even a request to ``127.0.0.1`` is sent to that proxy and comes back as a 502.
    That failure says nothing about the service under test, so loopback traffic is forced direct.
    """
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


@pytest.fixture(scope="session")
def golden() -> dict:
    import json

    path = ROOT / "tests" / "data" / "encoder_golden.json"
    if not path.is_file():
        pytest.skip("encoder golden fixture missing; run python -m aeroml.tools.make_encoder_golden")
    return json.loads(path.read_text(encoding="utf-8"))

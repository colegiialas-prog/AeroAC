"""Plain weights for the in-JVM Flash model.

The plugin can run the temporal ConvNet itself instead of calling the Python service. It does not
parse ONNX: it reads a flat little-endian float32 blob (``model.weights``) and an index
(``weights.json``) naming every tensor of the PyTorch ``state_dict`` with its shape and offset.
The Java side is ``dev.aeroac.neural.inference.local.TemporalConvNet``; the cross-language fixture in
``tests/data/local_model`` pins that both compute the same logits.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np

WEIGHTS_NAME = "model.weights"
INDEX_NAME = "weights.json"
WEIGHTS_FORMAT = 1


def export_weights(model, path: Path | str) -> dict:
    """Writes ``model.weights`` and ``weights.json`` next to the bundle manifest. Returns the index."""
    path = Path(path)
    path.mkdir(parents=True, exist_ok=True)
    tensors = []
    chunks = []
    offset = 0
    for name, tensor in model.state_dict().items():
        array = np.ascontiguousarray(tensor.detach().cpu().numpy(), dtype="<f4").ravel()
        if not np.all(np.isfinite(array)):
            raise ValueError(f"tensor {name} holds non-finite weights")
        tensors.append({"name": name, "shape": list(tensor.shape), "offset": offset, "count": int(array.size)})
        offset += int(array.size)
        chunks.append(array)
    blob = (np.concatenate(chunks) if chunks else np.zeros(0, dtype="<f4")).astype("<f4").tobytes()
    (path / WEIGHTS_NAME).write_bytes(blob)
    index = {
        "weightsFormat": WEIGHTS_FORMAT,
        "architecture": model.config.to_dict(),
        "floatCount": offset,
        "sha256": hashlib.sha256(blob).hexdigest(),
        "tensors": tensors,
    }
    (path / INDEX_NAME).write_text(json.dumps(index, indent=1) + "\n", encoding="utf-8")
    return index


def load_weights(path: Path | str) -> tuple[dict, dict[str, np.ndarray]]:
    """Reads what ``export_weights`` wrote; used by tests and by the numpy reference forward pass."""
    path = Path(path)
    index = json.loads((path / INDEX_NAME).read_text(encoding="utf-8"))
    if index.get("weightsFormat") != WEIGHTS_FORMAT:
        raise ValueError(f"unsupported weights format {index.get('weightsFormat')!r}")
    blob = (path / WEIGHTS_NAME).read_bytes()
    if hashlib.sha256(blob).hexdigest() != index["sha256"]:
        raise ValueError("model.weights does not match its index")
    flat = np.frombuffer(blob, dtype="<f4")
    if flat.size != index["floatCount"]:
        raise ValueError("model.weights has the wrong length")
    tensors = {item["name"]: flat[item["offset"]:item["offset"] + item["count"]].reshape(item["shape"])
               for item in index["tensors"]}
    return index, tensors

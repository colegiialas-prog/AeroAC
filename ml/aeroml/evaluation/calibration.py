"""Temperature scaling.

A raw sigmoid is a ranking score, not a probability. The risk engine multiplies it by a weight and
accumulates it, so if 0.9 does not mean "nine times in ten" the thresholds an operator sets mean
nothing. Temperature scaling is the cheapest fix that preserves ranking exactly: it divides the
logit by one learned scalar, so ROC-AUC is unchanged and only the calibration moves.

Fitted on a calibration fold that is disjoint from training, validation and test. Numpy only, so
calibration does not drag in the training stack.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

MIN_TEMPERATURE = 0.05
MAX_TEMPERATURE = 20.0
EPSILON = 1.0e-12


def probability_to_logit(probability: np.ndarray) -> np.ndarray:
    probability = np.clip(np.asarray(probability, dtype=np.float64), EPSILON, 1 - EPSILON)
    return np.log(probability / (1 - probability))


def sigmoid(logit: np.ndarray) -> np.ndarray:
    logit = np.asarray(logit, dtype=np.float64)
    out = np.empty_like(logit)
    positive = logit >= 0
    out[positive] = 1.0 / (1.0 + np.exp(-logit[positive]))
    exponent = np.exp(logit[~positive])
    out[~positive] = exponent / (1.0 + exponent)
    return out


def negative_log_likelihood(logits: np.ndarray, labels: np.ndarray, temperature: float) -> float:
    scaled = np.asarray(logits, dtype=np.float64) / temperature
    labels = np.asarray(labels, dtype=np.float64)
    # log(1 + exp(x)) computed stably for both signs.
    stable = np.maximum(scaled, 0) - scaled * labels + np.log1p(np.exp(-np.abs(scaled)))
    return float(np.mean(stable))


@dataclass
class TemperatureScaler:
    temperature: float
    fitted_on: int
    nll_before: float
    nll_after: float
    ece_before: float
    ece_after: float

    @classmethod
    def fit(cls, logits: np.ndarray, labels: np.ndarray, *, iterations: int = 80) -> "TemperatureScaler":
        logits = np.asarray(logits, dtype=np.float64).ravel()
        labels = np.asarray(labels, dtype=np.float64).ravel()
        if logits.shape != labels.shape:
            raise ValueError("logits and labels must have the same length")
        if logits.size == 0:
            raise ValueError("cannot calibrate on an empty fold")
        if not np.all(np.isfinite(logits)) or not np.all(np.isin(labels, (0, 1))):
            raise ValueError("finite logits and binary labels are required")
        if len(np.unique(labels)) < 2:
            raise ValueError("calibration needs both classes; a one-class fold cannot fix a scale")
        # Golden-section search on a one-dimensional, well-behaved objective: no optimiser needed.
        low, high = MIN_TEMPERATURE, MAX_TEMPERATURE
        phi = (np.sqrt(5.0) - 1.0) / 2.0
        left = high - phi * (high - low)
        right = low + phi * (high - low)
        value_left = negative_log_likelihood(logits, labels, left)
        value_right = negative_log_likelihood(logits, labels, right)
        for _ in range(iterations):
            if value_left < value_right:
                high, right, value_right = right, left, value_left
                left = high - phi * (high - low)
                value_left = negative_log_likelihood(logits, labels, left)
            else:
                low, left, value_left = left, right, value_right
                right = low + phi * (high - low)
                value_right = negative_log_likelihood(logits, labels, right)
        temperature = float((low + high) / 2.0)
        return cls(
            temperature=temperature,
            fitted_on=int(logits.size),
            nll_before=negative_log_likelihood(logits, labels, 1.0),
            nll_after=negative_log_likelihood(logits, labels, temperature),
            ece_before=expected_calibration_error(sigmoid(logits), labels),
            ece_after=expected_calibration_error(sigmoid(logits / temperature), labels),
        )

    def apply_logits(self, logits: np.ndarray) -> np.ndarray:
        return sigmoid(np.asarray(logits, dtype=np.float64) / self.temperature)

    def apply(self, probabilities: np.ndarray) -> np.ndarray:
        return self.apply_logits(probability_to_logit(probabilities))

    def to_dict(self) -> dict:
        return {
            "method": "temperature",
            "temperature": self.temperature,
            "fittedOn": self.fitted_on,
            "nllBefore": self.nll_before,
            "nllAfter": self.nll_after,
            "eceBefore": self.ece_before,
            "eceAfter": self.ece_after,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "TemperatureScaler":
        if data.get("method") != "temperature":
            raise ValueError(f"unsupported calibration method {data.get('method')!r}")
        if not np.isfinite(data.get("temperature", np.nan)) or float(data["temperature"]) <= 0:
            raise ValueError("calibration temperature must be finite and positive")
        return cls(
            temperature=float(data["temperature"]),
            fitted_on=int(data.get("fittedOn", 0)),
            nll_before=float(data.get("nllBefore", float("nan"))),
            nll_after=float(data.get("nllAfter", float("nan"))),
            ece_before=float(data.get("eceBefore", float("nan"))),
            ece_after=float(data.get("eceAfter", float("nan"))),
        )

    @property
    def improved(self) -> bool:
        return self.nll_after <= self.nll_before + 1e-9


@dataclass
class HeadCalibration:
    heads: tuple[str, ...]
    scalers: tuple[TemperatureScaler, ...]

    @classmethod
    def fit(cls, logits, labels, heads):
        return cls(tuple(heads), tuple(TemperatureScaler.fit(logits[:, i], labels[:, i]) for i in range(len(heads))))

    def apply_logits(self, logits):
        logits = np.asarray(logits)
        if logits.shape[-1] != len(self.heads):
            raise ValueError("calibration head shape mismatch")
        return np.stack([s.apply_logits(logits[..., i]) for i, s in enumerate(self.scalers)], axis=-1)

    def apply(self, scores):
        return self.apply_logits(probability_to_logit(scores))

    @property
    def improved(self):
        return all(s.improved for s in self.scalers)

    def to_dict(self):
        return {"method": "per-head-temperature", "heads": list(self.heads),
                "scalers": {h: s.to_dict() for h, s in zip(self.heads, self.scalers)}}


def load_calibration(data, heads):
    if not data:
        return None
    if data.get("method") == "per-head-temperature":
        if tuple(data.get("heads", [])) != tuple(heads) or set(data.get("scalers", {})) != set(heads):
            raise ValueError("calibration heads differ from model heads")
        return HeadCalibration(tuple(heads), tuple(TemperatureScaler.from_dict(data["scalers"][h]) for h in heads))
    return TemperatureScaler.from_dict(data)  # Legacy experimental bundles; promotion requires per-head evidence.


def expected_calibration_error(probabilities: np.ndarray, labels: np.ndarray, bins: int = 15) -> float:
    probabilities = np.asarray(probabilities, dtype=np.float64).ravel()
    labels = np.asarray(labels, dtype=np.float64).ravel()
    if probabilities.size == 0:
        return float("nan")
    edges = np.linspace(0.0, 1.0, bins + 1)
    error = 0.0
    for index in range(bins):
        low, high = edges[index], edges[index + 1]
        selected = (probabilities > low) & (probabilities <= high) if index else (probabilities <= high)
        count = int(np.count_nonzero(selected))
        if count == 0:
            continue
        error += count / probabilities.size * abs(labels[selected].mean() - probabilities[selected].mean())
    return float(error)


def reliability_table(probabilities: np.ndarray, labels: np.ndarray, bins: int = 10) -> list[dict]:
    """Predicted versus observed frequency per bin — the table to read before trusting a threshold."""
    probabilities = np.asarray(probabilities, dtype=np.float64).ravel()
    labels = np.asarray(labels, dtype=np.float64).ravel()
    edges = np.linspace(0.0, 1.0, bins + 1)
    rows = []
    for index in range(bins):
        low, high = edges[index], edges[index + 1]
        selected = (probabilities > low) & (probabilities <= high) if index else (probabilities <= high)
        count = int(np.count_nonzero(selected))
        rows.append({
            "bin": f"({low:.2f}, {high:.2f}]",
            "count": count,
            "predicted": float(probabilities[selected].mean()) if count else float("nan"),
            "observed": float(labels[selected].mean()) if count else float("nan"),
        })
    return rows

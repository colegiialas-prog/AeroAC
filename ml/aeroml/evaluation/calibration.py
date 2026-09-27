"""Temperature and Platt scaling.

A raw sigmoid is a ranking score, not a probability. The risk engine multiplies it by a weight and
accumulates it, so if 0.9 does not mean "nine times in ten" the thresholds an operator sets mean
nothing. Temperature scaling is the cheapest fix that preserves ranking exactly: it divides the
logit by one learned scalar, so ROC-AUC is unchanged and only the calibration moves.

Platt scaling adds an intercept to that scalar, and it is the default: the class-weighted training
loss shifts every logit by a constant, which a temperature alone cannot undo (see ``PlattScaler``).
It preserves ranking exactly as well, because its slope is constrained to be positive.

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


def _nll_affine(logits: np.ndarray, targets: np.ndarray, slope: float, bias: float) -> float:
    scaled = slope * np.asarray(logits, dtype=np.float64) + bias
    targets = np.asarray(targets, dtype=np.float64)
    stable = np.maximum(scaled, 0) - scaled * targets + np.log1p(np.exp(-np.abs(scaled)))
    return float(np.mean(stable))


@dataclass
class PlattScaler:
    """sigmoid(slope * logit + bias): temperature scaling plus an intercept.

    Training uses ``pos_weight = negatives / positives``. Under a weighted loss the optimal logit is
    the true log-odds shifted by roughly ``log(pos_weight)``, a constant a temperature cannot remove:
    dividing by T only rescales, so a model trained on 1 cheat window per 20 legit ones keeps
    reporting inflated probabilities for honest players. The intercept is exactly that correction.
    The slope is kept positive and inside the temperature bounds, so the ranking — and ROC-AUC,
    TPR at every FPR — is unchanged; only what 0.8 *means* moves.

    Fitted by Newton's method on Platt's smoothed targets ``(N+ + 1) / (N+ + 2)`` and
    ``1 / (N- + 2)``, which keeps the parameters finite on a separable calibration fold instead of
    driving the slope to infinity.
    """

    slope: float
    bias: float
    fitted_on: int
    nll_before: float
    nll_after: float
    ece_before: float
    ece_after: float

    @classmethod
    def fit(cls, logits: np.ndarray, labels: np.ndarray, *, iterations: int = 100) -> "PlattScaler":
        logits = np.asarray(logits, dtype=np.float64).ravel()
        labels = np.asarray(labels, dtype=np.float64).ravel()
        if logits.shape != labels.shape:
            raise ValueError("logits and labels must have the same length")
        if logits.size == 0:
            raise ValueError("cannot calibrate on an empty fold")
        if not np.all(np.isfinite(logits)) or not np.all(np.isin(labels, (0, 1))):
            raise ValueError("finite logits and binary labels are required")
        positives = float(labels.sum())
        negatives = float(labels.size - positives)
        if positives == 0 or negatives == 0:
            raise ValueError("calibration needs both classes; a one-class fold cannot fix a scale")
        targets = np.where(labels == 1, (positives + 1.0) / (positives + 2.0), 1.0 / (negatives + 2.0))
        slope, bias = 1.0, 0.0
        current = _nll_affine(logits, targets, slope, bias)
        for _ in range(iterations):
            probability = sigmoid(slope * logits + bias)
            residual = probability - targets
            weight = np.maximum(probability * (1.0 - probability), 1.0e-12)
            gradient = np.array([np.sum(residual * logits), np.sum(residual)])
            hessian = np.array([
                [np.sum(weight * logits * logits) + 1.0e-9, np.sum(weight * logits)],
                [np.sum(weight * logits), np.sum(weight) + 1.0e-9],
            ])
            try:
                step = np.linalg.solve(hessian, gradient)
            except np.linalg.LinAlgError:
                break
            # Backtracking: a full Newton step can overshoot on a nearly separable fold.
            scale = 1.0
            while scale > 1.0e-6:
                candidate_slope, candidate_bias = slope - scale * step[0], bias - scale * step[1]
                candidate = _nll_affine(logits, targets, candidate_slope, candidate_bias)
                if candidate <= current:
                    break
                scale *= 0.5
            else:
                break
            slope, bias, previous, current = candidate_slope, candidate_bias, current, candidate
            if abs(previous - current) < 1.0e-12 and np.max(np.abs(scale * step)) < 1.0e-9:
                break
        if not (np.isfinite(slope) and np.isfinite(bias)) or slope <= 0:
            raise ValueError("calibration would reverse or flatten the ranking; the model has no usable signal")
        slope = float(np.clip(slope, 1.0 / MAX_TEMPERATURE, 1.0 / MIN_TEMPERATURE))
        bias = float(bias)
        return cls(
            slope=slope,
            bias=bias,
            fitted_on=int(logits.size),
            nll_before=_nll_affine(logits, labels, 1.0, 0.0),
            nll_after=_nll_affine(logits, labels, slope, bias),
            ece_before=expected_calibration_error(sigmoid(logits), labels),
            ece_after=expected_calibration_error(sigmoid(slope * logits + bias), labels),
        )

    def apply_logits(self, logits: np.ndarray) -> np.ndarray:
        return sigmoid(self.slope * np.asarray(logits, dtype=np.float64) + self.bias)

    def apply(self, probabilities: np.ndarray) -> np.ndarray:
        return self.apply_logits(probability_to_logit(probabilities))

    def to_dict(self) -> dict:
        return {
            "method": "platt",
            "slope": self.slope,
            "bias": self.bias,
            "fittedOn": self.fitted_on,
            "nllBefore": self.nll_before,
            "nllAfter": self.nll_after,
            "eceBefore": self.ece_before,
            "eceAfter": self.ece_after,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "PlattScaler":
        if data.get("method") != "platt":
            raise ValueError(f"unsupported calibration method {data.get('method')!r}")
        slope, bias = float(data.get("slope", np.nan)), float(data.get("bias", np.nan))
        if not np.isfinite(slope) or slope <= 0 or not np.isfinite(bias):
            raise ValueError("platt calibration needs a finite positive slope and a finite bias")
        return cls(
            slope=slope,
            bias=bias,
            fitted_on=int(data.get("fittedOn", 0)),
            nll_before=float(data.get("nllBefore", float("nan"))),
            nll_after=float(data.get("nllAfter", float("nan"))),
            ece_before=float(data.get("eceBefore", float("nan"))),
            ece_after=float(data.get("eceAfter", float("nan"))),
        )

    @property
    def improved(self) -> bool:
        return self.nll_after <= self.nll_before + 1e-9


CALIBRATION_METHODS = ("platt", "temperature")


def _scaler_from_dict(data: dict):
    method = data.get("method")
    if method == "platt":
        return PlattScaler.from_dict(data)
    return TemperatureScaler.from_dict(data)


def fit_scaler(logits: np.ndarray, labels: np.ndarray, method: str = "platt"):
    """Platt when it fits and improves likelihood; temperature otherwise.

    Temperature is the fallback, not an alternative ranked by score: Platt contains it (bias 0,
    slope 1/T), so on the calibration fold Platt can only lose when its fit itself fails."""
    if method not in CALIBRATION_METHODS:
        raise ValueError(f"unknown calibration method {method!r}")
    if method == "platt":
        try:
            scaler = PlattScaler.fit(logits, labels)
            if scaler.improved:
                return scaler
        except ValueError:
            pass
    return TemperatureScaler.fit(logits, labels)


@dataclass
class HeadCalibration:
    heads: tuple[str, ...]
    scalers: tuple[TemperatureScaler | PlattScaler, ...]
    #: Positive rate of the calibration fold per head. A calibrated probability is a posterior under
    #: this base rate, so logit(p) - logit(prior) is the window's log-likelihood ratio: the quantity
    #: the Java RiskEngine accumulates in log-odds mode. None for bundles written before it existed.
    priors: dict[str, float] | None = None

    @classmethod
    def fit(cls, logits, labels, heads, method: str = "platt"):
        labels = np.asarray(labels, dtype=np.float64)
        priors = {head: float(np.mean(labels[:, i])) for i, head in enumerate(heads)}
        return cls(tuple(heads), tuple(fit_scaler(logits[:, i], labels[:, i], method) for i in range(len(heads))),
                   priors)

    def prior(self, head: str) -> float | None:
        """The calibration fold's positive rate for a head, or None when unknown or degenerate."""
        value = (self.priors or {}).get(head)
        return float(value) if value is not None and 0.0 < value < 1.0 else None

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
        # "per-head-temperature" is kept for an all-temperature calibration so bundles written
        # before Platt scaling existed and bundles written after it describe themselves the same way.
        method = "per-head-temperature" if all(isinstance(s, TemperatureScaler) for s in self.scalers) else "per-head"
        data = {"method": method, "heads": list(self.heads),
                "scalers": {h: s.to_dict() for h, s in zip(self.heads, self.scalers)}}
        if self.priors is not None:
            data["priors"] = dict(self.priors)
        return data


def _priors(data, heads):
    if data is None:
        return None
    if not isinstance(data, dict) or not set(data) <= set(heads):
        raise ValueError("calibration priors must name model heads")
    priors = {head: float(value) for head, value in data.items()}
    if any(not 0.0 <= value <= 1.0 for value in priors.values()):
        raise ValueError("calibration priors must be rates in [0, 1]")
    return priors


def load_calibration(data, heads):
    if not data:
        return None
    method = data.get("method")
    if method in ("per-head-temperature", "per-head"):
        if tuple(data.get("heads", [])) != tuple(heads) or set(data.get("scalers", {})) != set(heads):
            raise ValueError("calibration heads differ from model heads")
        priors = _priors(data.get("priors"), heads)
        if method == "per-head-temperature":
            return HeadCalibration(tuple(heads), tuple(TemperatureScaler.from_dict(data["scalers"][h]) for h in heads),
                                   priors)
        return HeadCalibration(tuple(heads), tuple(_scaler_from_dict(data["scalers"][h]) for h in heads), priors)
    # Legacy experimental bundles; promotion requires per-head evidence.
    return _scaler_from_dict(data)


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

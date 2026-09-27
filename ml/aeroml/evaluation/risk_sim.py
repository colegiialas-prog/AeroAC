"""Offline mirror of the Java RiskEngine.

Window metrics answer "can the model tell these two windows apart". They do not answer the question
an operator actually has: how often does an honest player reach SUSPICIOUS in an evening, and how
long does a cheating one survive. That depends on the accumulation rule, not on the classifier, so
the accumulation rule has to be simulated before anything is switched on.

This is a deliberate reimplementation, kept exact rather than approximate, and pinned against
``ml/tests/data/risk_golden.json`` which the Java ``RiskSimulationGoldenTest`` verifies too.

One subtlety worth stating: the server decays risk on every sample (20 Hz) while this simulates
decay only at prediction timestamps. Exponential decay composes — exp(-k*a)·exp(-k*b) = exp(-k*(a+b))
— so the two produce identical numbers, not merely close ones.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Iterable, Sequence

CLEAN = "CLEAN"
WATCH = "WATCH"
SUSPICIOUS = "SUSPICIOUS"
MITIGATED = "MITIGATED"
CONFIRMED = "CONFIRMED"
STATES = (CLEAN, WATCH, SUSPICIOUS, MITIGATED, CONFIRMED)

AI_AIM = "AI_AIM"
AI_KILLAURA = "AI_KILLAURA"
AI_TRIGGER = "AI_TRIGGER"
AI_OVERALL = "AI_OVERALL"
AI_RELIEF = "AI_RELIEF"

THRESHOLD = "threshold"
LOG_ODDS = "log-odds"
SCORING_MODES = (LOG_ODDS, THRESHOLD)

#: Head -> evidence type, in the order Java checks them: most specific head first.
HEAD_ORDER = (("aimAssist", AI_AIM), ("killAura", AI_KILLAURA),
              ("triggerBot", AI_TRIGGER), ("overall", AI_OVERALL))


@dataclass(frozen=True)
class RiskConfig:
    """Mirrors ``NeuralConfig.Risk``. Defaults match the bundled config.yml."""

    accept_uncalibrated: bool = False
    ai_scoring: str = LOG_ODDS
    decay_per_second: float = 0.001
    max_risk: float = 20.0
    ai_weight: float = 0.5
    ai_threshold: float = 0.80
    ai_clear_threshold: float = 0.20
    ai_relief: float = 0.15
    grim_weight: float = 0.5
    watch: float = 2.0
    suspicious: float = 6.0
    confirmed: float = 12.0
    log_odds_weight: float = 0.5
    ai_neutral: float = 0.5
    ai_clamp_low: float = 0.02
    ai_clamp_high: float = 0.98
    relief_scale: float = 0.5

    @classmethod
    def from_dict(cls, data: dict) -> "RiskConfig":
        return cls(
            accept_uncalibrated=bool(data.get("acceptUncalibrated", False)),
            ai_scoring=str(data.get("aiScoring", LOG_ODDS)),
            decay_per_second=float(data.get("decayPerSecond", 0.001)),
            max_risk=float(data.get("maxRisk", 20.0)),
            ai_weight=float(data.get("aiWeight", 0.5)),
            ai_threshold=float(data.get("aiThreshold", 0.80)),
            ai_clear_threshold=float(data.get("aiClearThreshold", 0.20)),
            ai_relief=float(data.get("aiRelief", 0.15)),
            grim_weight=float(data.get("grimWeight", 0.5)),
            watch=float(data.get("watch", 2.0)),
            suspicious=float(data.get("suspicious", 6.0)),
            confirmed=float(data.get("confirmed", 12.0)),
            log_odds_weight=float(data.get("logOddsWeight", 0.5)),
            ai_neutral=float(data.get("aiNeutral", 0.5)),
            ai_clamp_low=float(data.get("aiClampLow", 0.02)),
            ai_clamp_high=float(data.get("aiClampHigh", 0.98)),
            relief_scale=float(data.get("reliefScale", 0.5)),
        )

    def to_dict(self) -> dict:
        return {
            "acceptUncalibrated": self.accept_uncalibrated,
            "aiScoring": self.ai_scoring,
            "decayPerSecond": self.decay_per_second,
            "maxRisk": self.max_risk,
            "aiWeight": self.ai_weight,
            "aiThreshold": self.ai_threshold,
            "aiClearThreshold": self.ai_clear_threshold,
            "aiRelief": self.ai_relief,
            "grimWeight": self.grim_weight,
            "watch": self.watch,
            "suspicious": self.suspicious,
            "confirmed": self.confirmed,
            "logOddsWeight": self.log_odds_weight,
            "aiNeutral": self.ai_neutral,
            "aiClampLow": self.ai_clamp_low,
            "aiClampHigh": self.ai_clamp_high,
            "reliefScale": self.relief_scale,
        }

    def normalised(self) -> "RiskConfig":
        """Java clamps these while reading the config; a simulation must use the same numbers."""
        if any(not math.isfinite(v) for k, v in self.to_dict().items() if k not in ("acceptUncalibrated", "aiScoring")):
            raise ValueError("risk configuration must be finite")
        watch = max(0.0, self.watch)
        suspicious = max(watch, self.suspicious)
        confirmed = max(suspicious, self.confirmed)
        threshold = min(1.0, max(0.0, self.ai_threshold))
        low = min(0.5, max(1.0e-6, self.ai_clamp_low))
        high = max(0.5, min(1.0 - 1.0e-6, self.ai_clamp_high))
        return RiskConfig(
            accept_uncalibrated=self.accept_uncalibrated,
            ai_scoring=self.ai_scoring if self.ai_scoring in SCORING_MODES else LOG_ODDS,
            decay_per_second=max(0.0, self.decay_per_second),
            max_risk=max(confirmed, self.max_risk),
            ai_weight=max(0.0, self.ai_weight),
            ai_threshold=threshold,
            ai_clear_threshold=min(threshold, max(0.0, min(1.0, self.ai_clear_threshold))),
            ai_relief=max(0.0, self.ai_relief),
            grim_weight=max(0.0, self.grim_weight),
            watch=watch,
            suspicious=suspicious,
            confirmed=confirmed,
            log_odds_weight=max(0.0, self.log_odds_weight),
            ai_neutral=min(high, max(low, self.ai_neutral)),
            ai_clamp_low=low,
            ai_clamp_high=high,
            relief_scale=max(0.0, self.relief_scale),
        )


def logit(probability: float) -> float:
    return math.log(probability / (1.0 - probability))


@dataclass
class Prediction:
    nano_time: int
    heads: dict[str, float]
    calibrated: bool = True
    #: Fraction of this window's samples not already scored by the previous window. 1 = no overlap.
    share: float = 1.0
    #: Base rate the calibrated heads are posteriors under, as the service reports it; None = unknown.
    prior: float | None = None

    def head(self, name: str) -> float:
        value = self.heads.get(name)
        return float("nan") if value is None else float(value)

    @property
    def overall(self) -> float:
        return self.head("overall")

    @classmethod
    def from_dict(cls, data: dict) -> "Prediction":
        prior = data.get("prior")
        return cls(int(data["nanoTime"]), dict(data["heads"]), bool(data.get("calibrated", True)),
                   float(data.get("share", 1.0)), None if prior is None else float(prior))

    def to_dict(self) -> dict:
        return {"nanoTime": self.nano_time, "calibrated": self.calibrated, "heads": self.heads,
                "share": self.share, "prior": self.prior}


@dataclass
class Step:
    nano_time: int
    risk: float
    state: str
    evidence: str | None
    strength: float
    transitioned: bool

    def to_dict(self) -> dict:
        return {
            "nanoTime": self.nano_time,
            "risk": self.risk,
            "state": self.state,
            "evidence": self.evidence,
            "strength": self.strength,
            "transitioned": self.transitioned,
        }


@dataclass
class Trace:
    steps: list[Step] = field(default_factory=list)
    config: RiskConfig = field(default_factory=RiskConfig)

    @property
    def peak_risk(self) -> float:
        return max((step.risk for step in self.steps), default=0.0)

    def reached(self, state: str) -> bool:
        wanted = STATES.index(state)
        return any(STATES.index(step.state) >= wanted for step in self.steps)

    def time_to(self, state: str, origin_nanos: int | None = None) -> float:
        """Seconds from the first prediction until the state is first reached, or inf."""
        if not self.steps:
            return math.inf
        start = self.steps[0].nano_time if origin_nanos is None else origin_nanos
        wanted = STATES.index(state)
        for step in self.steps:
            if STATES.index(step.state) >= wanted:
                return (step.nano_time - start) / 1e9
        return math.inf

    def seconds_at_or_above(self, state: str, end_nanos: int | None = None) -> float:
        """Total time the simulated risk spent at or above a state, integrated between steps."""
        threshold = {CLEAN: 0, WATCH: self.config.watch, SUSPICIOUS: self.config.suspicious,
                     CONFIRMED: self.config.confirmed}.get(state)
        if threshold is None:
            return 0.0  # Offline engine never activates mitigation.
        seconds = 0.0
        for i, previous in enumerate(self.steps):
            end = self.steps[i + 1].nano_time if i + 1 < len(self.steps) else (end_nanos or previous.nano_time)
            duration = max(0, (end - previous.nano_time) / 1e9)
            if previous.risk < threshold:
                continue
            if self.config.decay_per_second > 0 and threshold > 0:
                duration = min(duration, max(0, math.log(previous.risk / threshold) / self.config.decay_per_second))
            seconds += duration
        return seconds

    def counts(self) -> dict[str, int]:
        """How many times each state was entered, not how many steps sat in it."""
        entered = {state: 0 for state in STATES}
        for step in self.steps:
            if step.transitioned:
                entered[step.state] += 1
        return entered

    def to_dict(self) -> dict:
        return {
            "config": self.config.to_dict(),
            "steps": [step.to_dict() for step in self.steps],
            "peakRisk": self.peak_risk,
            "counts": self.counts(),
        }


class RiskSimulator:
    """Stateful mirror of ``RiskEngine`` plus ``PlayerRiskProfile``, for one player."""

    def __init__(self, config: RiskConfig | None = None, start_nanos: int = 0) -> None:
        self.config = (config or RiskConfig()).normalised()
        self.risk = 0.0
        self.state = CLEAN
        self.last_update = start_nanos

    def state_for(self, risk: float) -> str:
        if risk >= self.config.confirmed:
            return CONFIRMED
        if risk >= self.config.suspicious:
            return SUSPICIOUS
        if risk >= self.config.watch:
            return WATCH
        return CLEAN

    def _clamp(self, risk: float) -> float:
        if not math.isfinite(risk):
            return 0.0
        return max(0.0, min(self.config.max_risk, risk))

    def decay(self, now_nanos: int) -> None:
        elapsed = (now_nanos - self.last_update) / 1e9
        if not elapsed > 0:
            return
        self.risk = self._clamp(self.risk * math.exp(-self.config.decay_per_second * elapsed))
        self.last_update = now_nanos
        self.state = self.state_for(self.risk)

    def accept(self, strength: float, now_nanos: int) -> bool:
        self.decay(now_nanos)
        before = self.state
        self.risk = self._clamp(self.risk + strength)
        self.last_update = now_nanos
        self.state = self.state_for(self.risk)
        return self.state != before

    def _escalation(self, probability: float) -> float:
        span = 1.0 - self.config.ai_threshold
        if span <= 1.0e-9:
            return 1.0 if probability >= self.config.ai_threshold else 0.0
        return max(0.0, min(1.0, (probability - self.config.ai_threshold) / span))

    def evidence_for(self, prediction: Prediction) -> tuple[str, float] | None:
        """Returns (evidenceType, strength) or None, exactly as ``RiskEngine.fromPrediction`` does."""
        if not prediction.calibrated and not self.config.accept_uncalibrated:
            return None
        if self.config.ai_scoring == LOG_ODDS:
            return self._log_odds(prediction)
        for head, kind in HEAD_ORDER:
            probability = prediction.head(head)
            if math.isnan(probability) or probability < self.config.ai_threshold:
                continue
            return kind, self.config.ai_weight * self._escalation(probability)
        overall = prediction.overall
        if not math.isnan(overall) and overall <= self.config.ai_clear_threshold and self.config.ai_clear_threshold > 0:
            relief = self.config.ai_relief * (self.config.ai_clear_threshold - overall) / self.config.ai_clear_threshold
            if relief <= 0:
                return None
            return AI_RELIEF, -relief
        return None

    def _log_odds(self, prediction: Prediction) -> tuple[str, float] | None:
        """Mirrors ``RiskEngine.logOdds``: the window's log-likelihood ratio, discounted by overlap."""
        config = self.config
        overall = prediction.overall
        share = prediction.share
        if math.isnan(overall) or not math.isfinite(share):
            return None
        share = max(0.0, min(1.0, share))
        prior = prediction.prior
        if prior is None or not math.isfinite(prior) or not 0.0 < prior < 1.0:
            prior = config.ai_neutral
        prior = max(config.ai_clamp_low, min(config.ai_clamp_high, prior))
        clamped = max(config.ai_clamp_low, min(config.ai_clamp_high, overall))
        ratio = logit(clamped) - logit(prior)
        strength = config.log_odds_weight * share * ratio
        if ratio < 0:
            strength *= config.relief_scale
        if abs(strength) < 1.0e-12:
            return None
        if strength < 0:
            return AI_RELIEF, strength
        for head, kind in HEAD_ORDER[:-1]:
            probability = prediction.head(head)
            if not math.isnan(probability) and probability >= config.ai_threshold:
                return kind, strength
        return AI_OVERALL, strength

    def step(self, prediction: Prediction) -> Step:
        if prediction.nano_time < self.last_update:
            raise ValueError("predictions must be chronological")
        if any(not math.isfinite(v) or not 0 <= v <= 1 for v in prediction.heads.values()):
            raise ValueError("prediction scores must be finite probabilities")
        evidence = self.evidence_for(prediction)
        if evidence is None:
            self.decay(prediction.nano_time)
            return Step(prediction.nano_time, self.risk, self.state, None, 0.0, False)
        kind, strength = evidence
        transitioned = self.accept(strength, prediction.nano_time)
        return Step(prediction.nano_time, self.risk, self.state, kind, strength, transitioned)

    def grim_flag(self, now_nanos: int) -> Step:
        """A deterministic check contributing its fixed unit, as ``RiskEngine.fromCheck`` does."""
        if self.config.grim_weight <= 0:
            self.decay(now_nanos)
            return Step(now_nanos, self.risk, self.state, None, 0.0, False)
        transitioned = self.accept(self.config.grim_weight, now_nanos)
        return Step(now_nanos, self.risk, self.state, "GRIM", self.config.grim_weight, transitioned)


def simulate(predictions: Iterable[Prediction], config: RiskConfig | None = None,
             start_nanos: int | None = None) -> Trace:
    predictions = list(predictions)
    if start_nanos is None:
        start_nanos = predictions[0].nano_time if predictions else 0
    simulator = RiskSimulator(config, start_nanos)
    trace = Trace(config=simulator.config)
    for prediction in predictions:
        trace.steps.append(simulator.step(prediction))
    return trace


def overlap_share(previous_end_seconds: float | None, end_seconds: float, window_seconds: float | None) -> float:
    """Share of a window not covered by the previous one; mirrors ``NeuralRuntime.overlapShare``."""
    if previous_end_seconds is None or not window_seconds or window_seconds <= 0:
        return 1.0
    return max(0.0, min(1.0, (end_seconds - previous_end_seconds) / window_seconds))


def simulate_scores(timestamps_seconds: Sequence[float], scores: Sequence[float],
                    config: RiskConfig | None = None, head: str = "overall",
                    calibrated: bool = True, window_seconds: float | None = None,
                    prior: float | None = None) -> Trace:
    """Convenience wrapper for evaluation, which works in seconds and a single score column.

    ``window_seconds`` is the model window's span (31 samples = 1.55 s): overlapping windows then
    share their evidence the way the server does. None treats every window as independent.
    """
    if len(timestamps_seconds) != len(scores):
        raise ValueError("timestamps and scores must have equal lengths")
    predictions = []
    previous = None
    for second, score in zip(timestamps_seconds, scores):
        heads = {"overall": float(score)} if head == "overall" else {"overall": float(score), head: float(score)}
        share = overlap_share(previous, float(second), window_seconds)
        previous = float(second)
        predictions.append(Prediction(int(round(second * 1e9)), heads, calibrated, share, prior))
    return simulate(predictions, config)

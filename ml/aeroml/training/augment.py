"""Label-preserving augmentation of normalised training windows.

Two transformations, both applied to the training fold only and never to validation, calibration or
test, so every reported number is still measured on unmodified windows.

* **Yaw mirror.** Reflecting the fight left-to-right negates every yaw-signed channel and leaves
  every magnitude, pitch and dot-product channel alone. An aim assist that pulls left is the same
  cheat as one that pulls right, and a dataset that happens to hold more fights circling one way
  must not teach the model a direction. The reflection is exact in encoded space because every
  yaw-odd channel has symmetric clip bounds (checked below), so a mirrored window is precisely the
  window the encoder would have produced from the mirrored fight.
* **Channel dropout.** A nullable channel is occasionally withdrawn for a whole window — value and
  mask both zero, which is exactly how the encoder writes "unknown". The server really does lose
  target geometry, LOS and ping mid-fight; a model that leans on one channel fails silently when
  it is missing, and one trained with this learns the redundant evidence too.

No additive noise. The strongest aim-assist tells are *too* regular motion — a near-constant
correction gain, no jitter where a hand would have some — and noise would teach the model to
ignore exactly that regularity.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from ..schema import FeatureSchema

# Channels whose sign flips when the fight is reflected left-to-right. Everything else is either a
# magnitude, a pitch quantity or a dot product of two reflected vectors, and is unchanged.
YAW_ODD_CHANNELS = (
    "DELTA_YAW",
    "DELTA2_YAW",
    "AIM_ERROR_YAW",
    "TARGET_ANGULAR_VELOCITY_YAW",
    "ROTATION_OFF_AXIS",
)


@dataclass(frozen=True)
class AugmentConfig:
    mirror_probability: float = 0.5
    channel_dropout: float = 0.05

    def __post_init__(self) -> None:
        if not 0.0 <= self.mirror_probability <= 1.0 or not 0.0 <= self.channel_dropout < 1.0:
            raise ValueError("augmentation probabilities must be in [0, 1)")


class Augmenter:
    """Operates on a (B, T, C) torch tensor that has already been normalised."""

    def __init__(self, schema: FeatureSchema, mean: np.ndarray, std: np.ndarray, config: AugmentConfig) -> None:
        import torch

        self.config = config
        names = [value.name for value in schema.values]
        odd = [name for name in YAW_ODD_CHANNELS if name in names]
        for name in odd:
            value = schema.values[names.index(name)]
            if value.low != -value.high or value.transform != "none":
                raise ValueError(f"{name} is mirrored but its encoding is not symmetric about zero")
        self.odd = torch.as_tensor([names.index(name) for name in odd], dtype=torch.long)
        # Mirroring the raw value v -> -v in normalised space: (-v - m) / s = -x - 2m / s.
        offsets = [-2.0 * float(mean[names.index(name)]) / float(std[names.index(name)]) for name in odd]
        self.odd_offset = torch.as_tensor(offsets, dtype=torch.float32)
        # (value index, mask index) of every nullable value; its mask says whether it was known.
        pairs = []
        mask_index = schema.value_count
        for channel, value in enumerate(schema.values):
            if value.nullable:
                pairs.append((channel, mask_index))
                mask_index += 1
        self.value_of_mask = torch.as_tensor([pair[0] for pair in pairs], dtype=torch.long)
        self.mask_of_value = torch.as_tensor([pair[1] for pair in pairs], dtype=torch.long)
        self.odd_masks = torch.as_tensor(
            [dict(pairs)[index] for index in self.odd.tolist()] if pairs else [], dtype=torch.long)

    def __call__(self, batch, generator=None):
        import torch

        batch = batch.clone()
        size = batch.shape[0]
        if self.config.mirror_probability > 0 and self.odd.numel():
            chosen = torch.rand(size, generator=generator) < self.config.mirror_probability
            if bool(chosen.any()):
                rows = batch[chosen]
                known = rows[:, :, self.odd_masks]
                rows[:, :, self.odd] = (-rows[:, :, self.odd] + self.odd_offset) * known
                batch[chosen] = rows
        if self.config.channel_dropout > 0 and self.value_of_mask.numel():
            dropped = torch.rand(size, self.value_of_mask.numel(), generator=generator) < self.config.channel_dropout
            if bool(dropped.any()):
                keep = (~dropped).to(batch.dtype).unsqueeze(1)
                batch[:, :, self.value_of_mask] = batch[:, :, self.value_of_mask] * keep
                batch[:, :, self.mask_of_value] = batch[:, :, self.mask_of_value] * keep
        return batch


def group_balance_weights(groups: np.ndarray, power: float) -> np.ndarray:
    """Per-window weights that stop a few long sessions from being most of the training signal.

    Every group (player, by default) contributes ``n ** (1 - power)`` in total instead of ``n``:
    ``power=0`` is plain per-window training, ``power=1`` gives every player equal total weight, and
    the default in between keeps more data meaning more signal without letting one marathon
    recording define "legit". Normalised to mean 1 so the learning rate keeps its meaning."""
    groups = np.asarray(groups, dtype=object).ravel()
    if not 0.0 <= power <= 1.0:
        raise ValueError("group balance power must be in [0, 1]")
    if groups.size == 0:
        return np.zeros(0, dtype=np.float32)
    _, inverse, counts = np.unique(groups.astype(str), return_inverse=True, return_counts=True)
    weights = counts[inverse].astype(np.float64) ** (-power)
    return (weights / weights.mean()).astype(np.float32)

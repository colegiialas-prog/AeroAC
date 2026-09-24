"""Temporal ConvNet for combat windows.

A dilated 1D residual stack, not a transformer. The sequences here are tens of samples long and the
signal is local — how a correction is shaped over a handful of ticks — so a convolution with a
receptive field that covers the window is the right size of model, and it exports to ONNX cleanly.

Normalisation is GroupNorm rather than BatchNorm on purpose: the server infers one window at a
time, and a batch-statistics layer would behave differently there than in training.

Heads are a dict. Adding ``killAura`` later changes this file and the bundle manifest, and nothing
in the Java telemetry protocol.
"""

from __future__ import annotations

from dataclasses import dataclass, field

try:  # torch is only needed for training and export, not for the dataset pipeline.
    import torch
    from torch import nn
except ImportError as error:  # pragma: no cover - exercised only on installs without torch
    raise ImportError("aeroml.models.tcn requires torch; install ml/requirements-train.txt") from error


@dataclass
class ModelConfig:
    feature_count: int
    sequence_length: int
    heads: tuple[str, ...] = ("overall", "aimAssist")
    width: int = 64
    blocks: int = 4
    kernel_size: int = 3
    dropout: float = 0.1
    groups: int = 8

    def to_dict(self) -> dict:
        return {
            "architecture": "temporal-convnet-v1",
            "featureCount": self.feature_count,
            "sequenceLength": self.sequence_length,
            "heads": list(self.heads),
            "width": self.width,
            "blocks": self.blocks,
            "kernelSize": self.kernel_size,
            "dropout": self.dropout,
            "groups": self.groups,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "ModelConfig":
        if data.get("architecture", "temporal-convnet-v1") != "temporal-convnet-v1":
            raise ValueError(f"unsupported architecture {data.get('architecture')!r}")
        return cls(
            feature_count=int(data["featureCount"]),
            sequence_length=int(data["sequenceLength"]),
            heads=tuple(data.get("heads", ("overall", "aimAssist"))),
            width=int(data.get("width", 64)),
            blocks=int(data.get("blocks", 4)),
            kernel_size=int(data.get("kernelSize", 3)),
            dropout=float(data.get("dropout", 0.1)),
            groups=int(data.get("groups", 8)),
        )

    @property
    def receptive_field(self) -> int:
        field_size = 1
        for block in range(self.blocks):
            field_size += 2 * (self.kernel_size - 1) * (2 ** block)
        return field_size


class ResidualBlock(nn.Module):
    def __init__(self, width: int, kernel_size: int, dilation: int, dropout: float, groups: int) -> None:
        super().__init__()
        padding = dilation * (kernel_size - 1) // 2
        self.first = nn.Conv1d(width, width, kernel_size, padding=padding, dilation=dilation)
        self.norm_first = nn.GroupNorm(min(groups, width), width)
        self.second = nn.Conv1d(width, width, kernel_size, padding=padding, dilation=dilation)
        self.norm_second = nn.GroupNorm(min(groups, width), width)
        self.dropout = nn.Dropout(dropout)
        self.activation = nn.GELU()

    def forward(self, x: "torch.Tensor") -> "torch.Tensor":
        residual = x
        out = self.activation(self.norm_first(self.first(x)))
        out = self.dropout(out)
        out = self.norm_second(self.second(out))
        return self.activation(out + residual)


class AttentionPool(nn.Module):
    """Learned weighting over time, so one decisive tick is not averaged away by a quiet window."""

    def __init__(self, width: int) -> None:
        super().__init__()
        self.score = nn.Conv1d(width, 1, 1)

    def forward(self, x: "torch.Tensor") -> "torch.Tensor":
        weights = torch.softmax(self.score(x), dim=-1)
        return torch.sum(x * weights, dim=-1)


class AeroTemporalNet(nn.Module):
    def __init__(self, config: ModelConfig) -> None:
        super().__init__()
        self.config = config
        self.project = nn.Sequential(
            nn.Conv1d(config.feature_count, config.width, 1),
            nn.GroupNorm(min(config.groups, config.width), config.width),
            nn.GELU(),
        )
        self.blocks = nn.ModuleList([
            ResidualBlock(config.width, config.kernel_size, 2 ** index, config.dropout, config.groups)
            for index in range(config.blocks)
        ])
        self.pool = AttentionPool(config.width)
        self.trunk = nn.Sequential(
            nn.Linear(config.width * 2, config.width),
            nn.GELU(),
            nn.Dropout(config.dropout),
        )
        self.heads = nn.ModuleDict({name: nn.Linear(config.width, 1) for name in config.heads})

    def forward(self, windows: "torch.Tensor") -> "torch.Tensor":
        """(B, T, C) -> (B, heads) logits, in ``config.heads`` order."""
        if windows.dim() != 3:
            raise ValueError("expected (batch, time, channels)")
        x = windows.transpose(1, 2)
        x = self.project(x)
        for block in self.blocks:
            x = block(x)
        pooled = torch.cat([self.pool(x), x.mean(dim=-1)], dim=-1)
        features = self.trunk(pooled)
        return torch.cat([self.heads[name](features) for name in self.config.heads], dim=-1)

    def probabilities(self, windows: "torch.Tensor") -> dict[str, "torch.Tensor"]:
        logits = torch.sigmoid(self.forward(windows))
        return {name: logits[:, index] for index, name in enumerate(self.config.heads)}

    def parameter_count(self) -> int:
        return sum(parameter.numel() for parameter in self.parameters())


@dataclass
class PresetConfig:
    name: str
    width: int
    blocks: int
    sequence_length: int
    notes: str = field(default="")


FLASH = PresetConfig("flash", width=64, blocks=4, sequence_length=31,
                     notes="runs on every completed attack window")
PRO = PresetConfig("pro", width=128, blocks=6, sequence_length=96,
                   notes="escalation only, triggered by a high Flash probability")


def build(preset: PresetConfig, feature_count: int, heads: tuple[str, ...] = ("overall", "aimAssist")) -> AeroTemporalNet:
    return AeroTemporalNet(ModelConfig(
        feature_count=feature_count,
        sequence_length=preset.sequence_length,
        heads=heads,
        width=preset.width,
        blocks=preset.blocks,
    ))

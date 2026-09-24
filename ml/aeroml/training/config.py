"""Training configuration.

Every field here is written into the model bundle. A model whose training settings cannot be
recovered from the artefact cannot be reproduced, compared or retired with confidence.
"""

from __future__ import annotations

import json
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path

# Which cheat families count as a positive for each head. "overall" is any cheat family at all.
HEAD_FAMILIES: dict[str, tuple[str, ...]] = {
    "overall": ("*",),
    "aimAssist": ("aimassist", "aim-assist", "aimbot", "aim"),
    "killAura": ("killaura", "kill-aura", "aura"),
    "triggerBot": ("triggerbot", "trigger-bot", "trigger"),
}


@dataclass
class TrainingConfig:
    dataset: Path
    output: Path
    window: str = "attack"
    sequence_length: int = 31
    stride: int = 4
    heads: tuple[str, ...] = ("overall", "aimAssist")
    preset: str = "flash"
    epochs: int = 30
    batch_size: int = 128
    learning_rate: float = 2.0e-3
    weight_decay: float = 1.0e-4
    dropout: float = 0.1
    seed: int = 0
    group_by: tuple[str, ...] = ("player",)
    holdout_clients: tuple[str, ...] = ()
    device: str = "cpu"
    max_cached_windows_bytes: int = 2 * 1024 ** 3
    early_stopping_patience: int = 6
    notes: str = ""
    model_version_prefix: str = ""
    allow_synthetic: bool = False
    include_review: bool = False
    torch_threads: int = 2
    golden_manifest: Path | None = None
    # "platt" corrects the prior shift the class-weighted loss puts on every logit; "temperature"
    # is the older scale-only method, kept for comparison with bundles trained before it.
    calibration: str = "platt"
    # 0 = every window counts once; 1 = every player counts once. See augment.group_balance_weights.
    group_balance: float = 0.5
    augment: bool = True
    mirror_probability: float = 0.5
    channel_dropout: float = 0.05
    # Epochs are selected on the validation partial AUC over FPR in [0, selection_max_fpr]: the
    # low-FPR corner an anticheat is operated in, not the whole curve.
    selection_max_fpr: float = 0.05

    def __post_init__(self) -> None:
        self.dataset = Path(self.dataset)
        self.output = Path(self.output)
        if self.golden_manifest is not None:
            self.golden_manifest = Path(self.golden_manifest)
        if self.window not in ("attack", "continuous"):
            raise ValueError(f"unknown window type {self.window!r}")
        unknown = [head for head in self.heads if head not in HEAD_FAMILIES]
        if unknown:
            raise ValueError(f"no family mapping for heads {unknown}; add it to HEAD_FAMILIES")
        if "overall" not in self.heads:
            raise ValueError("the overall head is required: the risk engine reads it")
        if len(set(self.heads)) != len(self.heads) or self.epochs < 1 or self.batch_size < 1 or not 1 <= self.sequence_length <= 512 or self.stride < 1 or self.torch_threads < 1:
            raise ValueError("invalid training dimensions or duplicate heads")
        if self.preset not in ("flash", "pro"):
            raise ValueError("unknown preset")
        if self.calibration not in ("platt", "temperature"):
            raise ValueError("calibration must be 'platt' or 'temperature'")
        if not 0.0 <= self.group_balance <= 1.0 or not 0.0 < self.selection_max_fpr <= 1.0:
            raise ValueError("group_balance must be in [0, 1] and selection_max_fpr in (0, 1]")
        if not 0.0 <= self.mirror_probability <= 1.0 or not 0.0 <= self.channel_dropout < 1.0:
            raise ValueError("augmentation probabilities must be in [0, 1)")
        if not self.model_version_prefix:
            self.model_version_prefix = f"aero-{self.preset}-{self.window}"

    def to_dict(self) -> dict:
        data = asdict(self)
        data["dataset"] = str(self.dataset)
        data["output"] = str(self.output)
        data["golden_manifest"] = str(self.golden_manifest) if self.golden_manifest else None
        data["heads"] = list(self.heads)
        data["group_by"] = list(self.group_by)
        data["holdout_clients"] = list(self.holdout_clients)
        return data

    @classmethod
    def from_json(cls, path: Path | str) -> "TrainingConfig":
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        data["heads"] = tuple(data.get("heads", ("overall", "aimAssist")))
        data["group_by"] = tuple(data.get("group_by", ("player",)))
        data["holdout_clients"] = tuple(data.get("holdout_clients", ()))
        return cls(**data)


def git_commit(root: Path | str = ".") -> str:
    """Best effort. An unknown commit is recorded as empty rather than as a plausible-looking lie."""
    try:
        result = subprocess.run(["git", "rev-parse", "HEAD"], cwd=str(root), capture_output=True,
                                text=True, timeout=5, check=False)
    except (OSError, subprocess.SubprocessError):
        return ""
    return result.stdout.strip() if result.returncode == 0 else ""


def head_positive(head: str, label: str, cheat_family: str | None) -> bool:
    """A window is positive for a head when its session is that cheat family (or any, for overall)."""
    if label != "CHEAT":
        return False
    families = HEAD_FAMILIES[head]
    if families == ("*",):
        return True
    family = (cheat_family or "").strip().lower().replace("_", "-")
    return any(family == candidate or family.startswith(candidate + "-") for candidate in families)

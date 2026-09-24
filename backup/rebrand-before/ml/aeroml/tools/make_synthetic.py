"""Synthetic session generator.

This exists to exercise the pipeline end to end without a Minecraft server: loader, windows,
features, splits, normalisation, training and the service all run against it in CI.

It is NOT training data. The "cheat" sessions are a toy aim controller, not a real client, and a
model trained on them says nothing about real players. Every session it writes is stamped
``synthetic`` in its notes and ``labelSource`` stays a lab label so nothing can quietly promote it.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import uuid
from pathlib import Path

from ..console import use_utf8_console

SCHEMA_VERSION = 1


def _schema_fields() -> list[str]:
    from ..schema import default_schema

    return list(default_schema().raw_fields)


class _Recorder:
    def __init__(self, fields: list[str]) -> None:
        self.fields = fields
        self.lines: list[str] = []
        self.frames = 0

    def frame(self, session_id: str, tick: int, offset_ns: int, values: dict[str, float | None]) -> None:
        row = {name: None for name in self.fields}
        for name, value in values.items():
            if name not in row:
                raise KeyError(name)
            row[name] = None if value is None or (isinstance(value, float) and math.isnan(value)) else float(value)
        self.lines.append(json.dumps({
            "schemaVersion": SCHEMA_VERSION,
            "sessionId": session_id,
            "offsetNanos": offset_ns,
            "type": "frame",
            "tick": tick,
            "values": row,
        }, separators=(",", ":")))
        self.frames += 1

    def event(self, session_id: str, offset_ns: int, kind: str, tick: int, entity_id: int,
              yaw: float, pitch: float) -> None:
        self.lines.append(json.dumps({
            "schemaVersion": SCHEMA_VERSION,
            "sessionId": session_id,
            "offsetNanos": offset_ns,
            "type": kind,
            "precedingTick": tick,
            "entityId": entity_id,
            "cancelledAtObservation": False,
            "yaw": yaw,
            "pitch": pitch,
        }, separators=(",", ":")))


def _wrap180(degrees: float) -> float:
    wrapped = math.fmod(degrees, 360.0)
    if wrapped >= 180.0:
        wrapped -= 360.0
    if wrapped < -180.0:
        wrapped += 360.0
    return wrapped


def _aim(player: tuple[float, float, float], eye: float, box: tuple[float, ...]) -> tuple[float, float, float, float, float, float]:
    """Closest point of the box to the eye, then the Minecraft yaw/pitch that points at it."""
    ex, ey, ez = player[0], player[1] + eye, player[2]
    px = min(max(ex, box[0]), box[3])
    py = min(max(ey, box[1]), box[4])
    pz = min(max(ez, box[2]), box[5])
    dx, dy, dz = px - ex, py - ey, pz - ez
    distance = math.sqrt(dx * dx + dy * dy + dz * dz)
    yaw = _wrap180(math.degrees(math.atan2(-dx, dz)))
    pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
    return yaw, pitch, distance, px, py, pz


def generate_session(directory: Path, *, label: str, client: str, configuration: str, player_id: str,
                     scenario: str | None = None, assist_strength: str | None = None,
                     seconds: float = 30.0, assist: float = 0.0, reaction_ticks: int = 3,
                     noise: float = 1.2, protocol: int = 47, seed: int = 0,
                     attack_interval: float = 11.0, jitter_ms: float = 6.0) -> str:
    """Writes one synthetic session and returns its id. ``assist`` is the toy aim-assist strength."""
    rng = random.Random(seed)
    fields = _schema_fields()
    recorder = _Recorder(fields)
    session_id = str(uuid.UUID(int=rng.getrandbits(128), version=4))
    samples = int(seconds * 20)
    start_ns = 0
    entity_id = 1000 + rng.randrange(500)

    yaw, pitch = rng.uniform(-180, 180), rng.uniform(-10, 10)
    previous_yaw, previous_pitch = yaw, pitch
    previous_delta_yaw = previous_delta_pitch = 0.0
    previous_acceleration = 0.0
    player = [0.0, 64.0, 0.0]
    eye = 1.62
    orbit = rng.uniform(2.4, 4.2)
    angle = rng.uniform(0, math.tau)
    angular_speed = rng.uniform(0.05, 0.12)
    history: list[tuple[float, float]] = []
    ticks_since_attack = None
    last_attack_ms = None
    attack_countdown = rng.uniform(4, attack_interval)
    interval_ms: float | None = None
    ping = rng.uniform(25, 80)
    previous_box = None
    previous_aim_error: float | None = None
    switch_tick = 0

    for tick in range(1, samples + 1):
        offset_ns = start_ns + int(tick * 50_000_000)
        angle += angular_speed
        target_x = math.cos(angle) * orbit
        target_z = math.sin(angle) * orbit
        target_y = 64.0 + math.sin(tick * 0.11) * 0.25
        box = (target_x - 0.3, target_y, target_z - 0.3, target_x + 0.3, target_y + 1.8, target_z + 0.3)
        want_yaw, want_pitch, distance, aim_x, aim_y, aim_z = _aim(tuple(player), eye, box)

        history.append((want_yaw, want_pitch))
        delayed = history[max(0, len(history) - 1 - reaction_ticks)]
        error_yaw = _wrap180(delayed[0] - yaw)
        error_pitch = delayed[1] - pitch
        # A human closes most of the gap with an overshooting, noisy correction.
        human_yaw = error_yaw * rng.uniform(0.18, 0.42) + rng.gauss(0, noise)
        human_pitch = error_pitch * rng.uniform(0.18, 0.42) + rng.gauss(0, noise * 0.6)
        if assist > 0:
            # The toy assist blends a fraction of the exact correction into the human motion.
            human_yaw = human_yaw * (1 - assist) + _wrap180(want_yaw - yaw) * assist
            human_pitch = human_pitch * (1 - assist) + (want_pitch - pitch) * assist
        yaw = _wrap180(yaw + human_yaw)
        pitch = max(-90.0, min(90.0, pitch + human_pitch))

        delta_yaw = _wrap180(yaw - previous_yaw)
        delta_pitch = pitch - previous_pitch
        acceleration = math.hypot(delta_yaw - previous_delta_yaw, delta_pitch - previous_delta_pitch)

        attack_countdown -= 1
        attacking = attack_countdown <= 0 and tick > 6
        if attacking:
            attack_countdown = max(2.0, rng.gauss(attack_interval, attack_interval * 0.18))
            # The recorder keeps the interval of the most recent attack, so it persists between attacks.
            interval_ms = None if last_attack_ms is None else (tick * 50.0 - last_attack_ms)
            last_attack_ms = tick * 50.0
            ticks_since_attack = 0
        else:
            ticks_since_attack = None if ticks_since_attack is None else ticks_since_attack + 1
        if attacking:
            recorder.event(session_id, offset_ns - 10_000_000, "attack", tick - 1, entity_id, yaw, pitch)

        ping += rng.gauss(0, jitter_ms * 0.25)
        ping = max(8.0, min(400.0, ping))
        velocity = (rng.gauss(0, 0.05), rng.gauss(0, 0.02), rng.gauss(0, 0.05))
        target_velocity = None if previous_box is None else (
            ((box[0] + box[3]) - (previous_box[0] + previous_box[3])) * 0.5,
            box[1] - previous_box[1],
            ((box[2] + box[5]) - (previous_box[2] + previous_box[5])) * 0.5,
        )
        aim_error_yaw = _wrap180(yaw - want_yaw)
        aim_error_pitch = pitch - want_pitch
        aim_error_total = math.hypot(aim_error_yaw, aim_error_pitch)
        # The recorder publishes the change in aim error whenever the previous sample tracked the
        # same target; leaving it unset made the fixture unrepresentative of a real recording.
        aim_error_delta = None if previous_aim_error is None else aim_error_total - previous_aim_error
        recorder.frame(session_id, tick, offset_ns, {
            "YAW": yaw, "PITCH": pitch,
            "DELTA_YAW": delta_yaw if tick > 1 else None,
            "DELTA_PITCH": delta_pitch if tick > 1 else None,
            "DELTA2_YAW": (delta_yaw - previous_delta_yaw) if tick > 2 else None,
            "DELTA2_PITCH": (delta_pitch - previous_delta_pitch) if tick > 2 else None,
            "ROTATION_SPEED": math.hypot(delta_yaw, delta_pitch) if tick > 1 else None,
            "ROTATION_ACCELERATION": acceleration if tick > 2 else None,
            "ROTATION_JERK": (acceleration - previous_acceleration) if tick > 3 else None,
            "PLAYER_X": player[0], "PLAYER_Y": player[1], "PLAYER_Z": player[2], "EYE_HEIGHT": eye,
            "VELOCITY_X": velocity[0], "VELOCITY_Y": velocity[1], "VELOCITY_Z": velocity[2],
            "ON_GROUND": 1, "SPRINTING": 1, "SNEAKING": 0, "AIRBORNE": 0,
            "TARGET_PRESENT": 1, "TARGET_ENTITY_ID": entity_id, "PREVIOUS_TARGET_ENTITY_ID": -1,
            "TARGET_TYPE": 116,
            "TARGET_X": (box[0] + box[3]) * 0.5, "TARGET_Y": box[1], "TARGET_Z": (box[2] + box[5]) * 0.5,
            "TARGET_VELOCITY_X": None if target_velocity is None else target_velocity[0],
            "TARGET_VELOCITY_Y": None if target_velocity is None else target_velocity[1],
            "TARGET_VELOCITY_Z": None if target_velocity is None else target_velocity[2],
            "TARGET_MIN_X": box[0], "TARGET_MIN_Y": box[1], "TARGET_MIN_Z": box[2],
            "TARGET_MAX_X": box[3], "TARGET_MAX_Y": box[4], "TARGET_MAX_Z": box[5],
            "AIM_POINT_X": aim_x, "AIM_POINT_Y": aim_y, "AIM_POINT_Z": aim_z,
            "DISTANCE_TO_TARGET": distance,
            "TARGET_YAW": want_yaw, "TARGET_PITCH": want_pitch,
            "AIM_ERROR_YAW": aim_error_yaw, "AIM_ERROR_PITCH": aim_error_pitch,
            "AIM_ERROR_TOTAL": aim_error_total,
            "AIM_ERROR_DELTA": aim_error_delta,
            "CROSSHAIR_INSIDE_HITBOX": None, "LINE_OF_SIGHT": None,
            "ATTACK": 1 if attacking else 0, "SWING": 1 if attacking else 0,
            "SUCCESSFUL_HIT": None,
            "ATTACK_COUNT": 1 if attacking else 0, "SWING_COUNT": 1 if attacking else 0,
            "CANCELLED_ATTACK_COUNT": 0,
            "TICKS_SINCE_ATTACK": ticks_since_attack,
            "ATTACK_INTERVAL_MS": interval_ms,
            "TARGET_SWITCH": 0, "TICKS_SINCE_TARGET_SWITCH": tick - switch_tick,
            "PING_MS": ping, "ESTIMATED_JITTER_MS": jitter_ms,
            "SERVER_TICK_DURATION_MS": 50.0 + rng.gauss(0, 1.5),
            "CLIENT_PROTOCOL_VERSION": protocol,
            "REACH_EVIDENCE": 0, "WALL_HIT_EVIDENCE": 0, "ENTITY_PIERCE_EVIDENCE": 0, "PACKET_ORDER_EVIDENCE": 0,
            "REACH_DISTANCE": None, "REACH_TARGET_ENTITY_ID": None, "REACH_OBSERVATION_AGE_MS": None,
            "TELEPORT_STATE": 0, "VEHICLE_STATE": 0, "INVENTORY_STATE": 0, "HELD_ITEM_TYPE": 267,
            "MOVEMENT_HAS_POSITION": 1, "MOVEMENT_HAS_LOOK": 1,
            "SEGMENT_START": 1 if tick == 1 else 0,
            "SAMPLE_INTERVAL_MS": None if tick == 1 else 50.0,
            "TRANSACTION_ID": tick, "SERVER_TICK": tick,
        })
        previous_yaw, previous_pitch = yaw, pitch
        previous_delta_yaw, previous_delta_pitch = delta_yaw, delta_pitch
        previous_acceleration = acceleration
        previous_box = box
        previous_aim_error = aim_error_total

    raw_dir = directory / "raw"
    metadata_dir = directory / "metadata"
    raw_dir.mkdir(parents=True, exist_ok=True)
    metadata_dir.mkdir(parents=True, exist_ok=True)
    (raw_dir / f"session-{session_id}.jsonl").write_text("\n".join(recorder.lines) + "\n", encoding="utf-8")
    (metadata_dir / f"session-{session_id}.json").write_text(json.dumps({
        "schemaVersion": SCHEMA_VERSION,
        "datasetVersion": "dataset-v1",
        "sessionId": session_id,
        "playerId": player_id,
        "startTimestamp": 1_700_000_000_000,
        "label": label,
        "labelSource": {"LEGIT": "LAB_LEGIT", "CHEAT": "LAB_CHEAT", "UNLABELED": "PRODUCTION_UNLABELED"}[label],
        "cheatFamily": "aimassist" if label == "CHEAT" else None,
        "clientFamily": client,
        "configuration": configuration,
        "scenario": scenario,
        "assistStrength": assist_strength or ("NONE" if label == "LEGIT" else "UNKNOWN"),
        "notes": "synthetic generator output, not a recording of a real player",
        "minecraftProtocol": protocol,
        "pluginVersion": "synthetic",
        "continuousSize": 96,
        "attackBefore": 20,
        "attackAfter": 10,
        "durationMs": int(seconds * 1000),
        "frames": recorder.frames,
        "records": recorder.frames,
        "droppedRecords": 0,
        "complete": True,
        "usableWithoutReview": True,
        "closeReason": "MANUAL_STOP",
        "failure": None,
    }, indent=2) + "\n", encoding="utf-8")
    return session_id


def generate_dataset(directory: Path, *, players: int = 12, clients: tuple[str, ...] = ("clientA", "clientB"),
                     seconds: float = 25.0, seed: int = 7) -> Path:
    """A small multi-player, multi-client corpus: enough to exercise every split strategy."""
    directory = Path(directory)
    rng = random.Random(seed)
    scenarios = ("box-pvp", "open-field", "crowd")
    for player in range(players):
        player_id = f"synthetic-player-{player:02d}"
        legit_client = "vanilla" if player % 2 == 0 else "lunar"
        scenario = scenarios[player % len(scenarios)]
        generate_session(directory, label="LEGIT", client=legit_client, configuration="default",
                         player_id=player_id, seconds=seconds, assist=0.0,
                         scenario=scenario, assist_strength="NONE",
                         reaction_ticks=rng.randrange(2, 5), noise=rng.uniform(0.9, 1.6),
                         protocol=rng.choice((47, 340, 765)), seed=seed * 100 + player)
        client = clients[player % len(clients)]
        strength = (0.35, 0.6, 0.85)[player % 3]
        label_strength = {0.35: "LOW", 0.6: "MEDIUM", 0.85: "HIGH"}[strength]
        generate_session(directory, label="CHEAT", client=client, configuration=f"assist-{strength}",
                         player_id=player_id, seconds=seconds, assist=strength,
                         scenario=scenario, assist_strength=label_strength,
                         reaction_ticks=rng.randrange(2, 5), noise=rng.uniform(0.9, 1.6),
                         protocol=rng.choice((47, 340, 765)), seed=seed * 200 + player)
    return directory


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate a synthetic Aero AC dataset (never for training)")
    parser.add_argument("output", type=Path)
    parser.add_argument("--players", type=int, default=12)
    parser.add_argument("--seconds", type=float, default=25.0)
    parser.add_argument("--seed", type=int, default=7)
    arguments = parser.parse_args()
    use_utf8_console()
    root = generate_dataset(arguments.output, players=arguments.players, seconds=arguments.seconds, seed=arguments.seed)
    print(f"wrote synthetic dataset to {root}")


if __name__ == "__main__":
    main()

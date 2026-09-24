# The Aero administrator interface

`/aero` is the interface the anticheat is meant to be operated from. The `/neural ...` commands are
unchanged and remain the debug surface; nothing here replaces them.

Everything in this document is monitoring. No screen, click or indicator changes a prediction, a
risk value, a mitigation, a threshold, or what a dataset session records.

## What the percentage means

Model output is labelled **AI Risk** everywhere it appears, never "chance of cheating". The heads
are an experimental model that has not been calibrated against a real labelled dataset, and a
percentage presented as a probability would be a claim nobody has earned yet.

A player the model has not scored shows **NO DATA**, never 0%. The distinction is the point: *scored
zero* is evidence that nothing is happening, *never scored* is the absence of evidence, and an
operator acting on one when they are looking at the other is exactly the failure this wording exists
to prevent. The same rule runs through the evaluation screens as **INSUFFICIENT DATA** and
**UNMEASURABLE**.

## Screens

```
/aero                  AERO                       hub, ten tiles
 ├── PLAYERS           AERO › PLAYERS             heads, sorted by AI Risk, paginated
 ├── SUSPICIOUS        AERO › SUSPICIOUS          WATCH and above, with per-state counts
 ├── LIVE MONITOR      AERO › LIVE MONITOR        who you are watching + everyone in combat
 ├── NEURAL STATUS     AERO › NEURAL STATUS       inference, models, traffic, risk, mitigation, recorder
 ├── CHECKS            AERO › CHECKS              deterministic evidence families
 ├── MITIGATIONS       AERO › MITIGATIONS         who was acted on, read-only
 ├── ALERTS            AERO › ALERTS              your alert switch and when alerts fire
 ├── STATISTICS        AERO › STATISTICS          counters this server produced
 ├── SETTINGS          AERO › SETTINGS            your indicator mode; configuration read-only
 └── TRAINING CENTER   AERO › TRAINING CENTER     collection and training
      ├── ACTIVE RECORDINGS   progress, live quality counters, stop (two clicks)
      ├── START RECORDING     the wizard
      ├── DATASET OVERVIEW    totals and breakdowns
      ├── COLLECTION COVERAGE what is thin against your own goals
      ├── RECENT SESSIONS     newest recordings and how they closed
      ├── REVIEW QUEUE        imported REVIEW verdicts and sessions that did not close cleanly
      └── MODEL TRAINING      job, evaluations, and CURRENT vs CANDIDATE
```

A player row carries: AI Risk, state, the three heads, ping, combat duration, risk score, evidence
count, an active mitigation and an active recording. Left click opens the profile, right click
toggles watching.

A profile carries risk, peak risk, time in state, the latest prediction and its heads, ping, combat
duration, current target, aim error, delta yaw/pitch, evidence and prediction counts, and the active
mitigation, plus a nine-pane timeline of recent model output. Four buttons lead to PREDICTIONS,
EVIDENCE, GRIM FLAGS and MITIGATION HISTORY; all four read the engine's existing ring buffers, so
what a ring has dropped is gone and the screen says so.

There is no ban button at this stage.

## Commands

```
/aero                                     open the hub (prints a summary from the console)
/aero players                             all players
/aero suspicious                          flagged players
/aero player <name>                       one player's profile
/aero watch <name> [off]                  stream live telemetry to your chat
/aero view <off|all|suspicious|auto>      your floating indicator
/aero alerts <on|off>                     your alerts
/aero status                              service health as text
/aero training                            training centre
/aero training active                     active recordings
/aero training player <name>              one recording
/aero training set <field> <value>        fill a wizard field (cheat-family, client,
                                          configuration, scenario, notes)
```

Every command that opens a screen prints its answer instead when no screen can be drawn — from the
console, or on a platform with no menu implementation.

## Permissions

| Node | Grants |
| --- | --- |
| `aero.admin` | every node below |
| `aero.gui` | open `/aero` |
| `aero.players` | the online player list |
| `aero.suspicious` | the flagged list |
| `aero.profile` | a player's profile and history |
| `aero.monitor` | stream live telemetry |
| `aero.view` | see the floating indicator |
| `aero.status` | service health, checks, statistics |
| `aero.mitigation` | mitigation history |
| `aero.alerts` | receive alerts |
| `aero.training` | open the training centre |
| `aero.training.record` | start a dataset recording |
| `aero.training.stop` | stop a dataset recording |
| `aero.training.overview` | dataset totals, breakdowns, coverage |
| `aero.training.review` | the review queue |
| `aero.training.model` | training jobs and evaluations |

All default to OP. They are split because they are not equally consequential: recording writes
labelled data a model will be trained on, and a review verdict is treated as ground truth by the
offline tooling.

## The floating indicator

Drawn with `WrapperPlayServerTeams` packets sent to one administrator's connection. Nothing exists
on the server: no entity, no armour stand, no text display, and the target player's real display
name is never touched. A player without `aero.view` cannot see an indicator, because the packets
were never sent to them.

```
● REC CHEAT AIM-ASSIST LOW 03:42 126w   PlayerName   ⚠ 91% | AIM
```

The recording tag takes the team prefix and the risk tag takes the suffix. A name tag has no way to
wrap, so the two-line form in the brief is compressed onto one line.

The dominant head is display only — `aimAssist → AIM`, `killAura → AURA`, `triggerBot → TRIGGER`,
and `AI` when the model published no specific head. Nothing branches on it and nothing is punished
because of it.

Modes: `off`, `all`, `suspicious`, `auto`. Under `auto` the tag disappears the moment a player
returns to CLEAN. Packets are only sent when the *rendered line* changes, so a risk value that moves
continuously costs nothing until a rounded percentage, a state or a clock second changes.

### Limitations

* One line. Multi-line would need a fake entity per viewer.
* Team packets move the target into an Aero team **on that administrator's client only**. If another
  plugin puts players in scoreboard teams, that plugin's prefix and name colour are replaced for the
  viewer while the tag is up, and are restored when the server next resends its own team state.
* Team names are 16 characters (`aero` + 12 hex from the target's UUID) to stay valid on 1.13.

## Alerts

```
[Aero] TestPlayer → SUSPICIOUS
  Risk 7.82 | AI Risk 91% | AIM
  [PROFILE] [WATCH]
```

`[PROFILE]` and `[WATCH]` are clickable. An alert fires on a state **transition upwards**, at or
above `neural.gui.alerts.min-state`, at most once per `throttle-seconds` per player. Predictions
never alert: the model produces an output every window, and alerting on that is a stream nobody
reads. A state that falls is recorded silently, so a player hovering on the boundary does not alert
on every crossing.

## Training centre

### Starting a LEGIT recording

```
TRAINING CENTER → START RECORDING
  → SELECT PLAYER   click a head
  → SELECT LABEL    LEGIT      (assist strength is set to NONE and the step is skipped)
  → CLIENT FAMILY   preset, or TYPE A VALUE
  → CONFIGURATION   TYPE A VALUE, or LEAVE UNSET
  → SCENARIO        flat-duel
  → CONFIRM         shows every field, then START RECORDING
```

### Starting a CHEAT AimAssist VERY_LOW recording

```
TRAINING CENTER → START RECORDING
  → SELECT PLAYER   click a head
  → SELECT LABEL    CHEAT
  → CHEAT FAMILY    aim-assist
  → CLIENT FAMILY   the cheat client's name
  → CONFIGURATION   e.g. smooth-30
  → ASSIST STRENGTH VERY_LOW      (NONE is not offered)
  → SCENARIO        tracking
  → CONFIRM         → START RECORDING
```

The wizard calls the same `NeuralManager.startSession` the command calls. There is no second
recording path: same validation, same metadata, same file.

Two rules are enforced before anything opens:

* **LEGIT is always NONE.** Honest play carries no assist strength.
* **CHEAT is never NONE.** A CHEAT session claiming no assistance would be counted as a cheat nobody
  could detect. An unrecorded strength is `UNKNOWN`, which is never treated as NONE.

Free-text fields are filled with `/aero training set <field> <value>`; clicking the field hands you
the command. An inventory cannot take typing, and a chat prompt would have to swallow messages.

### While recording

Progress bars run against `neural.gui.training.target-duration-seconds` and
`target-attack-windows`. **These are recommendations.** A session that misses them is not bad data,
and a session that meets them is not audited, reviewed, calibrated or known to be usable.

Live quality counters: dropped records, queued records, frames, attacks, swings, buildable attack
windows, and the fraction of recent frames carrying a target, an aim error and target geometry. A
`QUALITY WARNING` means a counter looks wrong — records were dropped, a long fight produced no
buildable window, or most frames carry no combat context. It is not a verdict. **GOOD / REVIEW /
UNUSABLE comes from the offline audit and nowhere else.**

Stopping takes two clicks on two different buttons. There is no discard button: deleting a recorded
session from a menu would destroy data the offline tooling may already have indexed.

### Dataset screens

Built from `datasets/metadata/*.json` on a background thread and cached
(`neural.gui.dataset.cache-seconds`). The raw telemetry is never opened.

Two numbers are absent and say so:

* **Attack windows across the dataset.** Metadata records frames, not how many windows could be
  built from them. The offline audit counts those.
* **Ping buckets for LEGIT sessions.** Metadata records the client protocol; ping lives in the
  telemetry frames.

Audit verdicts are imported from `datasets/audit/latest.json` (written by
`aeroml.tools.audit_dataset`). Human reviews are imported from `datasets/manifests/*.json` in the
golden manifest format — a session is shown as reviewed only when a named reviewer and timestamp are
in that file. Nothing in the plugin can mark a session reviewed.

Coverage measures what you have recorded against goals you set. Meeting every goal does **not** mean
the dataset supports any particular false-positive or detection rate; that is measured by evaluating
a model on held-out reviewed sessions.

### Model training

The JVM does not train. There is no PyTorch in the process and no subprocess: training is GPU-bound
and hours long, and a Minecraft server is a loop that must not stall for it.

`TrainingServiceClient` is the seam for an external service. The default is
`TrainingServiceClient.Offline`, which reports `NOT_CONFIGURED` — the expected state for a server
that is only running the anticheat. Every call is a cached snapshot; a backend that is slow,
unreachable or gone changes nothing about the anticheat.

**Nothing deploys automatically.** The status enum stops at `CANDIDATE`. Finishing training does not
swap the running model, enable mitigation or move a threshold, and a test asserts that no status
containing `DEPLOY`, `PROMOT`, `ACTIVE` or `LIVE` exists.

Model comparison states the direction per metric, because "higher is better" is wrong for half of
them: a candidate that detects more while producing more false positives per combat hour has moved
the cost onto honest players. Regressions are marked. A row unmeasured on either side is neither an
improvement nor a regression — it is a missing measurement, and shown as one.

## Threading

| Work | Thread |
| --- | --- |
| Reading neural state into a view | the owning player's event loop, via `runSafely` |
| Publishing the snapshot, alerts, indicator packets | the global region scheduler |
| Opening, redrawing and closing a screen | the viewer's region (Folia) or the main thread |
| Reading session metadata | a dedicated daemon thread, cached |
| Training backend I/O | the backend's own threads; the interface only reads a cached snapshot |

The published snapshot is at most one refresh old: a refresh requested on one tick lands on the
player loops asynchronously and is read on the next. That is the price of never touching
event-loop-confined state from anywhere else.

No screen reaches into the risk engine, no click takes a lock, nothing on this path runs inference,
recomputes geometry, makes an HTTP request, or stores anything that grows.

## Cleanup

| Event | What is dropped |
| --- | --- |
| A tracked player quits | their published view, alert history, recording draft, indicator mode, and their tag on every viewer |
| An administrator quits | their indicator subscription and every tag they were sent, their draft, their open screen |
| An administrator closes a window | that screen |
| `/grim reload` | every open screen is closed, every indicator is removed with packets before the state behind it is dropped, alert history and drafts are cleared, the dataset summary service is rebuilt, the refresh task is restarted |
| Plugin disable | the refresh task, every indicator, the summary thread, the training client |

The indicator permission is re-checked on every refresh, not only when the mode was set, so a
revoked permission takes the tags away without waiting for a relog.

## Configuration

```yaml
neural:
    gui:
        enabled: true
        refresh-ms: 1000
        floating:
            enabled: true
            refresh-ms: 500
            default-mode: suspicious     # off | all | suspicious | auto
        alerts:
            enabled: true
            min-state: WATCH
            throttle-seconds: 30
        training:
            target-duration-seconds: 300
            target-attack-windows: 150
            scenarios: [box-pvp, tracking, flick, strafe, flat-duel, custom]
            cheat-families: [aim-assist, kill-aura, trigger-bot, reach, auto-clicker, other]
            client-families: []
            goal-sessions-per-assist-strength: 20
            goal-distinct-cheat-clients: 5
            goal-high-ping-legit-sessions: 20
        dataset:
            cache-seconds: 60
            max-sessions: 5000
            recent-sessions: 45
```

## Platform support

The view models, sorting, pagination, validation, dataset summary, alerts, indicators and commands
live in `common` and are platform-neutral. Only drawing is behind `AdminGuiBridge`, which Bukkit
implements with `BukkitAdminGui`.

There is no Fabric implementation. A Fabric server has no `Inventory`/`InventoryHolder` in the shape
this uses, and a façade over both would fit one of them badly. On a platform with no bridge,
`AdminGuiBridge.UNAVAILABLE` is used and every command prints its answer instead of failing.

Compiled against Paper 1.20.6 with `api-version: 1.13`, so only APIs available since 1.13 are used:
legacy section-sign strings for inventory titles and item metadata, `PLAYER_HEAD`, and the 1.13
stained-glass materials.

ProtocolLib is not required and is not used; the indicator is built on the PacketEvents stack the
plugin already depends on.

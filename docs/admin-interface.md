# The Aero administrator interface

`/aero` is the interface the anticheat is meant to be operated from. Recording and diagnostic
commands live under `/aero neural ...`; the standalone `/neural` root remains as a compatibility
alias for existing scripts and operator habits.

Screens read published snapshots and never run inference, geometry or file I/O on a tick thread.
Two explicit controls are exceptions to the otherwise read-only interface: an `aero.admin` holder
can enable or disable dataset recording after a confirmation screen, and an
`aero.training.model` holder can start or cancel an external training job after confirmation.
Neither control changes detector weights, risk formulas, thresholds, mitigations or the active
model. A completed training job remains only a candidate bundle.

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
      ├── START RECORDING     quick start: one click per player (shift: the long form)
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

## Starting a recording

Two fields are required and the other six are not, so the default path asks for two.

* **START RECORDING** on the training centre opens the quick screen. Left click a player and an
  honest-play session opens immediately. Right click picks a cheat family first — the one field a
  CHEAT session cannot go without, because a family is what groups it during evaluation. Shift-click
  opens the long form for a session worth describing in full.
* **`/aero rec <player>`** does the same in one command; with a family after the name it records a
  cheat session instead.

Client, configuration and scenario stay unset on the quick path. Unset is a real value: it records
that nobody wrote the field down, which is the truth, and it can be filled in afterwards. Assist
strength on a quick CHEAT session is UNKNOWN for the same reason — never NONE, which would claim the
cheat was giving no help at all.

## Enforcement

The anticheat can ask for a ban. Whether it asks, and who answers, is `neural.enforcement.mode`:

| Mode | What happens when a player reaches the bar |
| --- | --- |
| `off` | Nothing. |
| `announce` (default) | Staff with `aero.enforce` see *"the anticheat would ban X — but will not without confirmation"*, with the numbers behind it and three buttons: **BAN**, **DECLINE**, **PROFILE**. Nothing happens until somebody with `aero.enforce.confirm` answers. |
| `automatic` | The ban runs on its own, in the next ban wave (`wave-minutes`, 0 for at once). |

The default is `announce` because the model behind the risk value has not been calibrated against a
labelled dataset recorded on this server. Pointed at automatic bans, an uncalibrated detector removes
honest players and nobody finds out until the appeal. Switch to `automatic` after measuring your own
false positive rate.

In automatic mode verdicts are not carried out when they are reached. They queue for a ban wave that
runs `wave-minutes` later, randomised between half and one and a half periods, and every verdict
queued by then runs together; a player who logs out in the meantime is still banned. An instant ban
tells a cheat developer exactly which fight and which setting tripped the detector; a wave does not.
Reloading into any mode other than `automatic` withdraws the queued verdicts.

The bar is a gate on a verdict the risk engine already reached — it computes nothing of its own:
reported state at least `min-state` (CONFIRMED), at least `min-evidence` accumulated evidence, and
at least `min-predictions` model outputs for that player. One player is decided on at most once per
`cooldown-seconds`.

A verdict is **frozen when it is made**. A confirmation half an hour later executes the case that
was shown, not whatever the risk value has drifted to since. An announced verdict nobody answers
within `confirm-timeout-seconds` expires into nothing — silence is a refusal, not a delayed ban.

The ban itself is `command`, run by the console with `{player}` and `{reason}` filled in, so it
works with whatever punishment plugin the server uses. The player name is stripped to
`[A-Za-z0-9_.-]` before substitution, so a name cannot smuggle in a second command.

Every decision, confirmation, refusal and expiry is written to the log with its numbers.

```
/aero ban list                 verdicts waiting for an answer
/aero ban confirm <id>         carry one out
/aero ban deny <id>            leave the player alone
/aero ban preview <player>     the animation only: nobody is banned, nothing is dropped
```

### The send-off

Before the ban command runs, the player gets a send-off. It is cosmetic from end to end — it
decides nothing, and a ban that ran without it is the same ban.

1. The player is lifted (levitation), lit up (glowing), shown a title, and loses every way to act:
   walking, chat, commands, opening or clicking inventories, dropping or picking up items,
   swapping hands, interacting, placing or breaking blocks, dealing or taking damage, and being
   teleported by another plugin. Looking around still works — they should be able to watch.
2. A coil of light winds up around them; small flames burn at their feet.
3. Once a second a low note sounds, a little higher each time, so the whole thing builds.
4. Their inventory leaves them across the flight — backpack, then hotbar, then off-hand, then
   armour last — each piece thrown outward with its own sound and puff. The items land around the
   spot for anyone to pick up.
5. At the top: a lightning flash (effect only, no damage or fire), a layered bang, a burst of
   particles, and a message to the whole server.

The bang is deliberately not the ordinary explosion. The default layers a warden sonic boom, a
lightning impact and a large firework blast, all at once:

```yaml
ban-sounds:
    - "entity.warden.sonic_boom 0.7 2.0"
    - "entity.lightning_bolt.impact 0.8 1.5"
    - "entity.firework_rocket.large_blast 0.6 1.5"
```

Sounds are `"key pitch volume"`. Keys rather than enum names, so an unknown key on an older version
is silence rather than an error; a line that is not exactly a cue is dropped whole.

What the send-off guarantees, and what its tests hold it to:

* The ban runs exactly once. Quitting mid-flight ends the show and the ban still runs. A presenter
  that called back twice would still ban once.
* Nobody stays frozen. Every flight ends on the timer, on quit, or on plugin stop, and gives back
  walk and fly speed, flight, invulnerability and collision.
* Nobody hits the ground from the top. Every flight ends with fall distance reset and a slow fall.
* **A preview costs nobody an item.** It throws copies that cannot be picked up and vanish after
  three seconds, leaves the real inventory untouched, and is never broadcast.
* A version that lacks a sound, particle or effect loses that one cue, never the flight and never
  the ban.

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
/aero training status                     cached external job status
/aero training start <flash|pro>          enqueue an external training job
/aero training active                     active recordings
/aero training player <name>              one recording
/aero rec <player>                        start recording honest play, one command
/aero rec <player> <cheat-family>         start recording a cheat session
/aero rec stop <player>                   close the session
/aero training set <field> <value>        fill a wizard field (cheat-family, client,
                                          configuration, scenario, notes)
/aero neural ...                          recording and diagnostic command surface
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

Drawn with PacketEvents as an invisible marker armour stand that exists only in one administrator's
client. No Bukkit entity or scoreboard team is created, the target player's display name is never
touched, and other players receive no spawn or metadata packet. The marker follows the position
already tracked for that viewer, is limited to 64 targets within 64 blocks, and is destroyed when
the target leaves the selected mode, the viewer changes world, the permission is revoked, or the
connection closes.

```
REC CHEAT AIM-ASSIST LOW 03:42 126w | ⚠ AI Risk 91% | AIM
```

The text is one compact line above the target. It is independent of the target's real name tag and
does not replace another plugin's scoreboard prefix or name colour.

The dominant head is display only — `aimAssist → AIM`, `killAura → AURA`, `triggerBot → TRIGGER`,
and `AI` when the model published no specific head. Nothing branches on it and nothing is punished
because of it.

Modes: `off`, `all`, `suspicious`, `auto`. Under `auto` the tag disappears when a player returns to
CLEAN. Position packets are sent when the tracked player moves; metadata is sent only when the
rendered text changes.

### Limitations

* One line and at most 64 visible targets per administrator.
* The marker is a client-side entity. Very old or unusual clients may render armour-stand nameplates
  differently; protocol-specific metadata indices are covered by unit tests, but this still needs a
  real multi-version server smoke test.

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
(`neural.gui.dataset.cache-seconds`). The GUI never opens raw telemetry.

Attack-window totals, combat hours and LEGIT ping buckets appear only when every metadata file in
the current bounded view has a matching row in `datasets/audit/latest.json`. Each row is accepted
only when its session id, label, metadata SHA-256 and raw-file size/mtime identity still match. A
partial, stale or malformed audit leaves those totals as **NO DATA** instead of mixing measured and
unmeasured sessions.

`aeroml.tools.audit_dataset` validates golden manifests and embeds a bounded `humanReview` object in
the same integrity-bound audit row. The plugin does not trust or import manifest files directly and
cannot create or edit a human verdict.

Coverage measures what you have recorded against goals you set. Meeting every goal does **not** mean
the dataset supports any particular false-positive or detection rate; that is measured by evaluating
a model on held-out reviewed sessions.

### Model training

The JVM does not import PyTorch or start a training subprocess. Training runs in the separate Python
service described in `ml/README.md`. `neural.training.enabled` defaults to `false`; in that state the
normal status is `NOT_CONFIGURED`. When enabled, the HTTP client polls on its daemon thread and the
GUI reads only its cached snapshot. A slow or unavailable backend therefore does not stall a tick or
change deterministic checks.

For an offline workflow, `neural.gui.training.report-directory` can point to exported
`current.json`, `candidate.json` and optional `job.json` files under the plugin data directory. This
adapter is read-only. The HTTP and report-directory adapters are mutually selected at reload, with
the HTTP service taking precedence when explicitly enabled.

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
| Training HTTP/report I/O | a dedicated daemon thread; the interface only reads a cached snapshot |

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
| `/aero reload` | every open screen is closed, every indicator is removed with packets before the state behind it is dropped, alert history and drafts are cleared, the dataset summary and training clients are rebuilt, the refresh task is restarted |
| Plugin disable | the refresh task, every indicator, the summary thread, the training client |

The indicator permission is re-checked on every refresh, not only when the mode was set, so a
revoked permission takes the tags away without waiting for a relog.

## Configuration

```yaml
neural:
    training:
        enabled: false
        endpoint: "http://127.0.0.1:8090"
        timeout-ms: 2000
        token: ""                       # needed for a non-loopback service
        dataset: "datasets"              # directory under the service's allowed root
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
            report-directory: ""        # read-only exported reports, relative to plugin data
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

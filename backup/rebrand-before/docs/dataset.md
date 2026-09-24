# Aero AC dataset v1

## Запуск записи

В существующем `plugins/GrimAC/config.yml` установите оба флага:

```yaml
neural:
    enabled: true
    collection:
        enabled: true
        target-timeout-ticks: 40
        queue-capacity: 256
        max-sessions: 16
        max-duration-seconds: 1800
        max-session-mib: 128
        max-total-mib: 1024
    windows:
        continuous-size: 96
        attack-before: 20
        attack-after: 10
    telemetry:
        idle-timeout-seconds: 30
```

Остальные секции (`inference`, `risk`, `mitigation`, `monitor`, `debug`) к записи датасета
отношения не имеют и остаются выключенными; они описаны в [inference.md](inference.md) и
[risk-and-mitigation.md](risk-and-mitigation.md).

Затем `/grim reload`. На Fabric используйте data folder, предоставленный платформой.
Включение разрешает явную запись, но не начинает её автоматически для всех игроков.
Каждый reload закрывает текущие sessions, чтобы параметры внутри session не менялись.

Телеметрия собирается только для игрока в бою: collector создаётся на его **первой атаке** и
уничтожается после `idle-timeout-seconds` без боя. Поэтому запись, открытая до первой атаки,
начнёт наполняться кадрами именно с неё; у самой первой атаки нет предшествующей истории, и
её attack window не строится. Для целей это ограничение существует и так — цель известна
только после атаки.

```text
/neural dataset start Player legit
/neural dataset start Player legit vanilla low-sensitivity test-session
/neural dataset start Player cheat aimassist liquidbounce smooth-low manual-lab
/neural dataset start Player cheat aimassist liquidbounce smooth-low --scenario box-pvp --assist low
/neural dataset start Player legit vanilla default --scenario crowd
/neural dataset start Player unlabeled
/neural dataset stop Player
/neural dataset status
/neural status
/neural profile Player
/neural monitor Player
/neural monitor Player off
```

После label: для CHEAT обязателен `<family>`; затем `[client] [configuration] [notes...]`.

Два необязательных флага описывают **условия записи** и могут стоять в любом месте команды:

```text
--scenario <name>    произвольная строка: box-pvp, open-field, crowd, bridge-fight, ...
--assist <strength>  none|very_low|low|medium|high|unknown
```

`--assist` для LEGIT обязан быть `none` (значение по умолчанию), для CHEAT — что угодно кроме
`none`; если оператор не указал, пишется `unknown`, а не тихо `none`. Строка scenario
нормализуется (`Box PvP` → `box-pvp`), чтобы одна и та же арена не превращалась в две группы.

Оба поля — **только metadata**. Они коррелируют с меткой по построению, поэтому в model input
не входят никогда: контракт признаков перечисляет их в `forbiddenModelInputs`, а leakage-аудит
отвергает любой канал с таким именем или источником. Нужны они для разбивки результатов
evaluation и для планирования того, что ещё осталось записать.

Сессии, записанные до появления этих полей, читаются как раньше: `scenario` отсутствует,
`assistStrength` становится `UNRECORDED` — это отдельное значение, не `NONE` и не `UNKNOWN`,
чтобы старые записи не попадали в группу «помощи не было».
Для LEGIT/UNLABELED сразу `[client] [configuration] [notes...]`. Имя игрока выбирает
операционную session и не попадает ни в её файлы, ни в model features.
Права: `grim.neural.dataset` для записи/status, `grim.neural` для help/status/profile,
`grim.neural.monitor` для монитора. Bukkit defaults — OP. Эти же nodes можно выдать через
permissions plugin.

Start подтверждается после открытия raw file и metadata. Stop прекращает новые
записи, но queued records закрываются асинхронно; сама телеметрия при этом продолжает
работать, если включены inference или risk. Profile показывает CLOSED и failure
последней сессии; глобальный status показывает active/closing sessions. Ошибки IO
также журналируются. Новая запись одного игрока возможна после завершения предыдущей.

## Файлы

```text
datasets/
  pseudonym.key
  raw/session-<UUID>.jsonl
  metadata/session-<UUID>.json
  snapshots/<timestamp>-<eventId>.json
```

`snapshots/` появляется только при включённом `neural.risk` и содержит evidence snapshots —
срезы вокруг сильных событий для разбора false positives. Они всегда помечены
`label: UNLABELED` / `labelSource: PRODUCTION_UNLABELED`: снимок существует потому, что
сработали модель и порог, и не является ни вердиктом, ни обучающей меткой.
Формат описан в [risk-and-mitigation.md](risk-and-mitigation.md).

`pseudonym.key` — случайный локальный HMAC-SHA256 key. Не включать его в экспорт.
HMAC от UUID позволяет держать одного игрока в одном split между sessions без
сохранения исходного UUID. На другом сервере/после смены ключа pseudonym изменится;
межсерверный split требует отдельной согласованной схемы identity. Псевдонимизация
не равна полной анонимности: координаты/поведение также могут идентифицировать запись.

Metadata включает schemaVersion=1, datasetVersion=dataset-v1, sessionId, playerId,
startTimestamp (Unix milliseconds), label/labelSource, cheatFamily/clientFamily,
configuration/notes, Minecraft protocol, pluginVersion, window settings, durationMs,
frames/records/droppedRecords, complete, closeReason/failure.

`usableWithoutReview` описывает техническую полноту файла, а не истинность label.
UNLABELED остаётся UNLABELED даже у технически полного файла. В fixture notes прямо
указывают синтетическое происхождение; fixture нельзя отправлять на обучение.

| Label | Источник команды |
| --- | --- |
| LEGIT | LAB_LEGIT: оператор подтвердил контрольную честную игру |
| CHEAT | LAB_CHEAT: оператор знает включённую cheat family/configuration |
| UNLABELED | PRODUCTION_UNLABELED: истины о поведении пока нет |

VERIFIED/REVIEWED_PRODUCTION — будущий отдельный процесс review/versioning, а не
автоматическая перезапись metadata моделью. Predictions и Grim flags никогда
не становятся training labels автоматически.

## JSONL records и время

Каждая строка имеет schemaVersion, sessionId, offsetNanos относительно начала
сессии. Монотонное время пригодно для интервалов; wall clock восстанавливается
приблизительно как startTimestamp + offsetNanos / 1e6.

* `type=frame`: tick и объект values с 79 полями из `FrameField`. Числа без boxing
  хранятся в immutable double array на JVM. Unknown — JSON null, flags — 0/1.
* `type=attack` / `swing`: precedingTick, entityId, cancelledAtObservation, yaw/pitch.
  Несколько событий между samples не схлопываются в один timestamp.
* `type=reachObservation`: entityId, distance, hitboxIntersection, lineOfSight,
  expandedCompensatedBox. Это результат существующей проверки взаимодействия,
  которая в Grim запускается и на некоторые right-click interactions.
* `type=flag:<checkName>`: принятый существующим Grim flag. Это evidence, не label.
* `type=teleport`, `respawnOrWorldChange`, `movementGap`, `cancelledMovement`,
  `invalidMovement`: markers разрыва последовательности.

У attack precedingTick=N означает, что событие пришло после sample N и относится
к interval до следующего sample. В кадре N+1 будут ATTACK_COUNT/SWING_COUNT и
ATTACK_INTERVAL_MS последней атаки. Полный timing восстанавливается из events.
В старых Minecraft версиях нет надёжного отдельного сообщения для каждого idle tick;
sample indices нельзя безусловно приравнивать к физическим клиентским ticks.

## Семантика features

Raw поля: rotations, player positions, eye height, Grim prediction velocity,
ground/sprint/sneak/vehicle/inventory flags, target id/type и полный compensated box,
aim point, attack/swing counters, transaction RTT, protocol/context и accepted flags.
Velocity player — существующая оценка Grim; это не новый физический расчёт.
Held item — только type ID для данного protocol, без дорогого NBT.

Derived: нормализованные deltaYaw/deltaPitch, разности этих delta, rotation speed,
acceleration norm и изменение acceleration norm (jerk), target displacement,
nearest-point aim error и его разность, ticks since attack/switch.
Углы в degrees, позиции в blocks, derived rotations в degrees/sample. Для
сопоставления при нерегулярном sampling используйте SAMPLE_INTERVAL_MS.
Jitter — EWMA абсолютной разницы новых transaction RTT (коэффициент 1/16),
не независимая оценка client network jitter.

CROSSHAIR_INSIDE_HITBOX и SUCCESSFUL_HIT в v1 неизвестны. Они зарезервированы без
ложных нулей. LOS может быть null из-за отсутствия/неопределённости ready-made
ray result. TARGET_PRESENT=0 означает, что все target-relative измерения неизвестны;
entity ID=-1 — только operational sentinel.

PLAYER_X/Y/Z, TARGET_X/Y/Z, ENTITY IDs, TRANSACTION_ID, SERVER_TICK, tick, timestamps,
sessionId и playerId **не являются** model feature list. Порядок enum нельзя молча считать
neural protocol: вход модели задаётся отдельным контрактом
[`ml/schema/feature_schema_v1.json`](../ml/schema/feature_schema_v1.json) — 36 относительных
величин плюс 27 масок, без идентичности, абсолютных координат и evidence существующих проверок.
Java decoder проверяет версию и полный набор полей; Python loader — тоже.

## Качество, limits и splits

Сохраняется raw telemetry, а не только prepared windows. Можно пересчитать размер
окон, скорости/производные и aim point по сохранённому box. Нельзя пересчитать
неизвестный successfulHit или не наблюдавшуюся до первой атаки target geometry.

НИКОГДА не делать random train/test split отдельных окон одной session. Перекрытие
окон и fingerprint игрока дают leakage. Минимум — split по sessionId; предпочтительно
группировать по playerId и отделять clientFamily/configuration. Fit normalization
только на training set. Validation/calibration и test должны быть независимы.

Для unknown-client benchmark: train на A/B/C, test на D; D целиком отсутствует
в training/validation/calibration, включая все configurations. Отдельно считать
known-client и unknown-client results. Dataset version и manifest split сохраняются.

Проверять hard negatives: сильные PvP игроки, sensitivity extremes, jitter/butterfly
clicking, высокий ping/jitter, server lag, flicks, разные protocol versions. Hard
positives: слабые assists, smoothing, low FOV/speed, randomization, редкая активация.

`complete=false`, IO failure, droppedRecords>0, нарушение chronology или segment
boundaries требуют review. Не достраивать потерянные attacks из булевого frame flag.
После аварии incomplete metadata остаётся явной; raw JSONL может иметь оборванную
последнюю строку. Recovery/training loader относится к Phase 2.

## Что делать с записанным

Порядок записи реальных сессий — в [data-collection-protocol.md](data-collection-protocol.md).
Проверка записанного:

```bash
cd ml
python -m aeroml.tools.inspect_session <dataset-root> <sessionId>   # одна сессия, сразу после записи
python -m aeroml.tools.audit_dataset <dataset-root> --features      # весь датасет
```

`inspect_session` отвечает на один вопрос: стоит ли оставлять эту запись. Он печатает
длительность и боевые секунды, кадры, атаки, взмахи, приобретения цели, сколько attack-окон из неё
строится, перцентили интервала сэмплирования (p99 заметно выше 50 мс — клиент или сервер
подтормаживал), разрывы и потерянные записи, долю известных aim error / target geometry / LOS /
ping, число сегментов, а также missingness, клиппинг, всегда отсутствующие и константные каналы
модели. Принимает префикс id, чтобы не вставлять UUID целиком.

Он печатает объём и покрытие датасета, распределение CHEAT по `family → client → configuration`,
распределения LEGIT, вердикт `GOOD`/`REVIEW`/`UNUSABLE` по каждой сессии с причинами и находки
feature audit. Ничего не удаляет.

Загрузка, построение окон, splits без утечек, normalization и статистика —
в [ml/README.md](../ml/README.md). Минимальный путь:

```bash
cd ml
python -m pip install -r requirements.txt
python - <<'EOF'
from aeroml.dataset.records import load_dataset, unusable_sessions, summarise
from aeroml.dataset.statistics import dataset_report, format_report
root = "plugins/GrimAC/datasets"
for session, reason in unusable_sessions(root):
    print("review:", session.metadata.session_id, reason)
print(format_report(dataset_report(load_dataset(root))))
EOF
```

Для локального запуска и проверки используйте [синтетический пример](examples/neural-session/README.md),
а для прогона всего пайплайна без сервера — `python -m aeroml.tools.make_synthetic`.

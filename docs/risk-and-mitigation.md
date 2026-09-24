# Aero AC — Risk Engine и Mitigation

Phase 5 и Phase 6. Обе стадии выключены по умолчанию и включаются независимо.
Inference описан в [inference.md](inference.md), сбор данных — в [dataset.md](dataset.md).

## Почему не порог

```text
prediction > 0.9  =>  ban
```

так делать нельзя. Одиночное значение — это суждение об одном окне длиной чуть больше секунды,
полученное моделью, у которой есть ошибка, и на телеметрии, у которой есть пропуски. Накопление
во времени даёт разработчику чита совсем другую задачу: нужно не пройти под одним порогом один
раз, а устойчиво и одновременно выглядеть человеком по всем характеристикам, которые модель
и deterministic checks измеряют независимо друг от друга.

Это не делает обход невозможным. Это делает его дороже и требует поддерживать имитацию под
каждое изменение модели.

## Формула

```text
risk(t) = risk(t-1) * exp(-decay * elapsedSeconds) + strength
risk = clamp(risk, 0, max-risk)
```

`decay-per-second: 0.01` — период полураспада около 69 секунд; за 10 минут тишины риск падает
примерно в 400 раз. Конкретные числа — конфиг, не константы кода, и покрыты тестами
(`RiskEngineTest`): затухание, идемпотентность повторного timestamp, накопление, переходы
состояний, невозможность одиночного максимального предсказания достичь CONFIRMED.

## Состояния

```text
CLEAN  ->  WATCH  ->  SUSPICIOUS  ->  CONFIRMED
```

Эти четыре выводятся из числа `risk` порогами `watch`/`suspicious`/`confirmed`.
Пятое состояние, `MITIGATED`, **не выводится из риска**: оно показывается, пока mitigation
действительно работает, а риск ещё не дошёл до CONFIRMED. Оператор должен различать
«к игроку применено воздействие» и «доказано».

Пороги приводятся к монотонности при чтении конфига: `suspicious >= watch`,
`confirmed >= suspicious`, `max-risk >= confirmed`.

## Evidence

RiskEngine принимает не число, а наблюдение:

```text
type       AI_AIM | AI_KILLAURA | AI_TRIGGER | AI_OVERALL | AI_RELIEF
           GRIM_REACH | GRIM_WALL_HIT | GRIM_ENTITY_PIERCE | GRIM_PACKET_ORDER
           GRIM_AIR_STUCK | GRIM_AURA_ROTATION | SESSION_ANOMALY
strength   дельта риска в единицах движка; отрицательная только у AI_RELIEF
timestamp  монотонное время
source     "flash/<modelVersion>" или "grim/<checkName>"
metadata   текст для оператора, не вход модели
```

Из одного предсказания рождается не более одного evidence — самый специфичный head,
перешедший `ai-threshold`: `aimAssist`, затем `killAura`, затем `triggerBot`, затем `overall`.
Сила: `ai-weight * (p - threshold) / (1 - threshold)`, то есть при p = threshold вклад нулевой,
при p = 1 — ровно `ai-weight`.

Очень низкое `overall` (ниже `ai-clear-threshold`) даёт `AI_RELIEF` с отрицательной силой:
устойчиво спокойное поведение постепенно снижает накопленный риск.

Grim flags входят как отдельное семейство с весом `grim-weight`. Принимаются только боевые проверки,
которые доказывают нечестный удар: Reach, WallHit, EntityPierce, PacketOrder*, AirStuck (удары из
позиции, которую клиент отказывается сообщать) и Aura* (ротации ауры: snap-back, тряска, лок на центр,
семейство `GRIM_AURA_ROTATION`). Их собственный учёт violations,
alerts и punishments **не меняется**. Grim flag — это evidence, а не label: если скармливать
его как истину, модель выучит существующие пороги вместо поведения.

### Некалиброванная модель

`neural.risk.accept-uncalibrated: false` по умолчанию. Если bundle опубликован без
calibration (`calibrated: false` в ответе), RiskEngine не принимает от него evidence вообще.
Сырой sigmoid — ранжирующий score; складывать его как вероятность значит делать пороги
оператора бессмысленными.

## Evidence snapshot

При первом пересечении `snapshot-threshold` снизу вверх собирается снимок для разбора:

```text
eventId, playerId (HMAC-псевдоним), timestamp
riskBefore/riskAfter, stateBefore/stateAfter
trigger evidence (тип, сила, источник, metadata)
prediction: requestId, model, modelVersion, calibrated, latency, все heads
счётчики evidence по типам
snapshot-before кадров до события и snapshot-after после
ping, длительность серверного tick, protocol
```

Хвост нельзя собрать в момент срабатывания — этих кадров ещё нет; снимок достраивается
следующими сэмплами и отправляется на тот же дисковый поток, что и датасеты
(`datasets/snapshots/<timestamp>-<eventId>.json`).

Снимок всегда помечен `label: UNLABELED` / `labelSource: PRODUCTION_UNLABELED`. Он существует
потому, что сработали модель и порог — это повод для review, а не вердикт, и он никогда не
становится обучающей меткой автоматически.

Ограничения: `max-snapshots-per-hour` на игрока, не более 64 снимков в очереди записи; при
переполнении снимок отбрасывается и считается в `/neural status`. Терять артефакт для разбора
лучше, чем задерживать запись сессий.

## Mitigation

Phase 6 включается **только после измерения false positive rate на собственном сервере**.

```yaml
neural:
    mitigation:
        enabled: false
        min-state: MITIGATED
        cancel-attacks: false
        duration-seconds: 30
        max-per-hour: 20
```

Правила:

* `OBSERVE` — фиксирует достижение порога и не меняет игру.
* `CANCEL_ATTACKS` — отменяет attack-пакеты игрока на время действия. Это то же
  server-authoritative действие, которое анticheat уже выполняет для невозможных ударов:
  игрок просто промахивается.

Множителя урона здесь нет намеренно: у модуля нет platform damage hook, а наполовину
применённый эффект рассинхронизировал бы бой, а не ослабил его.

Ограничения:

* mitigation не запускается, пока состояние ниже `min-state`;
* работающая mitigation никогда не продлевается — она истекает сама, и только потом может
  начаться новая;
* `max-per-hour` считается по скользящему часу на игрока;
* телеметрия записывает пакет **до** отмены, чтобы смягчение не выглядело в датасете как
  отказ deterministic проверки. Сессии игрока под mitigation всё равно не годятся для обучения.

История хранится в кольце на 16 записей: правило, время старта, риск и состояние на момент
срабатывания, причина, длительность, количество отменённых ударов.

## Команды

```text
/neural status               generation, включённые стадии, endpoint, health, снимки
/neural profile <player>     состояние, риск, предсказания, evidence, mitigation, телеметрия
/neural monitor <player>     живая строка; /neural monitor <player> off — выключить
```

`/neural profile` пример:

```text
Player: Test123  ping=43ms  protocol=765
State: SUSPICIOUS  Risk: 7.82  peak=8.10  transitions=2  inState=41s
Latest prediction: flash aero-flash-attack-20260913-153059 overall=0.910 aimAssist=0.950 12ms  age=340ms
Recent overall: 0.84 0.89 0.93 0.91 0.96  accepted=214 staleDropped=3
Evidence: AI_AIM x12 GRIM_WALL_HIT x1
Most recent: AI_AIM=+0.38 (flash/aero-flash-attack-20260913-153059, aimAssist=0.950)
Mitigation: none
Telemetry: tick=1841 ring=96/96 target=214 aimError=1.84 deltaYaw/Pitch=3.21/-0.44 distance=3.07
Complete attack window: 31 samples
```

Здесь нет объяснения работы сети и не должно быть. Показано то, что действительно известно:
какое evidence подняло риск, что вернула модель, какая была геометрия и что происходит сейчас.

`/neural monitor` ограничен `monitor.interval-ms` (по умолчанию 1 секунда) и восемью
наблюдателями на игрока; когда никто не смотрит, на packet path тратится одно чтение volatile.

Права: `grim.neural` — help, status, profile; `grim.neural.dataset` — запись датасетов;
`grim.neural.monitor` — монитор. На Bukkit по умолчанию OP.

## Порядок включения

1. Phase 4 (`inference.enabled`) — только мониторинг; убедиться, что `rejected` равен нулю
   и доля `shed` приемлема.
2. Phase 5 (`risk.enabled`) — по-прежнему без наказаний. Смотреть `/neural profile` на
   заведомо честных сильных игроках несколько дней. Ни одно состояние само по себе ничего не
   делает.
3. Измерить false positives: сколько честных игроков доходит до SUSPICIOUS за час боя.
   Настроить `ai-threshold`, `ai-weight`, пороги и `decay-per-second` по этим измерениям.
4. Только после этого Phase 6 (`mitigation.enabled`), начиная с `cancel-attacks: false`
   (правило OBSERVE), чтобы увидеть частоту срабатываний без влияния на игру.

Бана Risk Engine не выполняет ни на одном этапе. Решение о наказании остаётся за оператором
и существующей системой punishments deterministic проверок.

## Что хранится и что нет

Риск живёт на время подключения — и ещё `neural.risk.carry-over-seconds` (по умолчанию 300)
после выхода. Без этого перезаход за две секунды сбрасывал SUSPICIOUS в CLEAN, и «выйти, пока не
накопилось» было самым дешёвым обходом всего движка. Профиль хранится только в памяти текущей
конфигурации, вместе со своим кольцом evidence и временем последнего обновления, поэтому за время
оффлайна он затухает ровно так, как если бы игрок остался (при `decay-per-second: 0.01` через
30 секунд остаётся 74% риска, через 5 минут — 5%). Чистые профили не запоминаются, число
запомненных ограничено 4096, `/aero reload` их сбрасывает, `0` выключает перенос.

Долгий перенос между сессиями (часы, рестарт сервера) по-прежнему не делается: он потребовал бы
отдельного версионированного хранилища идентичности и review; устаревшее число хуже
отсутствующего, когда evidence за ним уже недоступно.

В model features никогда не входят username, UUID, псевдоним и серверные идентификаторы.
Псевдоним используется только в metadata датасета и снимков, чтобы один игрок не попадал
в разные split.

## Fingerprint (не реализован)

Долгосрочный поведенческий baseline игрока — распределение скоростей поворота, частота
микрокоррекций, время реакции, связь yaw/pitch, тайминги атак — планируется как дополнительный
anomaly-сигнал (`SESSION_ANOMALY` в перечислении evidence уже зарезервирован). Он не должен
быть единственным критерием: сравнение игрока с самим собой ловит смену поведения, а не
доказывает чит.

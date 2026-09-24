# Aero AC ML pipeline

Python-часть: загрузка raw sessions, построение окон, features, splits, normalization,
обучение temporal модели, evaluation, calibration, ONNX export и inference service.
Никакого кода Minecraft здесь нет; JVM-часть описана в [docs/neural-architecture.md](../docs/neural-architecture.md).

```text
ml/
    schema/feature_schema_v1.json   канонический контракт model input
    aeroml/
        schema.py                   загрузка и проверка контракта
        dataset/                    records, windows, features, splits, normalize, statistics
        models/tcn.py               Temporal ConvNet (Flash/Pro presets)
        training/                   config.py, train.py
        evaluation/                 metrics.py, calibration.py
        export/                     bundle.py, onnx_export.py
        service/                    runtime.py, server.py (stdlib), app.py (FastAPI)
        tools/                      make_synthetic.py, make_encoder_golden.py
    tests/                          pytest, включая cross-language fixture
```

## Установка

```bash
python -m pip install -r requirements.txt        # только dataset pipeline и метрики
python -m pip install -r requirements-train.txt  # + torch, onnx, onnxruntime
python -m pip install -r requirements-serve.txt  # + onnxruntime, FastAPI
python -m pip install -r requirements-dev.txt    # + pytest
```

`requirements.txt` намеренно содержит только numpy: работа с датасетом, splits и
evaluation не должны требовать установки training stack.

## Контракт features

`ml/schema/feature_schema_v1.json` — единственный источник истины о том, что получает модель.
Java `ModelFeature`/`FeatureEncoder`, training pipeline и service обязаны совпадать с ним.

* Текущая версия — **featureSchemaVersion 2** (`schema/feature_schema_v2.json`). Загрузчик берёт
  файл с наибольшим номером; v1 остаётся на диске для объяснения старых bundle, но bundle,
  собранный под v1, отклоняется по версии, а не переинтерпретируется.
* 36 value channels + 27 mask channels = **63 канала**. Mask идут после всех values,
  в порядке объявления nullable values, имя `<NAME>_MASK`.
* Канал может объявить монотонный `transform`, применяемый **до** клиппинга, поэтому границы
  указаны в преобразованных единицах. Сейчас поддерживаются `none` и `log1p`.
  `TICKS_SINCE_TARGET_SWITCH` использует `log1p` с границей 12: при прежнем сыром лимите 200
  канал был константой примерно на трёх четвертях окон (p50 = p99 = max = 200), то есть клип
  срезал не хвост, а основной режим. log1p сохраняет разрешение в первых сэмплах после смены
  цели, где и находится сигнал, и сжимает длинный хвост вместо того, чтобы стирать его.
* Unknown кодируется как `0` со значением mask `0`. Это пара: сам по себе ноль выглядел бы
  как измерение, а модель должна отличать «нет цели» от «точно на цели».
* Derived deltas window-local: у индекса 0 нет предшественника внутри окна, поэтому они unknown.
  Java делает ровно так же.
* В модель НЕ входят: username, UUID, pseudonym, entity/transaction/session identifiers,
  абсолютные координаты, protocol version, held item, и evidence существующих Grim checks.
  Первое — приватность и запрет выучивать личность; последнее — чтобы модель не копировала
  пороги deterministic проверок вместо изучения поведения.

Два теста удерживают контракт:

```bash
cd ml && python -m pytest tests/test_schema.py tests/test_features.py
cd .. && ./gradlew :common:test --tests '*FeatureSchemaTest*' --tests '*FeatureEncoderGoldenTest*'
```

`tests/data/encoder_golden.json` — общий fixture: Java и Python обязаны воспроизвести каждый
канал каждого случая. При изменении схемы или любого из двух энкодеров:

```bash
cd ml && python -m aeroml.tools.make_encoder_golden
```

затем **запустить оба набора тестов**. Перегенерация ради того, чтобы одна сторона стала
зелёной, уничтожает смысл fixture: расхождение энкодеров больше ничем не ловится.

## Синтетические данные

Реальных labeled sessions в репозитории нет и быть не должно. Для прогона всего pipeline:

```bash
cd ml && python -m aeroml.tools.make_synthetic /tmp/aero-synthetic --players 6 --seconds 25
```

Это игрушечный контроллер прицеливания, а не клиент. Модель, обученная на нём, ничего не
говорит о реальных игроках. Сессии помечены `synthetic` в notes. Использовать только для
проверки кода.

## Phase 2: dataset pipeline

```python
from aeroml.dataset.records import load_dataset, unusable_sessions
from aeroml.dataset.windows import attack_windows, continuous_windows, balance_report
from aeroml.dataset.splits import group_split, unknown_client_split
from aeroml.dataset.normalize import Normalizer
from aeroml.dataset.statistics import dataset_report, missingness, clipping_rate

sessions = load_dataset("plugins/GrimAC/datasets")
for session, reason in unusable_sessions("plugins/GrimAC/datasets"):
    print(session.metadata.session_id, reason)   # review, а не молчаливый пропуск

index = attack_windows(sessions)                 # 20 до + attack + 10 после
split = group_split(index, group_by=("player",), seed=0)
normalizer = Normalizer.fit(index.encode(split.train.tolist()))
```

Окна не материализуются: `WindowIndex` хранит ссылки `(session, start, length)` и вырезает
raw значения только для нужного батча. Сто тысяч перекрывающихся окон иначе заняли бы гигабайты.

Loader строгий к схеме и терпимый к аварии: оборванная последняя строка отбрасывается и
отмечается в `quality`, но ничего не достраивается. Пропущенный tick (переполненная очередь
recorder) трактуется как граница сегмента — окно через дыру не строится никогда.

### Правила split — это не рекомендация

**НИКОГДА не делать случайный train/test split отдельных окон одной сессии.** Окна одной
сессии перекрываются, а движение конкретного игрока — это отпечаток. Случайный split измеряет,
насколько модель запомнила игрока, а не насколько она детектирует поведение.

Минимум — группировка по `session`, по умолчанию по `player`. `group_split` отказывается
работать, если `group_by` не содержит ни того, ни другого. `verify()` падает, если группа или
сессия попала в два fold сразу, и вызывается автоматически после каждого split.

Folds: `train`, `validation`, `calibration`, `test`. Calibration отделён от validation и test
намеренно — temperature, подобранная на том же наборе, на котором выбиралась эпоха, выглядит
лучше, чем есть.

### Unknown-client split

Единственная проверка, отвечающая на вопрос про новый чит:

```python
split = unknown_client_split(index, ["clientD"], group_by=("player",))
```

Все окна held-out client family (со всеми configurations) уходят в test. Если игрок писался и
на held-out client, и на другом, **вся его группа** уходит в test: половина игрока в training
протекла бы в тот самый benchmark, который должен измерять обобщение. Сколько окон утянуто
группой — записано в `split.manifest["windowsPulledInByGroup"]`.

Результаты known-client и unknown-client публиковать **отдельно**. Разрыв между ними и есть
заявление об обобщении.

### Normalization

Fit только на training fold, статистики считаются только по known значениям (иначе канал,
который обычно unknown, притягивается собственными пропусками к нулю), unknown после
нормализации остаётся строго нулём. Mask channels проходят без изменений.
`normalizer.never_observed()` перечисляет каналы, которых training не видел ни разу.

## Phase 3: модель

Temporal ConvNet: dilated 1D residual blocks, GroupNorm, attention pooling, MLP heads.
Не transformer: последовательности здесь в десятки сэмплов, сигнал локальный, а ONNX export
такой сети предсказуем. GroupNorm вместо BatchNorm потому, что сервер считает по одному окну.

| Preset | Окно | Ширина | Блоков | Параметров | Receptive field |
| --- | ---: | ---: | ---: | ---: | ---: |
| Flash | 31 (attack) | 64 | 4 | 112 515 | 61 |
| Pro | 96 (continuous) | 128 | 6 | 636 163 | 253 |

Receptive field у обоих пресетов покрывает всё окно: модель видит связь между началом наводки
и самим ударом, а не только локальный участок.

```bash
cd ml
python -m aeroml.training.train /path/to/datasets ./bundles/flash \
    --window attack --preset flash --epochs 30 --heads overall aimAssist
python -m aeroml.training.train /path/to/datasets ./bundles/flash-unknown-d \
    --holdout-clients clientD
```

Порядок операций в `train.py` важнее гиперпараметров: сначала split по группам, затем
normalization по training fold, затем обучение, затем calibration на fold, который обучение не
видело, затем evaluation на fold, который не видели ни обучение, ни calibration.

Head без положительных примеров в training **не обучается и не публикуется**. Head, который
всегда отвечает «нет», потому что никогда не видел «да», всё равно попал бы в ответ как
уверенное суждение. Список необученных heads пишется в `provenance.evaluation.headsNotTrained`.

Первый этап — `LEGIT` против `AIM_ASSIST`. На таком датасете heads `overall` и `aimAssist`
обучаются на одной и той же метке и не являются независимыми сигналами; это видно в
`splitReport` и должно так и пониматься, пока не появятся другие cheat families.

AutoClicker на rotation data не обучается. Это отдельная временная модель по attack/swing
intervals, burst structure и variance; в этой ветке её нет.

## Evaluation

Accuracy не считается нигде. Детектор работает против потока, который почти целиком
легитимен, поэтому решают:

```text
ROC-AUC, PR-AUC
TPR @ FPR = 0.1% / 0.01% / 0.001%
false positives per legit combat hour
median cheat detection time (и доля обнаруженных сессий)
известный клиент vs неизвестный клиент — отдельно
```

`evaluate()` сам отмечает оценку как ненадёжную, если legit-окон меньше, чем нужно для
заявленного FPR (`negatives_needed`: ~10k окон для 0.1%, ~1M для 0.001%), и прикладывает
Wilson-интервал для TPR. Заявление «TPR 90% при FPR 0.001%» на пяти сотнях legit-окон —
это шум, а не результат.

`detection_times` считает время до срабатывания с параметром `consecutive`, повторяющим
логику Risk Engine: один всплеск детекцией не считается.

## Calibration

Сырой sigmoid — ранжирующий score, а не вероятность. Risk Engine умножает его на вес и
накапливает, поэтому если 0.9 не значит «девять раз из десяти», пороги оператора не значат
ничего. Temperature scaling делит logit на один скаляр: ранжирование (и ROC-AUC) не меняется,
меняется только калибровка. Подбирается на отдельном calibration fold.

Если temperature не улучшает likelihood, bundle публикуется как `calibrated: false`, и
Risk Engine по умолчанию (`neural.risk.accept-uncalibrated: false`) не принимает от него
evidence вообще.

## Model bundle

Развёртываемая единица — директория:

```text
bundle/
    manifest.json    modelVersion, featureSchemaVersion, channels, sequenceLength, window,
                     heads, normalization, calibration, provenance
    model.onnx
```

Bundle самоописателен намеренно: service обязан уметь отклонить запрос с чужой feature schema,
не обращаясь к репозиторию, а оператор — понять по артефакту, какой dataset, split и commit
его породили. Export не запишет bundle, если ONNX-граф расходится с PyTorch-весами.

`provenance` содержит `datasetVersion`, `datasetRoot`, `gitCommit`, `trainingConfig`,
`splitManifest`, `evaluation` (folds, история, split report, missingness, необученные heads).

## Phase 4: inference service

```bash
python -m aeroml.service.app --flash ./bundles/flash --host 127.0.0.1 --port 8080
python -m aeroml.service.app --flash ./bundles/flash --pro ./bundles/pro --framework fastapi
```

`runtime.py` содержит весь протокол и тестируется без веб-фреймворка и без onnxruntime.
`server.py` — stdlib HTTP (достаточно для теста и небольшого развёртывания), `app.py` — FastAPI.

Политика отказов: всё, что нельзя исправить повтором того же payload (несовместимый
protocolVersion/featureSchemaVersion/featureCount, незагруженная модель, чужая длина или тип
окна, нефинитные features) — **422**. Java-клиент считает это постоянным отказом и перестаёт
долбить endpoint. Временные проблемы — 503.

Сервис никого не аутентифицирует. Поднимать на приватном интерфейсе или за тем, что
аутентифицирует: endpoint принимает телеметрию и возвращает суждения об игроках.

## Инструменты качества датасета

Реального labeled датасета пока нет. До его появления ни одна метрика детектора не является
утверждением о людях; порядок записи — в [docs/data-collection-protocol.md](../docs/data-collection-protocol.md),
условия допуска — в [docs/model-promotion.md](../docs/model-promotion.md).

```bash
# 0. одна сессия сразу после записи: стоит ли её оставлять
python -m aeroml.tools.inspect_session <dataset> <sessionId>

# 1. что вообще записано: объём, покрытие, качество каждой сессии, распределения, feature audit
python -m aeroml.tools.audit_dataset <dataset> --features --json reports/audit.json

# 2. кандидаты на ручную проверку (это НЕ манифест: никто ещё ничего не проверял)
python -m aeroml.tools.audit_dataset <dataset> --write-review-candidates reports/candidates.json

# 3. перевод проверенной человеком записи в golden manifest
python -m aeroml.tools.review_session <dataset> ../datasets/manifests/golden-v1.json \
    --session <id> --expected-label LEGIT --reviewer <псевдоним> --notes "что видно в записи"

# 4. обучение
python -m aeroml.training.train <dataset> ./bundles/flash-v1 \
    --window attack --preset flash --heads overall aimAssist --split player --seed 42

# 5. утечки: восстанавливает split по sha256 сессий и переобучает normalization на train
python -m aeroml.tools.check_leakage <dataset> ./bundles/flash-v1     # exit code != 0 при нарушении

# 6. оценка: window + session метрики, risk-симуляция, hard negatives/positives, predictions
python -m aeroml.tools.evaluate_model ./bundles/flash-v1 <dataset> --fold test --output reports/eval

# 7. пороги под ограничение, а не под accuracy
python -m aeroml.tools.search_thresholds ./bundles/flash-v1 <dataset> --max-confirmed-per-100h 1.0

# 8. сравнение двух моделей на записях, которых не видела ни одна из них
python -m aeroml.tools.compare_models ./bundles/a ./bundles/b <dataset> --output reports/compare.json
```

### Что делает аудит

`audit_dataset` выдаёт версии схемы, число сессий по меткам, игроков, client families, cheat
families, конфигураций, версий протокола, боевые часы LEGIT/CHEAT, кадры, attack и continuous окна,
неполные сессии, сессии с потерянными записями, нарушениями хронологии и отсутствующей
телеметрией. Для CHEAT отдельно печатается дерево `family → client → configuration`: если корпус
фактически состоит из одной настройки одного клиента, это видно сразу, а не после обучения.

Каждая сессия получает вердикт `GOOD` / `REVIEW` / `UNUSABLE` с явными причинами
(`incomplete metadata`, `dropped records`, `too short`, `no combat`, `too few attacks`,
`missing target geometry`, `large sampling gaps`, `chronology violation`, `unsupported schema`).
Ничего не удаляется автоматически: инструмент, молча выбрасывающий неудобные записи, убирает
ровно те сессии, против которых детектор и нужно измерять.

### Feature audit и label leakage

Для каждого канала считаются known/missing, mean, std, min, max, p01, p50, p99 и доля клиппинга,
отдельно по LEGIT и CHEAT. Находки:

```text
ALWAYS_MISSING       канал ни разу не был известен
CONSTANT             канал всегда одно и то же значение
CLIPPED              канал постоянно упирается в границу диапазона
STRUCTURAL_LEAKAGE   канал известен в одном классе и отсутствует в другом:
                     LEGIT и CHEAT записывались разными путями, метка читается напрямую
SEPARABLE            распределения классов почти не пересекаются — повод разобраться,
                     а не вывод: настоящий сильный признак выглядит так же
```

Отдельно проверяется, что вход модели вообще не может кодировать метку. Проверка не только по
именам полей (`check_feature_schema`), но и эмпирическая (`check_encoder_invariance`): окно
переносится на 1024 блока, перенумеровываются entity/transaction/tick, меняются protocol,
held item и тип сущности, выставляются флаги Grim — выход энкодера обязан остаться
побитово тем же. Любое изменение означает, что в модель попало что-то абсолютное или
идентифицирующее.

### Risk-симуляция

`aeroml.evaluation.risk_sim` — точное повторение Java `RiskEngine`, а не приближение:
сервер затухает риск на каждом сэмпле, симулятор — только на предсказаниях, и экспоненциальное
затухание композируется, поэтому числа совпадают, а не «близки». Парность удерживает общий
fixture `tests/data/risk_golden.json`, который проверяют обе стороны
(`RiskSimulationGoldenTest` и `tests/test_risk_sim.py`); перегенерировать его командой
`python -m aeroml.tools.make_risk_golden`.

Симуляция даёт то, чего не даёт ROC-AUC: сколько честных игроков дошло бы до WATCH/SUSPICIOUS/
CONFIRMED за час боя, и через сколько секунд туда доходит читер — отдельно для известного и
неизвестного клиента.

## Anti-poisoning и версионирование

Production predictions **никогда** не становятся training labels автоматически. `WindowIndex.labels()`
падает на `UNLABELED` сессии с явным сообщением. Evidence snapshots, которые пишет сервер,
помечены `label: UNLABELED` / `labelSource: PRODUCTION_UNLABELED`: они повод для review, не вердикт.

Каждый training run сохраняет `datasetVersion`, `featureSchemaVersion`, `modelVersion`,
`gitCommit`, `trainingConfig`, split manifest, normalization и calibration параметры.
Dataset версионируется отдельно (`dataset-v1`, `dataset-v2`), схема features — своим номером.

## Adversarial и generalization testing

Модель обязана проверяться на слабых конфигурациях: низкая скорость наводки, высокое
сглаживание, малый FOV, рандомизация, редкая активация, частичная помощь. Эти hard positives
ценнее сильных читов.

Hard negatives так же обязательны: сильные PvP игроки, крайние значения sensitivity, jitter и
butterfly clicking, высокий ping и jitter, лаги сервера, резкие flick shots, разные версии
протокола. Без них низкий FPR ничего не значит.

## Тесты

```bash
cd ml && python -m pytest -q
```

Покрыто: контракт схемы и его расхождение, cross-language encoder fixture, загрузка и
повреждённые сессии, границы сегментов и дыры в tick, правила окон, отсутствие утечек в
splits и unknown-client split, normalization на training fold, метрики с проверяемыми
значениями, calibration (восстановление известной температуры, неизменность ранжирования),
протокол сервиса и HTTP round trip. Тесты, требующие torch/onnxruntime, пропускаются чисто.

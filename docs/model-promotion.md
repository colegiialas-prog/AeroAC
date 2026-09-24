# Aero AC — допуск модели

Документ о том, когда модель **можно** и когда **нельзя** рекомендовать даже для
monitor-only production. Автоматического promotion/deploy нет и не планируется на этом этапе:
все проверки ниже выполняет и подписывает человек.

Смежное: [архитектура](neural-architecture.md), [inference](inference.md),
[risk engine](risk-and-mitigation.md), [сбор данных](data-collection-protocol.md),
[ML pipeline](../ml/README.md).

## Уровни допуска

| Уровень | Что означает | Что включается |
| --- | --- | --- |
| `EXPERIMENTAL` | Модель существует и проходит технические проверки | ничего; только offline-отчёты |
| `MONITOR` | Можно смотреть на предсказания в production | `neural.inference.enabled` |
| `RISK` | Можно накапливать risk и показывать состояния | `neural.risk.enabled` |
| `MITIGATION` | Можно вмешиваться в бой | `neural.mitigation.enabled` |

Уровень присваивается модели (bundle), а не репозиторию. Новый bundle начинает с
`EXPERIMENTAL` независимо от того, чем был его предшественник.

## Блокирующие условия

Модель **нельзя** рекомендовать даже для `MONITOR`, если выполняется хотя бы одно:

```text
1. падают schema tests                      FeatureSchemaTest / tests/test_schema.py
2. падает encoder golden test               FeatureEncoderGoldenTest / tests/test_features.py
3. расходится ONNX parity                   export отказывается писать bundle
4. calibration невалидна                    calibrated=false либо не улучшила likelihood
5. найдена leakage в датасете или split      aeroml.tools.check_leakage exit code != 0
6. test fold слишком мал                    evaluate помечает FPR как ненадёжный
7. отсутствует unknown-client evaluation     нет held-out client family
```

Каждое проверяется командой, а не мнением:

```bash
cd ml && python -m pytest -q                       # 1, 2, 3 (и весь остальной контракт)
cd .. && ./gradlew :common:test                    # 1, 2 и risk parity со стороны JVM
cd ml && python -m aeroml.tools.check_leakage <dataset> <bundle>   # 5, exit code != 0 при нарушении
python -m aeroml.tools.evaluate_model <bundle> <dataset> --fold test   # 4, 6, 7
```

`check_leakage` проверяет по содержимому, а не по описанию: он восстанавливает split из
манифеста bundle по sha256 каждой сессии, заново обучает normalization на train fold и сравнивает
с тем, что лежит в bundle. Модель, чьи статистики не совпадают с её собственным train fold,
обучалась не на том, что заявляет.

### Почему «test fold слишком мал» — блокирующее условие

Заявление вида «TPR 90% при FPR 0.001%» на нескольких сотнях legit-окон — это шум.
`negatives_needed` даёт порядок: ~10 000 legit-окон для 0.1%, ~1 000 000 для 0.001%.
`evaluate` сам помечает такую оценку `reliable: 0` и пишет причину в `notes`. Отчёт с
`reliable: 0` по всем целевым FPR не является измерением.

### Почему unknown-client обязателен

Если в датасете ≥2 client families, хотя бы одна должна быть полностью held-out
(`--holdout-clients`). Если family одна, отчёт обязан написать

```text
UNKNOWN CLIENT GENERALIZATION NOT MEASURABLE
```

и это само по себе блокирует `MONITOR`: без held-out клиента известно только то, что модель
узнаёт конкретную сборку, а не поведение.

## Требования по уровням

### EXPERIMENTAL → MONITOR

```text
все блокирующие условия сняты
bundle содержит splitManifest со strategy, groupBy и sessionDigests
calibration: calibrated=true и nllAfter <= nllBefore
известный клиент:    ROC-AUC и PR-AUC опубликованы
неизвестный клиент:  ROC-AUC и PR-AUC опубликованы ОТДЕЛЬНО
session-level: доля CHEAT сессий, доходящих до WATCH/SUSPICIOUS, и медианная задержка
false positives: измерены на legit test fold в пересчёте на час боя
hard negatives и hard positives сохранены и просмотрены человеком
```

Отчёты, которые для этого нужны, целиком производит один прогон:

```bash
python -m aeroml.tools.evaluate_model <bundle> <dataset> --fold test --output reports/eval.json
```

Он пишет `predictions.jsonl`, `top_false_positive_sessions.json`,
`hard_positive_sessions.json` и `risk_simulation.json`.

### MONITOR → RISK

Дополнительно:

```text
пороги risk выбраны по offline-симуляции, а не по ощущению
aeroml.tools.search_thresholds отработал с явным constraint по CONFIRMED FP
на legit test fold ни один игрок не доходит до CONFIRMED
измерено, сколько legit-игроков доходит до WATCH за час боя
результат симуляции воспроизводится Java: RiskSimulationGoldenTest зелёный
```

`search_thresholds` **не** максимизирует accuracy. Он принимает ограничение
(`--max-confirmed-per-100h`) и среди удовлетворяющих ему конфигураций предлагает те, что
детектируют больше и быстрее. Он ничего не меняет в конфиге сервера: это список предложений.

### RISK → MITIGATION

Планка здесь значительно строже, и одних offline-чисел недостаточно:

```text
неделя и более наблюдения RISK на реальном сервере без единого ложного CONFIRMED
разобраны вручную все сессии из top_false_positive_sessions
измерен false positive rate на живом трафике, а не только на test fold
unknown-client detection не хуже, чем на известных клиентах, с запасом
оператор письменно принял риск: mitigation влияет на честных игроков при ошибке
mitigation включается с правилом OBSERVE (cancel-attacks: false) в первую очередь
```

Ни один уровень не даёт права банить. Решение о наказании остаётся за оператором и
существующей системой punishments deterministic-проверок.

## Regression gate

Новая модель не должна ухудшать то, что уже работало. Сравнение делается на одних и тех же
записях, которых не видела ни одна из моделей:

```bash
python -m aeroml.tools.compare_models <bundleA> <bundleB> <dataset> --output reports/compare.json
```

Отчёт разделяет `improvements` и `regressions` по ROC-AUC, PR-AUC, TPR@FPR, false positives
на час, known/unknown client, медианной задержке детекции и результату risk-симуляции.

Regression в любом из следующих пунктов блокирует замену модели того же уровня:

```text
false positives на час боя выросли
unknown-client TPR@FPR упал
медианное время до SUSPICIOUS выросло
доля CHEAT сессий, доходящих до SUSPICIOUS, упала
```

Для честного сравнения нужен golden manifest — набор сессий, проверенных человеком
(`datasets/manifests/golden-v1.json`). Манифест хранит sha256 каждой записи, имя проверяющего,
время и заметку; запись, изменившаяся после проверки, перестаёт совпадать. Ни один инструмент
в репозитории не может добавить туда сессию сам: `aeroml.tools.audit_dataset` умеет только
выписать **кандидатов на проверку**, а перевести кандидата в манифест может лишь
`aeroml.tools.review_session` с явными `--reviewer` и `--notes`.

## Чего делать нельзя

* Использовать production predictions как обучающие метки. Сессии остаются `UNLABELED` до
  ручной проверки, `WindowIndex.labels()` падает на `UNLABELED` с явным сообщением.
* Подбирать пороги на test fold. `search_thresholds` читает только validation и не имеет опции
  выбрать calibration или test.
* Перегенерировать golden fixture, чтобы тест стал зелёным. Fixture существует ровно для того,
  чтобы поймать расхождение Java и Python; перегенерация ради одной стороны уничтожает смысл.
* Объявлять качество детектора до появления реального labeled датасета. Синтетический
  генератор — проверка кода, а не данные: обученная на нём модель не говорит ничего о людях.

## Текущий статус

На момент написания реального labeled датасета **нет**. Собраны только синтетические сессии,
и `datasets/manifests/golden-v1.json` намеренно пуст со статусом `PENDING_REAL_DATA`.
Соответственно ни одна модель не имеет уровня выше `EXPERIMENTAL`, и никаких заявлений о
качестве детектора сделать нельзя. Порядок сбора настоящих записей —
в [data-collection-protocol.md](data-collection-protocol.md).

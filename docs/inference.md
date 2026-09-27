# Aero AC inference

Phase 4: bounded async inference и мониторинг. Никаких автоматических наказаний.
Risk Engine описан в [risk-and-mitigation.md](risk-and-mitigation.md), обучение модели —
в [ml/README.md](../ml/README.md).

## Что включается

```yaml
neural:
    enabled: true
    inference:
        enabled: true
        mode: remote
        endpoint: "http://127.0.0.1:8080/predict"
        timeout-ms: 300
        max-in-flight: 8
        min-interval-ms: 500
        flash:
            window: attack
            sequence-length: 31
        pro:
            enabled: false
            window: continuous
            sequence-length: 96
            trigger-overall: 0.5
            min-interval-ms: 2000
```

Затем `/aero reload`. `neural.enabled` — общий выключатель модуля; inference не работает без него.
Неразбираемый или не-HTTP(S) endpoint **выключает inference**, а не отправляет запросы куда-то ещё.
`mode` кроме `remote` тоже выключает inference: local ONNX Runtime внутри JVM не реализован,
и признавать это честнее, чем молча использовать remote.

`/neural status` показывает фактическое состояние: generation, включённые стадии, endpoint,
окна, и health-счётчики клиента.

## Когда отправляется запрос

Телеметрия собирается только для игрока в бою (см. [dataset.md](dataset.md)), поэтому у игрока
вне боя запросов нет вовсе.

* **Flash**, `window: attack` — на каждое завершённое attack window (`attack-before` сэмплов до
  атаки, сама атака, `attack-after` после), но не чаще `min-interval-ms`. Каждое окно
  предлагается один раз: повторной отправки того же удара нет.
* **Flash**, `window: continuous` — последние `sequence-length` подряд идущих сэмплов внутри
  одного сегмента, не чаще `min-interval-ms`.
* **Pro** — только эскалация: последний Flash-ответ должен иметь `overall >= trigger-overall`,
  и с прошлого Pro-запроса прошло `pro.min-interval-ms`. Pro не имеет своего расписания.

Длина attack-окна не настраивается отдельно: `flash.sequence-length` для `window: attack`
приводится к `attack-before + 1 + attack-after`. Иначе сервис отклонял бы каждый запрос.

## Протокол

`POST <endpoint>`, `Content-Type: application/json`. Дополнительно передаются заголовки
`X-Aero-Protocol` и `X-Aero-Feature-Schema` — те же значения, что в теле.

```json
{
  "protocolVersion": 1,
  "featureSchemaVersion": 4,
  "requestId": 1234,
  "model": "flash",
  "window": "attack",
  "sequenceLength": 31,
  "featureCount": 75,
  "features": [ ... sequenceLength * featureCount значений ... ]
}
```

`features` — row-major `[t][channel]`, всегда конечные числа. Ответ:

```json
{
  "protocolVersion": 1,
  "featureSchemaVersion": 4,
  "requestId": 1234,
  "model": "flash",
  "modelVersion": "aero-flash-attack-20260513-101500-1a2b3c4d",
  "calibrated": true,
  "heads": {"overall": 0.91, "aimAssist": 0.95}
}
```

Heads — пары имя/значение. Модель может добавить head без единого изменения Java-протокола:
неизвестные имена доносятся до оператора как есть. `overall` обязателен. Любое значение вне
`[0,1]`, нечисловой head, отсутствующий `modelVersion`, чужой `requestId`, несовпадающая
версия протокола или схемы — ответ отбрасывается и засчитывается как неисправность сервиса.

Ограничения клиента: максимум 16 heads, 64 символа на имя head, 128 на `modelVersion`,
64 KiB на всё тело ответа. Редиректы не выполняются.

### Версии

`protocolVersion` — форма запроса/ответа. `featureSchemaVersion` — порядок и смысл каналов,
задан в `ml/schema/feature_schema_v1.json`. `schemaVersion` raw dataset — третья, независимая
величина; она описывает файлы на диске, а не вход модели.

Сервис обязан **отклонять** несовместимую схему, а не интерпретировать чужие features.
Реализация отвечает 422 на всё, что нельзя исправить повтором того же payload: чужой
protocolVersion/featureSchemaVersion/featureCount, незагруженная модель, другая длина окна,
другой тип окна, нефинитные значения. Java-клиент считает 422/409/429 постоянным отказом и
учитывает отдельно от транспортных сбоев.

### Что никогда не попадает в запрос

Username, UUID, pseudonym, entity/transaction/session identifiers, абсолютные координаты,
версия протокола клиента, held item и evidence существующих Grim checks. Первое — приватность
и запрет учить личность; последнее — чтобы модель не выучила пороги deterministic проверок
вместо поведения. Полный список каналов и обоснование — в `ml/schema/feature_schema_v1.json`.

## Потоки

```text
player event loop     собрать sample -> собрать окно -> закодировать -> отдать клиенту -> продолжить
inference threads     JSON, HTTP exchange, разбор ответа
player event loop     применить результат через runSafely
```

Packet thread никогда не ждёт сеть. `InferenceGateway` строит запрос и отдаёт его клиенту;
дальше всё происходит на потоках клиента. Результат возвращается через `AeroPlayer.runSafely`
и проверяется: игрок ещё подключён, collector тот же (generation не менялся reload-ом),
`requestId` больше последнего принятого. `PredictionTrail` отбрасывает опоздавшие и
дублирующие ответы — поздний ответ не может перезаписать более новый.

`max-in-flight` — жёсткий предел одновременных запросов на весь сервер. Когда предел достигнут,
запрос **не ставится в очередь и не отправляется**: он просто пропускается и считается в
`shed`. Очередь бы только удлиняла задержку и в итоге всё равно упиралась в timeout.

## Поведение при отказе

Timeout, недоступный сервис, 429, мусор в ответе, несовместимая схема — всё это события
здоровья сервиса. Ни одно из них:

* не является evidence о читерстве;
* не отменяет и не ослабляет ни одну deterministic проверку;
* не блокирует обработку пакетов.

Счётчики видны в `/neural status`: `sent`, `ok`, `timeout`, `rejected` (постоянный отказ),
`failed` (транспорт), `shed` (не отправлено из-за предела), средняя задержка, последняя ошибка.

Если сервис недоступен всё время, Aero AC работает ровно как без ML: deterministic checks,
их violations, alerts и punishments не меняются.

## Local inference

Архитектура допускает будущий локальный режим: `InferenceClient` — интерфейс, `HttpInferenceClient` —
одна реализация, `ModelKind`/`ModelWindow`/`FeatureEncoder` от транспорта не зависят.
Локальный ONNX Runtime внутри JVM потребует отдельной зависимости и своего бюджета по CPU;
в этой ветке его нет, и `mode: local` не включает ничего.

Разделение Flash/Pro уже заложено в конфиге и протоколе, поэтому переезд «Flash локально,
Pro удалённо» не потребует изменения телеметрии.

## Проверка перед включением

1. Поднять сервис и убедиться, что `/health` отдаёт ту же `featureSchemaVersion`.
2. Включить inference на тестовом сервере, смотреть `/neural status` и `/neural monitor`.
3. Убедиться, что `rejected` равен нулю: ненулевой означает несовместимость, которую не
   исправит повтор.
4. Измерить долю `shed` и среднюю задержку под реальной нагрузкой, до включения `risk`.
5. Только после этого — Phase 5 (`neural.risk.enabled`), и по-прежнему без наказаний.

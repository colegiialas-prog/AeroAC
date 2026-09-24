# Синтетический пример session

Это serializer fixture, созданный `DatasetExampleTest` через рабочий `DatasetJson`.
Он содержит 33 frames и один attack event, tick 20. Значения искусственные, большая
часть полей null. Это НЕ запись Minecraft и НЕ обучающие данные.

* [Metadata](metadata/session-00000000-0000-4000-8000-000000000001.json)
* [Raw JSONL](raw/session-00000000-0000-4000-8000-000000000001.jsonl)

Тест также генерирует эти файлы в `common/build/neural-example/`. DatasetVersion=dataset-v1,
schemaVersion=1. Label UNLABELED; notes и closeReason маркируют синтетический источник.

Python-загрузчик читает этот пример как любую другую сессию:

```bash
cd ml
python -c "from aeroml.dataset.records import load_session; s = load_session('../docs/examples/neural-session/metadata/session-00000000-0000-4000-8000-000000000001.json'); print(len(s), s.quality.describe(), s.metadata.label)"
```

Из этих 33 кадров строится ровно одно полное attack window (20 до + атака + 10 после),
поэтому пример годится как проверка совместимости форматов, но не как датасет.
Для прогона всего пайплайна используйте `python -m aeroml.tools.make_synthetic`.

"""The external training service contract: states, request validation and human messages.

One module owns three things so the HTTP layer, the job manager and the training subprocess
cannot drift apart:

* the real job states and the order the pipeline moves through them;
* what a ``POST /training/jobs`` request may contain, and every reason it is refused;
* the Russian text an operator reads. Machine fields (``state``, ``errorCode``, ``jobId``)
  stay in English/dot.case; only ``message`` is written for a person.

Nothing here trains, imports torch or writes files: it is importable from the stdlib server and
from the training worker alike.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

CONTRACT_VERSION = 1
RAW_SCHEMA_VERSION = 2

# ---------------------------------------------------------------------------
# Real job states. The Java side keeps its own names; ``java_status`` maps onto them so the
# admin screen never has to know this enum.
#
# ``IDLE`` is not a job state: a job is never idle. It is the answer ``GET /training/status`` gives
# when the service holds no active job, so the first field an operator or the Java client reads is
# always one of the contract states instead of a missing key.
IDLE = "IDLE"
QUEUED = "QUEUED"
AUDITING = "AUDITING"
PREPARING = "PREPARING"
TRAINING = "TRAINING"
CALIBRATING = "CALIBRATING"
EVALUATING = "EVALUATING"
EXPORTING = "EXPORTING"
COMPLETED = "COMPLETED"
FAILED = "FAILED"
CANCELLED = "CANCELLED"

STATES = (IDLE, QUEUED, AUDITING, PREPARING, TRAINING, CALIBRATING, EVALUATING, EXPORTING,
          COMPLETED, FAILED, CANCELLED)
ACTIVE_STATES = frozenset({AUDITING, PREPARING, TRAINING, CALIBRATING, EVALUATING, EXPORTING})
RUNNING_STATES = ACTIVE_STATES | {QUEUED}
TERMINAL_STATES = frozenset({COMPLETED, FAILED, CANCELLED})

# The coarse status the JVM already knows (``TrainingJob.Status``). Audit, preparation, calibration
# and export collapse onto the nearest state that exists in the plugin; ``status`` keeps the real
# state for an operator while ``javaStatus`` stays parseable by the Java enum.
JAVA_STATUS = {
    IDLE: "IDLE",
    QUEUED: "QUEUED", AUDITING: "QUEUED", PREPARING: "QUEUED", TRAINING: "TRAINING",
    CALIBRATING: "TRAINING", EVALUATING: "EVALUATING", EXPORTING: "EVALUATING",
    COMPLETED: "EVALUATED", FAILED: "FAILED", CANCELLED: "FAILED",
}

# Where a stage sits on the 0..1 progress bar. Epoch progress interpolates inside TRAINING.
STAGE_BASE_PROGRESS = {
    IDLE: 0.0, QUEUED: 0.0, AUDITING: 0.02, PREPARING: 0.05, TRAINING: 0.10,
    CALIBRATING: 0.80, EVALUATING: 0.86, EXPORTING: 0.94, COMPLETED: 1.0,
}
TRAINING_CEILING = 0.78

# The worker reports these stage names; they are the only values that move a job forward.
STAGE_TO_STATE = {
    "queued": QUEUED,
    "auditing": AUDITING,
    "preparing": PREPARING,
    "training": TRAINING,
    "calibrating": CALIBRATING,
    "evaluating": EVALUATING,
    "exporting": EXPORTING,
    "completed": COMPLETED,
}

STAGE_ORDER = ("auditing", "preparing", "training", "calibrating", "evaluating", "exporting", "completed")

# ---------------------------------------------------------------------------
# Preset rules. Flash reads one completed attack window (31 samples); Pro is the escalation model
# and reads 96 continuous samples. These are properties of the existing TCN presets, not knobs.
PRESETS = {
    "flash": {"modelKind": "flash", "window": "attack", "sequenceLength": 31},
    "pro": {"modelKind": "pro", "window": "continuous", "sequenceLength": 96},
}
WINDOW_FOR_PRESET = {name: rule["window"] for name, rule in PRESETS.items()}
LENGTH_FOR_PRESET = {name: rule["sequenceLength"] for name, rule in PRESETS.items()}
DEFAULT_HEADS = ("overall", "aimAssist")
REQUIRED_HEAD = "overall"

PROMOTION_STATUS = "CANDIDATE_REQUIRES_HUMAN_REVIEW"
AUTOMATIC_DEPLOYMENT = False
UNLABELED_POLICY = "excluded-from-training"

DATASET_NAME_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")


# ---------------------------------------------------------------------------
# Human-readable messages. Every refusal an operator can trigger has one.
MESSAGES: dict[str, str] = {
    "not_found": "Такого эндпоинта нет: {path}. Доступны: {available}.",
    "malformed_json": "Некорректный JSON в теле запроса: {detail}.",
    "body_too_large": "Тело запроса больше допустимых {limit} байт.",
    "body_required": "Тело запроса должно быть JSON-объектом.",
    "missing_field": "В запросе отсутствует обязательное поле «{field}».",
    "invalid_type": "Поле «{field}» должно быть {expected}; получено {actual}.",
    "unknown_field": "Поле «{field}» не поддерживается контрактом.",
    "unknown_preset": "Неизвестный пресет «{preset}». Доступны: flash (окно attack, 31 сэмпл) и pro (окно continuous, 96 сэмплов).",
    "preset_window_mismatch": "Пресет {preset} обучается только на окне {expectedWindow}; в запросе указано «{window}». Пара пресет/окно не меняется: flash — attack 31, pro — continuous 96.",
    "feature_schema_mismatch": "Версия схемы признаков {requested} не совпадает с развёрнутой схемой {deployed}. Данные не переинтерпретируются: обучите модель под развёрнутую схему или разверните совместимую.",
    "unknown_head": "Head «{head}» не объявлен в схеме признаков. Доступны: {available}.",
    "overall_required": "Head «overall» обязателен: его читает Risk Engine.",
    "dataset_not_allowed": "Датасет «{dataset}» вне разрешённых каталогов ({roots}). Абсолютные пути и выход за пределы каталогов датасетов запрещены.",
    "dataset_missing": "Датасет «{dataset}» не найден или не содержит каталог metadata/ с записями сессий.",
    "dataset_unknown": "Датасет «{dataset}» неизвестен. Поле dataset принимает только имя датасета из разрешённого каталога сервера; произвольные пути запрещены. Доступные датасеты: {available}.",
    "dataset_insufficient": "В датасете «{dataset}» недостаточно размеченных сессий: LEGIT={legit}, CHEAT={cheat}. Для обучения нужны обе метки; сессии UNLABELED в обучение не попадают.",
    "dataset_synthetic": "Датасет «{dataset}» помечен как синтетический. Обучение на синтетике разрешено только для smoke-проверок: передайте allowSynthetic=true, и результат будет помечен как pipeline-smoke-only.",
    "epochs_range": "Число эпох должно быть целым от 1 до {maxEpochs}; получено {value}.",
    "batch_size_range": "Размер батча должен быть целым от 1 до {maxBatchSize}; получено {value}.",
    "stride_range": "Шаг окон должен быть целым от 1 до {maxStride}; получено {value}.",
    "seed_range": "seed должен быть целым от 0 до {maxSeed}; получено {value}.",
    "heads_empty": "Список heads пуст: нужен хотя бы head «overall».",
    "queue_full": "Очередь обучения заполнена ({depth} из {maxDepth}). Дождитесь завершения текущих задач и отправьте запрос снова.",
    "job_count_limit": "Достигнут предел числа задач сервиса ({maxJobs}). Отмените или дождитесь завершения старых задач: они занимают журнал и слоты.",
    "unauthorized": "Требуется токен доступа к сервису обучения: заголовок Authorization: Bearer <token> или X-Auth-Token. Сервис открыт только на loopback; для удалённого доступа токен обязателен.",
    "job_unknown": "Задача «{jobId}» не найдена. Список задач: GET /training/status.",
    "job_not_terminal": "Задача «{jobId}» ещё не завершена (состояние {state}). Результат доступен только после COMPLETED.",
    "job_no_result": "У задачи «{jobId}» нет результата: она завершилась в состоянии {state}.",
    "job_terminal": "Задача «{jobId}» уже завершена (состояние {state}); отменять нечего.",
    "job_cancel_requested": "Отмена задачи «{jobId}» запрошена. Обучение будет остановлено, неопубликованный бандл кандидатом не станет.",
    "job_timeout": "Обучение прервано: превышен лимит времени {seconds} с. Задача не завершена, бандл не публикуется.",
    "service_restarted": "Сервис обучения был перезапущен во время выполнения задачи; состояние задачи неизвестно, бандл не публикуется. Запустите обучение заново.",
    "internal": "Внутренняя ошибка сервиса обучения ({errorType}). Подробности — в логе задачи.",
}

STAGE_MESSAGES: dict[str, str] = {
    IDLE: "Сервис обучения свободен: активных задач нет.",
    QUEUED: "Задача поставлена в очередь: обучение начнётся, когда освободится слот.",
    AUDITING: "Аудит датасета: качество сессий, разметка, проверки утечек признаков.",
    PREPARING: "Подготовка: окна, сплит по игрокам, нормализация по train-фолду.",
    TRAINING: "Идёт обучение: эпоха {epoch} из {totalEpochs}, train loss {trainLoss}, validation loss {validationLoss}.",
    CALIBRATING: "Калибровка вероятностей на отдельном фолде, который обучение не видело.",
    EVALUATING: "Оценка модели на фолдах validation и test.",
    EXPORTING: "Экспорт в ONNX с проверкой совпадения графа и весов, запись манифеста bundle.",
    COMPLETED: "Обучение, калибровка, оценка и экспорт завершены. Бандл записан как кандидат; автоматического развёртывания нет.",
    CANCELLED: "Задача отменена оператором: бандл не публикуется.",
}

# Exception text -> (errorCode, Russian reason). Matched case-insensitively in order.
FAILURE_MATCHERS: tuple[tuple[str, str, str], ...] = (
    ("split leakage", "split_leakage", "проверка утечек между фолдами не пройдена"),
    ("feature leakage/schema checks failed", "schema_checks_failed", "проверки схемы признаков и инвариантности энкодера не пройдены"),
    ("possible structural label leakage", "structural_leakage", "в признаках найдена структурная утечка метки; запись LEGIT и CHEAT шла разными путями"),
    ("no usable labelled sessions", "no_labelled_sessions", "в датасете нет пригодных размеченных сессий"),
    ("synthetic data is only allowed", "synthetic_not_allowed", "обучение на синтетическом датасете разрешено только с allowSynthetic=true"),
    ("trains only LEGIT vs AIM_ASSIST", "unsupported_cheat_family", "датасет содержит семьи читов, которые этот этап не обучает (нужны LEGIT против AIM_ASSIST)"),
    ("no complete windows", "no_windows", "не набралось ни одного полного окна; запишите сессии длиннее или сократите окно"),
    ("fold is empty", "insufficient_fold_data", "после сплита остался пустой фолд: нужно больше игроков или сессий"),
    ("needs both independent", "insufficient_fold_data", "в одном из фолдов нет обеих меток: нужно больше независимых LEGIT и CHEAT сессий"),
    ("no finite validation score", "no_finite_validation_score", "не удалось выбрать модель: validation-оценка не конечна"),
    ("above configured budget", "cache_budget_exceeded", "датасет слишком велик для настроенного бюджета кэша окон"),
    ("does not look like a dataset root", "dataset_missing", "каталог не похож на корень датасета: нет metadata/"),
    ("differs from PyTorch", "onnx_verification_failed", "экспортированный ONNX-граф не совпал с весами PyTorch; бандл не записан"),
    ("Missing dependency", "training_stack_missing", "не установлен training stack (torch/onnx/onnxruntime); установите ml/requirements-train.txt"),
    ("No module named", "training_stack_missing", "не установлен training stack (torch/onnx/onnxruntime); установите ml/requirements-train.txt"),
)


def human(code: str, **details) -> str:
    """Russian text for an error code, with every placeholder filled or left visible as ``<name>``."""
    template = MESSAGES.get(code) or STAGE_MESSAGES.get(code) or MESSAGES["internal"]
    defaults = {"path": "", "available": "", "roots": "", "detail": "", "value": None,
                "maxEpochs": 200, "maxBatchSize": 4096, "maxStride": 512, "maxSeed": 2 ** 31 - 1,
                "errorType": "Exception", "jobId": "", "state": "", "dataset": "", "head": "",
                "epoch": 0, "totalEpochs": 0, "trainLoss": "н/д", "validationLoss": "н/д",
                "expectedWindow": "attack", "window": "", "preset": "", "legit": 0, "cheat": 0,
                "depth": 0, "maxDepth": 0, "seconds": 0, "requested": 0, "deployed": 0,
                "maxJobs": 32, "field": "", "expected": "", "actual": "", "limit": 0, "reason": ""}
    defaults.update(details)
    try:
        return template.format(**defaults)
    except (KeyError, IndexError):  # pragma: no cover - defensive, a missing placeholder keeps the code
        return template


def stage_message(state: str, **details) -> str:
    return human(state, **details)


def failure_from_exception(error: BaseException) -> tuple[str, str]:
    """Maps a training failure onto an error code and a Russian reason. The technical text stays in the log."""
    text = f"{type(error).__name__}: {error}".strip()
    lowered = text.casefold()
    for needle, code, reason in FAILURE_MATCHERS:
        if needle.casefold() in lowered:
            return code, reason
    return "training_failed", f"обучение остановлено на этапе {details_placeholder(text)}"


def details_placeholder(text: str) -> str:
    return (text.splitlines() or [""])[0][:300] or "без подробностей"


class RequestError(Exception):
    """A refusal an operator caused. ``code`` is machine-readable, ``message`` is Russian."""

    def __init__(self, code: str, status: int = 422, **details) -> None:
        self.code = code
        self.status = status
        self.details = details
        super().__init__(human(code, **details))

    @property
    def message(self) -> str:
        return human(self.code, **self.details)

    def to_dict(self) -> dict:
        return {"error": self.code, "message": self.message}


@dataclass
class JobRequest:
    """A validated ``POST /training/jobs`` body. Only these six fields are required.

    ``dataset`` is a *name* the server resolves inside its own dataset roots. ``dataset_path`` is
    filled in by the server after validation; a client cannot supply either an absolute path or a
    path that escapes a root, and the name must appear in the server's dataset inventory.
    """

    dataset: str
    preset: str
    window: str
    heads: tuple[str, ...]
    feature_schema_version: int
    seed: int
    epochs: int = 30
    batch_size: int = 128
    stride: int = 4
    allow_synthetic: bool = False
    include_review: bool = False
    notes: str = ""
    dataset_path: Path | None = None
    dataset_version: str = ""
    extra: dict = field(default_factory=dict)

    @property
    def sequence_length(self) -> int:
        return LENGTH_FOR_PRESET[self.preset]

    @property
    def model_kind(self) -> str:
        return PRESETS[self.preset]["modelKind"]

    def to_dict(self) -> dict:
        return {
            "dataset": self.dataset,
            "datasetPath": str(self.dataset_path) if self.dataset_path else self.dataset,
            "datasetVersion": self.dataset_version or None,
            "preset": self.preset,
            "modelType": self.model_kind,
            "window": self.window,
            "sequenceLength": self.sequence_length,
            "heads": list(self.heads),
            "featureSchemaVersion": self.feature_schema_version,
            "seed": self.seed,
            "epochs": self.epochs,
            "batchSize": self.batch_size,
            "stride": self.stride,
            "allowSynthetic": self.allow_synthetic,
            "includeReview": self.include_review,
            "notes": self.notes,
        }

    def to_worker_dict(self) -> dict:
        data = self.to_dict()
        data["datasetRoot"] = data.pop("datasetPath")
        return data


REQUIRED_FIELDS = ("dataset", "preset", "window", "heads", "featureSchemaVersion", "seed")
OPTIONAL_FIELDS = ("epochs", "batchSize", "batch_size", "stride", "allowSynthetic", "allow_synthetic",
                   "includeReview", "include_review", "notes")
KNOWN_FIELDS = frozenset(REQUIRED_FIELDS + OPTIONAL_FIELDS)


def _as_int(value, field_name: str, code: str, **limits) -> int:
    if type(value) is bool or not isinstance(value, int):
        raise RequestError("invalid_type", field=field_name, expected="целым числом", actual=type(value).__name__)
    return value


def _as_bool(value, field_name: str) -> bool:
    if isinstance(value, bool):
        return value
    raise RequestError("invalid_type", field=field_name, expected="true или false", actual=type(value).__name__)


def resolve_dataset_path(dataset: str, roots: tuple[Path, ...]) -> Path:
    """Resolves a dataset inside the configured roots. Traversal, absolute paths and escapes are refused."""
    if not isinstance(dataset, str) or not dataset.strip():
        raise RequestError("invalid_type", field="dataset", expected="непустой строкой", actual=type(dataset).__name__)
    value = dataset.strip().replace("\\", "/")
    roots_display = ", ".join(str(root) for root in roots) or "не настроены"
    if value.startswith("/") or value.startswith("~") or ".." in Path(value).parts or (len(value) > 1 and value[1] == ":"):
        raise RequestError("dataset_not_allowed", dataset=dataset, roots=roots_display)
    candidates = []
    for root in roots:
        candidate = (root / value).resolve()
        try:
            candidate.relative_to(root.resolve())
        except ValueError:
            continue
        candidates.append(candidate)
    if not candidates:
        raise RequestError("dataset_not_allowed", dataset=dataset, roots=roots_display)
    for candidate in candidates:
        if candidate.is_dir() and (candidate / "metadata").is_dir():
            return candidate
    raise RequestError("dataset_missing", dataset=dataset)


def validate_request(payload, *, schema, dataset_roots, available_datasets=(),
                     max_epochs: int = 200, max_batch_size: int = 4096, max_stride: int = 512) -> JobRequest:
    """Validates one job request. Raises ``RequestError`` (Russian message, machine code) on refusal."""
    if not isinstance(payload, dict):
        raise RequestError("body_required")
    for field_name in REQUIRED_FIELDS:
        if field_name not in payload or payload[field_name] is None:
            raise RequestError("missing_field", field=field_name)
    unknown = [name for name in payload if name not in KNOWN_FIELDS]
    if unknown:
        raise RequestError("unknown_field", field=sorted(unknown)[0])

    preset = payload["preset"]
    if not isinstance(preset, str) or preset.strip().lower() not in PRESETS:
        raise RequestError("unknown_preset", preset=preset)
    preset = preset.strip().lower()

    window = payload["window"]
    if not isinstance(window, str) or window.strip().lower() not in ("attack", "continuous"):
        raise RequestError("invalid_type", field="window", expected="attack или continuous", actual=repr(window))
    window = window.strip().lower()
    if window != WINDOW_FOR_PRESET[preset]:
        raise RequestError("preset_window_mismatch", preset=preset, window=window,
                           expectedWindow=WINDOW_FOR_PRESET[preset])

    requested_schema = payload["featureSchemaVersion"]
    _as_int(requested_schema, "featureSchemaVersion", "invalid_field")
    if int(requested_schema) != int(schema.version):
        raise RequestError("feature_schema_mismatch", requested=requested_schema, deployed=schema.version)

    heads_value = payload["heads"]
    if isinstance(heads_value, str) or not isinstance(heads_value, (list, tuple)):
        raise RequestError("invalid_type", field="heads", expected="списком имён heads", actual=type(heads_value).__name__)
    heads = tuple(str(head).strip() for head in heads_value if str(head).strip())
    if not heads:
        raise RequestError("heads_empty")
    for head in heads:
        if head not in schema.heads:
            raise RequestError("unknown_head", head=head, available=", ".join(schema.heads))
    if len(set(heads)) != len(heads):
        raise RequestError("invalid_type", field="heads", expected="списком без повторов", actual="повторы")
    if REQUIRED_HEAD not in heads:
        raise RequestError("overall_required")

    seed = payload["seed"]
    _as_int(seed, "seed", "invalid_field")
    if not 0 <= int(seed) <= 2 ** 31 - 1:
        raise RequestError("seed_range", value=seed, maxSeed=2 ** 31 - 1)

    epochs = payload.get("epochs", 30)
    _as_int(epochs, "epochs", "invalid_field")
    if not 1 <= int(epochs) <= max_epochs:
        raise RequestError("epochs_range", value=epochs, maxEpochs=max_epochs)
    batch_size = payload.get("batchSize", payload.get("batch_size", 128))
    _as_int(batch_size, "batchSize", "invalid_field")
    if not 1 <= int(batch_size) <= max_batch_size:
        raise RequestError("batch_size_range", value=batch_size, maxBatchSize=max_batch_size)
    stride = payload.get("stride", 4)
    _as_int(stride, "stride", "invalid_field")
    if not 1 <= int(stride) <= max_stride:
        raise RequestError("stride_range", value=stride, maxStride=max_stride)
    allow_synthetic = _as_bool(payload.get("allowSynthetic", payload.get("allow_synthetic", False)), "allowSynthetic")
    include_review = _as_bool(payload.get("includeReview", payload.get("include_review", False)), "includeReview")
    notes = payload.get("notes", "")
    if not isinstance(notes, str):
        raise RequestError("invalid_type", field="notes", expected="строкой", actual=type(notes).__name__)

    name = payload["dataset"].strip()
    if not DATASET_NAME_PATTERN.match(name):
        raise RequestError("dataset_not_allowed", dataset=payload["dataset"],
                           roots=", ".join(str(root) for root in dataset_roots) or "не настроены")
    # The inventory is the allowlist: a name that the server did not catalogue is refused, even if a
    # directory with that name happens to exist under a root.
    allowed = {str(entry) for entry in available_datasets}
    if allowed and name not in allowed:
        raise RequestError("dataset_unknown", dataset=name, available=", ".join(sorted(allowed)))
    dataset_path = resolve_dataset_path(name, tuple(dataset_roots))
    return JobRequest(dataset=name, preset=preset, window=window, heads=heads,
                      feature_schema_version=int(requested_schema), seed=int(seed), epochs=int(epochs),
                      batch_size=int(batch_size), stride=int(stride), allow_synthetic=allow_synthetic,
                      include_review=include_review, notes=notes, dataset_path=dataset_path)


def progress_for(state: str, epoch: int, total_epochs: int) -> float:
    """A monotone 0..1 estimate. It is a progress bar, not a claim about remaining CPU time."""
    base = STAGE_BASE_PROGRESS.get(state, 0.0)
    if state == TRAINING and total_epochs > 0:
        done = min(max(epoch, 0), total_epochs)
        return round(base + (TRAINING_CEILING - base) * (done / total_epochs), 4)
    return round(base, 4)

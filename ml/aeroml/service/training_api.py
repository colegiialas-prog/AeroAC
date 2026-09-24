"""Standard-library HTTP front end for the external training service.

Bind it to loopback. It writes model bundles and starts training subprocesses, so it is not an
internet-facing API, and it never deploys anything: a finished job is a candidate bundle.

    python -m aeroml.service.training_api --host 127.0.0.1 --port 8090
    python -m aeroml.service.training_api --host 0.0.0.0 --token <secret>   # remote requires a token

Endpoints (exact contract; the operator-facing notes live in ml/README.md):

    GET  /health                        service, schema and queue state
    GET  /training/status               the current job object directly, plus datasets/jobs/models
    GET  /datasets                      dataset inventory (same entries as in status)
    GET  /models                        candidate bundles produced by earlier jobs
    POST /training/jobs                 enqueue one job
    GET  /training/jobs/{id}            one job
    GET  /training/jobs/{id}/result     finished job: bundle, audit, evaluation, calibration, policy
    POST /training/jobs/{id}/cancel     stop a queued or running job

Every refusal is answered as ``{"error": <machine code>, "message": <Russian text>}``: the machine
fields stay stable for the Java client, the message is what an operator reads.

Loopback needs no authentication. The moment the socket is reachable from another machine a token is
mandatory, because this endpoint both starts compute and writes model bundles.
"""

from __future__ import annotations

import argparse
import hmac
import json
import logging
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

from ..console import use_utf8_console
from ..reporting import clean
from ..schema import default_schema
from ..training.contract import (
    AUTOMATIC_DEPLOYMENT,
    CONTRACT_VERSION,
    RAW_SCHEMA_VERSION,
    RequestError,
    human,
)
from .training_jobs import ML_ROOT, SERVICE_NAME, JobManager

MAX_BODY_BYTES = 256 * 1024
LOGGER = logging.getLogger("aeroml.service.training")
LOOPBACK_HOSTS = ("127.0.0.1", "::1", "localhost")
DEFAULT_PRIMARY_DATASET_ROOT = ML_ROOT.parent / "datasets"
DEFAULT_BUNDLE_ROOT = ML_ROOT / "bundles"
DEFAULT_STATE_ROOT = ML_ROOT / "var" / "training-service"
TOKEN_ENVIRONMENT = "AERO_TRAINING_TOKEN"
AVAILABLE_ENDPOINTS = ("/health", "/training/status", "/datasets", "/models", "/training/jobs",
                       "/training/jobs/{jobId}", "/training/jobs/{jobId}/result",
                       "/training/jobs/{jobId}/cancel")


def build_manager(*, dataset_roots, bundle_root, state_root, max_queue_depth=4,
                  max_jobs_retained=32, job_timeout_seconds=3600, max_epochs=200,
                  torch_threads=2, runner=None, poll_interval=0.25) -> JobManager:
    return JobManager(dataset_roots=dataset_roots, bundle_root=bundle_root, state_root=state_root,
                      schema=default_schema(), max_queue_depth=max_queue_depth,
                      max_jobs_retained=max_jobs_retained, job_timeout_seconds=job_timeout_seconds,
                      max_epochs=max_epochs, torch_threads=torch_threads, runner=runner,
                      poll_interval=poll_interval)


def _handler(manager: JobManager, *, host: str, token: str | None = None) -> type[BaseHTTPRequestHandler]:
    loopback = host in LOOPBACK_HOSTS
    # A remote socket without a token is a configuration mistake, not a feature: refuse to serve.
    if not loopback and not token:
        raise SystemExit("refusing to serve " + host + " without --token: the training service "
                         "starts compute and writes model bundles. Pass --token or set " + TOKEN_ENVIRONMENT + ".")

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "AeroTrainingService/1"

        def log_message(self, fmt: str, *args) -> None:  # noqa: A003 - BaseHTTPRequestHandler API
            LOGGER.debug("%s - %s", self.address_string(), fmt % args)

        # -- plumbing ---------------------------------------------------
        def _send(self, status: int, payload: dict) -> None:
            # ``allow_nan=False`` with a sanitising pass: a metric that came out non-finite is sent as
            # null rather than as ``NaN``, which is not JSON and breaks strict clients.
            body = json.dumps(clean(payload), ensure_ascii=False, allow_nan=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _refuse(self, error: RequestError) -> None:
            payload = error.to_dict()
            self._send(error.status, payload)

        def _authorized(self) -> bool:
            """No token configured (loopback) means open. A configured token is compared in constant time."""
            if not token:
                return True
            presented = ""
            header = self.headers.get("Authorization") or ""
            if header.lower().startswith("bearer "):
                presented = header[7:].strip()
            if not presented:
                presented = (self.headers.get("X-Auth-Token") or "").strip()
            return bool(presented) and hmac.compare_digest(presented.encode("utf-8"), token.encode("utf-8"))

        def _read_body(self) -> dict:
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                raise RequestError("malformed_json", detail="некорректный Content-Length") from None
            if length <= 0:
                raise RequestError("body_required")
            if length > MAX_BODY_BYTES:
                raise RequestError("body_too_large", status=413, limit=MAX_BODY_BYTES)
            raw = self.rfile.read(length)
            try:
                payload = json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise RequestError("malformed_json", detail=str(error)) from None
            if not isinstance(payload, dict):
                raise RequestError("body_required")
            return payload

        @property
        def _parts(self) -> list[str]:
            return [unquote(part) for part in urlparse(self.path).path.split("/") if part]

        # -- routes -----------------------------------------------------
        def _health(self) -> dict:
            schema = manager.schema
            return {
                "status": "ok",
                "service": SERVICE_NAME,
                "contractVersion": CONTRACT_VERSION,
                "featureSchemaVersion": schema.version,
                "featureCount": schema.feature_count,
                "rawSchemaVersion": RAW_SCHEMA_VERSION,
                "heads": list(schema.heads),
                "host": host,
                "localOnly": loopback,
                "authRequired": bool(token),
                "automaticDeployment": AUTOMATIC_DEPLOYMENT,
                "datasetRoots": [str(root) for root in manager.dataset_roots],
                "datasets": list(manager.allowed_datasets()),
                "bundleRoot": str(manager.bundle_root),
                "stateRoot": str(manager.state_root),
                "queue": manager.queue_status(),
                "limits": {"maxQueueDepth": manager.max_queue_depth,
                           "maxJobsRetained": manager.max_jobs_retained,
                           "maxEpochs": manager.max_epochs,
                           "jobTimeoutSeconds": manager.job_timeout_seconds,
                           "maxBodyBytes": MAX_BODY_BYTES},
            }

        def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            parts = self._parts
            try:
                if not self._authorized():
                    raise RequestError("unauthorized", status=401)
                if parts in (["health"], ["healthz"]):
                    self._send(200, self._health())
                elif parts == ["training", "status"]:
                    self._send(200, manager.status())
                elif parts == ["datasets"]:
                    self._send(200, {"datasetRoots": manager.datasets(),
                                     "unlabeledPolicy": manager.status()["unlabeledPolicy"]})
                elif parts == ["models"]:
                    self._send(200, {"models": manager.models(),
                                     "promotionPolicy": {"automaticDeployment": AUTOMATIC_DEPLOYMENT}})
                elif parts == ["training", "jobs"]:
                    self._send(200, {"jobs": [job.to_dict() for job in manager.list_jobs()]})
                elif len(parts) == 3 and parts[:2] == ["training", "jobs"]:
                    self._send(200, manager.get(parts[2]).to_dict())
                elif len(parts) == 4 and parts[:2] == ["training", "jobs"] and parts[3] == "result":
                    self._send(200, manager.result(parts[2]))
                else:
                    raise RequestError("not_found", status=404, path=self.path,
                                       available=", ".join(AVAILABLE_ENDPOINTS))
            except RequestError as error:
                self._refuse(error)
            except Exception as error:  # pragma: no cover - defensive
                LOGGER.exception("GET %s failed", self.path)
                self._send(500, RequestError("internal", errorType=type(error).__name__).to_dict())

        def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            parts = self._parts
            try:
                if not self._authorized():
                    raise RequestError("unauthorized", status=401)
                if parts == ["training", "jobs"]:
                    payload = self._read_body()
                    job = manager.submit(payload)
                    self._send(202, {"jobId": job.job_id, "state": job.state, "job": job.to_dict(),
                                     "message": job.message})
                elif len(parts) == 4 and parts[:2] == ["training", "jobs"] and parts[3] == "cancel":
                    job = manager.cancel(parts[2])
                    self._send(200, {"jobId": job.job_id, "state": job.state, "job": job.to_dict(),
                                     "message": human("job_cancel_requested", jobId=job.job_id)})
                else:
                    raise RequestError("not_found", status=404, path=self.path,
                                       available=", ".join(AVAILABLE_ENDPOINTS))
            except RequestError as error:
                self._refuse(error)
            except Exception as error:  # pragma: no cover - defensive
                LOGGER.exception("POST %s failed", self.path)
                self._send(500, RequestError("internal", errorType=type(error).__name__).to_dict())

    return Handler


def create_server(manager: JobManager, host: str = "127.0.0.1", port: int = 8090,
                  token: str | None = None) -> ThreadingHTTPServer:
    return ThreadingHTTPServer((host, port), _handler(manager, host=host, token=token))


def serve(manager: JobManager, host: str = "127.0.0.1", port: int = 8090,
          ready=None, token: str | None = None) -> None:
    server = create_server(manager, host, port, token)
    manager.start()
    if ready is not None:
        ready(server.server_address[1])
    LOGGER.info("%s on http://%s:%d (datasets=%s, bundles=%s, auth=%s)", SERVICE_NAME, host,
                server.server_address[1], ", ".join(str(root) for root in manager.dataset_roots),
                manager.bundle_root, "token" if token else "loopback-trust")
    try:
        server.serve_forever()
    finally:
        manager.stop()
        server.server_close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Aero AC external training service (localhost only)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--dataset-root", type=Path, action="append",
                        help="catalogue a job may read datasets from (repeatable)")
    parser.add_argument("--bundle-root", type=Path, default=DEFAULT_BUNDLE_ROOT)
    parser.add_argument("--state-root", type=Path, default=DEFAULT_STATE_ROOT)
    parser.add_argument("--max-queue-depth", type=int, default=4)
    parser.add_argument("--max-jobs-retained", type=int, default=32,
                        help="how many jobs the service keeps in its in-memory journal")
    parser.add_argument("--job-timeout", type=int, default=3600, help="seconds before a job is killed")
    parser.add_argument("--max-epochs", type=int, default=200)
    parser.add_argument("--torch-threads", type=int, default=2)
    parser.add_argument("--python", help="interpreter for training subprocesses (default: this one)")
    parser.add_argument("--token", help="shared token required on every request (mandatory off loopback; "
                                        f"or set {TOKEN_ENVIRONMENT})")
    parser.add_argument("--allow-non-local", action="store_true",
                        help="confirm binding a non-loopback address; a token is still required")
    parser.add_argument("--log-level", default="INFO")
    arguments = parser.parse_args()
    use_utf8_console()
    logging.basicConfig(level=arguments.log_level.upper(), format="%(asctime)s %(levelname)s %(message)s")

    token = arguments.token or os.environ.get(TOKEN_ENVIRONMENT) or None
    loopback = arguments.host in LOOPBACK_HOSTS
    if not loopback:
        if not arguments.allow_non_local:
            raise SystemExit("refusing to bind " + arguments.host + ": the training service starts "
                             "compute and writes model bundles. Use --allow-non-local to override.")
        if not token:
            raise SystemExit("refusing to bind " + arguments.host + " without a token: remote access to "
                             "the training service requires --token or " + TOKEN_ENVIRONMENT + ".")
    roots = arguments.dataset_root or [DEFAULT_PRIMARY_DATASET_ROOT]
    manager = build_manager(dataset_roots=roots, bundle_root=arguments.bundle_root,
                            state_root=arguments.state_root, max_queue_depth=arguments.max_queue_depth,
                            max_jobs_retained=arguments.max_jobs_retained,
                            job_timeout_seconds=arguments.job_timeout, max_epochs=arguments.max_epochs,
                            torch_threads=arguments.torch_threads)
    if arguments.python:
        from .training_jobs import SubprocessRunner

        manager.runner = SubprocessRunner(python=arguments.python)
    serve(manager, arguments.host, arguments.port, token=token)


if __name__ == "__main__":
    main()

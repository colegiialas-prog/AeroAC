"""Standard-library HTTP front end for the inference service.

The point of a dependency-free server is that the protocol can be exercised in CI and a deployment
can be brought up before anyone installs a web framework. ``app.py`` offers the FastAPI front end
for production; both share ``InferenceService`` so there is exactly one implementation of the
protocol rules.

Request bodies are capped: the Java client sends a fixed-shape window, so anything much larger is
either a misconfiguration or an attempt to make the service allocate.
"""

from __future__ import annotations

import json
import logging
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable

from .runtime import InferenceService, ProtocolError

MAX_BODY_BYTES = 8 * 1024 * 1024
LOGGER = logging.getLogger("aeroml.service")


def _handler(service: InferenceService) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "AeroInference/1"

        def log_message(self, fmt: str, *args) -> None:  # noqa: A003 - BaseHTTPRequestHandler API
            LOGGER.debug("%s - %s", self.address_string(), fmt % args)

        def _send(self, status: int, payload: dict) -> None:
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            if self.path.rstrip("/") in ("/health", "/healthz"):
                self._send(200, service.health())
            else:
                self._send(404, {"error": "not found"})

        def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            if self.path.rstrip("/") != "/predict":
                self._send(404, {"error": "not found"})
                return
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                self._send(422, {"error": "malformed Content-Length"})
                return
            if length <= 0 or length > MAX_BODY_BYTES:
                self._send(422, {"error": f"body must be 1..{MAX_BODY_BYTES} bytes"})
                return
            raw = self.rfile.read(length)
            try:
                payload = json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                self._send(422, {"error": f"malformed JSON: {error}"})
                return
            try:
                self._send(200, service.predict(payload))
            except ProtocolError as error:
                self._send(error.status, {"error": error.message})
            except Exception as error:  # pragma: no cover - defensive, never leaks a stack trace
                LOGGER.exception("inference failed")
                self._send(503, {"error": f"inference failed: {type(error).__name__}"})

    return Handler


def create_server(service: InferenceService, host: str = "127.0.0.1", port: int = 8080) -> ThreadingHTTPServer:
    return ThreadingHTTPServer((host, port), _handler(service))


def serve(service: InferenceService, host: str = "127.0.0.1", port: int = 8080,
          ready: Callable[[int], None] | None = None) -> None:
    server = create_server(service, host, port)
    if ready is not None:
        ready(server.server_address[1])
    LOGGER.info("Aero inference service on http://%s:%d", host, server.server_address[1])
    try:
        server.serve_forever()
    finally:
        server.server_close()

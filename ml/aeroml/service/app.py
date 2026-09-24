"""FastAPI front end and the service entry point.

Same rules as ``server.py``; only the transport differs. Run either with:

    python -m aeroml.service.app --flash path/to/bundle
    python -m aeroml.service.app --flash path/to/bundle --pro path/to/pro-bundle --framework fastapi

Nothing here authenticates a caller. Bind it to a private interface, or put it behind something
that does: the endpoint accepts telemetry and returns judgements about players.
"""

import argparse
import logging
from pathlib import Path

from ..console import use_utf8_console
from .runtime import InferenceService, ProtocolError, load_service


def create_app(service: InferenceService):
    """Builds the FastAPI application. Raises ImportError when FastAPI is not installed."""
    from fastapi import FastAPI, Request
    from fastapi.responses import JSONResponse
    from starlette.concurrency import run_in_threadpool
    from .server import MAX_BODY_BYTES
    import json

    app = FastAPI(title="Aero AC inference", version="1")

    @app.get("/health")
    async def health() -> dict:
        return service.health()

    @app.post("/predict")
    async def predict(request: Request) -> JSONResponse:
        try:
            body = bytearray()
            async for chunk in request.stream():
                if len(body) + len(chunk) > MAX_BODY_BYTES:
                    return JSONResponse({"error": "request body too large"}, status_code=413)
                body.extend(chunk)
            payload = json.loads(body)
        except Exception as error:
            return JSONResponse({"error": f"malformed JSON: {error}"}, status_code=422)
        try:
            return JSONResponse(await run_in_threadpool(service.predict, payload))
        except ProtocolError as error:
            return JSONResponse({"error": error.message}, status_code=error.status)
        except Exception as error:  # pragma: no cover - defensive
            logging.getLogger("aeroml.service").exception("inference failed")
            return JSONResponse({"error": f"inference failed: {type(error).__name__}"}, status_code=503)

    return app


def build_service(flash: Path | None, pro: Path | None) -> InferenceService:
    bundles: dict[str, Path] = {}
    if flash:
        bundles["flash"] = flash
    if pro:
        bundles["pro"] = pro
    if not bundles:
        raise SystemExit("give at least one bundle: --flash and/or --pro")
    return load_service(bundles)


def main() -> None:
    parser = argparse.ArgumentParser(description="Aero AC inference service")
    parser.add_argument("--flash", type=Path, help="bundle directory for the Flash model")
    parser.add_argument("--pro", type=Path, help="bundle directory for the Pro model")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--framework", choices=("stdlib", "fastapi"), default="stdlib")
    parser.add_argument("--log-level", default="INFO")
    arguments = parser.parse_args()
    use_utf8_console()
    logging.basicConfig(level=arguments.log_level.upper(), format="%(asctime)s %(levelname)s %(message)s")
    service = build_service(arguments.flash, arguments.pro)
    logging.getLogger("aeroml.service").info("loaded %s", service.health()["models"])
    if arguments.framework == "fastapi":
        import uvicorn

        uvicorn.run(create_app(service), host=arguments.host, port=arguments.port, log_level=arguments.log_level.lower())
    else:
        from .server import serve

        serve(service, arguments.host, arguments.port)


if __name__ == "__main__":
    main()

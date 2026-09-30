"""Run one real Hermes Agent turn against an APK-local model endpoint."""

import importlib
import json
import os
import platform
import shutil
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


_API_KEY = "android-ci-key"
_MODEL = "hermes-android-probe"
_PROMPT = "Return exactly ANDROID_HERMES_AGENT_TURN_PASS"
_RESPONSE = "ANDROID_HERMES_AGENT_TURN_PASS"


def _start_model_endpoint():
    evidence = []
    handler_errors = []

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, _format, *_args):
            # Never write request headers or bodies to Android instrumentation logs.
            return

        def do_POST(self):
            try:
                length = int(self.headers.get("Content-Length", "0"))
                payload = json.loads(self.rfile.read(length).decode("utf-8"))
                messages = payload.get("messages") or []
                user_messages = [
                    item.get("content")
                    for item in messages
                    if isinstance(item, dict) and item.get("role") == "user"
                ]
                request_evidence = {
                    "request_index": len(evidence) + 1,
                    "path": self.path,
                    "model": payload.get("model"),
                    "stream": payload.get("stream") is True,
                    "stream_options_present": bool(payload.get("stream_options")),
                    "message_count": len(messages),
                    "message_roles": [
                        item.get("role")
                        for item in messages
                        if isinstance(item, dict)
                    ],
                    "tools_present": bool(payload.get("tools")),
                    "sdk_retry_count": self.headers.get(
                        "x-stainless-retry-count", "missing"
                    ),
                    "authorization_verified": self.headers.get("Authorization")
                    == f"Bearer {_API_KEY}",
                    "prompt_verified": _PROMPT in user_messages,
                }
                evidence.append(request_evidence)

                chunks = (
                    {
                        "id": "chatcmpl-android-probe",
                        "object": "chat.completion.chunk",
                        "created": 0,
                        "model": _MODEL,
                        "choices": [
                            {
                                "index": 0,
                                "delta": {
                                    "role": "assistant",
                                    "content": _RESPONSE,
                                },
                                "logprobs": None,
                                "finish_reason": None,
                            }
                        ],
                    },
                    {
                        "id": "chatcmpl-android-probe",
                        "object": "chat.completion.chunk",
                        "created": 0,
                        "model": _MODEL,
                        "choices": [
                            {
                                "index": 0,
                                "delta": {},
                                "logprobs": None,
                                "finish_reason": "stop",
                            }
                        ],
                    },
                    {
                        "id": "chatcmpl-android-probe",
                        "object": "chat.completion.chunk",
                        "created": 0,
                        "model": _MODEL,
                        "choices": [],
                        "usage": {
                            "prompt_tokens": 1,
                            "completion_tokens": 1,
                            "total_tokens": 2,
                        },
                    },
                )
                body = "".join(
                    f"data: {json.dumps(chunk, separators=(',', ':'))}\n\n"
                    for chunk in chunks
                ) + "data: [DONE]\n\n"
                encoded = body.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "close")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)
                self.wfile.flush()
                self.close_connection = True
            except Exception as error:  # pragma: no cover - reported to instrumentation
                handler_errors.append(f"{type(error).__name__}: {error}")
                self.send_error(500)

    endpoint = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    endpoint.daemon_threads = True
    thread = threading.Thread(
        target=endpoint.serve_forever,
        name="hermes-android-model-probe",
        daemon=True,
    )
    thread.start()
    return endpoint, thread, evidence, handler_errors


def run():
    module = None
    agent_type = None
    endpoint = None
    endpoint_thread = None
    agent = None
    requests = []
    handler_errors = []
    hermes_home = tempfile.mkdtemp(prefix="hermes-android-probe-")
    changed_environment = {
        "HERMES_HOME": hermes_home,
        "HERMES_API_TIMEOUT": "30",
        "HERMES_STREAM_READ_TIMEOUT": "30",
        "NO_PROXY": "127.0.0.1,localhost",
        "no_proxy": "127.0.0.1,localhost",
    }
    previous_environment = {key: os.environ.get(key) for key in changed_environment}
    os.environ.update(changed_environment)

    try:
        # Set the writable Hermes home before importing run_agent because some
        # modules cache profile-scoped paths at import time.
        module = importlib.import_module("run_agent")
        agent_type = getattr(module, "AIAgent")
        endpoint, endpoint_thread, requests, handler_errors = _start_model_endpoint()
        port = endpoint.server_address[1]
        agent = agent_type(
            base_url=f"http://127.0.0.1:{port}/v1",
            api_key=_API_KEY,
            provider="custom",
            api_mode="chat_completions",
            model=_MODEL,
            max_iterations=1,
            enabled_toolsets=[],
            quiet_mode=True,
            skip_context_files=True,
            load_soul_identity=False,
            skip_memory=True,
            skip_background_review=True,
            run_budget_seconds=30,
        )
        response = agent.chat(_PROMPT)
        if handler_errors:
            raise AssertionError(f"Local model endpoint failed: {handler_errors}")
        if response.strip() != _RESPONSE:
            raise AssertionError(f"Unexpected Agent response: {response!r}")
        if len(requests) != 1:
            raise AssertionError(
                f"Expected one model request, got {len(requests)}: {requests!r}"
            )

        request = requests[0]
        expected_request = {
            "path": "/v1/chat/completions",
            "model": _MODEL,
            "stream": True,
            "authorization_verified": True,
            "prompt_verified": True,
        }
        unexpected = {
            key: request.get(key)
            for key, expected in expected_request.items()
            if request.get(key) != expected
        }
        if unexpected:
            raise AssertionError(f"Unexpected model request evidence: {request!r}")

        return json.dumps(
            {
                "stage": "actual_agent_model_turn",
                "python": sys.version.split()[0],
                "machine": platform.machine(),
                "module": module.__name__,
                "agent_type": agent_type.__name__,
                "model_turn_completed": True,
                "response": response.strip(),
                "request_count": len(requests),
                **request,
            },
            ensure_ascii=False,
        )
    finally:
        if agent is not None:
            try:
                agent.close()
            except Exception:
                pass
        if endpoint is not None:
            endpoint.shutdown()
            endpoint.server_close()
        if endpoint_thread is not None:
            endpoint_thread.join(timeout=5)
        for key, previous in previous_environment.items():
            if previous is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = previous
        shutil.rmtree(hermes_home, ignore_errors=True)

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
_PROMPT = "Use phone_wait for 1 millisecond, then return exactly ANDROID_HERMES_TOOL_ROUTE_PASS"
_RESPONSE = "ANDROID_HERMES_TOOL_ROUTE_PASS"


def _start_model_endpoint():
    evidence = []
    discovery_probes = []
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
                if self.path == "/api/show":
                    # A loopback OpenAI-compatible endpoint is conservatively
                    # probed for Ollama metadata. Return an explicit non-Ollama
                    # result and keep it separate from billable model calls.
                    discovery_probes.append(
                        {
                            "path": self.path,
                            "authorization_verified": self.headers.get(
                                "Authorization"
                            )
                            == f"Bearer {_API_KEY}",
                        }
                    )
                    encoded = b"{}"
                    self.send_response(404)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Connection", "close")
                    self.send_header("Content-Length", str(len(encoded)))
                    self.end_headers()
                    self.wfile.write(encoded)
                    self.wfile.flush()
                    self.close_connection = True
                    return
                if self.path != "/v1/chat/completions":
                    raise AssertionError(f"Unexpected endpoint path: {self.path}")

                messages = payload.get("messages") or []
                user_messages = [
                    item.get("content")
                    for item in messages
                    if isinstance(item, dict) and item.get("role") == "user"
                ]
                tool_names = [
                    item.get("function", {}).get("name")
                    for item in payload.get("tools") or []
                    if isinstance(item, dict)
                ]
                tool_messages = [
                    item
                    for item in messages
                    if isinstance(item, dict) and item.get("role") == "tool"
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
                    "tool_names": tool_names,
                    "tool_message_count": len(tool_messages),
                    "sdk_retry_count": self.headers.get(
                        "x-stainless-retry-count", "missing"
                    ),
                    "authorization_verified": self.headers.get("Authorization")
                    == f"Bearer {_API_KEY}",
                    "prompt_verified": _PROMPT in user_messages,
                }
                evidence.append(request_evidence)
                if len(evidence) == 1:
                    if "phone_wait" not in tool_names:
                        raise AssertionError(f"phone_wait schema missing: {tool_names!r}")
                    chunks = (
                        {
                            "id": "chatcmpl-android-tool",
                            "object": "chat.completion.chunk",
                            "created": 0,
                            "model": _MODEL,
                            "choices": [{
                                "index": 0,
                                "delta": {
                                    "role": "assistant",
                                    "tool_calls": [{
                                        "index": 0,
                                        "id": "call_android_wait",
                                        "type": "function",
                                        "function": {"name": "phone_wait", "arguments": ""},
                                    }],
                                },
                                "logprobs": None,
                                "finish_reason": None,
                            }],
                        },
                        {
                            "id": "chatcmpl-android-tool",
                            "object": "chat.completion.chunk",
                            "created": 0,
                            "model": _MODEL,
                            "choices": [{
                                "index": 0,
                                "delta": {"tool_calls": [{
                                    "index": 0,
                                    "function": {"arguments": "{\"timeout_ms\":1}"},
                                }]},
                                "logprobs": None,
                                "finish_reason": None,
                            }],
                        },
                        {
                            "id": "chatcmpl-android-tool",
                            "object": "chat.completion.chunk",
                            "created": 0,
                            "model": _MODEL,
                            "choices": [{
                                "index": 0,
                                "delta": {},
                                "logprobs": None,
                                "finish_reason": "tool_calls",
                            }],
                        },
                    )
                elif len(evidence) == 2:
                    if len(tool_messages) != 1:
                        raise AssertionError(f"expected one Android tool result: {messages!r}")
                    tool_result = json.loads(tool_messages[0].get("content") or "{}")
                    if (
                        tool_result.get("execution_status") != "SUCCEEDED"
                        or tool_result.get("tool") != "phone.wait"
                    ):
                        raise AssertionError(f"unexpected Android tool result: {tool_result!r}")
                    chunks = (
                        {
                            "id": "chatcmpl-android-final",
                            "object": "chat.completion.chunk",
                            "created": 0,
                            "model": _MODEL,
                            "choices": [{
                                "index": 0,
                                "delta": {"role": "assistant", "content": _RESPONSE},
                                "logprobs": None,
                                "finish_reason": None,
                            }],
                        },
                        {
                            "id": "chatcmpl-android-final",
                            "object": "chat.completion.chunk",
                            "created": 0,
                            "model": _MODEL,
                            "choices": [{
                                "index": 0,
                                "delta": {},
                                "logprobs": None,
                                "finish_reason": "stop",
                            }],
                        },
                    )
                else:
                    raise AssertionError(f"unexpected model request count: {len(evidence)}")
                chunks += ({
                    "id": "chatcmpl-android-usage",
                    "object": "chat.completion.chunk",
                    "created": 0,
                    "model": _MODEL,
                    "choices": [],
                    "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
                },)
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
    return endpoint, thread, evidence, discovery_probes, handler_errors


def run(android_bridge):
    module = None
    agent_type = None
    endpoint = None
    endpoint_thread = None
    agent = None
    phone_tools = None
    requests = []
    discovery_probes = []
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
        phone_tools = importlib.import_module("tools.android_phone_tools")
        phone_tools.configure_android_tool_transport(android_bridge)
        (
            endpoint,
            endpoint_thread,
            requests,
            discovery_probes,
            handler_errors,
        ) = _start_model_endpoint()
        port = endpoint.server_address[1]
        agent = agent_type(
            base_url=f"http://127.0.0.1:{port}/v1",
            api_key=_API_KEY,
            provider="custom",
            api_mode="chat_completions",
            model=_MODEL,
            max_iterations=3,
            enabled_toolsets=["mobile"],
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
        if len(requests) != 2:
            raise AssertionError(
                f"Expected two model requests, got {len(requests)}: {requests!r}"
            )
        expected_discovery = [
            {"path": "/api/show", "authorization_verified": True}
        ]
        if discovery_probes != expected_discovery:
            raise AssertionError(
                f"Unexpected model discovery probes: {discovery_probes!r}"
            )

        first_request = requests[0]
        expected_request = {
            "path": "/v1/chat/completions",
            "model": _MODEL,
            "stream": True,
            "authorization_verified": True,
            "prompt_verified": True,
        }
        unexpected = {
            key: first_request.get(key)
            for key, expected in expected_request.items()
            if first_request.get(key) != expected
        }
        if unexpected:
            raise AssertionError(f"Unexpected model request evidence: {first_request!r}")

        second_request = requests[1]
        tool_schema_verified = "phone_wait" in first_request.get("tool_names", [])
        tool_result_verified = second_request.get("tool_message_count") == 1

        return json.dumps(
            {
                "stage": "actual_agent_android_tool_route",
                "python": sys.version.split()[0],
                "machine": platform.machine(),
                "module": module.__name__,
                "agent_type": agent_type.__name__,
                "model_turn_completed": True,
                "response": response.strip(),
                "request_count": len(requests),
                "discovery_probe_count": len(discovery_probes),
                "tool_schema_verified": tool_schema_verified,
                "tool_result_verified": tool_result_verified,
                "authorization_verified": all(
                    request.get("authorization_verified") for request in requests
                ),
            },
            ensure_ascii=False,
        )
    finally:
        if phone_tools is not None:
            try:
                phone_tools.clear_android_tool_transport()
            except Exception:
                pass
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

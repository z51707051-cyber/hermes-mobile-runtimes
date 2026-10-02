from __future__ import annotations

import importlib.util
from pathlib import Path
from types import SimpleNamespace


REPO_ROOT = Path(__file__).resolve().parents[3]
RUNTIME_PATH = (
    REPO_ROOT
    / "apps"
    / "mobile-bridge-android"
    / "app"
    / "src"
    / "main"
    / "python"
    / "hermes_mobile_runtime.py"
)


def _load_runtime():
    spec = importlib.util.spec_from_file_location(
        "hermes_mobile_runtime_test", RUNTIME_PATH
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_runtime_requires_manual_authentication_handoff(monkeypatch, tmp_path) -> None:
    runtime = _load_runtime()
    prompts: list[str] = []
    events: list[object] = []

    class FakeAgent:
        def __init__(self, **kwargs) -> None:
            events.append(("agent", kwargs))

        def chat(self, prompt: str) -> str:
            prompts.append(prompt)
            return "done"

        def close(self) -> None:
            events.append("closed")

    phone_tools = SimpleNamespace(
        configure_android_tool_transport=lambda bridge: events.append((
            "configured",
            bridge,
        )),
        clear_android_tool_transport=lambda: events.append("cleared"),
    )

    def fake_import(name: str):
        if name == "run_agent":
            return SimpleNamespace(AIAgent=FakeAgent)
        if name == "tools.android_phone_tools":
            return phone_tools
        raise AssertionError(f"unexpected import: {name}")

    monkeypatch.setattr(runtime.importlib, "import_module", fake_import)
    monkeypatch.setattr(runtime.Path, "home", lambda: tmp_path)

    bridge = object()
    result = runtime.run_task(
        "https://api.example.test/v1",
        "test-key",
        "test-model",
        "Open the account page",
        bridge,
    )

    assert result == "done"
    assert len(prompts) == 1
    assert "Never read, type, submit, or repeat a password" in prompts[0]
    assert "verification code" in prompts[0]
    assert prompts[0].endswith("User task:\nOpen the account page")
    assert events[-2:] == ["cleared", "closed"]
    assert events[0] == ("configured", bridge)
    assert not any("test-key" in prompt for prompt in prompts)

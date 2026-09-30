"""Production entry point for one user-initiated Hermes mobile task."""

import importlib
import os
from pathlib import Path


def run_task(base_url, api_key, model, prompt, android_bridge):
    """Run one bounded Agent task and always revoke its Python tool transport."""
    hermes_home = Path.home() / ".hermes-mobile"
    hermes_home.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("HERMES_HOME", str(hermes_home))
    os.environ.setdefault("HERMES_API_TIMEOUT", "120")
    os.environ.setdefault("HERMES_STREAM_READ_TIMEOUT", "120")
    run_agent = importlib.import_module("run_agent")
    phone_tools = importlib.import_module("tools.android_phone_tools")
    agent = None
    phone_tools.configure_android_tool_transport(android_bridge)
    try:
        agent = run_agent.AIAgent(
            base_url=base_url,
            api_key=api_key,
            provider="custom",
            api_mode="chat_completions",
            model=model,
            max_iterations=64,
            enabled_toolsets=["mobile"],
            quiet_mode=True,
            skip_context_files=True,
            load_soul_identity=False,
            skip_background_review=True,
            run_budget_seconds=900,
        )
        return agent.chat(prompt)
    finally:
        phone_tools.clear_android_tool_transport()
        if agent is not None:
            agent.close()

"""Import the actual staged Hermes entry point and emit bounded evidence."""

import importlib
import json
import platform
import sys


def run():
    module = importlib.import_module("run_agent")
    agent_type = getattr(module, "AIAgent")
    return json.dumps(
        {
            "stage": "actual_run_agent_import",
            "python": sys.version.split()[0],
            "machine": platform.machine(),
            "module": module.__name__,
            "agent_type": agent_type.__name__,
            "model_turn_completed": False,
        },
        ensure_ascii=False,
    )

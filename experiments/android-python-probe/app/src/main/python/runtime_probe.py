"""Exercise bundled CPython; never claim that this starts Hermes Agent."""

import json
import platform
import sqlite3
import ssl
import sys
import tempfile
from pathlib import Path


def run(directory):
    with tempfile.TemporaryDirectory(prefix="python-probe-", dir=directory) as work:
        database = Path(work) / "probe.sqlite"
        with sqlite3.connect(database) as connection:
            connection.execute("CREATE TABLE probe (value TEXT NOT NULL)")
            connection.execute("INSERT INTO probe VALUES (?)", ("手机内 Python",))
            value = connection.execute("SELECT value FROM probe").fetchone()[0]
        if value != "手机内 Python":
            raise RuntimeError("SQLite round-trip failed")
    context = ssl.create_default_context()
    if not context.check_hostname or context.verify_mode != ssl.CERT_REQUIRED:
        raise RuntimeError("TLS certificate checks are disabled")
    return json.dumps({
        "stage": "bundled_cpython_only",
        "hermes_agent_started": False,
        "python": sys.version.split()[0],
        "machine": platform.machine(),
        "sqlite_round_trip": True,
        "tls_verification": True,
    }, ensure_ascii=False)

from __future__ import annotations

import json
from pathlib import Path

import pytest


FIXTURES = Path(__file__).parent / "fixtures" / "v0.1"


@pytest.fixture
def fixture_root() -> Path:
    return FIXTURES


@pytest.fixture
def load_fixture(fixture_root: Path):
    def load(relative: str):
        return json.loads((fixture_root / relative).read_text(encoding="utf-8"))

    return load

from __future__ import annotations

from pathlib import Path

import yaml


REPO_ROOT = Path(__file__).resolve().parents[3]
PROJECT_ROOT = REPO_ROOT / "apps" / "hermes-mobile-internal"
APP_ROOT = PROJECT_ROOT / "app"
WORKFLOW_PATH = REPO_ROOT / ".github" / "workflows" / "android-native-wheels.yml"


def _requirements(path: Path) -> set[str]:
    return {
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }


def test_internal_apk_reuses_the_reviewed_manifest_and_bridge() -> None:
    build = (APP_ROOT / "build.gradle.kts").read_text(encoding="utf-8")

    assert 'applicationId = "ai.hermes.mobile.runtime"' in build
    assert 'abiFilters += "arm64-v8a"' in build
    assert 'id("com.chaquo.python")' in build
    assert (
        'manifest.srcFile("../../mobile-bridge-android/app/src/main/AndroidManifest.xml")'
        in build
    )
    assert 'kotlin.srcDir("../../mobile-bridge-android/app/src/main/kotlin")' in build
    assert 'res.srcDir("../../mobile-bridge-android/app/src/main/res")' in build
    assert not (APP_ROOT / "src" / "main" / "AndroidManifest.xml").exists()


def test_internal_apk_uses_the_proven_runtime_dependency_set() -> None:
    expected = _requirements(
        REPO_ROOT
        / "experiments"
        / "android-hermes-import-probe"
        / "app"
        / "requirements-hermes-core.txt"
    )
    actual = _requirements(APP_ROOT / "requirements-hermes-core.txt")

    assert actual == expected
    backend = (
        APP_ROOT
        / "src"
        / "main"
        / "kotlin"
        / "ai"
        / "hermes"
        / "mobile"
        / "runtime"
        / "bridge"
        / "runtime"
        / "ChaquopyHermesTaskRuntime.kt"
    ).read_text(encoding="utf-8")
    assert 'getModule("hermes_mobile_runtime")' in backend
    assert ".callAttr(" in backend


def test_internal_apk_workflow_publishes_only_the_reviewed_arm64_artifact() -> None:
    workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))
    job = workflow["jobs"]["build-internal-arm64-apk"]
    steps = job["steps"]
    combined = "\n".join(step.get("run", "") for step in steps)
    artifact = next(
        step
        for step in steps
        if step.get("name") == "Upload installable ARM64 internal package"
    )

    assert job["needs"] == "build-wheel"
    assert "android-wheel-*-arm64_v8a" in str(steps)
    assert "--dependency-verification=strict" in combined
    assert "verify_android_manifest_policy.py" in combined
    assert "lib/(x86|x86_64|armeabi|armeabi-v7a)" in combined
    assert artifact["with"]["name"] == "hermes-mobile-internal-arm64"
    assert artifact["with"]["if-no-files-found"] == "error"

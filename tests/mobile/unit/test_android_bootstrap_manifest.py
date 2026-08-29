from __future__ import annotations

import importlib.util
from pathlib import Path
import textwrap


REPO_ROOT = Path(__file__).resolve().parents[3]
VERIFIER_PATH = REPO_ROOT / "scripts" / "android" / "verify_android_bootstrap.py"
SPEC = importlib.util.spec_from_file_location("verify_android_bootstrap", VERIFIER_PATH)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


def _write_manifest(tmp_path: Path, body: str) -> Path:
    path = tmp_path / "AndroidManifest.xml"
    path.write_text(textwrap.dedent(body), encoding="utf-8")
    return path


def test_accepts_the_zero_permission_bootstrap_manifest() -> None:
    manifest = (
        REPO_ROOT
        / "apps"
        / "mobile-bridge-android"
        / "app"
        / "src"
        / "main"
        / "AndroidManifest.xml"
    )

    assert VERIFIER.validate_manifest(manifest) == []


def test_rejects_a_permission_even_when_the_component_shape_is_valid(
    tmp_path: Path,
) -> None:
    manifest = _write_manifest(
        tmp_path,
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <uses-permission android:name="android.permission.RECORD_AUDIO" />
          <application android:allowBackup="false" android:usesCleartextTraffic="false">
            <activity android:name=".MainActivity" android:exported="true">
              <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
              </intent-filter>
            </activity>
          </application>
        </manifest>
        """,
    )

    assert VERIFIER.validate_manifest(manifest) == [
        "Android permissions are forbidden in HMR-101: android.permission.RECORD_AUDIO"
    ]


def test_rejects_an_added_service_and_insecure_application_defaults(
    tmp_path: Path,
) -> None:
    manifest = _write_manifest(
        tmp_path,
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <application android:allowBackup="true" android:usesCleartextTraffic="true">
            <activity android:name=".MainActivity" android:exported="true">
              <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
              </intent-filter>
            </activity>
            <service android:name=".RawBridgeService" android:exported="true" />
          </application>
        </manifest>
        """,
    )

    assert VERIFIER.validate_manifest(manifest) == [
        "application android:allowBackup must be false",
        "application android:usesCleartextTraffic must be false",
        "non-activity components are forbidden in HMR-101: service",
        "only the bootstrap launcher activity may be exported",
    ]

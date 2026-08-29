from __future__ import annotations

import importlib.util
from pathlib import Path
import textwrap


REPO_ROOT = Path(__file__).resolve().parents[3]
VERIFIER_PATH = REPO_ROOT / "scripts" / "android" / "verify_android_manifest_policy.py"
SPEC = importlib.util.spec_from_file_location("verify_android_manifest_policy", VERIFIER_PATH)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


def _write_xml(tmp_path: Path, name: str, body: str) -> Path:
    path = tmp_path / name
    path.write_text(textwrap.dedent(body), encoding="utf-8")
    return path


def test_accepts_current_transport_manifest_and_network_policy() -> None:
    app_root = REPO_ROOT / "apps" / "mobile-bridge-android" / "app" / "src" / "main"

    assert VERIFIER.validate_manifest(app_root / "AndroidManifest.xml") == []
    assert (
        VERIFIER.validate_network_security_config(
            app_root / "res" / "xml" / "network_security_config.xml"
        )
        == []
    )
    assert (
        VERIFIER.validate_backup_rules(
            app_root / "res" / "xml" / "backup_rules.xml",
            app_root / "res" / "xml" / "data_extraction_rules.xml",
        )
        == []
    )


def test_rejects_any_permission_beyond_internet(tmp_path: Path) -> None:
    manifest = _write_xml(
        tmp_path,
        "AndroidManifest.xml",
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <uses-permission android:name="android.permission.INTERNET" />
          <uses-permission android:name="android.permission.RECORD_AUDIO" />
          <application android:allowBackup="false"
              android:usesCleartextTraffic="false"
              android:networkSecurityConfig="@xml/network_security_config">
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
        "Android permissions must be exactly android.permission.INTERNET; found "
        "android.permission.INTERNET, android.permission.RECORD_AUDIO"
    ]


def test_rejects_exported_service_and_insecure_application_defaults(
    tmp_path: Path,
) -> None:
    manifest = _write_xml(
        tmp_path,
        "AndroidManifest.xml",
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <uses-permission android:name="android.permission.INTERNET" />
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
        "application android:networkSecurityConfig must reference @xml/network_security_config",
        "non-launcher components are forbidden in HMR-102: service",
        "only the launcher activity may be exported",
    ]


def test_rejects_user_ca_and_debug_trust_override(tmp_path: Path) -> None:
    config = _write_xml(
        tmp_path,
        "network_security_config.xml",
        """
        <network-security-config>
          <base-config cleartextTrafficPermitted="false">
            <trust-anchors><certificates src="user" /></trust-anchors>
          </base-config>
          <debug-overrides>
            <trust-anchors><certificates src="user" /></trust-anchors>
          </debug-overrides>
        </network-security-config>
        """,
    )

    assert VERIFIER.validate_network_security_config(config) == [
        "debug trust overrides are forbidden",
        "the only static trust anchor must be the Android system store",
    ]


def test_rejects_backup_or_transfer_that_can_clone_enrollment(tmp_path: Path) -> None:
    full_backup = _write_xml(
        tmp_path,
        "backup_rules.xml",
        """
        <full-backup-content>
          <exclude domain="root" path="." />
        </full-backup-content>
        """,
    )
    data_extraction = _write_xml(
        tmp_path,
        "data_extraction_rules.xml",
        """
        <data-extraction-rules>
          <cloud-backup><exclude domain="root" path="." /></cloud-backup>
        </data-extraction-rules>
        """,
    )

    assert VERIFIER.validate_backup_rules(full_backup, data_extraction) == [
        "full backup must exclude every app-data domain at path .",
        "cloud backup must exclude every app-data domain at path .",
        "data extraction rules require exactly one device-transfer",
    ]

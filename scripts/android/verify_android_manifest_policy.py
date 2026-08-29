#!/usr/bin/env python3
"""Fail closed when the HMR-102 Android manifest widens authority."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID = f"{{{ANDROID_NS}}}"
COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")
PERMISSION_TAGS = ("uses-permission", "uses-permission-sdk-23")
ALLOWED_PERMISSIONS = {"android.permission.INTERNET"}
NETWORK_SECURITY_CONFIG = "@xml/network_security_config"
COMPILED_REFERENCE = re.compile(r"@ref/(0x[0-9a-fA-F]{8})\\Z")
BACKUP_DOMAINS = {"root", "file", "database", "sharedpref", "external"}


def _android(element: ET.Element, attribute: str) -> str | None:
    return element.get(f"{ANDROID}{attribute}")


def _compiled_reference_matches(
    value: str | None,
    resource_table: str | None,
) -> bool:
    if value is None or resource_table is None:
        return False
    match = COMPILED_REFERENCE.fullmatch(value)
    if match is None:
        return False
    resource_id = re.escape(match.group(1).lower())
    resource_name = r"(?:[^\\s:]+:)?xml/network_security_config"
    return re.search(
        rf"(?im)^\\s*resource\\s+{resource_id}\\s+{resource_name}(?::|\\s|$)",
        resource_table,
    ) is not None


def validate_manifest(
    path: Path,
    resource_table: str | None = None,
) -> list[str]:
    """Return all HMR-102 manifest policy violations."""

    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        return [f"cannot parse manifest {path}: {exc}"]

    errors: list[str] = []
    permission_names = {
        _android(node, "name") or "<unnamed>"
        for tag in PERMISSION_TAGS
        for node in root.findall(tag)
    }
    if permission_names != ALLOWED_PERMISSIONS:
        errors.append(
            "Android permissions must be exactly "
            f"{', '.join(sorted(ALLOWED_PERMISSIONS))}; found "
            f"{', '.join(sorted(permission_names)) or '<none>'}"
        )

    application = root.find("application")
    if application is None:
        return errors + ["manifest must contain one application element"]

    if _android(application, "allowBackup") != "false":
        errors.append("application android:allowBackup must be false")
    if _android(application, "usesCleartextTraffic") != "false":
        errors.append("application android:usesCleartextTraffic must be false")
    network_security_config = _android(application, "networkSecurityConfig")
    if (
        network_security_config != NETWORK_SECURITY_CONFIG
        and not _compiled_reference_matches(network_security_config, resource_table)
    ):
        errors.append(
            "application android:networkSecurityConfig must reference "
            f"{NETWORK_SECURITY_CONFIG}; found "
            f"{network_security_config or '<none>'}"
        )
    if _android(application, "permission"):
        errors.append("application-level Android permission is forbidden")

    components = [node for tag in COMPONENT_TAGS for node in application.findall(tag)]
    forbidden = [node.tag for node in components if node.tag != "activity"]
    if forbidden:
        errors.append(
            "non-launcher components are forbidden in HMR-102: "
            f"{', '.join(sorted(forbidden))}"
        )

    activities = application.findall("activity")
    if len(activities) != 1:
        errors.append(f"expected exactly one launcher activity, found {len(activities)}")
        return errors

    activity = activities[0]
    activity_names = {
        ".MainActivity",
        "ai.hermes.mobile.runtime.bridge.MainActivity",
    }
    if _android(activity, "name") not in activity_names:
        errors.append("the only activity must resolve to MainActivity")
    if _android(activity, "exported") != "true":
        errors.append("the launcher activity must explicitly set android:exported=true")
    if _android(activity, "permission"):
        errors.append("activity-level Android permission is forbidden")

    actions = {
        _android(node, "name")
        for intent_filter in activity.findall("intent-filter")
        for node in intent_filter.findall("action")
    }
    categories = {
        _android(node, "name")
        for intent_filter in activity.findall("intent-filter")
        for node in intent_filter.findall("category")
    }
    if "android.intent.action.MAIN" not in actions:
        errors.append("launcher activity must declare android.intent.action.MAIN")
    if "android.intent.category.LAUNCHER" not in categories:
        errors.append("launcher activity must declare android.intent.category.LAUNCHER")

    exported = [node for node in components if _android(node, "exported") == "true"]
    if exported != [activity]:
        errors.append("only the launcher activity may be exported")

    return errors


def validate_network_security_config(path: Path) -> list[str]:
    """Require system trust anchors and globally disabled cleartext."""

    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        return [f"cannot parse network security config {path}: {exc}"]

    errors: list[str] = []
    if root.tag != "network-security-config":
        return ["network security config root must be network-security-config"]
    if root.find("debug-overrides") is not None:
        errors.append("debug trust overrides are forbidden")
    if root.findall("domain-config"):
        errors.append("static domain trust exceptions are forbidden")

    base_configs = root.findall("base-config")
    if len(base_configs) != 1:
        return errors + [f"expected exactly one base-config, found {len(base_configs)}"]
    base_config = base_configs[0]
    if base_config.get("cleartextTrafficPermitted") != "false":
        errors.append("base-config cleartextTrafficPermitted must be false")

    certificates = base_config.findall("./trust-anchors/certificates")
    certificate_sources = [certificate.get("src") for certificate in certificates]
    if certificate_sources != ["system"]:
        errors.append("the only static trust anchor must be the Android system store")
    if any(set(certificate.attrib) != {"src"} for certificate in certificates):
        errors.append("static trust-anchor overrides are forbidden")

    return errors


def _validate_exclude_set(
    parent: ET.Element,
    label: str,
) -> list[str]:
    errors: list[str] = []
    if parent.findall("include"):
        errors.append(f"{label} must not contain include rules")
    exclusions = {(node.get("domain"), node.get("path")) for node in parent.findall("exclude")}
    expected = {(domain, ".") for domain in BACKUP_DOMAINS}
    if exclusions != expected:
        errors.append(f"{label} must exclude every app-data domain at path .")
    return errors


def validate_backup_rules(
    full_backup_path: Path,
    data_extraction_path: Path,
) -> list[str]:
    """Ensure enrollment/replay state cannot be cloned by backup or transfer."""

    errors: list[str] = []
    try:
        full_backup = ET.parse(full_backup_path).getroot()
    except (ET.ParseError, OSError) as exc:
        errors.append(f"cannot parse full backup rules {full_backup_path}: {exc}")
    else:
        if full_backup.tag != "full-backup-content":
            errors.append("full backup rules root must be full-backup-content")
        else:
            errors.extend(_validate_exclude_set(full_backup, "full backup"))

    try:
        data_extraction = ET.parse(data_extraction_path).getroot()
    except (ET.ParseError, OSError) as exc:
        errors.append(f"cannot parse data extraction rules {data_extraction_path}: {exc}")
    else:
        if data_extraction.tag != "data-extraction-rules":
            errors.append("data extraction rules root must be data-extraction-rules")
        else:
            cloud_backup = data_extraction.findall("cloud-backup")
            device_transfer = data_extraction.findall("device-transfer")
            if len(cloud_backup) != 1:
                errors.append("data extraction rules require exactly one cloud-backup")
            else:
                errors.extend(_validate_exclude_set(cloud_backup[0], "cloud backup"))
            if len(device_transfer) != 1:
                errors.append("data extraction rules require exactly one device-transfer")
            else:
                errors.extend(_validate_exclude_set(device_transfer[0], "device transfer"))

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifests", nargs="+", type=Path)
    parser.add_argument("--network-security-config", required=True, type=Path)
    parser.add_argument("--full-backup-rules", required=True, type=Path)
    parser.add_argument("--data-extraction-rules", required=True, type=Path)
    parser.add_argument("--compiled-resource-table", type=Path)
    args = parser.parse_args()

    resource_table: str | None = None
    if args.compiled_resource_table is not None:
        try:
            resource_table = args.compiled_resource_table.read_text(encoding="utf-8")
        except OSError as exc:
            print(
                f"cannot read resource table {args.compiled_resource_table}: {exc}",
                file=sys.stderr,
            )
            return 1

    failures = {
        str(path): errors
        for path in args.manifests
        if (errors := validate_manifest(path, resource_table))
    }
    network_errors = validate_network_security_config(args.network_security_config)
    if network_errors:
        failures[str(args.network_security_config)] = network_errors
    backup_errors = validate_backup_rules(
        args.full_backup_rules,
        args.data_extraction_rules,
    )
    if backup_errors:
        failures["Android backup policy"] = backup_errors

    if failures:
        for path, errors in failures.items():
            for error in errors:
                print(f"{path}: {error}", file=sys.stderr)
        return 1

    for path in args.manifests:
        print(f"verified HMR-102 Android manifest policy: {path}")
    print(f"verified TLS-only network policy: {args.network_security_config}")
    print("verified no-backup/no-transfer policy")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

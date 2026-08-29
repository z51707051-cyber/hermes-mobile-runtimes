#!/usr/bin/env python3
"""Fail closed when the HMR-101 Android manifest gains capabilities."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID = f"{{{ANDROID_NS}}}"
COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")
PERMISSION_TAGS = ("uses-permission", "uses-permission-sdk-23")


def _android(element: ET.Element, attribute: str) -> str | None:
    return element.get(f"{ANDROID}{attribute}")


def validate_manifest(path: Path) -> list[str]:
    """Return all HMR-101 policy violations in one deterministic pass."""

    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        return [f"cannot parse manifest {path}: {exc}"]

    errors: list[str] = []
    permissions = [node for tag in PERMISSION_TAGS for node in root.findall(tag)]
    if permissions:
        names = sorted(_android(node, "name") or "<unnamed>" for node in permissions)
        errors.append(
            f"Android permissions are forbidden in HMR-101: {', '.join(names)}"
        )

    application = root.find("application")
    if application is None:
        return errors + ["manifest must contain one application element"]

    if _android(application, "allowBackup") != "false":
        errors.append("application android:allowBackup must be false")
    if _android(application, "usesCleartextTraffic") != "false":
        errors.append("application android:usesCleartextTraffic must be false")
    if _android(application, "permission"):
        errors.append("application-level Android permission is forbidden in HMR-101")

    components = [node for tag in COMPONENT_TAGS for node in application.findall(tag)]
    forbidden = [node.tag for node in components if node.tag != "activity"]
    if forbidden:
        errors.append(
            f"non-activity components are forbidden in HMR-101: {', '.join(sorted(forbidden))}"
        )

    activities = application.findall("activity")
    if len(activities) != 1:
        errors.append(
            f"expected exactly one bootstrap activity, found {len(activities)}"
        )
        return errors

    activity = activities[0]
    activity_names = {
        ".MainActivity",
        "ai.hermes.mobile.runtime.bridge.MainActivity",
    }
    if _android(activity, "name") not in activity_names:
        errors.append("the only activity must resolve to the bootstrap MainActivity")
    if _android(activity, "exported") != "true":
        errors.append("the launcher activity must explicitly set android:exported=true")
    if _android(activity, "permission"):
        errors.append("activity-level Android permission is forbidden in HMR-101")

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
        errors.append("bootstrap activity must declare android.intent.action.MAIN")
    if "android.intent.category.LAUNCHER" not in categories:
        errors.append(
            "bootstrap activity must declare android.intent.category.LAUNCHER"
        )

    exported = [node for node in components if _android(node, "exported") == "true"]
    if exported != [activity]:
        errors.append("only the bootstrap launcher activity may be exported")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifests", nargs="+", type=Path)
    args = parser.parse_args()

    failures = {
        str(path): errors
        for path in args.manifests
        if (errors := validate_manifest(path))
    }
    if failures:
        for path, errors in failures.items():
            for error in errors:
                print(f"{path}: {error}", file=sys.stderr)
        return 1

    for path in args.manifests:
        print(f"verified minimal-permission manifest: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

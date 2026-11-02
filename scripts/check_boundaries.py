"""Fail CI if shared core starts depending on Android/native/UI APIs."""

import argparse
import pathlib
import re
import sys
import xml.etree.ElementTree as ET


def source_violations(root):
    violations = []
    common = root / "shared/src/commonMain/kotlin/app/finance"
    imports = re.compile(r"^\s*import\s+([\w.]+)")
    for path in (root / "shared/src/commonMain").rglob("*.kt"):
        in_data = path.is_relative_to(common / "data")
        for line in path.read_text().splitlines():
            match = imports.match(line)
            if not match:
                continue
            name = match.group(1)
            portable_data = in_data and name.startswith(("androidx.room.", "androidx.sqlite."))
            platform = name.startswith(("android.", "androidx.", "java.", "javax.")) or ".jni." in name
            inward = not in_data and name.startswith("app.finance.data.")
            if (platform and not portable_data) or inward:
                violations.append(f"Platform/layer dependency: {path.relative_to(root)}")
    return violations


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest", type=pathlib.Path,
        default=root / "androidApp/src/main/AndroidManifest.xml",
        help="Source or packaged manifest to validate (plain XML)",
    )
    args = parser.parse_args()
    violations = source_violations(root)

    try:
        tree = ET.parse(args.manifest)
    except (OSError, ET.ParseError):
        print("Manifest is missing, unreadable or invalid XML", file=sys.stderr)
        return 1
    namespace = "{http://schemas.android.com/apk/res/android}"
    permissions = tree.findall("uses-permission") + tree.findall("uses-permission-sdk-23")
    for permission in permissions:
        if permission.get(namespace + "name") == "android.permission.INTERNET":
            violations.append("Foundation must work without INTERNET permission")
    application = tree.find("application")
    if application is None or application.get(namespace + "allowBackup") != "false":
        violations.append("Implicit OS backup must be disabled")

    for violation in violations:
        print(violation, file=sys.stderr)
    if violations:
        return 1
    print("PASS: commonMain boundaries; supplied manifest network/backup policy")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Verify an additive APK upgrade using synthetic UI records on a fresh emulator app.

Requires a previously built Finance App schema2 APK, never downloaded or cleared here.
"""
import argparse
import re
import subprocess

from emulator_smoke import PACKAGE
from ledger_emulator_smoke import LedgerSmoke


def run_upgrade(runner, previous_apk, current_apk):
    if runner.shell("getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("A real emulator is required before install")
    installed = runner.shell("pm", "list", "packages", PACKAGE).splitlines()
    if "package:" + PACKAGE in installed:
        runner.assert_fresh()
    if runner.shell("settings", "get", "system", "font_scale") not in ["1", "1.0"]:
        raise RuntimeError("Use the dedicated portrait emulator at font100 before this run")
    if "Success" not in runner.command("install", "-r", previous_apk):
        raise AssertionError("Previous APK installation failed")
    runner.assert_fresh()
    original_airplane = runner.shell("settings", "get", "global", "airplane_mode_on")
    original = {key: runner.shell("settings", "get", "system", key)
                for key in ["font_scale", "accelerometer_rotation", "user_rotation"]}
    night = re.search(r"Night mode: (\w+)", runner.shell("cmd", "uimode", "night"))
    if night is None or night.group(1) not in ["yes", "no", "auto"]:
        raise RuntimeError("Cannot determine theme for restoration")
    try:
        runner.shell("settings", "put", "system", "accelerometer_rotation", "0")
        runner.shell("settings", "put", "system", "user_rotation", "0")
        runner.shell("cmd", "uimode", "night", "no")
        runner.shell("cmd", "connectivity", "airplane-mode", "enable")
        assert runner.shell("settings", "get", "global", "airplane_mode_on") == "1"
        runner.launch()
        runner.fill("setup_currency", "USD")
        runner.fill("setup_zone", "UTC", replace=True)
        runner.click("setup_save")
        runner.balance("0.00 USD")
        runner.add("12.50", "UpgExpense", "Fin012IdentityFixture")
        runner.balance("-12.50 USD")
        runner.click("home_add")
        runner.fill("editor_amount", "1.245")
        runner.fill("editor_currency", "KWD", replace=True)
        runner.fill("editor_rate", "1.004")
        runner.click("editor_new_category")
        runner.fill("category_name", "UpgForeign")
        runner.click("category_save")
        runner.fill("editor_time", "2026-11-01T09:02:03.123456789", replace=True)
        runner.fill("editor_note", "Fin012ForeignFixture")
        runner.click("editor_save")
        runner.balance("-13.75 USD")
        runner.capture("01-v2-before-upgrade")
        old_pid = runner.shell("pidof", PACKAGE)
        runner.shell("am", "force-stop", PACKAGE)
        if "Success" not in runner.command("install", "-r", current_apk):
            raise AssertionError("Current APK installation failed")
        runner.launch()
        runner.balance("-13.75 USD")
        assert old_pid and runner.shell("pidof", PACKAGE) != old_pid
        runner.capture("02-v3-after-upgrade")
        runner.click("nav_TRANSACTIONS")
        runner.click(runner.row_action("UpgForeign", "edit"))
        for tag, expected in [("editor_amount", "1.245"), ("editor_currency", "KWD"),
                              ("editor_rate", "1.004"),
                              ("editor_time", "2026-11-01T09:02:03.123456789"),
                              ("editor_note", "Fin012ForeignFixture")]:
            assert runner.seek(tag).get("text") == expected, "Migrated synthetic field differs: " + tag
        runner.click("fx_remember")
        runner.seek("fx_saved")
        runner.capture("03-migrated-frozen-fields")
        runner.click("editor_cancel")
        runner.click("nav_HOME")
        before_restart = runner.shell("pidof", PACKAGE)
        runner.shell("am", "force-stop", PACKAGE)
        runner.launch()
        runner.balance("-13.75 USD")
        assert before_restart and runner.shell("pidof", PACKAGE) != before_restart
        runner.capture("04-v3-after-process-restart")
        print("PASS v2→v3 install-r retained two records, exact KWD snapshot/nanos/note and balance across new processes", flush=True)
        runner.click("home_add")
        runner.fill("editor_currency", "KWD", replace=True)
        runner.fill("editor_time", "2026-11-01T10:02:03.123456789", replace=True)
        runner.click("fx_find")
        runner.seek("fx_candidate_rate")
        assert runner.seek("editor_rate").get("text") == ""
        runner.click("fx_use")
        assert runner.seek("editor_rate").get("text") == "1.004"
        assert "09:02:03" in runner.seek("fx_candidate_date").get("text", "")
        runner.capture("05-cache-after-process-restart")
        runner.shell("cmd", "uimode", "night", "yes")
        runner.shell("settings", "put", "system", "font_scale", "2.0")
        runner.wait_configuration(2.0, True, False)
        runner.seek("fx_applied")
        runner.seek("fx_candidate_rate")
        runner.capture("06-cache-dark-font200")
        runner.shell("settings", "put", "system", "user_rotation", "1")
        runner.wait_configuration(2.0, True, True)
        runner.seek("fx_applied")
        runner.seek("fx_use")
        runner.capture("07-cache-landscape-font200", landscape=True)
        runner.shell("settings", "put", "system", "font_scale", "1.0")
        runner.shell("settings", "put", "system", "user_rotation", "0")
        runner.shell("cmd", "uimode", "night", "no")
        runner.wait_configuration(1.0, False, False)
        assert runner.seek("editor_rate").get("text") == "1.004"
        runner.click("editor_cancel")
        runner.balance("-13.75 USD")
        print("PASS remembered quote survived process death, required explicit use and did not create a third transaction", flush=True)
        for category in ["UpgForeign", "UpgExpense"]:
            runner.click("nav_TRANSACTIONS")
            runner.click(runner.row_action(category, "delete"))
            runner.click("delete_confirm")
            runner.click("nav_HOME")
        runner.balance("0.00 USD")
        runner.capture("08-owned-fixtures-deleted")
    finally:
        errors = []
        cleanup = [("settings", "delete", "system", key) if value == "null"
                   else ("settings", "put", "system", key, value)
                   for key, value in original.items()]
        cleanup += [("cmd", "uimode", "night", night.group(1)),
                    ("cmd", "connectivity", "airplane-mode", "enable" if original_airplane == "1" else "disable"),
                    ("rm", "-f", "/sdcard/finance-foundation-ui.xml")]
        for action in cleanup:
            try:
                runner.shell(*action)
            except (subprocess.SubprocessError, OSError):
                errors.append(action[0])
        if errors:
            raise RuntimeError("Could not restore emulator smoke settings")
    print(f"PASS upgrade smoke; {len(runner.captures)} real PNGs; owned rows deleted by confirmed UI; airplane mode restored", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--from-apk", required=True)
    parser.add_argument("--to-apk", default="androidApp/build/outputs/apk/debug/androidApp-debug.apk")
    parser.add_argument("--output", default="artifacts/screenshots/fin012-upgrade")
    args = parser.parse_args()
    run_upgrade(LedgerSmoke(args.adb, args.serial, args.output), args.from_apk, args.to_apk)


if __name__ == "__main__":
    main()

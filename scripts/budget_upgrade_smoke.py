"""Verify schema3→4 upgrade, explicit budget save and process survival on an owned emulator.

Requires a fresh app and a previously built schema3 APK. Never clears application data.
"""
import argparse
import re
import subprocess

from emulator_smoke import PACKAGE
from ledger_emulator_smoke import LedgerSmoke


def run_budget(runner, previous_apk, current_apk):
    if runner.shell("getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("A real emulator is required before installation")
    if "package:" + PACKAGE in runner.shell("pm", "list", "packages", PACKAGE).splitlines():
        runner.assert_fresh()
    original = {key: runner.shell("settings", "get", "system", key)
                for key in ["font_scale", "accelerometer_rotation", "user_rotation"]}
    if original["font_scale"] not in ["1", "1.0"]:
        raise RuntimeError("Use the dedicated emulator at font100")
    airplane = runner.shell("settings", "get", "global", "airplane_mode_on")
    night = re.search(r"Night mode: (\w+)", runner.shell("cmd", "uimode", "night"))
    if night is None or night.group(1) not in ["yes", "no", "auto"]:
        raise RuntimeError("Cannot determine theme for restoration")
    if "Success" not in runner.command("install", "-r", previous_apk):
        raise AssertionError("Previous APK install failed")
    runner.assert_fresh()
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
        runner.add("12.50", "BudgetExpense", "Fin013ExpenseFixture")
        runner.add("50.00", "BudgetIncome", "Fin013IncomeFixture", income=True)
        runner.balance("37.50 USD")
        runner.capture("01-v3-ledger")
        runner.shell("am", "force-stop", PACKAGE)
        if "Success" not in runner.command("install", "-r", current_apk):
            raise AssertionError("Current APK install failed")
        runner.launch()
        runner.balance("37.50 USD")
        runner.capture("02-v4-after-upgrade")
        runner.click("nav_ANALYTICS")
        assert "12.50 USD" in runner.seek("budget_spent").get("text", "")
        runner.seek("budget_empty")
        runner.click("budget_edit")
        runner.fill("budget_amount", "10.00")
        runner.click("budget_save")
        assert "2.50 USD" in runner.seek("budget_overrun").get("text", "")
        runner.capture("03-overrun-income-not-offset")
        runner.click("budget_edit")
        runner.fill("budget_amount", "20.00", replace=True)
        if "mInputShown=true" in runner.shell("dumpsys", "input_method"):
            runner.shell("input", "keyevent", "4")
        runner.shell("cmd", "uimode", "night", "yes")
        runner.shell("settings", "put", "system", "font_scale", "2.0")
        runner.wait_configuration(2.0, True, False)
        assert runner.seek("budget_amount").get("text") == "20.00"
        runner.capture("04-unsaved-dark-font200")
        runner.shell("settings", "put", "system", "user_rotation", "1")
        runner.wait_configuration(2.0, True, True)
        assert runner.seek("budget_amount").get("text") == "20.00"
        runner.seek("budget_save")
        runner.capture("05-unsaved-landscape-font200", landscape=True)
        runner.shell("settings", "put", "system", "font_scale", "1.0")
        runner.shell("settings", "put", "system", "user_rotation", "0")
        runner.shell("cmd", "uimode", "night", "no")
        runner.wait_configuration(1.0, False, False)
        assert runner.seek("budget_amount").get("text") == "20.00"
        runner.click("budget_save")
        assert "7.50 USD" in runner.seek("budget_remaining").get("text", "")
        runner.capture("06-explicit-save")
        before = runner.shell("pidof", PACKAGE)
        runner.shell("am", "force-stop", PACKAGE)
        runner.launch()
        runner.balance("37.50 USD")
        assert before and runner.shell("pidof", PACKAGE) != before
        runner.click("nav_ANALYTICS")
        assert "7.50 USD" in runner.seek("budget_remaining").get("text", "")
        runner.capture("07-budget-after-process-restart")
        for category in ["BudgetExpense", "BudgetIncome"]:
            runner.click("nav_TRANSACTIONS")
            runner.click(runner.row_action(category, "delete"))
            runner.click("delete_confirm")
            runner.click("nav_HOME")
        runner.balance("0.00 USD")
        runner.click("nav_ANALYTICS")
        assert "0.00 USD" in runner.seek("budget_spent").get("text", "")
        assert "20.00 USD" in runner.seek("budget_remaining").get("text", "")
        runner.capture("08-after-owned-fixture-deletion")
        print("PASS schema3→4 retained ledger; income did not offset spend; exact limit/overrun/remaining", flush=True)
        print("PASS unsaved limit survived theme/font200/rotation; committed budget survived new process; CRUD refreshed budget", flush=True)
    finally:
        actions = [("settings", "delete", "system", key) if value == "null"
                   else ("settings", "put", "system", key, value)
                   for key, value in original.items()]
        actions += [("cmd", "uimode", "night", night.group(1)),
                    ("cmd", "connectivity", "airplane-mode", "enable" if airplane == "1" else "disable"),
                    ("rm", "-f", "/sdcard/finance-foundation-ui.xml")]
        failed = 0
        for action in actions:
            try:
                runner.shell(*action)
            except (subprocess.SubprocessError, OSError):
                failed += 1
        if failed:
            raise RuntimeError("Failed to restore emulator smoke settings")
    print(f"PASS budget smoke; {len(runner.captures)} real PNGs; own transaction fixtures deleted by confirmed UI; settings restored", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--from-apk", required=True)
    parser.add_argument("--to-apk", required=True)
    parser.add_argument("--output", default="artifacts/screenshots/fin013-budget")
    args = parser.parse_args()
    run_budget(LedgerSmoke(args.adb, args.serial, args.output), args.from_apk, args.to_apk)


if __name__ == "__main__":
    main()

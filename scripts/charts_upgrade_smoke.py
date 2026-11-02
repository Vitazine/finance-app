"""Check retained synthetic ledger and chart UI on an owned emulator, including font/rotation.

Requires a fresh app plus an existing FIN-014 APK. Never clears application data.
"""
import argparse
import datetime
import re
import struct
import subprocess

from emulator_smoke import PACKAGE
from ledger_emulator_smoke import LedgerSmoke


def dollars(minor):
    return str(minor // 100) + "." + str(minor % 100).zfill(2) + " USD"


def frame_chart(runner):
    for _ in range(3):
        node = runner.seek("daily_chart")
        x1, y1, x2, y2 = runner.bounds(node)
        width, height = struct.unpack(">II", runner.png()[16:24])
        delta = (y1 + y2) / 2 - height * 0.55
        if abs(delta) < height * 0.04:
            return
        step = max(-height * 0.18, min(height * 0.18, delta))
        runner.shell("input", "swipe", str(int(width * 0.8)), str(int(height * 0.48)),
                     str(int(width * 0.8)), str(int(height * 0.48 - step)), "300")
    runner.seek("daily_chart")


def add_dated(runner, amount, category, day):
    runner.click("home_add")
    runner.fill("editor_amount", amount)
    runner.click("editor_new_category")
    runner.fill("category_name", category)
    runner.click("category_save")
    runner.fill("editor_time", day.isoformat() + "T12:00:00", replace=True)
    runner.fill("editor_note", "Fin015SyntheticFixture")
    runner.click("editor_save")


def assert_expanded(runner):
    node = runner.seek("analytics_days_toggle")
    if not any("Скрыть значения" in child.get("text", "") for child in node.iter("node")):
        raise AssertionError("Day details expansion did not survive configuration")


def run_charts(runner, previous_apk, current_apk):
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
    today = datetime.date.fromisoformat(runner.shell("date", "-u", "+%Y-%m-%d"))
    past = [(today - datetime.timedelta(days=1), 1250),
            (today - datetime.timedelta(days=3), 750)]
    actual = 500 + sum(minor for day, minor in past if (day.year, day.month) == (today.year, today.month))
    if "Success" not in runner.command("install", "-r", previous_apk):
        raise AssertionError("Previous APK install failed")
    runner.assert_fresh()

    def same_day():
        if runner.shell("date", "-u", "+%Y-%m-%d") != today.isoformat():
            raise RuntimeError("UTC day changed during scenario; fixture expectations no longer apply")

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
        add_dated(runner, "12.50", "CafeFixture", past[0][0])
        add_dated(runner, "7.50", "FoodFixture", past[1][0])
        runner.add("5.00", "TodayFixture", "Fin015TodayFixture")
        runner.add("50.00", "IncomeFixture", "Fin015IncomeFixture", income=True)
        same_day()
        runner.balance("25.00 USD")
        runner.shell("am", "force-stop", PACKAGE)
        if "Success" not in runner.command("install", "-r", current_apk):
            raise AssertionError("Current APK install failed")
        runner.launch()
        runner.balance("25.00 USD")
        assert "20.00 USD" in runner.seek("analytics_total").get("text", "")
        frame_chart(runner)
        runner.capture("01-home-chart-light")
        runner.click("nav_ANALYTICS")
        runner.click("budget_edit")
        runner.fill("budget_amount", "10.00")
        runner.click("budget_save")
        budget_tag = "budget_overrun" if actual > 1000 else "budget_remaining"
        assert dollars(abs(actual - 1000)) in runner.seek(budget_tag).get("text", "")
        assert "≈ 0.67 USD" in runner.seek("analytics_daily_average").get("text", "")
        assert "≈ 4.67 USD" in runner.seek("analytics_weekly").get("text", "")
        assert dollars(actual) in runner.seek("analytics_month_actual").get("text", "")
        runner.capture("02-budget-rates-light")
        runner.seek("analytics_categories_title")
        runner.seek("analytics_category")
        runner.capture("03-category-chart-light")
        runner.click("analytics_days_toggle")
        runner.shell("cmd", "uimode", "night", "yes")
        runner.shell("settings", "put", "system", "font_scale", "2.0")
        runner.wait_configuration(2.0, True, False)
        assert_expanded(runner)
        frame_chart(runner)
        runner.capture("04-chart-dark-font200")
        runner.shell("settings", "put", "system", "user_rotation", "1")
        runner.wait_configuration(2.0, True, True)
        assert_expanded(runner)
        frame_chart(runner)
        runner.capture("05-chart-landscape-font200", landscape=True)
        runner.shell("settings", "put", "system", "font_scale", "1.0")
        runner.shell("settings", "put", "system", "user_rotation", "0")
        runner.shell("cmd", "uimode", "night", "no")
        runner.wait_configuration(1.0, False, False)
        same_day()
        before = runner.shell("pidof", PACKAGE)
        runner.shell("am", "force-stop", PACKAGE)
        runner.launch()
        runner.balance("25.00 USD")
        assert before and runner.shell("pidof", PACKAGE) != before
        runner.click("nav_ANALYTICS")
        assert "20.00 USD" in runner.seek("analytics_total").get("text", "")
        frame_chart(runner)
        runner.capture("06-charts-after-process-restart")
        runner.click("nav_TRANSACTIONS")
        runner.click("history_chart_toggle")
        frame_chart(runner)
        runner.capture("07-history-chart-light")
        runner.click("filters_toggle")
        runner.click("type_INCOME")
        runner.click("filters_apply")
        runner.seek("analytics_history_scope")
        assert not any(runner.resource(n) == "daily_chart" for n in runner.nodes())
        runner.capture("08-filtered-history-scope")
        runner.click("filters_reset")
        runner.click("filters_toggle")
        runner.click("history_chart_toggle")
        for category in ["CafeFixture", "FoodFixture", "TodayFixture", "IncomeFixture"]:
            runner.click(runner.row_action(category, "delete"))
            runner.click("delete_confirm")
        runner.click("nav_HOME")
        runner.balance("0.00 USD")
        runner.click("nav_ANALYTICS")
        runner.seek("analytics_empty")
        assert "0.00 USD" in runner.seek("analytics_total").get("text", "")
        frame_chart(runner)
        runner.capture("09-empty-chart-after-own-deletions")
        print("PASS retained four synthetic records; completed-day total20.00, rounded daily0.67/weekly4.67, separate month actual", flush=True)
        print("PASS exact chart UI offline; dark/font200/rotation/details; new process; explicit filtered-history scope; own row deletions", flush=True)
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
            raise RuntimeError("Failed to restore emulator settings")
    print(f"PASS chart smoke; {len(runner.captures)} real PNGs; settings restored; own ledger fixtures deleted by confirmed UI", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--from-apk", required=True)
    parser.add_argument("--to-apk", required=True)
    parser.add_argument("--output", default="artifacts/screenshots/fin015-charts")
    args = parser.parse_args()
    run_charts(LedgerSmoke(args.adb, args.serial, args.output), args.from_apk, args.to_apk)


if __name__ == "__main__":
    main()

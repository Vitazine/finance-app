"""Exercise synthetic manual CRUD and process restart on a fresh, owned emulator app.

Refuses a pre-existing default ledger. Never clears/uninstalls application data.
"""
import argparse
import re
import struct
import subprocess
import time

from emulator_smoke import PACKAGE, EmulatorSmoke


class LedgerSmoke(EmulatorSmoke):
    def nodes(self):
        nodes = super().nodes()
        children = {id(child) for node in nodes for child in node}
        self._scroll_clips = {}

        def visit(node, clip):
            self._scroll_clips[id(node)] = clip
            if node.get("scrollable") == "true":
                values = [int(x) for x in re.findall(r"-?\d+", node.get("bounds", ""))]
                if len(values) != 4:
                    raise AssertionError("Scrollable viewport has invalid bounds")
                if clip is not None:
                    values = [max(values[0], clip[0]), max(values[1], clip[1]),
                              min(values[2], clip[2]), min(values[3], clip[3])]
                clip = values
            for child in node:
                visit(child, clip)

        for node in nodes:
            if id(node) not in children:
                visit(node, None)
        return nodes

    def resource(self, node):
        return node.get("resource-id", "").split(":id/")[-1]

    def bounds(self, node):
        values = [int(x) for x in re.findall(r"-?\d+", node.get("bounds", ""))]
        if len(values) != 4:
            return None
        width, height = struct.unpack(">II", self.png()[16:24])
        x1, y1, x2, y2 = values
        clip = getattr(self, "_scroll_clips", {}).get(id(node))
        if clip is not None and not (clip[0] <= x1 < x2 <= clip[2] and clip[1] <= y1 < y2 <= clip[3]):
            return None
        return values if 0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height else None

    def swipe_content(self, down=False):
        width, height = struct.unpack(">II", self.png()[16:24])
        first, last = (0.25, 0.48) if down else (0.48, 0.25)
        self.shell("input", "swipe", str(int(width * 0.8)), str(int(height * first)),
                   str(int(width * 0.8)), str(int(height * last)), "300")

    def seek(self, tag, scroll=True):
        deadline = time.monotonic() + 40
        attempts = 0
        while time.monotonic() < deadline:
            found = next((n for n in self.nodes() if self.resource(n) == tag), None)
            if found is not None and found.get("enabled") == "false":
                time.sleep(0.25)
                continue
            if found is not None and self.bounds(found):
                return found
            if scroll:
                self.swipe_content(down=attempts % 12 < 3)
                attempts += 1
            else:
                time.sleep(0.25)
        raise AssertionError("UI resource not usable: " + tag)

    def click(self, tag):
        node = self.seek(tag)
        x1, y1, x2, y2 = self.bounds(node)
        self.shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def fill(self, tag, value, replace=False):
        if not re.fullmatch(r"[A-Za-z0-9._:-]+", value):
            raise ValueError("Runner accepts only bounded synthetic ASCII input")
        if len(value) > 80:
            raise ValueError("Synthetic input is too long")
        self.click(tag)
        if replace:
            previous = self.seek(tag, scroll=False).get("text", "")
            if len(previous) > 80:
                raise ValueError("Refusing oversized synthetic replacement")
            self.shell("input", "keyevent", "123")  # KEYCODE_MOVE_END
            self.shell("input", "keyevent", *(["67"] * (len(previous) + 1)))
        self.shell("input", "text", value)
        if self.seek(tag, scroll=False).get("text") != value:
            raise AssertionError("Synthetic field did not receive the intended value: " + tag)

    def balance(self, value):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            node = next((n for n in self.nodes() if self.resource(n) == "home_balance"), None)
            if node is not None and node.get("text") == value and self.bounds(node):
                return
            time.sleep(0.25)
        raise AssertionError("Committed balance not displayed")

    def row_action(self, category, prefix):
        for attempt in range(15):
            for node in self.nodes():
                if self.resource(node) == "transaction_row" and any(child.get("text") == category for child in node.iter("node")):
                    action = next((self.resource(child) for child in node.iter("node") if self.resource(child).startswith(prefix + "_")), None)
                    if action:
                        return action
            self.swipe_content(down=attempt < 3)
        raise AssertionError("Synthetic row action not found")

    def add(self, amount, category, note, income=False):
        self.click("home_add")
        if income:
            self.click("type_INCOME")
        self.fill("editor_amount", amount)
        self.click("editor_new_category")
        self.fill("category_name", category)
        self.click("category_save")
        self.seek("editor_amount")
        self.fill("editor_note", note)
        self.click("editor_save")

    def assert_fresh(self):
        if self.shell("getprop", "ro.kernel.qemu") != "1":
            raise RuntimeError("A real emulator is required")
        result = subprocess.run(
            [self.adb, "-s", self.serial, "shell", "run-as", PACKAGE, "ls", "databases/finance-ledger.db"],
            capture_output=True, text=True, timeout=20,
        )
        if result.returncode == 0:
            raise RuntimeError("Refusing pre-existing default ledger; no data has been cleared")
        if "No such file or directory" not in result.stderr + result.stdout:
            raise RuntimeError("Cannot prove default ledger is absent")

    def run_ledger(self):
        self.assert_fresh()
        original_airplane = self.shell("settings", "get", "global", "airplane_mode_on")
        original = {key: self.shell("settings", "get", "system", key)
                    for key in ["font_scale", "accelerometer_rotation", "user_rotation"]}
        night = re.search(r"Night mode: (\w+)", self.shell("cmd", "uimode", "night"))
        if night is None or night.group(1) not in ["yes", "no", "auto"]:
            raise AssertionError("Cannot determine theme for restoration")
        try:
            self.shell("settings", "put", "system", "font_scale", "1.0")
            self.shell("settings", "put", "system", "accelerometer_rotation", "0")
            self.shell("settings", "put", "system", "user_rotation", "0")
            self.shell("cmd", "uimode", "night", "no")
            self.shell("cmd", "connectivity", "airplane-mode", "enable")
            assert self.shell("settings", "get", "global", "airplane_mode_on") == "1"
            self.launch()
            self.fill("setup_currency", "USD")
            self.click("setup_save")
            self.balance("0.00 USD")
            self.capture("01-initial-ledger")
            self.add("12.50", "SmokeExpense", "Fin011ProcessFixture")
            self.balance("-12.50 USD")
            self.add("50.00", "SmokeIncome", "Fin011IncomeFixture", income=True)
            self.balance("37.50 USD")
            self.capture("02-income-expense-home")
            self.click("nav_TRANSACTIONS")
            expense_edit = self.row_action("SmokeExpense", "edit")
            self.click(expense_edit)
            self.fill("editor_amount", "20.00", replace=True)
            self.shell("input", "keyevent", "4")  # Dismiss the input keyboard.
            self.capture("03-editing-expense")
            self.shell("cmd", "uimode", "night", "yes")
            self.shell("settings", "put", "system", "font_scale", "2.0")
            self.wait_configuration(2.0, True, False)
            assert self.seek("editor_amount").get("text") == "20.00"
            self.capture("03a-editor-dark-font-200")
            self.shell("settings", "put", "system", "user_rotation", "1")
            self.wait_configuration(2.0, True, True)
            assert self.seek("editor_amount").get("text") == "20.00"
            self.capture("03b-editor-landscape-font-200", landscape=True)
            self.shell("settings", "put", "system", "font_scale", "1.0")
            self.shell("settings", "put", "system", "user_rotation", "0")
            self.shell("cmd", "uimode", "night", "no")
            self.wait_configuration(1.0, False, False)
            assert self.seek("editor_amount").get("text") == "20.00"
            print("PASS unsaved amount retained through theme/font/rotation recreation", flush=True)
            self.click("editor_save")
            self.click("nav_HOME")
            self.balance("30.00 USD")
            old_pid = self.shell("pidof", PACKAGE)
            self.shell("am", "force-stop", PACKAGE)
            self.launch()
            self.balance("30.00 USD")
            new_pid = self.shell("pidof", PACKAGE)
            assert old_pid and new_pid and old_pid != new_pid
            self.capture("04-after-process-restart")
            print("PASS committed income/expense/edit/balance survived a new process", flush=True)
            self.click("nav_TRANSACTIONS")
            self.expect("Fin011ProcessFixture")
            self.capture("05-persisted-history")
            expense_delete = self.row_action("SmokeExpense", "delete")
            self.click(expense_delete)
            self.click("delete_cancel")
            self.click("nav_HOME")
            self.balance("30.00 USD")
            self.click("nav_TRANSACTIONS")
            self.click(expense_delete)
            self.click("delete_confirm")
            self.click("nav_HOME")
            self.balance("50.00 USD")
            self.click("nav_TRANSACTIONS")
            self.click(self.row_action("SmokeIncome", "delete"))
            self.click("delete_confirm")
            self.seek("history_empty")
            self.click("nav_HOME")
            self.balance("0.00 USD")
            self.capture("06-after-confirmed-deletions")
            print("PASS cancelled delete kept data; confirmed deletes recalculated balance", flush=True)
        finally:
            cleanup = [("settings", "delete", "system", key) if value == "null"
                       else ("settings", "put", "system", key, value)
                       for key, value in original.items()]
            cleanup += [("cmd", "uimode", "night", night.group(1)),
                        ("cmd", "connectivity", "airplane-mode", "enable" if original_airplane == "1" else "disable"),
                        ("rm", "-f", "/sdcard/finance-foundation-ui.xml")]
            failures = 0
            for args in cleanup:
                try:
                    self.shell(*args)
                except (subprocess.SubprocessError, OSError):
                    failures += 1
            if failures:
                raise RuntimeError(f"Failed to restore {failures} emulator settings/actions")
        print(f"PASS ledger process smoke; {len(self.captures)} real PNGs; airplane mode restored", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", default="androidApp/build/outputs/apk/debug/androidApp-debug.apk")
    parser.add_argument("--output", default="artifacts/screenshots/fin011-process")
    args = parser.parse_args()
    runner = LedgerSmoke(args.adb, args.serial, args.output)
    if runner.shell("getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("A real emulator is required before APK install")
    if "Success" not in runner.command("install", "-r", args.apk):
        raise AssertionError("APK install failed")
    runner.run_ledger()


if __name__ == "__main__":
    main()

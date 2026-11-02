"""Observe foundation UI on an explicitly selected emulator; capture real PNGs."""

import argparse
import pathlib
import re
import struct
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "app.finance.android"
ACTIVITY = PACKAGE + "/.MainActivity"


class EmulatorSmoke:
    def __init__(self, adb, serial, output):
        if not re.fullmatch(r"emulator-\d+", serial):
            raise ValueError("This runner only operates on an explicitly selected emulator")
        self.adb = adb
        self.serial = serial
        self.output = pathlib.Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.captures = []

    def command(self, *args):
        return subprocess.check_output(
            [self.adb, "-s", self.serial, *args], timeout=45
        ).decode().strip()

    def shell(self, *args):
        return self.command("shell", *args)

    def nodes(self):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            result = self.shell("uiautomator", "dump", "/sdcard/finance-foundation-ui.xml")
            if "dumped to:" in result:
                xml = self.command("exec-out", "cat", "/sdcard/finance-foundation-ui.xml")
                try:
                    return list(ET.fromstring(xml).iter("node"))
                except ET.ParseError:
                    pass
            time.sleep(0.3)
        raise AssertionError("uiautomator did not return a valid UI tree")

    def visible_bounds(self, text):
        nodes = self.nodes()
        width, height = struct.unpack(">II", self.png()[16:24])
        for node in nodes:
            if node.get("text") == text:
                bounds = [int(x) for x in re.findall(r"-?\d+", node.get("bounds", ""))]
                if len(bounds) == 4:
                    x1, y1, x2, y2 = bounds
                    if 0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height:
                        return bounds
        return None

    def expect(self, text):
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            bounds = self.visible_bounds(text)
            if bounds is not None:
                return bounds
            time.sleep(0.3)
        raise AssertionError("UI text not displayed: " + text)

    def wait_configuration(self, font_scale, dark, landscape):
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            selected = False
            for line in self.shell("dumpsys", "activity", "top").splitlines():
                line = line.strip()
                if line.startswith("ACTIVITY "):
                    selected = ACTIVITY in line
                if selected and "mCurrentConfig={" in line:
                    match = re.search(r"mCurrentConfig=\{([\d.]+) ", line)
                    correct_font = match is not None and float(match.group(1)) == font_scale
                    correct_theme = (" night " in line) == dark
                    correct_orientation = (" land " in line) if landscape else (" port " in line)
                    if correct_font and correct_theme and correct_orientation:
                        print(f"CONFIG font={font_scale} dark={dark} landscape={landscape}", flush=True)
                        return
            time.sleep(0.3)
        raise AssertionError("Active Activity did not apply expected configuration")

    def tap(self, text):
        x1, y1, x2, y2 = self.expect(text)
        self.shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def png(self):
        data = subprocess.check_output(
            [self.adb, "-s", self.serial, "exec-out", "screencap", "-p"], timeout=30
        )
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise AssertionError("screencap did not produce PNG")
        return data

    def capture(self, name, landscape=False):
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            data = self.png()
            width, height = struct.unpack(">II", data[16:24])
            if (width > height) == landscape:
                path = self.output / (name + ".png")
                path.write_bytes(data)
                self.captures.append(path.name)
                print(f"CAPTURE {path.name} {width}x{height}", flush=True)
                return
            time.sleep(0.3)
        raise AssertionError("Expected display orientation not observed")

    def launch(self):
        result = self.shell("am", "start", "-W", "-n", ACTIVITY)
        if "Status: ok" not in result:
            raise AssertionError("Activity launch did not report Status: ok")

    def run(self):
        original = {
            key: self.shell("settings", "get", "system", key)
            for key in ["font_scale", "accelerometer_rotation", "user_rotation"]
        }
        original_airplane = self.shell("settings", "get", "global", "airplane_mode_on")
        night = re.search(r"Night mode: (\w+)", self.shell("cmd", "uimode", "night"))
        if night is None or night.group(1) not in ["yes", "no", "auto"]:
            raise AssertionError("Cannot determine night mode for restoration")
        try:
            self.shell("cmd", "connectivity", "airplane-mode", "enable")
            assert self.shell("settings", "get", "global", "airplane_mode_on") == "1"
            self.shell("settings", "put", "system", "accelerometer_rotation", "0")
            self.shell("settings", "put", "system", "user_rotation", "0")
            self.shell("settings", "put", "system", "font_scale", "1.0")
            self.shell("cmd", "uimode", "night", "no")
            self.shell("am", "force-stop", PACKAGE)
            self.launch()
            self.wait_configuration(1.0, False, False)
            self.expect("Ваши финансы")
            self.capture("01-home-light-offline")
            print("PASS launch in airplane mode without model", flush=True)

            sections = [
                ("Операции", "Операций пока нет", "02-transactions"),
                ("Бюджет", "Бюджет ещё не задан", "03-budget"),
                ("AI", "Модель не подключена", "04-ai-unavailable"),
                ("Настройки", "Хранение на устройстве", "05-settings"),
            ]
            for label, heading, name in sections:
                self.tap(label)
                self.expect(heading)
                self.capture(name)
            print("PASS all five sections and missing-model state", flush=True)

            self.shell("input", "keyevent", "4")
            self.expect("Ваши финансы")
            print("PASS Android system Back returns Home", flush=True)
            self.shell("cmd", "uimode", "night", "yes")
            self.wait_configuration(1.0, True, False)
            self.expect("Ваши финансы")
            self.capture("06-home-dark")

            self.shell("settings", "put", "system", "font_scale", "2.0")
            self.wait_configuration(2.0, True, False)
            self.expect("Ваши финансы")
            self.capture("07-home-dark-font-200")
            self.tap("Посмотреть операции")
            self.expect("Операций пока нет")
            self.shell("input", "keyevent", "4")
            self.expect("Ваши финансы")
            self.tap("Настройки")
            self.expect("Хранение на устройстве")
            self.shell("settings", "put", "system", "user_rotation", "1")
            self.wait_configuration(2.0, True, True)
            self.expect("Хранение на устройстве")
            self.capture("08-settings-landscape-font-200", landscape=True)
            before = self.expect("Хранение на устройстве")
            width, height = struct.unpack(">II", self.png()[16:24])
            moved = False
            for _ in range(12):
                scrollable = next((n for n in self.nodes() if n.get("scrollable") == "true"), None)
                if scrollable is None:
                    raise AssertionError("No scrollable Settings content exposed")
                area = [int(x) for x in re.findall(r"-?\d+", scrollable.get("bounds", ""))]
                if len(area) != 4:
                    raise AssertionError("Invalid scrollable content bounds")
                left, top, right, bottom = area
                x = (left + right) // 2
                start = int(top + (bottom - top) * 0.8)
                end = int(top + (bottom - top) * 0.2)
                self.shell("input", "swipe", str(x), str(start), str(x), str(end), "350")
                after = self.visible_bounds("Хранение на устройстве")
                moved = moved or after is None or after[1] < before[1] - 10
                if self.visible_bounds("Ранняя версия · базовый каркас") is not None:
                    break
            if not moved:
                raise AssertionError("Landscape content did not actually scroll")
            self.expect("Ранняя версия · базовый каркас")
            self.capture("09-settings-landscape-scrolled", landscape=True)
            print("PASS applied Settings configuration; real scroll moved header and revealed footer", flush=True)

            self.shell("settings", "put", "system", "font_scale", "1.0")
            self.shell("settings", "put", "system", "user_rotation", "0")
            self.wait_configuration(1.0, True, False)
            self.shell("input", "keyevent", "4")
            self.expect("Ваши финансы")
            self.shell("input", "keyevent", "4")
            resumed = self.shell("dumpsys", "activity", "activities")
            if any(PACKAGE in line for line in resumed.splitlines() if "ResumedActivity" in line):
                raise AssertionError("System Back at Home did not leave the activity")
            self.launch()
            self.expect("Ваши финансы")
            print("PASS Home Back exits, application relaunches", flush=True)
        finally:
            cleanup = []
            for key, value in original.items():
                if value == "null":
                    cleanup.append(("settings", "delete", "system", key))
                else:
                    cleanup.append(("settings", "put", "system", key, value))
            cleanup.append(("cmd", "uimode", "night", night.group(1)))
            cleanup.append((
                "cmd", "connectivity", "airplane-mode",
                "enable" if original_airplane == "1" else "disable",
            ))
            cleanup.append(("rm", "-f", "/sdcard/finance-foundation-ui.xml"))
            errors = []
            for action in cleanup:
                try:
                    self.shell(*action)
                except (subprocess.SubprocessError, OSError) as error:
                    errors.append(error)
            if errors:
                raise RuntimeError(f"Failed to restore {len(errors)} emulator settings/actions")
        print(f"PASS emulator smoke; {len(self.captures)} real screenshots; settings restored", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", default="artifacts/screenshots/foundation")
    parser.add_argument("--apk", default="androidApp/build/outputs/apk/debug/androidApp-debug.apk")
    args = parser.parse_args()
    runner = EmulatorSmoke(args.adb, args.serial, args.output)
    if "Success" not in runner.command("install", "-r", args.apk):
        raise AssertionError("APK installation failed")
    print("PASS APK installed for standalone UI run", flush=True)
    runner.run()


if __name__ == "__main__":
    main()

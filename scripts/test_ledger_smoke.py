"""Safety boundaries of the synthetic-only emulator runner."""
import pathlib
import tempfile
import unittest
import struct
import xml.etree.ElementTree as ET
from unittest.mock import patch

from emulator_smoke import EmulatorSmoke
from ledger_emulator_smoke import LedgerSmoke


class LedgerSmokeSafetyTest(unittest.TestCase):
    def test_field_below_scroll_viewport_is_not_tappable(self):
        self.check_viewport("[63,1329][1017,1352]", None)

    def test_field_inside_scroll_viewport_is_tappable(self):
        self.check_viewport("[63,760][1017,981]", [63, 760, 1017, 981])

    def check_viewport(self, field_bounds, expected):
        tree = ET.fromstring(
            '<node bounds="[0,0][1080,2400]"><node scrollable="true" '
            'bounds="[0,128][1080,1289]"><node resource-id="editor_rate" '
            f'bounds="{field_bounds}"/></node></node>'
        )
        with tempfile.TemporaryDirectory() as directory:
            runner = LedgerSmoke("nonexistent-adb", "emulator-5554", directory)
            with patch.object(EmulatorSmoke, "nodes", return_value=list(tree.iter("node"))), \
                 patch.object(runner, "png", return_value=b"\0" * 16 + struct.pack(">II", 1080, 2400)):
                field = next(n for n in runner.nodes() if runner.resource(n) == "editor_rate")
                self.assertEqual(expected, runner.bounds(field))

    def test_physical_serial_is_rejected_before_output_or_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "no-output"
            with self.assertRaises(ValueError):
                LedgerSmoke("nonexistent-adb", "physical-device", output)
            self.assertFalse(output.exists())

    def test_untrusted_text_is_rejected_before_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = LedgerSmoke("nonexistent-adb", "emulator-5554", directory)
            for value in ["bad;command", "$(bad)", "x" * 81]:
                with self.assertRaises(ValueError):
                    runner.fill("unused", value)


if __name__ == "__main__":
    unittest.main()

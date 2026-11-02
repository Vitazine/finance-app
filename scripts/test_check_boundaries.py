"""Regression tests for the manifest policy used by CI."""

import pathlib
import subprocess
import sys
import tempfile
import unittest


class SourceBoundaryTest(unittest.TestCase):
    def violations(self, location, imported):
        import check_boundaries
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            path = root / "shared/src/commonMain/kotlin/app/finance" / location / "Example.kt"
            path.parent.mkdir(parents=True)
            path.write_text("import " + imported + "\n")
            return check_boundaries.source_violations(root)

    def test_room_kmp_is_allowed_only_in_data(self):
        self.assertFalse(self.violations("data/database", "androidx.room.RoomDatabase"))
        self.assertTrue(self.violations("domain/ledger", "androidx.room.RoomDatabase"))

    def test_sqlite_kmp_is_allowed_only_in_data(self):
        self.assertFalse(self.violations("data/database", "androidx.sqlite.SQLiteConnection"))
        self.assertTrue(self.violations("core/money", "androidx.sqlite.SQLiteConnection"))

    def test_domain_cannot_import_data_implementation(self):
        self.assertTrue(self.violations("domain/ledger", "app.finance.data.database.FinanceDatabase"))

    def test_data_allowance_does_not_allow_android_ui_or_jvm(self):
        for imported in ["android.content.Context", "androidx.compose.runtime.State", "java.io.File"]:
            self.assertTrue(self.violations("data/database", imported))


class ManifestPolicyTest(unittest.TestCase):
    def check_manifest(self, permission="", backup="false"):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "AndroidManifest.xml"
            path.write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                + permission
                + f'<application android:allowBackup="{backup}" />'
                + '</manifest>'
            )
            return subprocess.run(
                [sys.executable, str(pathlib.Path(__file__).with_name("check_boundaries.py")),
                 "--manifest", str(path)],
                capture_output=True,
                text=True,
            )

    def test_rejects_internet_in_merged_manifest(self):
        result = self.check_manifest('<uses-permission android:name="android.permission.INTERNET" />')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("INTERNET", result.stderr)

    def test_rejects_sdk_scoped_internet_permission(self):
        result = self.check_manifest('<uses-permission-sdk-23 android:name="android.permission.INTERNET" />')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)

    def test_rejects_implicit_backup(self):
        result = self.check_manifest(backup="true")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)

    def test_accepts_offline_private_storage_manifest(self):
        result = self.check_manifest()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.prepare_release import next_version, prepare_release


class ReleaseTests(unittest.TestCase):
    def make_project(self, root, version="1.0.0", code=3, notes="Current notes"):
        (root / "src").mkdir()
        (root / "app.json").write_text(json.dumps({"expo": {"version": version, "android": {"versionCode": code}}}))
        (root / "package.json").write_text(json.dumps({"version": version}))
        (root / "package-lock.json").write_text(json.dumps({"version": version, "packages": {"": {"version": version}}}))
        (root / "RELEASE_NOTES.md").write_text(notes + "\n")

    def test_patch_increments_and_explicit_major_minor_are_preserved(self):
        self.assertEqual(next_version("1.0.0", None), "1.0.0")
        self.assertEqual(next_version("1.0.9", "v1.0.9"), "1.0.10")
        self.assertEqual(next_version("1.0.0", "v1.0.9"), "1.0.10")
        self.assertEqual(next_version("1.1.0", "v1.0.9"), "1.1.0")
        self.assertEqual(next_version("2.0.0", "v1.1.0"), "2.0.0")

    def test_first_release_is_newer_than_existing_side_loaded_builds(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.make_project(root)
            with patch("scripts.prepare_release.git", return_value=""):
                self.assertEqual(prepare_release(root), "1.0.0")
            self.assertEqual(json.loads((root / "app.json").read_text())["expo"]["android"]["versionCode"], 4)

    def test_versions_codes_and_bundled_notes_match(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.make_project(root, version="1.0.9", code=4, notes="Previous notes")
            previous = json.dumps({"expo": {"android": {"versionCode": 7}}})
            with patch("scripts.prepare_release.git", side_effect=["v1.0.2\nv1.0.9", previous, "Previous notes", "Fix update installation\nchore: release v1.0.9"]):
                self.assertEqual(prepare_release(root), "1.0.10")
            app = json.loads((root / "app.json").read_text())
            self.assertEqual(app["expo"]["version"], "1.0.10")
            self.assertEqual(app["expo"]["android"]["versionCode"], 8)
            self.assertEqual(json.loads((root / "package.json").read_text())["version"], "1.0.10")
            lock = json.loads((root / "package-lock.json").read_text())
            self.assertEqual(lock["version"], lock["packages"][""]["version"])
            self.assertEqual(lock["version"], "1.0.10")
            bundled = json.loads((root / "src" / "release-notes.json").read_text())
            self.assertEqual(bundled["version"], "1.0.10")
            self.assertIn("Fix update installation", bundled["notes"])
            self.assertNotIn("chore: release", bundled["notes"])

    def test_custom_notes_are_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.make_project(root, version="1.1.0", notes="New features for friends")
            previous = json.dumps({"expo": {"android": {"versionCode": 3}}})
            with patch("scripts.prepare_release.git", side_effect=["v1.0.0", previous, "Previous notes"]):
                self.assertEqual(prepare_release(root), "1.1.0")
            self.assertEqual((root / "RELEASE_NOTES.md").read_text(), "New features for friends\n")

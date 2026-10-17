import os
import subprocess
import unittest
from pathlib import Path


class UpdaterDownloadTests(unittest.TestCase):
    def test_real_http_download_integrity_progress_and_cleanup(self):
        root = Path(__file__).resolve().parents[1]
        output = root / ".expo" / "updater-test-classes"
        output.mkdir(parents=True, exist_ok=True)
        java_bin = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else Path()
        javac = str(java_bin / "javac") if java_bin.parts else "javac"
        java = str(java_bin / "java") if java_bin.parts else "java"
        source = root / "modules" / "etherdrop" / "android" / "src" / "main" / "java" / "expo" / "modules" / "etherdrop" / "UpdateDownloader.java"
        subprocess.run([javac, "--add-modules", "jdk.httpserver", "-d", str(output), str(source), str(root / "tests" / "UpdateDownloaderTest.java")], check=True)
        subprocess.run([java, "--add-modules", "jdk.httpserver", "-cp", str(output), "UpdateDownloaderTest"], check=True)

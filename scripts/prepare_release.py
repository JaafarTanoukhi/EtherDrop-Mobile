import json
import os
import re
import subprocess
from pathlib import Path


def parse_version(value: str) -> tuple[int, int, int]:
    if not re.fullmatch(r"v?\d+\.\d+\.\d+", value):
        raise ValueError(f"Invalid release version: {value}")
    return tuple(int(part) for part in value.removeprefix("v").split("."))


def next_version(current: str, previous: str | None) -> str:
    if previous is None or parse_version(current) > parse_version(previous):
        return current
    major, minor, patch = parse_version(previous)
    return f"{major}.{minor}.{patch + 1}"


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True, encoding="utf-8").strip()


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def prepare_release(root: Path) -> str:
    app = json.loads((root / "app.json").read_text(encoding="utf-8"))
    tags = [tag for tag in git("tag", "--list").splitlines() if re.fullmatch(r"v\d+\.\d+\.\d+", tag)]
    previous_tag = max(tags, key=parse_version) if tags else None
    version = next_version(app["expo"]["version"], previous_tag)
    previous_code = json.loads(git("show", f"{previous_tag}:app.json"))["expo"]["android"]["versionCode"] if previous_tag else 0
    app["expo"]["version"] = version
    app["expo"]["android"]["versionCode"] = max(app["expo"]["android"]["versionCode"], previous_code) + 1
    write_json(root / "app.json", app)

    package = json.loads((root / "package.json").read_text(encoding="utf-8"))
    package["version"] = version
    write_json(root / "package.json", package)
    lock = json.loads((root / "package-lock.json").read_text(encoding="utf-8"))
    lock["version"] = lock["packages"][""]["version"] = version
    write_json(root / "package-lock.json", lock)

    notes_path = root / "RELEASE_NOTES.md"
    notes = notes_path.read_text(encoding="utf-8").strip()
    if previous_tag and notes == git("show", f"{previous_tag}:RELEASE_NOTES.md"):
        subjects = git("log", f"{previous_tag}..HEAD", "--format=%s", "--no-merges").splitlines()
        subjects = [subject for subject in subjects if not subject.startswith("chore: release v")]
        notes = f"What's new in EtherDrop Mobile v{version}\n\n" + "\n".join(f"- {subject}" for subject in subjects)
        notes_path.write_text(notes + "\n", encoding="utf-8")
    write_json(root / "src" / "release-notes.json", {"version": version, "notes": notes})
    return version


if __name__ == "__main__":
    version = prepare_release(Path.cwd())
    print(f"Preparing EtherDrop Mobile v{version}")
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        output.write(f"version={version}\n")

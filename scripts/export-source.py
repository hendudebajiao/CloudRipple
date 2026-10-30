#!/usr/bin/env python3
"""Create a fresh, allowlisted source tree and ZIP without device/build data."""
from datetime import datetime
import hashlib
from pathlib import Path
import re
import shutil
import stat
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FILES = [
    ".gitignore", ".gitattributes", ".github/workflows/build.yml",
    "README.md", "CHANGELOG.md", "LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md", "PRIVACY.md",
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties", "app/.gitignore",
    "app/build.gradle.kts", "app/proguard-rules.pro", "scripts/export-source.py",
]
TREES = ["app/src/main", "app/src/debug", "app/src/androidTest", "docs", "third-party/licenses"]
BLOCKED = {".git", ".gradle", ".idea", ".diagnostics", "build", "__pycache__"}
BAD_SUFFIX = {".apk", ".aab", ".jks", ".keystore", ".p12", ".pem", ".log", ".pyc", ".db"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    version = re.search(r'versionName\s*=\s*"([\w.\-]+)"',
                        (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")).group(1)
    name = "yunyang-{}-source-{}".format(version, datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
    output = ROOT / "release-source"
    output.mkdir(exist_ok=True)
    target = output / name
    target.mkdir(exist_ok=False)
    sources = {ROOT / path for path in FILES}
    for tree in TREES:
        sources.update(path for path in (ROOT / tree).rglob("*") if path.is_file())
    paths = sorted(sources, key=lambda path: path.relative_to(ROOT).as_posix())
    for source in paths:
        relative = source.relative_to(ROOT)
        try:
            source.resolve().relative_to(ROOT)
            linked = any(part.is_symlink() for part in [source] + list(source.parents))
        except ValueError:
            linked = True
        if linked:
            raise ValueError("Refusing linked/outside source: " + str(relative))
        if BLOCKED.intersection(relative.parts) or source.suffix.lower() in BAD_SUFFIX:
            raise ValueError("Unexpected private/generated file: " + str(relative))
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    manifest = target / "SOURCE_MANIFEST.sha256"
    manifest.write_text("".join(digest(target / p.relative_to(ROOT)) + "  "
                                + p.relative_to(ROOT).as_posix() + "\n" for p in paths), encoding="utf-8")
    archive = output / (name + ".zip")
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for file in sorted(target.rglob("*")):
            if not file.is_file():
                continue
            info = zipfile.ZipInfo.from_file(file, arcname=name + "/" + file.relative_to(target).as_posix())
            info.create_system = 3
            mode = 0o755 if file.name == "gradlew" else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            zf.writestr(info, file.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
    archive.with_suffix(".zip.sha256").write_text(digest(archive) + "  " + archive.name + "\n", encoding="utf-8")
    print("Source: " + str(target))
    print("ZIP: " + str(archive))
    print("Files: " + str(len(paths) + 1))
    print("SHA256: " + digest(archive))


if __name__ == "__main__":
    main()

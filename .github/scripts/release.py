"""Validate release versions and package the artifacts built by GitHub Actions."""

import argparse
import hashlib
from pathlib import Path
import re
import tarfile
import tomllib
import zipfile


ROOT = Path(__file__).resolve().parents[2]
DIST = ROOT / "dist"
SEMVER = re.compile(
    r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-(?P<prerelease>[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?",
    re.ASCII,
)


def workspace_version() -> str:
    with (ROOT / "Cargo.toml").open("rb") as manifest:
        return tomllib.load(manifest)["workspace"]["package"]["version"]


def validate_version(tag: str) -> None:
    version = workspace_version()
    match = SEMVER.fullmatch(version)
    if not match:
        raise SystemExit(f"Invalid workspace version: {version}")
    prerelease = match.group("prerelease")
    for identifier in (prerelease or "").split("."):
        if identifier.isdigit() and len(identifier) > 1 and identifier.startswith("0"):
            raise SystemExit("Numeric prerelease identifiers cannot have leading zeroes")

    properties = dict(
        line.strip().split("=", 1)
        for line in (ROOT / "mod/gradle.properties").read_text().splitlines()
        if "=" in line and not line.lstrip().startswith("#")
    )
    if properties.get("version") != version:
        raise SystemExit("Cargo.toml and mod/gradle.properties versions must match")
    if tag and tag != f"v{version}":
        raise SystemExit(f"Tag {tag!r} must match the component version v{version}")

    with (ROOT / "Cargo.lock").open("rb") as lockfile:
        packages = tomllib.load(lockfile)["package"]
    for package in packages:
        if "source" not in package and package["version"] != version:
            raise SystemExit(f"Cargo.lock has a stale version for {package['name']}")

    print(f"version={version}")
    print(f"prerelease={str(prerelease is not None).lower()}")


def package_binaries(target: str) -> None:
    windows = target.endswith("windows-msvc")
    suffix = ".exe" if windows else ""
    binaries = ROOT / "target" / target / "release"
    files = [binaries / f"{name}{suffix}" for name in ("lodestar", "lodeproxy")]
    files.append(ROOT / "README.md")
    for path in files:
        if not path.is_file():
            raise SystemExit(f"Missing package file: {path}")

    DIST.mkdir(exist_ok=True)
    name = f"lodestar-{workspace_version()}-{target}"
    if windows:
        with zipfile.ZipFile(DIST / f"{name}.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            for path in files:
                archive.write(path, f"{name}/{path.name}")
        return

    with tarfile.open(DIST / f"{name}.tar.gz", "w:gz") as archive:
        for path in files:
            entry = archive.gettarinfo(str(path), f"{name}/{path.name}")
            entry.mode = 0o644 if path.name == "README.md" else 0o755
            with path.open("rb") as contents:
                archive.addfile(entry, contents)


def write_checksums() -> None:
    files = sorted(path for path in DIST.iterdir() if path.name != "SHA256SUMS")
    if not files:
        raise SystemExit("No release artifacts found")
    lines = []
    for path in files:
        with path.open("rb") as contents:
            digest = hashlib.file_digest(contents, "sha256").hexdigest()
        lines.append(f"{digest}  {path.name}\n")
    (DIST / "SHA256SUMS").write_text("".join(lines), encoding="utf-8", newline="\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("version").add_argument("--tag", default="")
    commands.add_parser("package").add_argument("--target", required=True)
    commands.add_parser("checksums")
    args = parser.parse_args()
    if args.command == "version":
        validate_version(args.tag)
    elif args.command == "package":
        package_binaries(args.target)
    else:
        write_checksums()


if __name__ == "__main__":
    main()

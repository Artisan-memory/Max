#!/usr/bin/env python3
"""Select the next repository-wide Max build version from successful-build tags."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
from pathlib import Path


TAG_RE = re.compile(r"^max-build-v(?P<name>[0-9]+(?:\.[0-9]+)*)-code(?P<code>[0-9]+)$")


def read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def increment_patch(version: str) -> str:
    parts = version.split(".")
    if not parts or not parts[-1].isdigit():
        raise SystemExit(f"MAX_VERSION_NAME must end with a numeric patch component: {version}")
    parts[-1] = str(int(parts[-1]) + 1)
    return ".".join(parts)


def existing_versions() -> list[tuple[int, str]]:
    result = subprocess.run(
        ["git", "tag", "--list", "max-build-v*-code*"],
        check=True,
        text=True,
        capture_output=True,
    )
    versions: list[tuple[int, str]] = []
    for tag in result.stdout.splitlines():
        match = TAG_RE.fullmatch(tag.strip())
        if match:
            versions.append((int(match.group("code")), match.group("name")))
    return versions


def append_environment(path: str | None, values: dict[str, str]) -> None:
    if not path:
        return
    with open(path, "a", encoding="utf-8") as stream:
        for key, value in values.items():
            stream.write(f"{key}={value}\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--properties", type=Path, default=Path("gradle.properties"))
    args = parser.parse_args()

    properties = read_properties(args.properties)
    try:
        base_code = int(properties["MAX_VERSION_CODE"])
        base_name = properties["MAX_VERSION_NAME"]
    except (KeyError, ValueError) as error:
        raise SystemExit("MAX_VERSION_CODE/MAX_VERSION_NAME are missing or invalid") from error

    versions = existing_versions()
    if versions:
        current_code, current_name = max(versions, key=lambda item: item[0])
        if current_code < base_code:
            current_code, current_name = base_code, base_name
    else:
        current_code, current_name = base_code, base_name

    next_code = current_code + 1
    if next_code > 2_100_000_000:
        raise SystemExit(f"Android versionCode limit reached: {next_code}")
    next_name = increment_patch(current_name)
    tag = f"max-build-v{next_name}-code{next_code}"
    values = {
        "MAX_VERSION_CODE_OVERRIDE": str(next_code),
        "MAX_VERSION_NAME_OVERRIDE": next_name,
        "MAX_VERSION_TAG": tag,
    }
    append_environment(os.environ.get("GITHUB_ENV"), values)
    append_environment(os.environ.get("GITHUB_OUTPUT"), values)
    for key, value in values.items():
        print(f"{key}={value}")


if __name__ == "__main__":
    main()

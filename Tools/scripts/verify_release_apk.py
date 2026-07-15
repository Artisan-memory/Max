#!/usr/bin/env python3
"""Fail a CI build if an APK is unsigned, signed by another key, or mis-versioned."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
from pathlib import Path


CERT_RE = re.compile(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)")
BADGING_RE = re.compile(r"versionCode='([0-9]+)'\s+versionName='([^']+)'", re.DOTALL)


def find_android_tool(name: str) -> Path:
    executable_names = [name]
    if os.name == "nt":
        executable_names = [f"{name}.bat", f"{name}.exe", name]
    for executable in executable_names:
        from_path = shutil.which(executable)
        if from_path:
            return Path(from_path)
    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_root:
        raise SystemExit("ANDROID_HOME/ANDROID_SDK_ROOT is not set")
    candidates = []
    for executable in executable_names:
        candidates.extend((Path(sdk_root) / "build-tools").glob(f"*/{executable}"))
    candidates = sorted(candidates, reverse=True)
    if not candidates:
        raise SystemExit(f"Android build tool not found: {name}")
    return candidates[0]


def run(tool: Path, *args: str) -> str:
    result = subprocess.run([str(tool), *args], text=True, capture_output=True)
    output = result.stdout + result.stderr
    if result.returncode != 0:
        raise SystemExit(output.strip() or f"{tool.name} failed with {result.returncode}")
    return output


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("expected_code", type=int)
    parser.add_argument("expected_name")
    parser.add_argument("expected_cert_sha256")
    args = parser.parse_args()

    if not args.apk.is_file():
        raise SystemExit(f"APK not found: {args.apk}")
    if "unsigned" in args.apk.name.lower():
        raise SystemExit(f"Unsigned APK filename is forbidden: {args.apk.name}")

    signature_output = run(find_android_tool("apksigner"), "verify", "--print-certs", str(args.apk))
    cert_match = CERT_RE.search(signature_output)
    if not cert_match:
        raise SystemExit("APK signature certificate digest was not reported")
    actual_cert = cert_match.group(1).lower()
    expected_cert = args.expected_cert_sha256.lower().replace(":", "")
    if actual_cert != expected_cert:
        raise SystemExit(f"Signing certificate mismatch: expected {expected_cert}, got {actual_cert}")

    badging = run(find_android_tool("aapt2"), "dump", "badging", str(args.apk))
    version_match = BADGING_RE.search(badging)
    if not version_match:
        raise SystemExit("APK versionCode/versionName could not be read")
    actual_code = int(version_match.group(1))
    actual_name = version_match.group(2)
    if actual_code != args.expected_code:
        raise SystemExit(f"versionCode mismatch: expected {args.expected_code}, got {actual_code}")
    if actual_name != args.expected_name and not actual_name.startswith(args.expected_name + "-"):
        raise SystemExit(f"versionName mismatch: expected {args.expected_name}, got {actual_name}")

    print(f"Verified {args.apk.name}: versionCode={actual_code}, versionName={actual_name}, cert={actual_cert}")


if __name__ == "__main__":
    main()

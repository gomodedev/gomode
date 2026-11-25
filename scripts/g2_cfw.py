#!/usr/bin/env -S uv run --locked --script
# Build the pinned g2flash custom firmware and flash it onto both arms of an Even G2 pair.
# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "bleak",
# ]
# ///
#
# Usage, from a computer with Bluetooth LE next to the glasses:
#
#   uv run --locked --script scripts/g2_cfw.py --check   # build, find the pair, connect; write nothing
#   uv run --locked --script scripts/g2_cfw.py           # build, find the pair, flash both arms
#
# g2flash (https://github.com/jimrandomh/g2flash, GPL-3.0) owns the firmware: its build_cfw.sh
# downloads stock G2 2.3.0.24 from Even's CDN, applies the committed patch set, and checks both
# images against pinned SHA-256 hashes. This script clones it at G2FLASH_COMMIT, so the pin
# fixes the image. It runs g2flash.py with this script's interpreter, which provides bleak.
#
# Flashing voids the warranty and can brick an arm; g2flash asks the user to type a warranty
# phrase before it writes. --check stops before the OTA BEGIN and FILE_CHECK writes, so it
# skips that prompt. Installing an update from the Even app, or flashing the stock image,
# restores stock firmware.
#
# Each arm advertises as "Even G2_<pair>_<L|R>_<MAC tail>" only while no phone holds it: quit
# Go Mode and the Even app, or turn off the phone's Bluetooth, before running. On macOS bleak
# reports per-host UUIDs instead of MACs, which g2flash takes as addressType=random.
#
# The firmware of the pinned commit reports "Faceclaw/45" in its settings reply. Go Mode's G2
# client (android/evenG2, G2Settings) detects it and draws text and images on the whole panel
# through the firmware's private stream; on any other firmware it keeps the stock Even Hub page.
# No pair has run Go Mode on this firmware yet; the TODO(hardware) notes in android/evenG2 list
# what to check. A new pin whose revision changes the drawing contract needs a matching
# G2Firmware.MIN_FACECLAW_REVISION.

import argparse
import asyncio
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

from bleak import BleakScanner
from bleak.exc import BleakError

G2FLASH_URL = "https://github.com/jimrandomh/g2flash"
G2FLASH_COMMIT = "c8fd8e85c77e8f378cce52029dd1225e4ab11caa"
# build_cfw.sh at G2FLASH_COMMIT writes this image after checking its pinned hash.
CFW_IMAGE = "g2_2.3.0.24_cfw.bin"
SCAN_SECONDS = 15.0
ARM_NAME = re.compile(r"G2_(\d+)_([LR])_[0-9A-Fa-f]{6}")


@dataclass(frozen=True)
class Pair:
    serial: str
    left: str
    right: str


def checkout(directory: Path) -> None:
    """Clone or update g2flash and detach it at G2FLASH_COMMIT."""
    if not directory.exists():
        directory.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "clone", "--quiet", G2FLASH_URL, str(directory)], check=True)
    git = ["git", "-C", str(directory)]
    has_commit = subprocess.run([*git, "cat-file", "-e", f"{G2FLASH_COMMIT}^{{commit}}"], capture_output=True)
    if has_commit.returncode != 0:
        subprocess.run([*git, "fetch", "--quiet", "origin"], check=True)
    subprocess.run([*git, "checkout", "--quiet", "--detach", G2FLASH_COMMIT], check=True)
    # Ignored files (the downloaded and built images) may stay; tracked edits would change the image.
    dirty = subprocess.run([*git, "status", "--porcelain"], capture_output=True, text=True, check=True).stdout
    if dirty:
        raise SystemExit(f"{directory} has local changes; delete it and run again:\n{dirty}")


def build(directory: Path) -> Path:
    """Run g2flash's build, which verifies the stock and patched images' hashes."""
    subprocess.run(["./build_cfw.sh", "--skip-venv"], cwd=directory, check=True)
    image = directory / CFW_IMAGE
    if not image.is_file():
        raise SystemExit(f"build_cfw.sh did not produce {image}")
    return image


async def scan() -> list[Pair]:
    """Group advertising G2 arms into pairs that have both arms."""
    found = await BleakScanner.discover(timeout=SCAN_SECONDS, return_adv=True)
    arms: dict[str, dict[str, str]] = {}
    for address, (device, advertisement) in found.items():
        match = ARM_NAME.search(advertisement.local_name or device.name or "")
        if match:
            arms.setdefault(match.group(1), {})[match.group(2)] = address
    return [Pair(serial, sides["L"], sides["R"]) for serial, sides in sorted(arms.items()) if len(sides) == 2]


def choose(pairs: list[Pair], serial: str | None) -> Pair:
    if serial is not None:
        for pair in pairs:
            if pair.serial == serial:
                return pair
        raise SystemExit(f"Even G2 pair {serial} is not advertising both arms; found {[p.serial for p in pairs]}.")
    if len(pairs) == 1:
        return pairs[0]
    if not pairs:
        raise SystemExit(
            "No Even G2 pair is advertising both arms. Power the glasses on, keep them close, and quit "
            "Go Mode and the Even app (or turn off the phone's Bluetooth) so the arms advertise."
        )
    raise SystemExit(f"Several Even G2 pairs are advertising; pick one with --pair: {[p.serial for p in pairs]}.")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build g2flash's custom firmware and flash it onto an Even G2 pair.")
    parser.add_argument("--check", action="store_true", help="connect and authenticate both arms; write nothing")
    parser.add_argument("--pair", help="pair number from the arms' advertised names, when several pairs advertise")
    args = parser.parse_args()

    directory = Path.home() / ".cache" / "gomode" / "g2flash"
    checkout(directory)
    image = build(directory)

    print(f"Scanning {SCAN_SECONDS:g} s for Even G2 arms…")
    try:
        pairs = asyncio.run(scan())
    except BleakError as error:
        message = f"Bluetooth LE scan failed: {error}. Check that this computer has a working adapter."
        raise SystemExit(message) from error
    pair = choose(pairs, args.pair)
    print(f"Even G2 pair {pair.serial}: left {pair.left}, right {pair.right}")

    address_type = "random" if sys.platform == "darwin" else "public"
    connection = f"g2://local?left={pair.left}&right={pair.right}&addressType={address_type}"
    command = [sys.executable, "g2flash.py", "-c", connection, "-f", str(image)]
    if args.check:
        # Stopping before file_check sends no OTA write, so the warranty prompt guards nothing.
        command += ["--stop-before", "file_check", "--my-warranty-is-void"]
    return subprocess.run(command, cwd=directory).returncode


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Stop the running Go Mode test emulator, if present."""

import subprocess
import sys
import time

from android_devices import adb_path
from android_start_emulator import _avd_process_running, _running_avd_serial

_STOP_TIMEOUT_SECONDS = 30.0
_STOP_POLL_SECONDS = 0.25


def _serial_listed(adb: str, serial: str) -> bool:
    devices = subprocess.run([adb, "devices"], capture_output=True, check=True, text=True)
    return any(line.startswith(f"{serial}\t") for line in devices.stdout.splitlines()[1:])


def _wait_for_stop(adb: str, serial: str) -> bool:
    deadline = time.monotonic() + _STOP_TIMEOUT_SECONDS
    while True:
        if not _serial_listed(adb, serial) and not _avd_process_running():
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(_STOP_POLL_SECONDS)


def main() -> int:
    try:
        adb = adb_path()
    except RuntimeError as error:
        print(error, file=sys.stderr)
        return 1
    serial = _running_avd_serial(adb)
    if serial is None:
        print("No Go Mode emulator is running")
        return 0
    subprocess.run([adb, "-s", serial, "emu", "kill"], check=True)
    if not _wait_for_stop(adb, serial):
        print(
            f"Go Mode emulator {serial} did not stop within {int(_STOP_TIMEOUT_SECONDS)}s",
            file=sys.stderr,
        )
        return 1
    print(f"Stopped Go Mode emulator {serial}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)

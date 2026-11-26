"""Locate Android adb, select connected devices, and ensure stock emulator IPv4 routing."""

import os
import pathlib
import shutil
import subprocess
import sys
import time

from android_sdk import AVD_NAME

_IPV4_RECOVERY_ATTEMPTS = 3
_IPV4_ATTEMPT_WAIT_SECONDS = 15.0


def adb_path() -> str:
    roots = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        "~/.local/share/android-sdk",
        "~/Android/Sdk",
        "~/Library/Android/sdk",
        "/usr/local/lib/android/sdk",
    ]
    for root in roots:
        if root:
            candidate = pathlib.Path(root).expanduser() / "platform-tools" / "adb"
            if candidate.is_file():
                return str(candidate.resolve())
    path = shutil.which("adb")
    if path:
        return path
    raise RuntimeError("Android adb not found; set ANDROID_HOME or run make android-sdk")


def ready_device_serials(adb: str) -> list[str]:
    result = subprocess.run([adb, "devices"], check=True, capture_output=True, text=True)
    serials: list[str] = []
    for line in result.stdout.splitlines()[1:]:
        serial, separator, state = line.partition("\t")
        if separator and state.split(maxsplit=1)[0] == "device":
            serials.append(serial)
    return serials


def selected_device_serial(adb: str) -> str:
    ready = ready_device_serials(adb)
    requested = os.environ.get("ANDROID_SERIAL")
    if requested:
        if requested not in ready:
            raise RuntimeError(f"ANDROID_SERIAL device is not ready: {requested}")
        return requested
    if len(ready) != 1:
        raise RuntimeError(f"Expected one ready Android device, found {len(ready)}; set ANDROID_SERIAL")
    return ready[0]


def print_failure(error: BaseException) -> None:
    """Print an error and the errors it interrupted, root cause first.

    Device cleanup in `finally` blocks fails again once adb loses the device;
    the earlier errors explain why.
    """
    chain: list[BaseException] = []
    current: BaseException | None = error
    while current is not None and current not in chain:
        chain.append(current)
        current = current.__cause__ or current.__context__
    for item in reversed(chain):
        message = str(item)
        if isinstance(item, subprocess.CalledProcessError) and isinstance(item.stderr, str) and item.stderr.strip():
            message += ": " + item.stderr.strip()
        print(message, file=sys.stderr)


def _has_emulator_ipv4_route(adb: str, serial: str) -> tuple[bool, str]:
    """Check for a gateway-backed IPv4 default route on wlan0 without sending packets."""
    result = subprocess.run(
        [adb, "-s", serial, "shell", "ip", "-4", "route", "get", "192.0.2.1"],
        capture_output=True,
        text=True,
        timeout=5,
        check=False,
    )
    output = (result.stdout or result.stderr).strip()
    healthy = (
        result.returncode == 0
        and " via " in result.stdout
        and " dev wlan0 " in result.stdout
        and " src " in result.stdout
    )
    return healthy, output


def _wait_for_ipv4_route(adb: str, serial: str, timeout_seconds: float) -> tuple[bool, str]:
    deadline = time.monotonic() + timeout_seconds
    while True:
        healthy, detail = _has_emulator_ipv4_route(adb, serial)
        if healthy or time.monotonic() >= deadline:
            return healthy, detail
        time.sleep(0.25)


def ensure_emulator_ipv4_route(adb: str, serial: str, initial_wait_seconds: float) -> None:
    """Ensure the owned stock AVD has a gateway-backed AndroidWifi IPv4 default route."""
    if not serial.startswith("emulator-"):
        return
    avd = subprocess.run(
        [adb, "-s", serial, "emu", "avd", "name"],
        capture_output=True,
        text=True,
        timeout=10,
        check=True,
    )
    if AVD_NAME not in (line.strip() for line in avd.stdout.splitlines()):
        return

    healthy, detail = _wait_for_ipv4_route(adb, serial, initial_wait_seconds)
    if healthy:
        return

    for attempt in range(1, _IPV4_RECOVERY_ATTEMPTS + 1):
        print(
            f"Restoring stock emulator '{AVD_NAME}' AndroidWifi IPv4 route "
            f"(attempt {attempt}/{_IPV4_RECOVERY_ATTEMPTS})...",
            file=sys.stderr,
            flush=True,
        )
        # Rejoining an already-associated AP can leave stale link properties in place
        # (e.g. dhcpclient_wifi --no-gateway without an IPv4 default route). Cycle Wi-Fi
        # cleanly before re-associating AndroidWifi.
        for state in ("disabled", "enabled"):
            subprocess.run(
                [adb, "-s", serial, "shell", "cmd", "wifi", "set-wifi-enabled", state],
                capture_output=True,
                text=True,
                timeout=10,
                check=True,
            )
            state_deadline = time.monotonic() + 10.0
            while True:
                status = subprocess.run(
                    [adb, "-s", serial, "shell", "cmd", "wifi", "status"],
                    capture_output=True,
                    text=True,
                    timeout=5,
                    check=False,
                ).stdout.strip()
                if status.startswith(f"Wifi is {state}"):
                    break
                if time.monotonic() >= state_deadline:
                    raise RuntimeError(f"Stock emulator Wi-Fi did not reach state {state}: {status}")
                time.sleep(0.25)
        subprocess.run(
            [adb, "-s", serial, "shell", "cmd", "wifi", "connect-network", "AndroidWifi", "open"],
            capture_output=True,
            text=True,
            timeout=10,
            check=True,
        )
        healthy, detail = _wait_for_ipv4_route(adb, serial, _IPV4_ATTEMPT_WAIT_SECONDS)
        if healthy:
            return

    wifi_status = subprocess.run(
        [adb, "-s", serial, "shell", "cmd", "wifi", "status"],
        capture_output=True,
        text=True,
        timeout=5,
        check=False,
    ).stdout.strip()
    wlan_addr = subprocess.run(
        [adb, "-s", serial, "shell", "ip", "-4", "addr", "show", "dev", "wlan0"],
        capture_output=True,
        text=True,
        timeout=5,
        check=False,
    ).stdout.strip()
    raise RuntimeError(
        f"AndroidWifi did not provision an IPv4 default route after {_IPV4_RECOVERY_ATTEMPTS} attempts: "
        f"route={detail!r}; wifi={wifi_status!r}; wlan0={wlan_addr!r}"
    )

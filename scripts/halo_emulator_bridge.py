#!/usr/bin/env -S uv run --locked --script
# Serve the pinned Brilliant Labs Halo Lua emulator to HaloEmulatorBridgeClient over a local WebSocket.
# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = [
#   "halo-emulator",
#   "websockets>=15.0.0",
# ]
#
# [tool.uv.sources]
# halo-emulator = { git = "https://github.com/brilliantlabsAR/brilliant_sdk.git", subdirectory = "python/packages/halo_emulator", rev = "b644147eb14021c297f6533d1b38b7e063b399f0" }  # noqa: E501
# ///
#
# The pinned halo-emulator requires pygame, whose 2.6 release ships no CPython 3.14 wheels; the
# bridge never imports it.
#
# Usage:
#
#   uv run --locked --script scripts/halo_emulator_bridge.py --port 8765
#
# The bridge listens on 127.0.0.1, prints its ws:// URL on stdout once it accepts connections,
# and exits when stdin closes, so the process that started it owns its lifetime.
# HaloEmulatorRenderingTest starts it with --port 0, which picks a free port.
#
# The protocol is one JSON object per WebSocket text message. A request carries "id" and "op"
# plus the op's fields; its response echoes "id" with "ok" and either the op's result fields or
# "error". The Lua runtime's print() output and every Bluetooth packet it sends reach all clients
# as {"event": "print", "text"} and {"event": "bluetooth_sent", "data": base64}.

import argparse
import asyncio
import base64
import io
import json
import sys
from collections.abc import Awaitable, Callable
from typing import Any

import websockets
from halo_emulator import HaloEmulator
from websockets.asyncio.server import ServerConnection

JsonObject = dict[str, Any]

HOST = "127.0.0.1"


class HaloBridge:
    def __init__(self, emulator: HaloEmulator, loop: asyncio.AbstractEventLoop) -> None:
        self._emulator = emulator
        self._loop = loop
        self._clients: set[ServerConnection] = set()
        self._lock = asyncio.Lock()
        self._emulator._bluetooth.add_send_listener(self._on_bluetooth_send)
        self._emulator._print_handler = self._on_print
        self._operations: dict[str, Callable[[JsonObject], Awaitable[JsonObject]]] = {
            "break": self._break,
            "button_double": self._button_double,
            "button_long": self._button_long,
            "button_single": self._button_single,
            "clear_display": self._clear_display,
            "connect_repl": self._connect_repl,
            "execute_lua": self._execute_lua,
            "get_framebuffer": self._get_framebuffer,
            "imu_tap": self._imu_tap,
            "ping": self._ping,
            "remove_all_files": self._remove_all_files,
            "reset": self._reset,
            "send_message": self._send_message,
            "start": self._start,
            "stop": self._stop,
            "upload_file": self._upload_file,
        }

    async def handle_client(self, websocket: ServerConnection) -> None:
        self._clients.add(websocket)
        try:
            async for message in websocket:
                await websocket.send(json.dumps(await self._handle_message(message)))
        finally:
            self._clients.remove(websocket)

    async def _handle_message(self, message: str | bytes) -> JsonObject:
        request_id = None
        try:
            request = json.loads(message)
            if not isinstance(request, dict):
                raise ValueError("request must be a JSON object")
            request_id = request.get("id")
            handler = self._operations.get(request.get("op"))
            if handler is None:
                raise ValueError(f"unsupported op: {request.get('op')}")
            async with self._lock:
                result = await handler(request)
            return {"id": request_id, "ok": True, **result}
        except Exception as exc:  # noqa: BLE001 - Error boundary for remote requests.
            return {"id": request_id, "ok": False, "error": str(exc)}

    # The emulator's stop() sets a stop flag and also queues a stop event. A running script's
    # frame.sleep raises on the flag before it drains the queue, and an idle emulator has no script
    # to drain it; start() and connect() clear the flag but keep the queue. The next script then
    # consumed that event at its first frame.sleep and ended, so each start after a stop failed
    # once. Every stop goes through here and empties the queue after the script has ended, so a
    # new runtime begins without events left over from the previous one.
    def _halt(self) -> None:
        self._emulator.stop()
        if self._emulator.is_running():
            raise RuntimeError("the Lua script did not stop within 2 s")
        self._emulator._event_queue.drain()

    async def _ping(self, request: JsonObject) -> JsonObject:
        return {"running": self._emulator.is_running(), "error": self._error_text()}

    async def _connect_repl(self, request: JsonObject) -> JsonObject:
        self._halt()
        self._emulator.connect()
        return {}

    async def _execute_lua(self, request: JsonObject) -> JsonObject:
        result = self._emulator.execute_lua(self._required_str(request, "code"))
        return {"result": None if result is None else str(result)}

    async def _start(self, request: JsonObject) -> JsonObject:
        script = request.get("script", "main.lua")
        if not isinstance(script, str):
            raise ValueError("script must be a string")
        self._halt()
        self._emulator.start(script_name=script)
        return {}

    async def _stop(self, request: JsonObject) -> JsonObject:
        self._halt()
        return {"error": self._error_text()}

    async def _break(self, request: JsonObject) -> JsonObject:
        self._halt()
        return {}

    async def _reset(self, request: JsonObject) -> JsonObject:
        self._halt()
        started = (self._emulator._sandbox_dir / "main.lua").exists()
        if started:
            self._emulator.start(script_name="main.lua")
        else:
            self._emulator.connect()
        return {"started": started}

    async def _remove_all_files(self, request: JsonObject) -> JsonObject:
        self._halt()
        for path in self._emulator._sandbox_dir.iterdir():
            if path.is_file():
                path.unlink()
        self._emulator.connect()
        return {}

    async def _upload_file(self, request: JsonObject) -> JsonObject:
        path = self._required_str(request, "path")
        content = self._required_str(request, "content")
        sandbox = self._emulator._sandbox_dir.resolve()
        destination = (sandbox / path).resolve()
        if sandbox not in destination.parents:
            raise ValueError(f"path escapes sandbox: {path}")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding="utf-8")
        return {}

    async def _clear_display(self, request: JsonObject) -> JsonObject:
        if self._emulator.is_running():
            raise ValueError("cannot clear display while a Lua script is running")
        self._emulator.execute_lua("frame.display.clear(0)")
        return {}

    async def _send_message(self, request: JsonObject) -> JsonObject:
        msg_code = request.get("msgCode")
        if not isinstance(msg_code, int) or not 0 <= msg_code <= 255:
            raise ValueError("msgCode must be an integer in range 0..255")
        payload = base64.b64decode(self._required_str(request, "payload").encode("ascii"), validate=True)
        if len(payload) > 65535:
            raise ValueError("payload must fit in 65535 bytes")
        self._emulator.inject_bluetooth_data(bytes([msg_code, len(payload) >> 8, len(payload) & 0xFF]) + payload)
        return {}

    async def _button_single(self, request: JsonObject) -> JsonObject:
        self._emulator.inject_button_single()
        return {}

    async def _button_double(self, request: JsonObject) -> JsonObject:
        self._emulator.inject_button_double()
        return {}

    async def _button_long(self, request: JsonObject) -> JsonObject:
        self._emulator.inject_button_long()
        return {}

    async def _imu_tap(self, request: JsonObject) -> JsonObject:
        self._emulator.inject_imu_tap()
        return {}

    async def _get_framebuffer(self, request: JsonObject) -> JsonObject:
        output = io.BytesIO()
        self._emulator.get_framebuffer().save(output, format="PNG")
        return {"imagePng": base64.b64encode(output.getvalue()).decode("ascii")}

    # Called on the Lua thread.
    def _on_print(self, text: str) -> None:
        self._schedule_event({"event": "print", "text": text})

    # Called on the Lua thread.
    def _on_bluetooth_send(self, data: bytes) -> None:
        self._schedule_event({"event": "bluetooth_sent", "data": base64.b64encode(data).decode("ascii")})

    def _schedule_event(self, event: JsonObject) -> None:
        asyncio.run_coroutine_threadsafe(self._broadcast(json.dumps(event)), self._loop)

    async def _broadcast(self, encoded: str) -> None:
        await asyncio.gather(*(client.send(encoded) for client in self._clients), return_exceptions=True)

    def _error_text(self) -> str | None:
        error = self._emulator.get_error()
        return None if error is None else str(error)

    def _required_str(self, request: JsonObject, key: str) -> str:
        value = request.get(key)
        if not isinstance(value, str):
            raise ValueError(f"{key} must be a string")
        return value


async def serve(port: int) -> None:
    with HaloEmulator() as emulator:
        bridge = HaloBridge(emulator, asyncio.get_running_loop())
        emulator.connect()
        async with websockets.serve(bridge.handle_client, HOST, port) as server:
            bound_port = server.sockets[0].getsockname()[1]
            print(f"ws://{HOST}:{bound_port}", flush=True)
            # Reading stdin to EOF returns when the parent closes it or exits.
            await asyncio.to_thread(sys.stdin.buffer.read)


def main() -> int:
    parser = argparse.ArgumentParser(description="Serve the Halo Lua emulator over a local WebSocket.")
    parser.add_argument("--port", type=int, required=True, help="TCP port on 127.0.0.1; 0 picks a free one")
    args = parser.parse_args()
    asyncio.run(serve(args.port))
    return 0


if __name__ == "__main__":
    sys.exit(main())

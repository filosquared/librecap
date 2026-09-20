"""Single-window desktop launcher for LibreCap on macOS and Windows."""

from __future__ import annotations

import asyncio
import ctypes
import os
import subprocess
import sys
import threading


# Packaged desktop apps have no terminal for the interactive setup wizard.
os.environ["LIBRECAP_SKIP_WIZARD"] = "1"
os.environ["LIBRECAP_CHECK_BROWSER"] = "0"

import librusik
from lib import utils
from lib.desktop import build_app_url, initial_password_message


def app_url() -> str:
	return build_app_url(librusik.config, port=librusik.ACTIVE_PORT)


def show_initial_password() -> None:
	password = utils.INITIAL_ADMIN_PASSWORD
	if not password:
		return
	message = initial_password_message(librusik.config["name"], password)
	if sys.platform == "win32":
		ctypes.windll.user32.MessageBoxW(None, message, "LibreCap", 0)
		return
	if sys.platform == "darwin":
		escaped = message.replace("\\", "\\\\").replace('"', '\\"')
		script = f'display dialog "{escaped}" with title "LibreCap" buttons {{"OK"}} default button "OK"'
		result = subprocess.run(["/usr/bin/osascript", "-e", script], check=False)
		if result.returncode == 0:
			return
	print(message)


def _serve(stop_holder: dict[str, object], ready: threading.Event, errors: list[BaseException]) -> None:
	async def serve() -> None:
		shutdown_event = asyncio.Event()
		loop = asyncio.get_running_loop()
		stop_holder["request"] = lambda: loop.call_soon_threadsafe(shutdown_event.set)
		try:
			await librusik.run_server(
				on_started=ready.set,
				shutdown_event=shutdown_event,
				port=0,
			)
		except BaseException as error:
			errors.append(error)
			ready.set()

	asyncio.run(serve())


def run_embedded() -> None:
	try:
		import webview
	except ImportError:
		asyncio.run(librusik.run_server(open_browser=True, on_started=show_initial_password))
		return

	ready = threading.Event()
	startup_errors: list[BaseException] = []
	stop_holder: dict[str, object] = {}
	thread = threading.Thread(
		target=_serve,
		args=(stop_holder, ready, startup_errors),
		name="librecap-server",
		daemon=True,
	)
	thread.start()
	if not ready.wait(timeout=15):
		raise RuntimeError("LibreCap server did not start within 15 seconds")
	if startup_errors:
		raise startup_errors[0]

	show_initial_password()
	window = webview.create_window(
		"LibreCap",
		app_url(),
		width=1200,
		height=800,
		min_size=(900, 600),
	)
	window.events.closed += lambda: stop_holder["request"]()
	try:
		webview.start(debug=False)
	finally:
		stop_holder["request"]()
		thread.join(timeout=15)


if __name__ == "__main__":
	run_embedded()

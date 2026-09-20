"""Pure helpers shared by the desktop launchers."""

from __future__ import annotations

from collections.abc import Mapping


def build_app_url(config: Mapping[str, object], port: int | None = None) -> str:
	"""Build the local URL used by an embedded desktop window."""
	scheme = "https" if config.get("ssl") else "http"
	bound_port = port if port is not None else int(config["port"])
	subdirectory = str(config.get("subdirectory") or "/")
	if not subdirectory.startswith("/"):
		subdirectory = f"/{subdirectory}"
	return f"{scheme}://127.0.0.1:{bound_port}{subdirectory}"


def initial_password_message(admin_name: str, password: str) -> str:
	return (
		"LibreCap is ready.\n\n"
		f"Administrator: {admin_name}\n"
		f"Initial password: {password}\n\n"
		"Save this password, then change it in the admin panel."
	)

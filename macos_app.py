"""Compatibility entry point for the packaged macOS desktop app."""

from __future__ import annotations

import sys

from desktop_app import app_url, run_embedded, show_initial_password

__all__ = ["app_url", "run_embedded", "show_initial_password"]


if __name__ == "__main__":
	if sys.platform != "darwin":
		raise SystemExit("The macOS app launcher must run on macOS.")
	run_embedded()

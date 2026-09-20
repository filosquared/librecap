import unittest
from pathlib import Path

from lib.desktop import build_app_url, initial_password_message
from lib.utils import default_data_dir


class RuntimeDataDirectoryTests(unittest.TestCase):
	def test_frozen_windows_uses_local_app_data(self):
		data_dir = default_data_dir(
			Path("C:/bundle"),
			frozen=True,
			platform_name="win32",
			home=Path("C:/Users/Alice"),
			environ={"LOCALAPPDATA": "C:/Users/Alice/AppData/Local"},
		)

		self.assertEqual(data_dir, Path("C:/Users/Alice/AppData/Local/LibreCap"))

	def test_frozen_windows_falls_back_to_home_when_local_app_data_is_missing(self):
		data_dir = default_data_dir(
			Path("C:/bundle"),
			frozen=True,
			platform_name="win32",
			home=Path("C:/Users/Alice"),
			environ={},
		)

		self.assertEqual(data_dir, Path("C:/Users/Alice/AppData/Local/LibreCap"))

	def test_explicit_data_directory_overrides_platform_defaults(self):
		data_dir = default_data_dir(
			Path("C:/bundle"),
			frozen=True,
			platform_name="win32",
			home=Path("C:/Users/Alice"),
			environ={"LIBRECAP_DATA_DIR": "D:/LibreCapData"},
		)

		self.assertEqual(data_dir, Path("D:/LibreCapData"))

	def test_source_runs_keep_repository_local_data_directory(self):
		data_dir = default_data_dir(
			Path("/project"),
			frozen=False,
			platform_name="win32",
			home=Path("C:/Users/Alice"),
			environ={},
		)

		self.assertEqual(data_dir, Path("/project/data"))


class DesktopLauncherTests(unittest.TestCase):
	def test_app_url_uses_bound_ephemeral_port(self):
		config = {"ssl": False, "port": 7777, "subdirectory": "/"}

		self.assertEqual(build_app_url(config, port=49152), "http://127.0.0.1:49152/")

	def test_initial_password_message_is_explicit(self):
		message = initial_password_message("admin", "one-time-password")

		self.assertIn("Administrator: admin", message)
		self.assertIn("Initial password: one-time-password", message)
		self.assertIn("change it", message.lower())

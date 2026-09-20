# LibreCap for Windows

The Windows desktop app runs the existing LibreCap server locally and opens
the existing HTML/CSS/JavaScript interface in a single embedded window. It
does not require a separate browser or Python installation after packaging.

## Run from source

Use Windows 10 or 11 with 64-bit Python 3.13:

```powershell
py -3.13 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements-windows.txt
python desktop_app.py
```

The packaged app binds only to `127.0.0.1` and stores its runtime database,
encryption key, and profile images in:

```text
%LOCALAPPDATA%\LibreCap
```

Set `LIBRECAP_DATA_DIR` before launch when an existing web-app data directory
must be used explicitly. The application never deletes that directory during
uninstallation.

The embedded window uses the Microsoft Edge WebView2 runtime supplied by
pywebview. Install the Evergreen WebView2 Runtime on machines where it is not
already present.

## Build the executable

Install the dependencies and run PowerShell from the repository root:

```powershell
python -m pip install -r requirements-windows.txt
powershell -ExecutionPolicy Bypass -File .\tools\build-windows.ps1
```

The executable is written to `dist\LibreCap\LibreCap.exe`. The build must be
performed on Windows; the old `tools/build-win-exe.sh` script is a legacy
Wine-based experiment and is not the release build path.

## Build the installer

Install Inno Setup, then run:

```powershell
iscc .\packaging\windows\LibreCap.iss
```

The installer is written to `dist\installer`. It installs per-user, creates
Start Menu and Desktop shortcuts, and leaves `%LOCALAPPDATA%\LibreCap` intact
when uninstalled.

## Troubleshooting

- If the window does not open, install or repair the WebView2 Evergreen
  Runtime.
- If an existing installation is not found, set `LIBRECAP_DATA_DIR` to the
  directory containing `librecap.sqlite3` and `fernet.key`.
- Do not expose the local server to the network unless TLS and access control
  are configured deliberately.

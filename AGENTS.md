## Project Purpose

LibreCap is a self-hosted, open-source school journal. Keep Librus connectivity optional through an adapter; prefer provider-neutral data models.

## Stack and Runtime

Python 3.13, `aiohttp`, `BeautifulSoup4`, `cryptography`, SQLite, HTML/CSS/vanilla JavaScript, native SwiftUI/URLSession/Keychain for iOS, optional `pywebview`/PyInstaller for macOS, Docker.

## Build, Test, Run

- Run: `python3 librusik.py --skip-wizard`
- macOS app: `python3 macos_app.py` after `pip install -r requirements-macos.txt`
- macOS bundle: `./tools/build-macos-app.sh`
- Windows app: `python desktop_app.py` after `pip install -r requirements-windows.txt`
- Windows bundle: `powershell -ExecutionPolicy Bypass -File .\tools\build-windows.ps1`
- iOS simulator build: `./tools/build-ios.sh` after installing full Xcode
- Syntax check: `PYTHONPYCACHEPREFIX=/tmp/librecap-pycache python3 -m compileall -q .`
- Tests: `python3 -m unittest discover -s tests`
- Validate patches: `git diff --check`

## Architecture Map

- `librusik.py`: server and routes
- `macos_app.py`: embedded-window macOS launcher
- `ios/LibreCap/`: native SwiftUI app, direct Librus client, Keychain, and local cache
- `lib/api/`: provider adapters and sessions
- `lib/utils.py`: persistence and helpers
- `html/`, `static/`: frontend
- `data/`: runtime data; never commit it
- `tests/`: offline tests and anonymized fixtures
- `tools/build-macos-app.sh`: macOS `.app` build
- `tools/build-ios.sh`: iOS simulator build helper

## Domain Model

Users own accounts and sessions. Providers supply students, grades, attendance, timetable, homework, and messages. Credentials and provider tokens are secrets.

## Agent Guardrails

- Preserve the MIT license.
- Never commit credentials, tokens, real student data, or API dumps.
- Never use live credentials in tests.
- Do not copy proprietary Librus code, assets, or bypass authentication.
- Keep provider integration isolated and user-authorized.
- Preserve backward compatibility unless a migration is documented.
- Treat database migrations and credential changes as high-risk.

## Known Failure Modes

- Browser-held reversible credentials.
- Plain SHA-256 passwords and documented default credentials.
- Stack traces exposed to clients.
- JSON persistence can be corrupted by concurrent writes.
- Upstream API responses are incomplete or unstable.
- Timetable classroom lookup currently has inconsistent ID handling.

## Verification Before Completion

Run `PYTHONPYCACHEPREFIX=/tmp/librecap-pycache python3 -m compileall -q .`, `python3 -m unittest discover -s tests`, and `git diff --check`.

## Escalation - Ask the User When

Ask before using real credentials, transmitting personal data, deploying publicly, deleting data, or changing the upstream integration contract.

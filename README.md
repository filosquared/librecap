# LibreCap

LibreCap is a self-hosted, open-source school journal for students and
families. It provides grades, attendance, timetable, homework, school days
off, messages, and account settings in a provider-neutral data model.

The optional Librus/Synergia adapter uses credentials supplied by the account
owner. LibreCap is an independent community project and is not affiliated with
Librus. Never expose a server instance without TLS, access controls, and a
trusted reverse proxy.

## Choose an app

| App | Best for | Server required |
| --- | --- | --- |
| Web app | Self-hosting on a Mac, NAS, or server | Yes |
| Native macOS app | A single desktop app with the shared SwiftUI interface | No |
| Native Windows app | A single desktop app using the existing LibreCap interface | No |
| Native iPhone app | Direct Librus access with local caching | No |
| Apple Watch companion | Quick timetable, grades, homework, and lesson alerts | No; paired iPhone required |
| Native Android app | Direct Librus access with local caching | No |

The native Apple apps use the school-issued **Synergia login** and keep the
Librus password in Keychain. They do not require a LibreCap server. The Watch
receives a privacy-limited snapshot from the paired iPhone; it has no separate
login or direct network client.

## Features

- Grades with independent average calculation
- Messages and attachment downloads
- Attendance summaries and per-semester views
- Timetable, homework, school free days, and teacher free days
- Local-first native apps with offline cache
- Native Android app with the same dashboard and school-data categories
- Recent grade, exam, absence, and parent-teacher conference notifications
- Apple Watch companion with optional five-minute lesson-ending alerts
- HttpOnly server-managed sessions for the web app
- SQLite persistence with migration from older JSON installations
- Dark theme and optional confetti
- Administrator panel with registration, account, and tier controls

## Web app

The Home page shows notification banners for new school activity observed in
the last 14 days. The first successful refresh establishes a baseline, so
existing records do not generate a burst of alerts. Notifications are kept in
memory per account and are not written to the persistent school-data store.

```bash
git clone https://github.com/filosquared/librecap.git
cd librecap
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install -r requirements.txt
python3 librusik.py --skip-wizard
```

Open [http://localhost:7777](http://localhost:7777). On first start, LibreCap
prints a randomly generated administrator password. Save it, sign in at
`/panel`, and change it immediately. Omit `--skip-wizard` to use the
interactive setup wizard.

New installations listen on `127.0.0.1`. Set
`LIBRECAP_LISTEN_ADDRESS=0.0.0.0` only when the service is intentionally
behind a protected network boundary.

## Native Apple apps

The shared Xcode project is [`ios/LibreCap.xcodeproj`](ios/LibreCap.xcodeproj).
Open it in full Xcode, choose a signing team, and use these schemes:

- `LibreCap` on an iPhone or iOS Simulator
- `LibreCapWatch` on a paired Apple Watch or watchOS Simulator
- `LibreCap` with **My Mac** for the native macOS app

The native macOS app starts directly in one window and stores its cache in
`~/Library/Application Support/LibreCap`. It does not start the Python server.
The older Python macOS wrapper remains available when the web app is preferred.

Convenience build commands:

```bash
./tools/build-ios.sh
./tools/build-watch.sh
./tools/build-macos-native.sh
```

The macOS native build writes `dist/native/LibreCap.app`. For the older Python
wrapper, install `requirements-macos.txt`, run `python3 macos_app.py`, or use
`./tools/build-macos-app.sh`.

See the platform guides for details:

- [iOS and shared Xcode project](ios/README.md)
- [native macOS app](macos/README.md)
- [Windows desktop app](windows/README.md)
- [Apple Watch companion and lesson alerts](watchOS/README.md)
- [Android app](android/README.md)

### Apple Watch lesson alerts

On iPhone, enable **More → Apple Watch → Lesson-ending alerts**, allow Watch
notifications, and refresh the timetable. The Watch schedules reminders from
the saved phone snapshot and can show the next lesson, classroom, and teacher
five minutes before the current lesson ends. Alerts are off by default and are
not critical alerts or forced app launches.

### App icon

The portable Icon Composer source is [`LibreCap.icon`](LibreCap.icon). The
generated iOS and Watch asset catalogs are included in the Xcode project.

## Data and privacy

The web app stores runtime state in `data/`:

- `librecap.sqlite3` contains configuration and application accounts.
- `fernet.key` encrypts stored upstream credentials and must be protected.
- `profile_pics/` contains uploaded profile images.

If `config.json` or `database.json` exists, it is migrated once into SQLite and
retained as a recoverable backup. Never commit `data/`, real student records,
passwords, session tokens, or provider API responses.

Useful environment overrides:

```bash
LIBRECAP_DATA_DIR=/srv/librecap/data
LIBRECAP_LISTEN_ADDRESS=127.0.0.1
LIBRECAP_PORT=7777
```

Native apps store credentials in the platform Keychain and school data in
private app storage. Signing out removes saved credentials and local cache.

## Docker

```bash
docker build -t librecap .
docker run -d --name librecap \
  -p 7777:7777 \
  -v "$(pwd)/data:/app/data" \
  librecap
```

The container listens on all interfaces inside the container. Use TLS and
authentication at a reverse proxy outside a trusted local network.

## Development checks

```bash
PYTHONPYCACHEPREFIX=/tmp/librecap-pycache python3 -m compileall -q .
python3 -m unittest discover -s tests
./tools/test-native.sh
git diff --check
```

The tests are offline and use synthetic data only. Do not add live Librus
credentials or real student records to the repository.

## License

LibreCap is distributed under the MIT License. See [LICENSE](LICENSE).

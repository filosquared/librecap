# Message attachment download — TDD evidence

## Source plan

Derived from the user request to make message attachments actually download.
The key decision was to use LibreCap's authenticated `URLSession` instead of
opening the attachment URL in Safari, because Safari does not share the
session cookies held by the app.

## User journey

As a LibreCap user, I want to download a file attached to a message so that I
can save or share the file even when Librus redirects the attachment request.

## RED evidence

Test: `tests/ios_auth_checks.swift`

Command:

```text
swiftc -module-cache-path /tmp/librecap-swift-cache ios/LibreCap/Models.swift ios/LibreCap/LibrusClient.swift tests/ios_auth_checks.swift -o /tmp/librecap-auth-checks
```

Result: RED. Compilation failed at the new test calls because
`LibrusClient.downloadMessageAttachment` did not exist.

Checkpoint: `73f70f8 test: add authenticated message attachment download reproducer`

## GREEN evidence

| # | Guarantee | Test | Result |
|---|---|---|---|
| 1 | Attachment requests follow a Synergia-to-sandbox redirect using the app session. | `tests/ios_auth_checks.swift` | PASS |
| 2 | Binary data is written to a local file with the server-provided filename. | `tests/ios_auth_checks.swift` | PASS |
| 3 | Attachments without `Content-Disposition` use the message filename fallback. | `tests/ios_auth_checks.swift` | PASS |
| 4 | An HTML Librus login response is rejected as `sessionUnauthorized`, not offered as a file. | `tests/ios_auth_checks.swift` | PASS |
| 5 | AppModel can refresh an expired Librus session and retry the attachment request. | `ios/LibreCap/AppModel.swift` and lifecycle-compatible stub | PASS: native harness compiles and runs |
| 6 | The message UI shows progress, then exposes the local file through the iOS save/share sheet. | `ios/LibreCap/SchoolViews.swift` | Source-integrated; simulator UI execution not available in this environment |

Commands and results:

```text
swiftc -module-cache-path /tmp/librecap-swift-cache ios/LibreCap/Models.swift ios/LibreCap/LibrusClient.swift tests/ios_auth_checks.swift -o /tmp/librecap-auth-checks
/tmp/librecap-auth-checks
# iOS authentication checks passed
# iOS message attachment checks passed

./tools/test-native.sh
# all native checks passed, including authenticated attachment download

PYTHONPYCACHEPREFIX=/tmp/librecap-pycache python3 -m compileall -q .
# passed

python3 -m unittest discover -s tests
# Ran 14 tests ... OK

git diff --check
# passed
```

GREEN checkpoint: `d94a708 fix: download message attachments in authenticated session`.

## Coverage and known gaps

The repository has no configured Swift coverage command or coverage threshold,
so numeric coverage was not available. The focused offline fixture covers the
redirect, binary response, filename fallback, and login-page failure paths.

The full Xcode scheme build remains blocked by the host environment's existing
watchOS Preview macro failure (`PreviewsMacros.SwiftUIView` malformed response)
and CoreSimulator service errors. The failure occurs in the Watch target before
the iOS target is compiled; it is unrelated to the attachment implementation.

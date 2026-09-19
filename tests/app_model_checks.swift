// Compile AppModel against in-memory substitutes, never the real Keychain/client.
import Foundation

struct LibrusCredentials { var username: String; var password: String }

enum LibrusClientError: Error, Equatable {
    case invalidCredentials
    case sessionUnauthorized
}

@MainActor final class KeychainStore {
    static var credentials: LibrusCredentials?
    func load() -> LibrusCredentials? { Self.credentials }
    func save(username: String, password: String) throws { Self.credentials = LibrusCredentials(username: username, password: password) }
    func delete() { Self.credentials = nil }
}

@MainActor final class LocalStore {
    static var cached = CachedSchoolData.empty
    func load() -> CachedSchoolData { Self.cached }
    @discardableResult
    func save(_ data: CachedSchoolData) -> Bool { Self.cached = data; return true }
    func clear() { Self.cached = .empty }
}

@MainActor final class LibrusClient {
    static let profile = StudentProfile(firstName: "Fixture", lastName: "Student", tutorFirstName: "", tutorLastName: "",
                                       schoolYearStarts: "", schoolYearMiddles: "", schoolYearEnds: "", type: "Student", className: "Test")
    static var failTimetable = false
    static var pauseProfile = false
    static var pauseLogin = false
    static var pending: CheckedContinuation<Void, Never>?

    func login(username: String, password: String) async throws -> StudentProfile {
        if Self.pauseLogin { await withCheckedContinuation { Self.pending = $0 } }
        return Self.profile
    }
    func fetchProfile() async throws -> StudentProfile {
        if Self.pauseProfile { await withCheckedContinuation { Self.pending = $0 } }
        return Self.profile
    }
    func fetchGrades() async throws -> [GradeRecord] { [] }
    func fetchTimetable() async throws -> TimetableData {
        if Self.failTimetable { throw NSError(domain: "OfflineFixture", code: 1) }
        return TimetableData(nextWeek: false, days: [:], weekStart: "2026-09-14")
    }
    func fetchAttendances() async throws -> [AttendanceRecord] { [] }
    func fetchHomeworks() async throws -> [HomeworkRecord] { [] }
    func fetchMessages() async throws -> [MessageSummary] { [] }
    func fetchMessage(id: String) async throws -> MessageDetail { MessageDetail(subject: "", sender: "", date: "", content: "") }
    func downloadMessageAttachment(_ attachment: MessageAttachment) async throws -> DownloadedMessageAttachment {
        fatalError("Attachment downloads are not part of the AppModel lifecycle fixture")
    }
}

struct NoopReleaseProvider: GitHubReleaseProviding {
    func fetchLatest() async throws -> GitHubRelease {
        throw NSError(domain: "OfflineFixture", code: 2)
    }
}

@main @MainActor struct AppModelChecks {
    static func waitForSuspension() async {
        for _ in 0..<1000 {
            if LibrusClient.pending != nil { return }
            await Task.yield()
        }
        preconditionFailure("Fixture did not suspend")
    }

    static func resume() {
        let pending = LibrusClient.pending
        LibrusClient.pending = nil
        pending?.resume()
    }

    static func main() async {
        let model = AppModel(releaseProvider: NoopReleaseProvider())
        await model.login(username: "fixture-student", password: "not-a-real-password")
        precondition(model.isAuthenticated && model.data.profile != nil)
        precondition(model.data.timetableUpdatedAt != nil && model.data.gradesUpdatedAt != nil && model.data.homeworksUpdatedAt != nil)
        model.saveNote(id: "fixture-note", text: "Remember the project", reminds: true, isNoLongerRelevant: false)
        precondition(model.note(for: "fixture-note")?.reminds == true)
        model.saveNote(id: "fixture-note", text: "", reminds: false, isNoLongerRelevant: false)
        precondition(model.note(for: "fixture-note") == nil)
        let timetableDate = model.data.timetableUpdatedAt
        LibrusClient.failTimetable = true
        await model.sync()
        precondition(model.data.timetableUpdatedAt == timetableDate)
        precondition(model.errorMessage != nil && model.data.timetable?.weekStart == "2026-09-14")
        LibrusClient.failTimetable = false

        LibrusClient.pauseProfile = true
        let refresh = Task { await model.sync() }
        await waitForSuspension()
        model.logout()
        resume()
        await refresh.value
        precondition(!model.isAuthenticated && model.isReady && !model.isSyncing)
        precondition(model.data.profile == nil && LocalStore.cached.profile == nil && KeychainStore.credentials == nil)
        LibrusClient.pauseProfile = false

        LibrusClient.pauseLogin = true
        let login = Task { await model.login(username: "fixture-student", password: "not-a-real-password") }
        await waitForSuspension()
        model.logout()
        resume()
        await login.value
        precondition(!model.isAuthenticated && model.data.profile == nil && KeychainStore.credentials == nil)

        KeychainStore.credentials = LibrusCredentials(username: "fixture", password: "fake")
        LocalStore.cached.profile = LibrusClient.profile
        let restoring = AppModel(releaseProvider: NoopReleaseProvider())
        precondition(restoring.isReady && restoring.isAuthenticated, "Cached data should remain readable during restore")
        await waitForSuspension()
        restoring.logout()
        resume()
        for _ in 0..<10 { await Task.yield() }
        precondition(!restoring.isAuthenticated && restoring.isReady && restoring.data.profile == nil)
        precondition(LocalStore.cached.profile == nil && KeychainStore.credentials == nil)
        print("AppModel lifecycle checks passed (partial refresh, logout during sync/login/restore; in-memory substitutes).")
    }
}

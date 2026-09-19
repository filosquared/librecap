import Foundation
import SwiftUI

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var isReady = false
    @Published private(set) var isAuthenticated = false
    @Published private(set) var isSyncing = false
    @Published private(set) var username = ""
    @Published private(set) var data = CachedSchoolData.empty
    @Published private(set) var notes: [SchoolNote]
    @Published private(set) var automaticSyncEnabled: Bool
    @Published private(set) var notificationsEnabled: Bool
    @Published private(set) var notificationPermission: NotificationPermission = .notDetermined
    @Published private(set) var isUpdatingNotifications = false
    @Published private(set) var availableUpdate: GitHubRelease?
    @Published var errorMessage: String?

    private let keychain = KeychainStore()
    private let store = LocalStore()
    private let notesStore = UserNotesStore()
    private let notificationClient: LocalNotificationClient
    private let releaseProvider: GitHubReleaseProviding
    private let currentAppVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.0.0"
    private var client: LibrusClient?
    private var sessionGeneration = UUID()
    private var syncingSession: UUID?
    private var automaticSyncTask: Task<Void, Never>?
    private var notificationBaselineEstablished: Bool
    private static let notificationPreferenceKey = "librecap.schoolNotificationsEnabled"
    private static let notificationBaselineKey = "librecap.schoolNotificationsBaselineEstablished"
    #if os(iOS)
    let watchSync = PhoneWatchSync()
    #endif

    init(notificationClient: LocalNotificationClient? = nil, releaseProvider: GitHubReleaseProviding? = nil) {
        self.notificationClient = notificationClient ?? SystemLocalNotificationClient()
        self.releaseProvider = releaseProvider ?? GitHubReleaseClient()
        data = store.load()
        notes = notesStore.load()
        automaticSyncEnabled = UserDefaults.standard.object(forKey: "automaticSyncEnabled") as? Bool ?? true
        notificationsEnabled = UserDefaults.standard.bool(forKey: Self.notificationPreferenceKey)
        notificationBaselineEstablished = UserDefaults.standard.bool(forKey: Self.notificationBaselineKey)
        if let credentials = keychain.load() {
            username = credentials.username
            // Let the user read the last successful sync while a fresh login runs.
            isAuthenticated = data.profile != nil
            isReady = isAuthenticated
            publishToWatch()
            let generation = sessionGeneration
            Task { await restore(credentials, generation: generation) }
        } else {
            isReady = true
            publishToWatch()
        }
#if os(iOS)
        Task { await refreshNotificationPermission() }
#endif
        Task { await checkForUpdates() }
        startAutomaticSync()
    }

    deinit {
        automaticSyncTask?.cancel()
    }

    func login(username: String, password: String) async {
        let generation = UUID()
        sessionGeneration = generation
        syncingSession = nil
        client = nil
        errorMessage = nil
        isSyncing = true

        do {
            let newClient = LibrusClient()
            let profile = try await newClient.login(username: username, password: password)
            try Task.checkCancellation()
            guard sessionGeneration == generation else { return }
            try keychain.save(username: username, password: password)
            client = newClient
            self.username = username
            isAuthenticated = true
            isReady = true
            data = .empty
            data.profile = profile
            notificationBaselineEstablished = false
            UserDefaults.standard.removeObject(forKey: Self.notificationBaselineKey)
            await notificationClient.clearLibreCapNotifications()
            publishToWatch()
            await sync()
        } catch is CancellationError {
            return
        } catch {
            guard sessionGeneration == generation else { return }
            isReady = true
            errorMessage = error.localizedDescription
        }
        if sessionGeneration == generation { isSyncing = false }
    }

    func sync() async {
        guard let initialClient = client else {
            await retry()
            return
        }
        let generation = sessionGeneration
        guard syncingSession != generation else { return }
        syncingSession = generation
        isSyncing = true
        errorMessage = nil
        let previousData = data
        defer {
            if syncingSession == generation {
                syncingSession = nil
                isSyncing = false
            }
        }

        do {
            var activeClient = initialClient
            var didRecoverSession = false

            while true {
                let result = try await fetchAll(using: activeClient, startingFrom: data)
                if result.sessionExpired && !didRecoverSession {
                    guard let credentials = keychain.load() else {
                        errorMessage = result.firstError?.localizedDescription
                        return
                    }
                    let refreshedClient = LibrusClient()
                    do {
                        _ = try await refreshedClient.login(username: credentials.username, password: credentials.password)
                        try Task.checkCancellation()
                        guard sessionGeneration == generation else { return }
                        activeClient = refreshedClient
                        client = refreshedClient
                        didRecoverSession = true
                        continue
                    } catch is CancellationError {
                        return
                    } catch {
                        handleAuthenticationFailure(error)
                        return
                    }
                }

                // A refresh finishing after sign-out must not restore old account data.
                guard sessionGeneration == generation else { return }
                var refreshed = result.data
                await deliverNotifications(from: previousData, to: refreshed, canEstablishBaseline: result.firstError == nil)
                refreshed.lastSync = Date()
                data = refreshed
                errorMessage = result.firstError?.localizedDescription
                if !store.save(data) && errorMessage == nil {
                    errorMessage = "LibreCap could not save the latest school data on this device."
                }
                publishToWatch()
                isAuthenticated = true
                isReady = true
                return
            }
        } catch is CancellationError {
            return
        } catch {
            guard sessionGeneration == generation else { return }
            errorMessage = error.localizedDescription
        }
    }

    func retry() async {
        guard !Task.isCancelled else { return }
        if let credentials = keychain.load() {
            let generation = UUID()
            sessionGeneration = generation
            client = nil
            errorMessage = nil
            await restore(credentials, generation: generation)
        } else if client != nil {
            await sync()
        } else {
            isReady = true
            isAuthenticated = false
            errorMessage = "Please sign in again to refresh your school data."
        }
    }

    func setAutomaticSyncEnabled(_ enabled: Bool) {
        automaticSyncEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "automaticSyncEnabled")
    }

    func refreshNotificationPermission() async {
        let permission = await notificationClient.permission()
        notificationPermission = permission
        if permission == .denied || permission == .restricted {
            notificationsEnabled = false
            UserDefaults.standard.set(false, forKey: Self.notificationPreferenceKey)
        }
    }

    func checkForUpdates() async {
        guard availableUpdate == nil else { return }
        guard let release = try? await releaseProvider.fetchLatest(),
              !release.draft,
              !release.prerelease,
              ReleaseVersion.isNewer(release.tagName, than: currentAppVersion) else { return }
        availableUpdate = release
    }

    func setNotificationsEnabled(_ enabled: Bool) async {
        guard !isUpdatingNotifications else { return }
        if !enabled {
            notificationsEnabled = false
            UserDefaults.standard.set(false, forKey: Self.notificationPreferenceKey)
            await notificationClient.clearLibreCapNotifications()
            await refreshNotificationPermission()
            return
        }

        isUpdatingNotifications = true
        defer { isUpdatingNotifications = false }
        var permission = await notificationClient.permission()
        if permission == .notDetermined {
            _ = await notificationClient.requestAuthorization()
            permission = await notificationClient.permission()
        }
        notificationPermission = permission
        let allowed = permission == .authorized
        notificationsEnabled = allowed
        UserDefaults.standard.set(allowed, forKey: Self.notificationPreferenceKey)
    }

    func note(for id: String) -> SchoolNote? {
        notes.first { $0.id == id }
    }

    func saveNote(id: String, text: String, reminds: Bool, isNoLongerRelevant: Bool) {
        let note = SchoolNote(
            id: id,
            text: text,
            reminds: reminds,
            isNoLongerRelevant: isNoLongerRelevant,
            updatedAt: Date()
        )
        notes.removeAll { $0.id == id }
        if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || reminds || isNoLongerRelevant {
            notes.append(note)
        }
        notesStore.save(notes)
    }

    func loadMessage(_ summary: MessageSummary) async -> MessageDetail? {
        guard let client else { return nil }
        do {
            return try await client.fetchMessage(id: summary.id)
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    func downloadMessageAttachment(_ attachment: MessageAttachment) async throws -> DownloadedMessageAttachment {
        let generation = sessionGeneration
        guard let initialClient = client else {
            throw LibrusClientError.sessionUnauthorized
        }

        do {
            return try await initialClient.downloadMessageAttachment(attachment)
        } catch let error as LibrusClientError where error == .sessionUnauthorized {
            guard let credentials = keychain.load() else { throw error }
            let refreshedClient = LibrusClient()
            _ = try await refreshedClient.login(username: credentials.username, password: credentials.password)
            try Task.checkCancellation()
            guard sessionGeneration == generation else { throw CancellationError() }
            client = refreshedClient
            return try await refreshedClient.downloadMessageAttachment(attachment)
        }
    }

    func logout() {
        sessionGeneration = UUID()
        syncingSession = nil
        isSyncing = false
        isReady = true
        keychain.delete()
        store.clear()
        notesStore.clear()
        client = nil
        username = ""
        data = .empty
        notes = []
        isAuthenticated = false
        notificationsEnabled = false
        notificationBaselineEstablished = false
        UserDefaults.standard.set(false, forKey: Self.notificationPreferenceKey)
        UserDefaults.standard.removeObject(forKey: Self.notificationBaselineKey)
        Task { await notificationClient.clearLibreCapNotifications() }
        errorMessage = nil
        publishToWatch()
    }

    private struct SyncResult {
        var data: CachedSchoolData
        var firstError: Error?
        var sessionExpired = false
    }

    private func fetchAll(using client: LibrusClient, startingFrom startingData: CachedSchoolData) async throws -> SyncResult {
        var refreshed = startingData
        var firstError: Error?
        var sessionExpired = false

        func record(_ error: Error) {
            if error is CancellationError { return }
            firstError = firstError ?? error
            if isSessionExpired(error) { sessionExpired = true }
        }

        do {
            refreshed.profile = try await client.fetchProfile()
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            record(error)
        }
        if !sessionExpired {
            do {
                refreshed.grades = try await client.fetchGrades()
                refreshed.gradesUpdatedAt = Date()
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                record(error)
            }
        }
        if !sessionExpired {
            do {
                refreshed.timetable = try await client.fetchTimetable()
                refreshed.timetableUpdatedAt = Date()
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                record(error)
            }
        }
        if !sessionExpired {
            do {
                refreshed.attendances = try await client.fetchAttendances()
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                record(error)
            }
        }
        if !sessionExpired {
            do {
                refreshed.homeworks = try await client.fetchHomeworks()
                refreshed.homeworksUpdatedAt = Date()
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                record(error)
            }
        }
        if !sessionExpired {
            do {
                refreshed.messages = try await client.fetchMessages()
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                record(error)
            }
        }

        return SyncResult(data: refreshed, firstError: firstError, sessionExpired: sessionExpired)
    }

    private func isSessionExpired(_ error: Error) -> Bool {
        (error as? LibrusClientError) == .sessionUnauthorized
    }

    private func handleAuthenticationFailure(_ error: Error) {
        client = nil
        if (error as? LibrusClientError) == .invalidCredentials {
            keychain.delete()
            username = ""
            isAuthenticated = false
            errorMessage = "Please sign in again: \(error.localizedDescription)"
        } else {
            errorMessage = error.localizedDescription
        }
        isReady = true
    }

    private func restore(_ credentials: LibrusCredentials, generation: UUID) async {
        guard sessionGeneration == generation else { return }
        let newClient = LibrusClient()
        isSyncing = true
        defer {
            if sessionGeneration == generation { isSyncing = false }
        }
        do {
            _ = try await newClient.login(username: credentials.username, password: credentials.password)
            try Task.checkCancellation()
            guard sessionGeneration == generation else { return }
            client = newClient
            isAuthenticated = true
            isReady = true
            await sync()
        } catch is CancellationError {
            return
        } catch {
            guard sessionGeneration == generation else { return }
            isReady = true
            if (error as? LibrusClientError) == .invalidCredentials {
                handleAuthenticationFailure(error)
            } else if !isAuthenticated {
                errorMessage = "Please sign in again: \(error.localizedDescription)"
            } else {
                errorMessage = "Showing your last saved data. Sync failed: \(error.localizedDescription)"
            }
        }
    }

    private func publishToWatch() {
        #if os(iOS)
        watchSync.publish(data, signedIn: isAuthenticated, accountID: sessionGeneration.uuidString)
        #endif
    }

    private func deliverNotifications(
        from previous: CachedSchoolData,
        to current: CachedSchoolData,
        canEstablishBaseline: Bool
    ) async {
        guard notificationBaselineEstablished else {
            guard canEstablishBaseline else { return }
            notificationBaselineEstablished = true
            UserDefaults.standard.set(true, forKey: Self.notificationBaselineKey)
            return
        }
        guard notificationsEnabled else { return }

        let permission = await notificationClient.permission()
        notificationPermission = permission
        guard permission == .authorized else {
            if permission == .denied || permission == .restricted {
                notificationsEnabled = false
                UserDefaults.standard.set(false, forKey: Self.notificationPreferenceKey)
            }
            return
        }

        let language = SchoolNotificationLanguage(
            rawValue: UserDefaults.standard.string(forKey: "librecap.language") ?? "en"
        ) ?? .english
        let notifications = SchoolNotificationPlanner.newNotifications(
            from: previous,
            to: current,
            baselineEstablished: true,
            language: language
        )
        await notificationClient.schedule(notifications)
    }

    private func startAutomaticSync() {
        automaticSyncTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: 45 * 60 * 1_000_000_000)
                } catch {
                    return
                }
                guard !Task.isCancelled, let self else { return }
                guard self.automaticSyncEnabled, self.isAuthenticated, !self.isSyncing else { continue }
                if self.client == nil {
                    await self.retry()
                } else {
                    await self.sync()
                }
            }
        }
    }
}

private final class UserNotesStore {
    private let key = "librecap.schoolNotes"
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init() {
        encoder.dateEncodingStrategy = .iso8601
        decoder.dateDecodingStrategy = .iso8601
    }

    func load() -> [SchoolNote] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let notes = try? decoder.decode([SchoolNote].self, from: data) else { return [] }
        return notes
    }

    func save(_ notes: [SchoolNote]) {
        guard let data = try? encoder.encode(notes) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }

    func clear() {
        UserDefaults.standard.removeObject(forKey: key)
    }
}

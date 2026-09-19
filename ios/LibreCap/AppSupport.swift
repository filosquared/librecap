import Foundation
import SwiftUI

enum AppLanguage: String, CaseIterable, Identifiable {
    case english = "en"
    case polish = "pl"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .english: return "English"
        case .polish: return "Polski"
        }
    }
}

enum AppAppearance: String, CaseIterable, Identifiable {
    case system
    case light
    case dark

    var id: String { rawValue }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

enum AppCopyKey: Hashable {
    case welcome, goodToSee, connecting, tagline, schoolAccount, loginHint, schoolLogin, password
    case passwordStored, signIn, signingIn, home, grades, schedule, messages, more
    case github
    case today, currentLesson, noLessonsToday, noRemainingLessons, seeAll, tryAgain
    case tests, homework, attendance, absences, syncNow, syncing, lastSync, quickActions
    case thisDevice, noGrades, firstSemester, secondSemester, average, gradesCount
    case noWeight, weight, finalGrade, details, noTimetable, selectDay, lessonNumber
    case classroom, teacher, cancelled, substitution, noAttendance, present, absent
    case recentRecords, school, account, signOut, settings, appName, language, appearance, appearanceSystem, appearanceLight, appearanceDark
    case automaticSync, automaticSyncDescription, every45Minutes, save, reset
    case notifications, schoolNotifications, schoolNotificationsDescription, notificationsBlocked
    case updateAvailable, updateAvailableDescription, viewRelease
    case inbox, sent, announcements, notes, noMessages, folderUnavailable, due, addedBy, attachments, download, saveFile
    case noHomework, all, assessments, noLongerRelevant, remindMe, note, saveNote, saved
    case lessonDetails, gradeDetails, tutor, teacherRole, replacedTeacher, studentInformation, accountType, appleWatch, lessonAlerts, watchAlertDescription, sendLatestToWatch, watchDataDescription, teacherNames
    case watchChecking, watchUnsupported, watchUnavailable, watchNotPaired, watchAppMissing, watchPrepareFailed, watchQueued, watchQueueFailed
    case connection
    case syncDescription, lastUpdated, className, noData, refreshOnPhone
}

final class AppSettings: ObservableObject {
    @Published var language: AppLanguage {
        didSet { UserDefaults.standard.set(language.rawValue, forKey: Self.languageKey) }
    }

    @Published var appName: String {
        didSet { UserDefaults.standard.set(appName, forKey: Self.appNameKey) }
    }

    @Published var appearance: AppAppearance {
        didSet { UserDefaults.standard.set(appearance.rawValue, forKey: Self.appearanceKey) }
    }

    private static let languageKey = "librecap.language"
    private static let appNameKey = "librecap.appName"
    private static let appearanceKey = "librecap.appearance"

    init() {
        let storedLanguage = UserDefaults.standard.string(forKey: Self.languageKey)
        let defaultLanguage = Locale.current.language.languageCode?.identifier == "pl" ? AppLanguage.polish : .english
        language = storedLanguage.flatMap(AppLanguage.init(rawValue:)) ?? defaultLanguage
        appName = UserDefaults.standard.string(forKey: Self.appNameKey) ?? "LibreCap"
        appearance = UserDefaults.standard.string(forKey: Self.appearanceKey)
            .flatMap(AppAppearance.init(rawValue:)) ?? .system
    }

    func text(_ key: AppCopyKey) -> String {
        CopyBook.value(for: key, language: language)
    }
}

private enum CopyBook {
    static func value(for key: AppCopyKey, language: AppLanguage) -> String {
        switch language {
        case .english: return english[key] ?? key.fallback
        case .polish: return polish[key] ?? english[key] ?? key.fallback
        }
    }

    private static let english: [AppCopyKey: String] = [
        .welcome: "Welcome to LibreCap", .goodToSee: "Good to see you", .connecting: "Connecting to Librus…", .tagline: "Your school day, in one calm place.",
        .schoolAccount: "School Synergia account", .loginHint: "Use the login and password issued by your school. Konto LIBRUS email sign-in is not supported.",
        .schoolLogin: "School-issued login", .password: "Password", .passwordStored: "Your password is stored only in this device's Keychain. LibreCap connects directly to Librus; no separate server is required.",
        .signIn: "Sign in", .signingIn: "Signing in…", .home: "Home", .grades: "Grades", .schedule: "Schedule", .messages: "Messages", .more: "More", .github: "GitHub",
        .today: "Today", .currentLesson: "Current lesson", .noLessonsToday: "No lessons today", .noRemainingLessons: "No remaining lessons today", .seeAll: "See all", .tryAgain: "Try again",
        .tests: "Tests", .homework: "Homework", .attendance: "Attendance", .absences: "Absences", .syncNow: "Sync now", .syncing: "Syncing…", .lastSync: "Last sync", .quickActions: "Quick actions", .thisDevice: "On this device",
        .noGrades: "No grades yet", .firstSemester: "First semester", .secondSemester: "Second semester", .average: "Average", .gradesCount: "grades", .noWeight: "No weight", .weight: "Weight", .finalGrade: "Final grade", .details: "Details",
        .noTimetable: "No timetable", .selectDay: "Select day", .lessonNumber: "Lesson", .classroom: "Classroom", .teacher: "Teacher", .cancelled: "Cancelled", .substitution: "Substitution",
        .noAttendance: "No attendance records", .present: "Present", .absent: "Absent", .recentRecords: "Recent records", .school: "School", .account: "Account", .signOut: "Sign out",
        .settings: "Settings", .appName: "App name", .language: "Language", .appearance: "Appearance", .appearanceSystem: "System", .appearanceLight: "Light", .appearanceDark: "Dark", .automaticSync: "Automatic sync", .automaticSyncDescription: "Refresh school data automatically while the app is open.", .every45Minutes: "Every 45 minutes", .notifications: "Notifications", .schoolNotifications: "School activity notifications", .schoolNotificationsDescription: "Notify you when a sync finds a new grade, homework, absence, or message. LibreCap checks for changes only while the app is open.", .notificationsBlocked: "Notifications are blocked. Enable them in iPhone Settings.", .save: "Save", .reset: "Reset",
        .updateAvailable: "A new LibreCap version is available", .updateAvailableDescription: "Open the GitHub release page to see what's new and download the update.", .viewRelease: "View release",
        .inbox: "Inbox", .sent: "Sent", .announcements: "Announcements", .notes: "Notes", .noMessages: "No messages", .folderUnavailable: "This category is not provided by the current Librus adapter yet.", .due: "Due", .addedBy: "Added by", .attachments: "Attachments", .download: "Download", .saveFile: "Save file",
        .noHomework: "No homework", .all: "All", .assessments: "Tests & classwork", .noLongerRelevant: "No longer relevant", .remindMe: "Remind me", .note: "Note", .saveNote: "Save note", .saved: "Saved",
        .lessonDetails: "Lesson details", .gradeDetails: "Grade details", .tutor: "Tutor", .teacherRole: "Tutor", .replacedTeacher: "Replaced teacher", .studentInformation: "Student information", .accountType: "Account type", .appleWatch: "Apple Watch", .lessonAlerts: "Lesson-ending alerts", .watchAlertDescription: "Notify your Watch 5 minutes before each lesson ends, with the next lesson, room and teacher. Open LibreCap on Watch once to allow notifications.", .sendLatestToWatch: "Send latest data to Watch", .watchDataDescription: "Refresh school data on this iPhone first. Your Watch receives the timetable, recent grades and upcoming homework—not your password.", .teacherNames: "Teacher names are included while alerts are enabled. Disabling takes effect when the Watch receives the update.", .watchChecking: "Checking Apple Watch…", .watchUnsupported: "Apple Watch is not supported on this device.", .watchUnavailable: "Watch connection unavailable. Open both apps and try again.", .watchNotPaired: "Pair an Apple Watch with this iPhone.", .watchAppMissing: "Install LibreCap using the iPhone’s Watch app.", .watchPrepareFailed: "Could not prepare Watch data. Try syncing again.", .watchQueued: "Latest snapshot queued for Apple Watch.", .watchQueueFailed: "Watch sync could not be queued. Open both apps and try again.", .connection: "Connection", .syncDescription: "Refreshes all available school data.", .lastUpdated: "Last updated", .className: "Class", .noData: "No data", .refreshOnPhone: "Refresh school data on this iPhone first."
    ]

    private static let polish: [AppCopyKey: String] = [
        .welcome: "Witaj w LibreCap", .goodToSee: "Dobrze Cię widzieć", .connecting: "Łączenie z Librusem…", .tagline: "Twój dzień szkolny w jednym spokojnym miejscu.",
        .schoolAccount: "Szkolne konto Synergia", .loginHint: "Użyj loginu i hasła otrzymanego w szkole. Logowanie e-mailem do Konta LIBRUS nie jest obsługiwane.",
        .schoolLogin: "Login szkolny", .password: "Hasło", .passwordStored: "Hasło jest przechowywane tylko w pęku kluczy tego urządzenia. LibreCap łączy się bezpośrednio z Librusem — osobny serwer nie jest potrzebny.",
        .signIn: "Zaloguj się", .signingIn: "Logowanie…", .home: "Start", .grades: "Oceny", .schedule: "Plan lekcji", .messages: "Wiadomości", .more: "Więcej", .github: "GitHub",
        .today: "Dzisiaj", .currentLesson: "Trwająca lekcja", .noLessonsToday: "Brak lekcji dzisiaj", .noRemainingLessons: "Brak pozostałych lekcji dzisiaj", .seeAll: "Zobacz wszystkie", .tryAgain: "Spróbuj ponownie",
        .tests: "Sprawdziany", .homework: "Prace domowe", .attendance: "Frekwencja", .absences: "Nieobecności", .syncNow: "Synchronizuj", .syncing: "Synchronizowanie…", .lastSync: "Ostatnia synchronizacja", .quickActions: "Szybkie akcje", .thisDevice: "Na tym urządzeniu",
        .noGrades: "Brak ocen", .firstSemester: "Pierwsze półrocze", .secondSemester: "Drugie półrocze", .average: "Średnia", .gradesCount: "ocen", .noWeight: "Bez wagi", .weight: "Waga", .finalGrade: "Ocena końcowa", .details: "Szczegóły",
        .noTimetable: "Brak planu lekcji", .selectDay: "Wybierz dzień", .lessonNumber: "Lekcja", .classroom: "Sala", .teacher: "Nauczyciel", .cancelled: "Odwołana", .substitution: "Zastępstwo",
        .noAttendance: "Brak wpisów frekwencji", .present: "Obecności", .absent: "Nieobecności", .recentRecords: "Ostatnie wpisy", .school: "Szkoła", .account: "Konto", .signOut: "Wyloguj się",
        .settings: "Ustawienia", .appName: "Nazwa aplikacji", .language: "Język", .appearance: "Wygląd", .appearanceSystem: "Systemowy", .appearanceLight: "Jasny", .appearanceDark: "Ciemny", .automaticSync: "Automatyczna synchronizacja", .automaticSyncDescription: "Odświeżaj dane szkolne automatycznie, gdy aplikacja jest otwarta.", .every45Minutes: "Co 45 minut", .notifications: "Powiadomienia", .schoolNotifications: "Powiadomienia o aktywności szkolnej", .schoolNotificationsDescription: "Powiadamiaj, gdy synchronizacja znajdzie nową ocenę, pracę domową, nieobecność lub wiadomość. LibreCap sprawdza zmiany tylko przy otwartej aplikacji.", .notificationsBlocked: "Powiadomienia są zablokowane. Włącz je w Ustawieniach iPhone’a.", .save: "Zapisz", .reset: "Przywróć domyślną",
        .updateAvailable: "Dostępna jest nowa wersja LibreCap", .updateAvailableDescription: "Otwórz stronę wydania na GitHubie, aby zobaczyć zmiany i pobrać aktualizację.", .viewRelease: "Zobacz wydanie",
        .inbox: "Odebrane", .sent: "Wysłane", .announcements: "Ogłoszenia", .notes: "Uwagi", .noMessages: "Brak wiadomości", .folderUnavailable: "Ta kategoria nie jest jeszcze udostępniana przez obecny adapter Librusa.", .due: "Termin", .addedBy: "Dodane przez", .attachments: "Załączniki", .download: "Pobierz", .saveFile: "Zapisz plik",
        .noHomework: "Brak prac domowych", .all: "Wszystkie", .assessments: "Sprawdziany i prace klasowe", .noLongerRelevant: "To już nieaktualne", .remindMe: "Przypominaj mi", .note: "Notatka", .saveNote: "Zapisz notatkę", .saved: "Zapisano",
        .lessonDetails: "Szczegóły lekcji", .gradeDetails: "Szczegóły oceny", .tutor: "Wychowawca", .teacherRole: "wychowawca", .replacedTeacher: "Zastąpiony nauczyciel", .studentInformation: "Informacje o uczniu", .accountType: "Typ konta", .appleWatch: "Apple Watch", .lessonAlerts: "Przypomnienia o końcu lekcji", .watchAlertDescription: "Powiadom Watch 5 minut przed końcem każdej lekcji, pokazując następną lekcję, salę i nauczyciela. Otwórz LibreCap na Watch, aby zezwolić na powiadomienia.", .sendLatestToWatch: "Wyślij najnowsze dane na Watch", .watchDataDescription: "Najpierw odśwież dane szkolne na iPhonie. Watch otrzyma plan lekcji, ostatnie oceny i nadchodzące prace domowe — nie hasło.", .teacherNames: "Imiona nauczycieli są przekazywane, gdy przypomnienia są włączone. Wyłączenie zadziała po otrzymaniu zmiany przez Watch.", .watchChecking: "Sprawdzanie Apple Watch…", .watchUnsupported: "Apple Watch nie jest obsługiwany na tym urządzeniu.", .watchUnavailable: "Połączenie z Watch jest niedostępne. Otwórz obie aplikacje i spróbuj ponownie.", .watchNotPaired: "Połącz Apple Watch z tym iPhonem.", .watchAppMissing: "Zainstaluj LibreCap przez aplikację Watch na iPhonie.", .watchPrepareFailed: "Nie udało się przygotować danych dla Watch. Spróbuj zsynchronizować ponownie.", .watchQueued: "Najnowszy zestaw danych czeka na wysłanie do Apple Watch.", .watchQueueFailed: "Nie udało się wysłać danych do Watch. Otwórz obie aplikacje i spróbuj ponownie.", .connection: "Połączenie", .syncDescription: "Odświeża wszystkie dostępne dane szkolne.", .lastUpdated: "Ostatnia aktualizacja", .className: "Klasa", .noData: "Brak danych", .refreshOnPhone: "Najpierw odśwież dane szkolne na iPhonie."
    ]
}

private extension AppCopyKey {
    var fallback: String { String(describing: self).capitalized }
}

enum SchoolAppDate {
    static let dayKeys = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]

    static var calendar: Calendar {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = TimeZone(identifier: "Europe/Warsaw")!
        return calendar
    }

    static func dayKey(for date: Date) -> String {
        let weekday = calendar.component(.weekday, from: date)
        return dayKeys[(weekday + 5) % 7]
    }

    static func dayDate(for dayKey: String, timetable: TimetableData, now: Date = Date()) -> Date? {
        guard let weekStart = timetable.weekStart.flatMap(SchoolDate.parse),
              let index = dayKeys.firstIndex(of: dayKey) else { return nil }
        return calendar.date(byAdding: .day, value: index, to: weekStart)
    }

    static func time(_ value: String, on day: Date) -> Date? {
        let parts = value.split(separator: ":")
        guard parts.count >= 2, let hour = Int(parts[0]), let minute = Int(parts[1]) else { return nil }
        return calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day)
    }

    static func formatted(_ date: Date, language: AppLanguage? = nil) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        if let language {
            formatter.locale = Locale(identifier: language == .polish ? "pl_PL" : "en_US")
        } else {
            formatter.locale = Locale.current
        }
        // Use a localized day-month-year template so dates read naturally in
        // both languages instead of inheriting the simulator's ISO ordering.
        formatter.setLocalizedDateFormatFromTemplate("d MMM yyyy")
        return formatter.string(from: date)
    }
}

extension TimetableLesson {
    func startDate(on day: Date) -> Date? { SchoolAppDate.time(hourFrom, on: day) }
    func endDate(on day: Date) -> Date? { SchoolAppDate.time(hourTo, on: day) }
}

enum GradeSemester: String, CaseIterable, Identifiable {
    case first, second

    var id: String { rawValue }

    func title(using settings: AppSettings) -> String {
        settings.text(self == .first ? .firstSemester : .secondSemester)
    }
}

extension GradeRecord {
    func belongs(to semester: GradeSemester) -> Bool {
        let value = self.semester.lowercased()
        if value.isEmpty { return true }
        if value.contains("2") || value.contains("second") || value.contains("drug") { return semester == .second }
        if value.contains("1") || value.contains("first") || value.contains("pierw") { return semester == .first }
        return true
    }

    var numericValue: Double? {
        Double(value.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespacesAndNewlines))
    }
}

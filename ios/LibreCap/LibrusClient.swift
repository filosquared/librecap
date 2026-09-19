import Foundation
import OSLog
#if os(macOS)
import AppKit
#endif

enum LibrusClientError: LocalizedError, Equatable {
    case invalidCredentials
    case synergiaAccountRequired
    case loginFlowUnavailable
    case additionalVerificationRequired
    case sessionUnauthorized
    case unavailable
    case unexpectedResponse
    case malformedData

    var errorDescription: String? {
        switch self {
        case .invalidCredentials:
            return "Librus rejected this sign-in. Check your school-issued Synergia login and password."
        case .synergiaAccountRequired:
            return "Use the Synergia login issued by your school. Email-based Konto LIBRUS sign-in is not supported in LibreCap yet."
        case .loginFlowUnavailable:
            return "LibreCap could not complete the Librus login flow. This does not mean your password is incorrect."
        case .additionalVerificationRequired:
            return "Librus requires an additional verification step. Complete it on the official Synergia website; LibreCap cannot complete it yet."
        case .sessionUnauthorized:
            return "Librus did not authorize access to your school data. The session may be incomplete or expired."
        case .unavailable:
            return "Librus is currently unavailable. Check your internet connection."
        case .unexpectedResponse, .malformedData:
            return "Librus returned data the app could not read."
        }
    }
}

final class LibrusClient {
    private let apiBase = URL(string: "https://synergia.librus.pl/gateway/api/2.0/")!
    private let portalBase = URL(string: "https://synergia.librus.pl")!
    private let logger = Logger(subsystem: "com.filiplopes.LibreCap", category: "network")
    private let cookieStorage: HTTPCookieStorage
    private let session: URLSession

    init(configuration: URLSessionConfiguration = .ephemeral) {
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 60
        configuration.waitsForConnectivity = true
        configuration.httpShouldSetCookies = true
        configuration.httpCookieAcceptPolicy = .always
        let cookieStorage = configuration.httpCookieStorage ?? HTTPCookieStorage()
        configuration.httpCookieStorage = cookieStorage
        self.cookieStorage = cookieStorage
        session = URLSession(configuration: configuration)
    }

    func login(username: String, password: String) async throws -> StudentProfile {
        let username = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !username.contains("@") else { throw LibrusClientError.synergiaAccountRequired }
        guard !username.isEmpty, !password.isEmpty else { throw LibrusClientError.invalidCredentials }
        do {
			let portalLoginURL = portalBase.appendingPathComponent("loguj/portalRodzina")
			let (_, portalResponse) = try await request(
				url: portalLoginURL,
				headers: [
					"Referer": "https://portal.librus.pl/",
					"Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
				]
			)
			// portalRodzina redirects to the current OAuth authorization URL. Do
			// not issue another OAuth GET: that creates a new OAuth session.
			guard portalResponse.statusCode == 200, let redirectedURL = portalResponse.url else {
				throw LibrusClientError.loginFlowUnavailable
			}
			let loginURL = try Self.authorizationURL(redirectedURL.absoluteString)
			let loginBody = Self.formBody([
				"action": "login",
				"login": username,
				"pass": password
			])
			let (loginData, loginResponse) = try await request(
				url: loginURL,
				method: "POST",
				body: loginBody,
				headers: [
					"Accept": "application/json",
					"Content-Type": "application/x-www-form-urlencoded"
				]
			)
			if loginResponse.statusCode == 401 || loginResponse.statusCode == 403 {
				throw LibrusClientError.invalidCredentials
			}
			let nextURL = try Self.loginContinuation(data: loginData, response: loginResponse)
			let (_, nextResponse) = try await request(url: nextURL)
			guard (200..<400).contains(nextResponse.statusCode) else {
				throw LibrusClientError.sessionUnauthorized
			}
			guard nextResponse.url?.host == portalBase.host else {
				throw LibrusClientError.additionalVerificationRequired
			}
			// URLSession stores cookies from the redirect chain with their original
			// domains and paths. Never overwrite them with OAuth-domain cookies.

			let tokenInfo = try await apiJSON("Auth/TokenInfo")
			let identifier = string(tokenInfo["UserIdentifier"], fallback: "")
			guard !identifier.isEmpty else {
				throw LibrusClientError.malformedData
			}
			let (_, accessResponse) = try await request(url: URL(string: "Auth/UserInfo/\(identifier)", relativeTo: apiBase)!.absoluteURL)
			guard accessResponse.statusCode == 200 else {
				if accessResponse.statusCode == 401 { throw LibrusClientError.sessionUnauthorized }
				throw LibrusClientError.unexpectedResponse
			}
			return try await fetchProfile()
        } catch let error as LibrusClientError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw LibrusClientError.unavailable
        }
    }

    func fetchProfile() async throws -> StudentProfile {
        async let meTask = apiJSON("Me")
        async let profileTask = apiJSON("UserProfile")
        async let usersTask = apiJSON("Users")
        async let classesTask = apiJSON("Classes")

        let me = try await meTask
        let userProfile = try await profileTask
        let users = try await usersTask
        let classes = try await classesTask

        let account = dictionary(me["Me"])?["Account"].flatMap(dictionary) ?? [:]
        let schoolClass = dictionary(classes["Class"]) ?? [:]
        let tutorID = identifier(dictionary(schoolClass["ClassTutor"])?["Id"])
        let tutor = userMap(users)[tutorID] ?? [:]

        let number = string(schoolClass["Number"], fallback: "")
        let symbol = string(schoolClass["Symbol"], fallback: "").uppercased()
        let className = [number, symbol].filter { !$0.isEmpty }.joined(separator: " ")

        return StudentProfile(
            firstName: string(account["FirstName"], fallback: "Student"),
            lastName: string(account["LastName"], fallback: ""),
            tutorFirstName: string(tutor["FirstName"], fallback: ""),
            tutorLastName: string(tutor["LastName"], fallback: ""),
            schoolYearStarts: string(schoolClass["BeginSchoolYear"], fallback: ""),
            schoolYearMiddles: string(schoolClass["EndFirstSemester"], fallback: ""),
            schoolYearEnds: string(schoolClass["EndSchoolYear"], fallback: ""),
            type: string(dictionary(userProfile["UserProfile"])?["UnitType"], fallback: "Student").capitalized,
            className: className.isEmpty ? "Class" : className
        )
    }

    func fetchGrades() async throws -> [GradeRecord] {
        async let responseTask = apiJSON("Grades")
        async let categoriesTask = apiJSON("Grades/Categories")
        async let commentsTask = apiJSON("Grades/Comments")
        async let subjectsTask = apiJSON("Subjects")
        async let teachersTask = apiJSON("Users")

        let response = try await responseTask
        let categories = gradeCategories(try await categoriesTask)
        let comments = commentMap(try await commentsTask)
        let subjects = subjectMap(try await subjectsTask)
        let teachers = userMap(try await teachersTask)

        let rawGrades = array(response["Grades"])
        return rawGrades.enumerated().map { index, raw in
            let subjectID = identifier(dictionary(raw["Subject"])?["Id"])
            let categoryID = identifier(dictionary(raw["Category"])?["Id"])
            let addedByID = identifier(dictionary(raw["AddedBy"])?["Id"])
            let subject = subjects[subjectID] ?? "Subject"
            let category = categories[categoryID] ?? (name: "Grade", weight: "none")
            let teacher = teachers[addedByID] ?? [:]
            let commentID = identifier(dictionary(array(raw["Comments"]).first)?["Id"])
            let value = string(raw["Grade"], fallback: "—")
            let addedDate = string(raw["AddDate"], fallback: "")

            return GradeRecord(
                id: identifier(raw["Id"]).isEmpty ? "\(subject)-\(addedDate)-\(value)-\(index)" : identifier(raw["Id"]),
                subject: subject,
                value: value,
                weight: category.weight,
                comment: comments[commentID] ?? "",
                category: category.name,
                isFinal: boolean(raw["IsFinal"]) || boolean(raw["IsFinalProposition"]),
                isSemester: boolean(raw["IsSemester"]) || boolean(raw["IsSemesterProposition"]),
                semester: string(raw["Semester"], fallback: ""),
                addedDate: addedDate,
                teacher: teacherName(teacher)
            )
        }
        .sorted { $0.subject.localizedCaseInsensitiveCompare($1.subject) == .orderedAscending }
    }

    func fetchTimetable() async throws -> TimetableData {
        let calendar = Calendar(identifier: .iso8601)
        let today = Date()
        let pivot = calendar.date(byAdding: .day, value: 2, to: today) ?? today
        let weekday = calendar.component(.weekday, from: pivot)
        let daysFromMonday = (weekday + 5) % 7
        let weekStart = calendar.date(byAdding: .day, value: -daysFromMonday, to: pivot) ?? pivot
        let weekEnd = calendar.date(byAdding: .day, value: 6, to: weekStart) ?? weekStart
        let dateFrom = dateString(weekStart)
        let dateTo = dateString(weekEnd)

        async let timetableTask = apiJSON("Timetables?weekStart=\(dateFrom)")
        async let activitiesTask = apiJSON("Timetables/OtherActivitiesRegister?dateFrom=\(dateFrom)&dateTo=\(dateTo)&hideOutdatedEntries=false")
        async let classroomsTask = apiJSON("TimetableEntries")

        let timetableResponse = try await timetableTask
        let activitiesResponse = try await activitiesTask
        let classrooms = classroomMap(try await classroomsTask)
        let substitutionDetails = try await fetchTimetableSubstitutions(dateFrom: dateFrom, dateTo: dateTo)
        var lessonsByDay: [String: [TimetableLesson]] = [:]
        let dayNames = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
        var lessonByStart: [String: String] = [:]

        if let timetable = timetableResponse["Timetable"] as? [String: Any] {
            for (date, rawDay) in timetable {
				guard let dateValue = parseDate(date),
					  let dayName = dayName(for: dateValue, calendar: calendar, names: dayNames) else {
                    continue
                }
                guard let entryLists = rawDay as? [[Any]] else { continue }
                for (index, rawEntry) in entryLists.enumerated() {
                    let entries = rawEntry.compactMap(dictionary)
                    guard !entries.isEmpty else { continue }

                    let hasSubstitution = entries.contains { boolean($0["IsSubstitutionClass"]) }
                    // Librus usually sends the regular lesson first and the
                    // replacement second. Keep both so the UI can explain the change.
                    let effectiveIndex = hasSubstitution && entries.count > 1 ? entries.count - 1 : 0
                    let lesson = entries[effectiveIndex]
                    let originalEntry = effectiveIndex == 0 ? nil : entries.first
                    let apiSubject = string(dictionary(lesson["Subject"])?["Name"], fallback: "Lesson")
                    let apiTeacher = teacherName(dictionary(lesson["Teacher"]) ?? [:])
                    let directOriginalSubject = firstString(in: lesson, keys: ["OriginalSubjectName", "PreviousSubjectName", "OriginalLessonSubject"])
                    let nestedOriginalSubject = firstNestedString(in: lesson, keys: ["OriginalSubject", "PreviousSubject", "OriginalLessonSubject"], field: "Name")
                    let originalSubject = firstNonBlank([
                        firstString(in: originalEntry?["Subject"], keys: ["Name"]),
                        directOriginalSubject,
                        nestedOriginalSubject
                    ])
                        .flatMap { $0.caseInsensitiveCompare(apiSubject) == .orderedSame ? nil : $0 }
                    let directOriginalTeacher = firstString(in: lesson, keys: ["OriginalTeacherName", "PreviousTeacherName", "FormerTeacherName"])
                    let nestedOriginalTeacher = firstNestedTeacherName(in: lesson, keys: ["OriginalTeacher", "PreviousTeacher", "FormerTeacher"])
                    let originalTeacher = firstNonBlank([
                        teacherName(dictionary(originalEntry?["Teacher"]) ?? [:]),
                        directOriginalTeacher,
                        nestedOriginalTeacher
                    ])
                        .flatMap { $0.caseInsensitiveCompare(apiTeacher) == .orderedSame ? nil : $0 }
                    let hourFrom = string(lesson["HourFrom"], fallback: "")
                    let lessonNumber = string(lesson["LessonNo"], fallback: "-")
                    lessonByStart[hourFrom] = lessonNumber
                    let classroomID = identifier(dictionary(lesson["Classroom"])?["Id"])
                    let substitution = substitutionDetails["\(date)-\(hourFrom)"]
                    let currentSubject = firstNonBlank([substitution?.subject ?? "", apiSubject]) ?? apiSubject
                    let currentTeacher = firstNonBlank([substitution?.teacher ?? "", apiTeacher]) ?? apiTeacher
                    let resolvedOriginalSubject = firstNonBlank([substitution?.originalSubject ?? ""]) ?? originalSubject
                    let resolvedOriginalTeacher = firstNonBlank([substitution?.originalTeacher ?? ""]) ?? originalTeacher
                    let isSubstitution = hasSubstitution || substitution != nil || resolvedOriginalSubject != nil || resolvedOriginalTeacher != nil
                    lessonsByDay[dayName, default: []].append(TimetableLesson(
                        id: "\(date)-\(lessonNumber)-\(index)",
                        lessonNumber: lessonNumber,
                        subject: currentSubject,
                        isSubstitution: isSubstitution,
                        isCancelled: boolean(lesson["IsCanceled"]),
                        teacher: currentTeacher,
                        hourFrom: hourFrom,
                        hourTo: string(lesson["HourTo"], fallback: ""),
                        classroom: firstNonBlank([substitution?.classroom ?? ""]) ?? classrooms[classroomID] ?? "—",
                        originalSubject: resolvedOriginalSubject,
                        originalTeacher: resolvedOriginalTeacher
                    ))
                }
            }
        }

        if let activities = activitiesResponse["data"] as? [[String: Any]] {
            for (index, item) in activities.enumerated() {
                let date = string(item["date"], fallback: "")
				guard let dateValue = parseDate(date),
					  let dayName = dayName(for: dateValue, calendar: calendar, names: dayNames) else {
                    continue
                }
                let teacherParts = string(item["teacherName"], fallback: "").split(separator: " ").map(String.init)
                let classroom = dictionary(item["classroom"])
                let start = string(item["startTime"], fallback: "")
                lessonsByDay[dayName, default: []].append(TimetableLesson(
                    id: "activity-\(date)-\(index)",
                    lessonNumber: lessonByStart[start] ?? "-",
                    subject: string(item["title"], fallback: "Activity"),
                    isSubstitution: false,
                    isCancelled: false,
                    teacher: teacherParts.count > 1 ? teacherParts.dropFirst().joined(separator: " ") + " " + teacherParts[0] : teacherParts.first ?? "—",
                    hourFrom: start,
                    hourTo: string(item["endTime"], fallback: ""),
                    classroom: string(classroom?["symbol"], fallback: "—")
                ))
            }
        }

        for day in lessonsByDay.keys {
            lessonsByDay[day]?.sort { $0.hourFrom < $1.hourFrom }
        }

        return TimetableData(
            nextWeek: calendar.component(.weekOfYear, from: today) < calendar.component(.weekOfYear, from: weekStart),
            days: lessonsByDay,
            weekStart: dateFrom
        )
    }

    func fetchAttendances() async throws -> [AttendanceRecord] {
        async let attendancesTask = apiJSON("Attendances")
        async let teachersTask = apiJSON("Users")
        async let lessonsTask = apiJSON("Lessons")
        async let subjectsTask = apiJSON("Subjects")
        async let typesTask = apiJSON("Attendances/Types")

        let attendances = try await attendancesTask
        let teachers = userMap(try await teachersTask)
        let lessons = lessonSubjectMap(try await lessonsTask)
        let subjects = subjectMap(try await subjectsTask)
        let types = attendanceTypeMap(try await typesTask)

		let records = array(attendances["Attendances"]).enumerated().map { index, raw in
            let lessonID = identifier(dictionary(raw["Lesson"])?["Id"])
            let typeID = identifier(dictionary(raw["Type"])?["Id"])
            let addedByID = identifier(dictionary(raw["AddedBy"])?["Id"])
            let type = types[typeID] ?? (name: "Attendance", short: "?", isPresence: false)
            return AttendanceRecord(
                id: identifier(raw["Id"]).isEmpty ? "attendance-\(index)" : identifier(raw["Id"]),
                subject: subjects[lessons[lessonID] ?? ""] ?? "Lesson",
                type: type.name,
                shortType: type.short,
                isPresence: type.isPresence,
                addedDate: string(raw["AddDate"], fallback: ""),
                date: string(raw["Date"], fallback: ""),
                teacher: teacherName(teachers[addedByID] ?? [:])
            )
		}
		return Array(records.reversed())
    }

    func fetchHomeworks() async throws -> [HomeworkRecord] {
        async let homeworksTask = apiJSON("HomeWorks")
        async let teachersTask = apiJSON("Users")
        async let categoriesTask = apiJSON("HomeWorks/Categories")
        async let subjectsTask = apiJSON("Subjects")

        let homeworks = try await homeworksTask
        let teachers = userMap(try await teachersTask)
        let categories = homeworkCategoryMap(try await categoriesTask)
        let subjects = subjectMap(try await subjectsTask)

		let records = array(homeworks["HomeWorks"]).enumerated().map { index, raw in
            let subjectID = identifier(dictionary(raw["Subject"])?["Id"])
            let teacherID = identifier(dictionary(raw["CreatedBy"])?["Id"])
            let categoryID = identifier(dictionary(raw["Category"])?["Id"])
            return HomeworkRecord(
                id: identifier(raw["Id"]).isEmpty ? "homework-\(index)" : identifier(raw["Id"]),
                subject: subjects[subjectID] ?? "Lesson \(string(raw["LessonNo"], fallback: "") )",
                addedBy: teacherName(teachers[teacherID] ?? [:]),
                type: categories[categoryID] ?? "Homework",
                startTime: string(raw["TimeFrom"], fallback: ""),
                endTime: string(raw["TimeTo"], fallback: ""),
                date: string(raw["Date"], fallback: ""),
                addedDate: string(raw["AddDate"], fallback: ""),
                content: string(raw["Content"], fallback: "")
            )
		}
		return Array(records.reversed())
    }

    func fetchMessages() async throws -> [MessageSummary] {
        let html = try await portalHTML(path: "/wiadomosci")
        let rows = allCaptures(#"<tr[^>]*>(.*?)</tr>"#, in: html)
        let messages: [MessageSummary] = rows.compactMap { row in
            let columns = allCaptures(#"<td[^>]*>(.*?)</td>"#, in: row)
            guard columns.count > 1 else { return nil }
            guard let linkIndex = columns.firstIndex(where: { column in
                capture(#"href=["']([^"']+)["']"#, in: column).map { !messageID(from: $0).isEmpty } ?? false
            }),
            let href = capture(#"href=["']([^"']+)["']"#, in: columns[linkIndex]) else { return nil }
            let id = messageID(from: href)
            guard !id.isEmpty else { return nil }
            let senderText = htmlText(columns[linkIndex])
            let sender = senderText.components(separatedBy: "(").first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? senderText
            let subject = columns.indices.contains(linkIndex + 1) ? htmlText(columns[linkIndex + 1]) : ""
            let date = columns.indices.contains(linkIndex + 2) ? htmlText(columns[linkIndex + 2]) : ""
            guard !subject.isEmpty || !date.isEmpty else { return nil }
            let headerText = "\(sender) \(subject) \(date)".folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .current)
            let headerWords = ["temat", "subject", "wyslano", "sent", "napisz", "archiwum", "etykiety", "kosz"]
            guard !headerWords.contains(where: headerText.contains) else { return nil }
            return MessageSummary(
                id: id,
                sender: sender,
                subject: subject,
                date: date
            )
        }
        // Keep inbox/sent parsing available on schools whose adapter does not
        // expose SchoolNotices yet; announcements are an additive feature.
        let announcements: [MessageSummary]
        do {
            announcements = try await fetchAnnouncements()
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            announcements = []
        }
        return messages + announcements
    }

    private func fetchAnnouncements() async throws -> [MessageSummary] {
        let object = try await apiJSON("SchoolNotices")
        return array(object["SchoolNotices"]).compactMap { raw in
            let noticeID = identifier(raw["Id"])
            guard !noticeID.isEmpty else { return nil }
            let subject = string(raw["Subject"], fallback: "").trimmingCharacters(in: .whitespacesAndNewlines)
            let content = htmlText(string(raw["Content"], fallback: ""))
            let date = string(raw["CreationDate"], fallback: string(raw["StartDate"], fallback: ""))
            return MessageSummary(
                id: "announcement-\(noticeID)",
                sender: "Librus",
                subject: subject.isEmpty ? "Announcement" : subject,
                date: date,
                folder: .announcements,
                content: content
            )
        }
    }

    func fetchMessage(id: String) async throws -> MessageDetail {
        let path = "/wiadomosci/\(id.replacingOccurrences(of: "-", with: "/"))"
        let html = try await portalHTML(path: path)
        let content = capture(#"<div[^>]*class=["'][^"']*container-message-content[^"']*["'][^>]*>(.*?)</div>"#, in: html) ?? ""
        let subject = htmlText(capture(#"<table[^>]*class=["'][^"']*stretch[^"']*["'][^>]*>.*?<td[^>]*>(.*?)</td>"#, in: html) ?? "")
        return MessageDetail(
            subject: subject.isEmpty ? "Message" : subject,
            sender: "",
            date: "",
            content: htmlText(content),
            attachments: messageAttachments(from: html)
        )
    }

    func downloadMessageAttachment(_ attachment: MessageAttachment) async throws -> DownloadedMessageAttachment {
        guard let url = attachment.downloadURL else {
            throw LibrusClientError.malformedData
        }

        var request = URLRequest(url: url)
        request.setValue("*/*", forHTTPHeaderField: "Accept")
        request.setValue(portalBase.absoluteString + "/wiadomosci/", forHTTPHeaderField: "Referer")
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw LibrusClientError.unexpectedResponse
        }

        if isLoginRedirect(data: data, response: httpResponse) {
            throw LibrusClientError.sessionUnauthorized
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            throw responseError(httpResponse, operation: "downloading a message attachment")
        }
        guard httpResponse.mimeType?.lowercased().contains("html") != true else {
            throw LibrusClientError.unexpectedResponse
        }

        let fileName = attachmentFileName(from: httpResponse, fallback: attachment.name)
        let safeFileName = (fileName as NSString).lastPathComponent.isEmpty
            ? "Attachment"
            : (fileName as NSString).lastPathComponent
        let fileDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: fileDirectory, withIntermediateDirectories: true)
            let fileURL = fileDirectory.appendingPathComponent(safeFileName)
            try data.write(to: fileURL, options: .atomic)
            return DownloadedMessageAttachment(fileURL: fileURL, fileName: safeFileName)
        } catch {
            throw LibrusClientError.unavailable
        }
    }

    private struct SubstitutionDetails {
        var subject = ""
        var originalSubject = ""
        var teacher = ""
        var originalTeacher = ""
        var classroom = ""
    }

    private func fetchTimetableSubstitutions(dateFrom: String, dateTo: String) async throws -> [String: SubstitutionDetails] {
        do {
            let html = try await portalHTML(
                path: "/przegladaj_plan_lekcji",
                method: "POST",
                body: Self.formBody(["tydzien": "\(dateFrom)_\(dateTo)"])
            )
            let cells = allGroupCaptures(#"<([A-Za-z0-9]+)([^>]*)>(.*?)</\1>"#, in: html)
            var result: [String: SubstitutionDetails] = [:]
            for groups in cells {
                guard groups.count >= 4 else { continue }
                let attributes = groups[1]
                let contents = groups[2]
                guard attributes.range(of: #"data-date=["'][^"']+["']"#, options: .regularExpression) != nil,
                      attributes.range(of: #"data-time_from=["'][^"']+["']"#, options: .regularExpression) != nil else { continue }
                let date = capture(#"data-date=["']([^"']+)["']"#, in: attributes)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let start = capture(#"data-time_from=["']([^"']+)["']"#, in: attributes)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                guard !date.isEmpty, !start.isEmpty else { continue }

                let title = capture(#"title=["']([^"']+)["']"#, in: contents) ?? ""
                let info = htmlText(contents)
                let subjectParts = substitutionParts(substitutionValue(title, key: "Przedmiot"))
                let teacherParts = substitutionParts(substitutionValue(title, key: "Nauczyciel"))
                let classroomParts = substitutionParts(substitutionValue(title, key: "Sala"))
                let details = SubstitutionDetails(
                    subject: subjectParts.current,
                    originalSubject: subjectParts.original,
                    teacher: normalizePortalTeacher(teacherParts.current),
                    originalTeacher: normalizePortalTeacher(teacherParts.original),
                    classroom: classroomParts.current
                )
                let hasDetails = info.localizedCaseInsensitiveContains("zastęp") ||
                    !details.subject.isEmpty || !details.teacher.isEmpty || !details.classroom.isEmpty
                if hasDetails {
                    result["\(date)-\(start)"] = details
                }
            }
            return result
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            // The API timetable remains useful if the optional portal page is unavailable.
            return [:]
        }
    }

    private func substitutionValue(_ title: String, key: String) -> String {
        let cleaned = title
            .replacingOccurrences(of: #"<br\s*/?>"#, with: "\n", options: [.regularExpression, .caseInsensitive])
            .replacingOccurrences(of: "&nbsp;", with: " ", options: .caseInsensitive)
        let lines = cleaned.components(separatedBy: CharacterSet.newlines.union(CharacterSet(charactersIn: "|")))
        return lines.first {
            $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().hasPrefix("\(key.lowercased()):")
        }?
        .split(separator: ":", maxSplits: 1)
        .dropFirst()
        .joined(separator: ":")
        .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private func substitutionParts(_ value: String) -> (current: String, original: String) {
        let parts = value
            .components(separatedBy: "→")
            .flatMap { $0.components(separatedBy: "->") }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        if parts.count >= 2 { return (parts[0], parts[parts.count - 1]) }
        return (parts.first ?? "", "")
    }

    private func normalizePortalTeacher(_ value: String) -> String {
        let parts = value.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard parts.count > 1 else { return value.trimmingCharacters(in: .whitespacesAndNewlines) }
        return parts.dropFirst().joined(separator: " ") + " " + parts[0]
    }

    private func firstString(in value: Any?, keys: [String]) -> String {
        guard let object = dictionary(value) else { return "" }
        return firstNonBlank(keys.map { string(object[$0], fallback: "") }) ?? ""
    }

    private func firstNestedString(in value: [String: Any], keys: [String], field: String) -> String {
        firstNonBlank(keys.map { key in
            string(dictionary(value[key])?[field], fallback: "")
        }) ?? ""
    }

    private func firstNestedTeacherName(in value: [String: Any], keys: [String]) -> String {
        firstNonBlank(keys.map { key in teacherName(dictionary(value[key]) ?? [:]) }) ?? ""
    }

    private func firstNonBlank(_ values: [String]) -> String? {
        values
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty }
    }

    private func apiJSON(_ path: String) async throws -> [String: Any] {
		let url = URL(string: path, relativeTo: apiBase)!.absoluteURL
		let (data, response) = try await request(url: url, headers: ["Accept": "application/json"])
		if isLoginRedirect(data: data, response: response) {
			throw LibrusClientError.sessionUnauthorized
		}
		guard response.statusCode == 200 else {
			throw responseError(response, operation: "reading school data")
		}
        guard let object = try? JSONSerialization.jsonObject(with: data),
              let result = object as? [String: Any] else {
            throw LibrusClientError.malformedData
        }
        return result
    }

	private func portalHTML(path: String, method: String = "GET", body: Data? = nil) async throws -> String {
		let url = portalBase.appendingPathComponent(path)
		var headers = ["Accept": "text/html,application/xhtml+xml"]
		if method == "POST" {
			headers["Content-Type"] = "application/x-www-form-urlencoded"
		}
		let (data, response) = try await request(
			url: url,
			method: method,
			body: body,
			headers: headers
		)
		if isLoginRedirect(data: data, response: response) {
			throw LibrusClientError.sessionUnauthorized
		}
		guard response.statusCode == 200, let html = String(data: data, encoding: .utf8) else {
			throw responseError(response, operation: "reading the school portal")
		}
		return html
	}

	private func request(url: URL, method: String = "GET", body: Data? = nil, headers: [String: String] = [:]) async throws -> (Data, HTTPURLResponse) {
		let attempts = method == "GET" ? 3 : method == "POST" ? 2 : 1
		for attempt in 0..<attempts {
			var request = URLRequest(url: url)
			request.httpMethod = method
			request.httpBody = body
			request.setValue(
				"Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
				forHTTPHeaderField: "User-Agent"
			)
			for (key, value) in headers {
				request.setValue(value, forHTTPHeaderField: key)
			}

			do {
				let (data, response) = try await session.data(for: request)
				guard let httpResponse = response as? HTTPURLResponse else {
					throw LibrusClientError.unexpectedResponse
				}
				// Exclude query strings, bodies and cookies: they can contain secrets.
				logger.debug("Librus request \(method, privacy: .public) \(url.host ?? "unknown", privacy: .public) returned \(httpResponse.statusCode, privacy: .public); final host \(httpResponse.url?.host ?? "unknown", privacy: .public); type \(httpResponse.mimeType ?? "unknown", privacy: .public)")
				if httpResponse.statusCode >= 500, attempt < attempts - 1 {
					try await Task.sleep(nanoseconds: UInt64(250_000_000 * (attempt + 1)))
					continue
				}
				return (data, httpResponse)
			} catch let error as LibrusClientError {
				throw error
			} catch is CancellationError {
				throw CancellationError()
			} catch {
				logger.error("Librus request failed for \(url.host ?? "unknown", privacy: .public): \(error.localizedDescription, privacy: .public)")
				if attempt == attempts - 1 {
					throw LibrusClientError.unavailable
				}
				try await Task.sleep(nanoseconds: UInt64(250_000_000 * (attempt + 1)))
			}
		}
		throw LibrusClientError.unavailable
	}

	private func isLoginRedirect(data: Data, response: HTTPURLResponse) -> Bool {
		let path = response.url?.path.lowercased() ?? ""
		let body = String(data: data, encoding: .utf8)?.lowercased() ?? ""
		let isLoginForm = (response.mimeType?.contains("html") == true) &&
			body.contains("type=\"password\"") &&
			(body.contains("name=\"login\"") || body.contains("name='login'"))
		return path.contains("/loguj") || path.contains("/oauth/authorization") || isLoginForm
	}

	private func responseError(_ response: HTTPURLResponse, operation _: String) -> LibrusClientError {
		switch response.statusCode {
		case 401, 403:
			return .sessionUnauthorized
		case 429, 500...:
			return .unavailable
		default:
			return .unexpectedResponse
		}
	}

    static func authorizationURL(_ value: String) throws -> URL {
        guard !value.isEmpty,
              let url = URL(string: value, relativeTo: URL(string: "https://api.librus.pl/"))?.absoluteURL,
              url.scheme == "https", url.host == "api.librus.pl",
              url.port == nil || url.port == 443,
              url.user == nil, url.password == nil,
              url.path == "/OAuth/Authorization" || url.path.hasPrefix("/OAuth/Authorization/"),
              URLComponents(url: url, resolvingAgainstBaseURL: true)?.queryItems?.contains(where: { $0.name == "error" }) != true
        else { throw LibrusClientError.loginFlowUnavailable }
        return url
    }

	static func loginContinuation(data: Data, response: HTTPURLResponse) throws -> URL {
		guard response.statusCode == 200,
			  let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
		else { throw LibrusClientError.loginFlowUnavailable }
		let status = (payload["status"] as? String)?.lowercased() ?? ""
		if status == "error" {
			throw LibrusClientError.invalidCredentials
		}
		guard status == "ok", let next = payload["goTo"] as? String else {
            throw LibrusClientError.loginFlowUnavailable
        }
        return try authorizationURL(next)
    }

    static func formBody(_ values: [String: String]) -> Data {
        // Query encoding leaves '+' literal; form decoding interprets it as a space.
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        func encode(_ value: String) -> String {
            value.addingPercentEncoding(withAllowedCharacters: allowed)!
        }
        return Data(values.keys.sorted().map { "\(encode($0))=\(encode(values[$0]!))" }.joined(separator: "&").utf8)
    }

	private func dictionary(_ value: Any?) -> [String: Any]? {
		value as? [String: Any]
	}

    private func array(_ value: Any?) -> [[String: Any]] {
        value as? [[String: Any]] ?? []
    }

    private func identifier(_ value: Any?) -> String {
        if let value = value as? String { return value }
        if let value = value as? NSNumber { return value.stringValue }
        return ""
    }

    private func string(_ value: Any?, fallback: String) -> String {
        if let value = value as? String { return value }
        if let value = value as? NSNumber { return value.stringValue }
        return fallback
    }

    private func boolean(_ value: Any?) -> Bool {
        if let value = value as? Bool { return value }
        if let value = value as? NSNumber { return value.boolValue }
        return false
    }

    private func userMap(_ object: [String: Any]) -> [String: [String: Any]] {
        var result: [String: [String: Any]] = [:]
        for user in array(object["Users"]) {
            result[identifier(user["Id"])] = user
        }
        return result
    }

    private func subjectMap(_ object: [String: Any]) -> [String: String] {
        Dictionary(uniqueKeysWithValues: array(object["Subjects"]).map {
            (identifier($0["Id"]), string($0["Name"], fallback: "Subject"))
        })
    }

    private func gradeCategories(_ object: [String: Any]) -> [String: (name: String, weight: String)] {
        var result: [String: (name: String, weight: String)] = [:]
        for category in array(object["Categories"]) {
            result[identifier(category["Id"])] = (
                string(category["Name"], fallback: "Grade"),
                string(category["Weight"], fallback: "none")
            )
        }
        return result
    }

    private func homeworkCategoryMap(_ object: [String: Any]) -> [String: String] {
        Dictionary(uniqueKeysWithValues: array(object["Categories"]).map {
            (identifier($0["Id"]), string($0["Name"], fallback: "Homework"))
        })
    }

    private func commentMap(_ object: [String: Any]) -> [String: String] {
        Dictionary(uniqueKeysWithValues: array(object["Comments"]).map {
            (identifier($0["Id"]), string($0["Text"], fallback: ""))
        })
    }

    private func lessonSubjectMap(_ object: [String: Any]) -> [String: String] {
        var result: [String: String] = [:]
        for lesson in array(object["Lessons"]) {
            result[identifier(lesson["Id"])] = identifier(dictionary(lesson["Subject"])?["Id"])
        }
        return result
    }

    private func attendanceTypeMap(_ object: [String: Any]) -> [String: (name: String, short: String, isPresence: Bool)] {
        var result: [String: (name: String, short: String, isPresence: Bool)] = [:]
        for type in array(object["Types"]) {
            result[identifier(type["Id"])] = (
                string(type["Name"], fallback: "Attendance"),
                string(type["Short"], fallback: "?"),
                boolean(type["IsPresenceKind"])
            )
        }
        return result
    }

    private func classroomMap(_ object: [String: Any]) -> [String: String] {
        var result: [String: String] = [:]
        for entry in array(object["TimetableEntries"]) {
            if let classroom = dictionary(entry["Classroom"]) {
                result[identifier(classroom["Id"])] = string(classroom["Symbol"] ?? classroom["Name"], fallback: "—")
            }
        }
        return result
    }

    private func teacherName(_ teacher: [String: Any]) -> String {
        let first = string(teacher["FirstName"], fallback: "")
        let last = string(teacher["LastName"], fallback: "")
        return [first, last].filter { !$0.isEmpty }.joined(separator: " ")
    }

    private func dateString(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .iso8601)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

	private func parseDate(_ value: String) -> Date? {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .iso8601)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
		return formatter.date(from: value)
	}

	private func dayName(for date: Date, calendar: Calendar, names: [String]) -> String? {
		let weekday = calendar.component(.weekday, from: date)
		return names[safe: (weekday + 5) % 7]
	}

    private func messageID(from href: String) -> String {
        var path = href
        if path.hasPrefix("/") { path.removeFirst() }
        if let range = path.range(of: "wiadomosci/") {
            path = String(path[range.upperBound...])
        }
        return path.split(separator: "?").first.map(String.init)?.replacingOccurrences(of: "/", with: "-") ?? ""
    }

    private func messageAttachments(from html: String) -> [MessageAttachment] {
        let normalizedHTML = decodeHTMLEntities(html).replacingOccurrences(of: "\\/", with: "/")
        let pattern = #"(?:https://synergia\.librus\.pl)?/wiadomosci/pobierz_zalacznik/[^"'\s<>)]+"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return []
        }

        let htmlRange = NSRange(normalizedHTML.startIndex..<normalizedHTML.endIndex, in: normalizedHTML)
        let nsHTML = normalizedHTML as NSString
        var seenSources = Set<String>()
        var attachments: [MessageAttachment] = []

        for match in regex.matches(in: normalizedHTML, range: htmlRange) {
            let source = String(normalizedHTML[Range(match.range, in: normalizedHTML)!])
                .replacingOccurrences(of: "\\", with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard let url = URL(string: source, relativeTo: portalBase)?.absoluteURL,
                  url.scheme == "https",
                  url.host == portalBase.host,
                  url.path.lowercased().contains("/pobierz_zalacznik/") else {
                continue
            }

            let rowStart = nsHTML.range(
                of: "<tr",
                options: [.caseInsensitive, .backwards],
                range: NSRange(location: 0, length: match.range.location)
            )
            let rowEnd = nsHTML.range(
                of: "</tr>",
                options: [.caseInsensitive],
                range: NSRange(location: match.range.location, length: nsHTML.length - match.range.location)
            )
            guard rowStart.location != NSNotFound, rowEnd.location != NSNotFound else { continue }

            let row = nsHTML.substring(
                with: NSRange(
                    location: rowStart.location,
                    length: rowEnd.location + rowEnd.length - rowStart.location
                )
            )
            let name = allCaptures(#"<td[^>]*>(.*?)</td>"#, in: row)
                .map(htmlText)
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .first { $0.contains(".") && !$0.isEmpty }
                ?? attachmentFileName(from: url)
            guard seenSources.insert(source).inserted else { continue }
            attachments.append(MessageAttachment(name: name, source: source))
        }
        return attachments
    }

    private func attachmentFileName(from url: URL) -> String {
        let component = url.path
            .split(separator: "/")
            .last
            .map(String.init)?
            .removingPercentEncoding ?? ""
        return component.isEmpty ? "Attachment" : component
    }

    private func attachmentFileName(from response: HTTPURLResponse, fallback: String) -> String {
        let header = response.value(forHTTPHeaderField: "Content-Disposition") ?? ""
        if let match = try? NSRegularExpression(pattern: #"filename\*?=(?:UTF-8''|\")?([^\";]+)"#, options: .caseInsensitive)
            .firstMatch(in: header, range: NSRange(header.startIndex..<header.endIndex, in: header)),
           let range = Range(match.range(at: 1), in: header) {
            let value = String(header[range]).removingPercentEncoding ?? String(header[range])
            let name = (value as NSString).lastPathComponent
            if !name.isEmpty { return name }
        }
        return fallback.isEmpty ? "Attachment" : fallback
    }

    private func capture(_ pattern: String, in text: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else { return nil }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, range: range), match.numberOfRanges > 1,
              let captureRange = Range(match.range(at: 1), in: text) else { return nil }
        return String(text[captureRange])
    }

    private func allCaptures(_ pattern: String, in text: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else { return [] }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.matches(in: text, range: range).compactMap { match in
            guard match.numberOfRanges > 1, let captureRange = Range(match.range(at: 1), in: text) else { return nil }
            return String(text[captureRange])
        }
    }

    private func allGroupCaptures(_ pattern: String, in text: String) -> [[String]] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else { return [] }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.matches(in: text, range: range).map { match in
            (1..<match.numberOfRanges).compactMap { index in
                guard let captureRange = Range(match.range(at: index), in: text) else { return nil }
                return String(text[captureRange])
            }
        }
    }

    private func htmlText(_ html: String) -> String {
        let withoutScripts = html.replacingOccurrences(of: #"<script[^>]*>.*?</script>"#, with: "", options: [.regularExpression, .caseInsensitive])
            .replacingOccurrences(of: #"<style[^>]*>.*?</style>"#, with: "", options: [.regularExpression, .caseInsensitive])
        if let data = withoutScripts.data(using: .utf8),
           let attributed = try? NSAttributedString(
               data: data,
               options: [
                   .documentType: NSAttributedString.DocumentType.html,
                   .characterEncoding: String.Encoding.utf8.rawValue
               ],
               documentAttributes: nil
           ) {
            return attributed.string.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return withoutScripts.replacingOccurrences(of: #"<[^>]+>"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func decodeHTMLEntities(_ value: String) -> String {
        value
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#34;", with: "\"")
            .replacingOccurrences(of: "&#x22;", with: "\"")
            .replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&#x27;", with: "'")
            .replacingOccurrences(of: "&amp;", with: "&")
    }
}

private extension Array {
    subscript(safe index: Index) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

import Foundation
import SwiftUI

struct GradesView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    @State private var semester: GradeSemester = .first

    var body: some View {
        List {
                Section {
                    Picker(settings.text(.details), selection: $semester) {
                        ForEach(GradeSemester.allCases) { value in
                            Text(value.title(using: settings)).tag(value)
                        }
                    }
                    .pickerStyle(.segmented)
                    GradeSummary(average: average, count: filteredGrades.count, settings: settings)
                }

                if filteredGrades.isEmpty {
                    EmptyState(title: settings.text(.noGrades), message: settings.text(.noData), icon: "book.closed")
                        .listRowBackground(Color.clear)
                } else {
                    ForEach(subjects, id: \.self) { subject in
                        Section {
                            ForEach(filteredGrades.filter { $0.subject == subject }) { grade in
                                NavigationLink { GradeDetailView(grade: grade) } label: {
                                    GradeRow(grade: grade)
                                }
                            }
                        } header: {
                            HStack {
                                Text(subject)
                                Spacer()
                                Text(subjectAverage(for: subject))
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        .schoolListStyle()
        .navigationTitle(settings.text(.grades))
    }

    private var filteredGrades: [GradeRecord] {
        model.data.grades.filter { $0.belongs(to: semester) }
    }

    private var subjects: [String] {
        Array(Set(filteredGrades.map(\.subject))).sorted()
    }

    private var average: Double? {
        average(for: filteredGrades)
    }

    private func subjectAverage(for subject: String) -> String {
        guard let value = average(for: filteredGrades.filter { $0.subject == subject }) else { return "—" }
        return value.formatted(.number.precision(.fractionLength(2)))
    }

    private func average(for grades: [GradeRecord]) -> Double? {
        let values = grades.compactMap(\.numericValue)
        guard !values.isEmpty else { return nil }
        return values.reduce(0, +) / Double(values.count)
    }
}

private struct GradeSummary: View {
    let average: Double?
    let count: Int
    let settings: AppSettings

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(settings.text(.average))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(average?.formatted(.number.precision(.fractionLength(2))) ?? "—")
                    .font(.title.weight(.bold))
            }
            Spacer()
            Text("\(count) \(settings.text(.gradesCount))")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }
}

private struct GradeRow: View {
    @EnvironmentObject private var settings: AppSettings
    let grade: GradeRecord

    var body: some View {
        HStack(spacing: 14) {
            Text(grade.value)
                .font(.title3.weight(.bold))
                .frame(width: 44, height: 44)
                .background(grade.isFinal ? .indigo.opacity(0.18) : .secondary.opacity(0.12), in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text(grade.category.isEmpty ? settings.text(.grades) : grade.category)
                    .font(.body.weight(.medium))
                HStack(spacing: 6) {
                    Text(grade.weight == "none" ? settings.text(.noWeight) : "\(settings.text(.weight)) \(grade.weight)")
                    if !grade.teacher.isEmpty { Text("· \(grade.teacher)") }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer()
            if grade.isFinal {
                Image(systemName: "star.fill")
                    .foregroundStyle(.orange)
                    .accessibilityLabel(settings.text(.finalGrade))
            }
        }
        .padding(.vertical, 4)
    }
}

private struct GradeDetailView: View {
    @EnvironmentObject private var settings: AppSettings
    let grade: GradeRecord

    var body: some View {
        List {
            LabeledContent(settings.text(.grades), value: grade.value)
            LabeledContent(settings.text(.details), value: grade.category)
            if grade.weight != "none" {
                LabeledContent(settings.text(.weight), value: grade.weight)
            }
            if !grade.semester.isEmpty {
                LabeledContent(settings.text(.details), value: grade.semester)
            }
            if !grade.teacher.isEmpty {
                LabeledContent(settings.text(.teacher), value: grade.teacher)
            }
            if !grade.addedDate.isEmpty {
                LabeledContent(settings.text(.lastUpdated), value: grade.addedDate)
            }
            if !grade.comment.isEmpty {
                Section(settings.text(.note)) {
                    Text(grade.comment)
                        .textSelection(.enabled)
                }
            }
        }
        .schoolListStyle()
        .navigationTitle(settings.text(.gradeDetails))
    }
}

struct ScheduleView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    @State private var selectedDay = SchoolAppDate.dayKey(for: Date())

    var body: some View {
        Group {
                if let timetable = model.data.timetable, !timetable.days.isEmpty {
                    List {
                        Section {
                            Picker(settings.text(.selectDay), selection: $selectedDay) {
                                ForEach(availableDays, id: \.self) { day in
                                    Text(dayTitle(day)).tag(day)
                                }
                            }
                            .pickerStyle(.menu)
                        }

                        if selectedLessons.isEmpty {
                            EmptyState(title: settings.text(.noLessonsToday), message: settings.text(.noData), icon: "calendar.badge.clock")
                                .listRowBackground(Color.clear)
                        } else {
                            Section(dayTitle(selectedDay)) {
                                ForEach(selectedLessons) { lesson in
                                    NavigationLink {
                                        LessonDetailView(lesson: lesson, dayName: selectedDay)
                                    } label: {
                                        LessonRow(lesson: lesson)
                                    }
                                }
                            }
                        }
                    }
                    .schoolListStyle()
                    .onAppear { chooseInitialDay() }
                } else {
                    EmptyState(title: settings.text(.noTimetable), message: settings.text(.refreshOnPhone), icon: "calendar.badge.clock")
                }
            }
        .navigationTitle(settings.text(.schedule))
    }

    private var availableDays: [String] {
        guard let timetable = model.data.timetable else { return [] }
        return SchoolAppDate.dayKeys.filter { timetable.days[$0] != nil }
    }

    private var selectedLessons: [TimetableLesson] {
        model.data.timetable?.days[selectedDay] ?? []
    }

    private func chooseInitialDay() {
        guard let first = availableDays.first else { return }
        if !availableDays.contains(selectedDay) {
            selectedDay = first
        }
    }

    private func dayTitle(_ day: String) -> String {
        let names: [String: String]
        if settings.language == .polish {
            names = ["Monday": "Poniedziałek", "Tuesday": "Wtorek", "Wednesday": "Środa", "Thursday": "Czwartek", "Friday": "Piątek", "Saturday": "Sobota", "Sunday": "Niedziela"]
        } else {
            names = Dictionary(uniqueKeysWithValues: SchoolAppDate.dayKeys.map { ($0, $0) })
        }
        return names[day] ?? day
    }
}

struct LessonRow: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    let lesson: TimetableLesson

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(lesson.hourFrom)
                    .font(.subheadline.weight(.semibold))
                Text(lesson.hourTo)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(width: 48, alignment: .leading)
            Divider()
            VStack(alignment: .leading, spacing: 4) {
                Text(lesson.displaySubject)
                    .font(.body.weight(.medium))
                    .strikethrough(lesson.isCancelled)
                let teacherText: String = {
                    if lesson.hasOriginalTeacher {
                        return [lesson.teacher, lesson.originalTeacher ?? ""].filter { !$0.isEmpty && $0 != "—" }.joined(separator: " > ")
                    }
                    return lesson.teacher
                }()
                Text([teacherText, lesson.classroom].filter { !$0.isEmpty && $0 != "—" }.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if lesson.isCancelled || lesson.isSubstitution {
                    Text(lesson.isCancelled ? settings.text(.cancelled) : settings.text(.substitution))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(lesson.isCancelled ? .red : .orange)
                }
            }
            Spacer(minLength: 0)
            if model.note(for: lesson.id) != nil {
                Image(systemName: "note.text")
                    .foregroundStyle(.indigo)
                    .accessibilityLabel(settings.text(.note))
            }
        }
        .padding(.vertical, 5)
    }
}

struct LessonDetailView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    let lesson: TimetableLesson
    let dayName: String

    var body: some View {
        List {
            Section {
                LabeledContent(settings.text(.lessonNumber), value: lesson.lessonNumber)
                LabeledContent(settings.text(.selectDay), value: dayName)
                LabeledContent(settings.text(.details), value: lesson.displaySubject)
                LabeledContent(settings.text(.schedule), value: "\(lesson.hourFrom) – \(lesson.hourTo)")
                LabeledContent(settings.text(.classroom), value: lesson.classroom)
                LabeledContent(settings.text(.teacher), value: lesson.teacher)
                if lesson.hasOriginalTeacher {
                    LabeledContent(settings.text(.replacedTeacher), value: lesson.originalTeacher ?? "")
                }
            }
            if lesson.isCancelled || lesson.isSubstitution {
                Section {
                    Label(lesson.isCancelled ? settings.text(.cancelled) : settings.text(.substitution), systemImage: lesson.isCancelled ? "xmark.circle" : "arrow.triangle.2.circlepath")
                        .foregroundStyle(lesson.isCancelled ? .red : .orange)
                }
            }
            Section(settings.text(.note)) {
                SchoolNoteEditor(noteID: lesson.id)
            }
        }
        .schoolListStyle()
        .navigationTitle(settings.text(.lessonDetails))
    }
}

enum HomeworkFilter: String, CaseIterable, Identifiable {
    case all, assessments
    var id: String { rawValue }
}

struct HomeworkView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    @State private var filter: HomeworkFilter

    init(filter: HomeworkFilter = .all) {
        _filter = State(initialValue: filter)
    }

    var body: some View {
        List {
                Section {
                    Picker(settings.text(.details), selection: $filter) {
                        ForEach(HomeworkFilter.allCases) { value in
                            Text(value == .all ? settings.text(.all) : settings.text(.assessments)).tag(value)
                        }
                    }
                    .pickerStyle(.segmented)
                }
                if filteredHomeworks.isEmpty {
                    EmptyState(title: settings.text(.noHomework), message: settings.text(.noData), icon: "doc.text")
                        .listRowBackground(Color.clear)
                } else {
                    ForEach(filteredHomeworks) { homework in
                        NavigationLink { HomeworkDetailView(homework: homework) } label: {
                            HomeworkRow(homework: homework)
                        }
                    }
                }
            }
        .schoolListStyle()
        .navigationTitle(settings.text(.homework))
    }

    private var filteredHomeworks: [HomeworkRecord] {
        model.data.homeworks.filter { filter == .all || $0.isAssessment }
    }
}

private struct HomeworkRow: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    let homework: HomeworkRecord

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: homework.isAssessment ? "checklist" : "doc.text.fill")
                .foregroundStyle(homework.isAssessment ? .purple : .orange)
            VStack(alignment: .leading, spacing: 5) {
                HStack {
                    Text(homework.subject).font(.headline)
                    Spacer()
                    Text(homework.date).font(.caption).foregroundStyle(.secondary)
                }
                Text(homework.content.isEmpty ? homework.type : homework.content)
                    .font(.body)
                    .lineLimit(3)
                if let note = model.note(for: homework.id), note.isNoLongerRelevant {
                    Text(settings.text(.noLongerRelevant))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                } else if !homework.addedBy.isEmpty {
                    Text("\(settings.text(.addedBy)) \(homework.addedBy)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 6)
        .opacity(model.note(for: homework.id)?.isNoLongerRelevant == true ? 0.55 : 1)
    }
}

private struct HomeworkDetailView: View {
    @EnvironmentObject private var settings: AppSettings
    let homework: HomeworkRecord

    var body: some View {
        List {
            Section {
                LabeledContent(settings.text(.homework), value: homework.subject)
                LabeledContent(settings.text(.due), value: homework.date)
                if !homework.type.isEmpty {
                    LabeledContent(settings.text(.details), value: homework.type)
                }
                if !homework.addedBy.isEmpty {
                    LabeledContent(settings.text(.addedBy), value: homework.addedBy)
                }
            }
            Section(settings.text(.details)) {
                Text(homework.content.isEmpty ? settings.text(.noData) : homework.content)
                    .textSelection(.enabled)
            }
            Section(settings.text(.note)) {
                SchoolNoteEditor(noteID: homework.id)
            }
        }
        .schoolListStyle()
        .navigationTitle(settings.text(.homework))
    }
}

struct AttendanceView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        Group {
                if model.data.attendances.isEmpty {
                    EmptyState(title: settings.text(.noAttendance), message: settings.text(.noData), icon: "checkmark.circle")
                } else {
                    List {
                        Section {
                            HStack {
                                AttendanceMetric(title: settings.text(.present), value: model.data.attendances.filter(\.isPresence).count, color: .green)
                                Divider()
                                AttendanceMetric(title: settings.text(.absent), value: model.data.attendances.filter { !$0.isPresence }.count, color: .red)
                            }
                            .padding(.vertical, 8)
                        }
                        Section(settings.text(.recentRecords)) {
                            ForEach(model.data.attendances) { attendance in
                                HStack(spacing: 12) {
                                    Text(attendance.shortType)
                                        .font(.subheadline.weight(.bold))
                                        .frame(width: 38, height: 38)
                                        .background((attendance.isPresence ? Color.green : Color.red).opacity(0.14), in: Circle())
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(attendance.subject).font(.body.weight(.medium))
                                        Text("\(attendance.type) · \(attendance.date)")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Image(systemName: attendance.isPresence ? "checkmark" : "xmark")
                                        .foregroundStyle(attendance.isPresence ? .green : .red)
                                }
                                .padding(.vertical, 4)
                            }
                        }
                    }
                    .schoolListStyle()
                }
            }
        .navigationTitle(settings.text(.attendance))
    }
}

private struct AttendanceMetric: View {
    let title: String
    let value: Int
    let color: Color

    var body: some View {
        VStack(spacing: 4) {
            Text("\(value)").font(.title2.weight(.bold)).foregroundStyle(color)
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

struct MessagesView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    @State private var folder: MessageFolder = .inbox

    var body: some View {
        List {
                Section {
                    Picker(settings.text(.details), selection: $folder) {
                        ForEach(MessageFolder.allCases) { value in
                            Text(folderTitle(value)).tag(value)
                        }
                    }
                    .pickerStyle(.menu)
                }
                if folderMessages.isEmpty {
                    EmptyState(
                        title: settings.text(.noMessages),
                        message: folder == .notes ? settings.text(.folderUnavailable) : settings.text(.noData),
                        icon: folder == .announcements ? "megaphone" : folder == .notes ? "exclamationmark.bubble" : "envelope.open"
                    )
                    .listRowBackground(Color.clear)
                } else {
                    ForEach(folderMessages) { message in
                        NavigationLink {
                            MessageDetailView(summary: message)
                        } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                HStack {
                                    Text(message.subject).font(.body.weight(.semibold))
                                    Spacer()
                                    Text(message.date).font(.caption).foregroundStyle(.secondary)
                                }
                                Text(message.sender).font(.subheadline).foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 5)
                        }
                    }
                }
            }
        .schoolListStyle()
        .navigationTitle(settings.text(.messages))
    }

    private var folderMessages: [MessageSummary] {
        model.data.messages.filter { $0.folder == folder && !$0.isLikelyHeaderRow }
    }

    private func folderTitle(_ folder: MessageFolder) -> String {
        switch folder {
        case .inbox: return settings.text(.inbox)
        case .sent: return settings.text(.sent)
        case .announcements: return settings.text(.announcements)
        case .notes: return settings.text(.notes)
        }
    }
}

private struct MessageDetailView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    let summary: MessageSummary
    @State private var detail: MessageDetail?
    @State private var isLoading = true
    @State private var downloadingAttachmentID: String?
    @State private var downloadedAttachmentID: String?
    @State private var downloadedAttachment: DownloadedMessageAttachment?
    @State private var attachmentError: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if isLoading {
                    ProgressView(settings.text(.syncing))
                } else if let detail {
                    Text(detail.subject).font(.title2.weight(.bold))
                    if !detail.sender.isEmpty { Text(detail.sender).foregroundStyle(.secondary) }
                    Text(detail.content.isEmpty ? settings.text(.noData) : detail.content)
                        .font(.body)
                        .textSelection(.enabled)
                    if let attachmentError {
                        Text(attachmentError)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                    if !detail.attachments.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(settings.text(.attachments))
                                .font(.headline)
                            ForEach(detail.attachments) { attachment in
                                HStack(spacing: 12) {
                                    Image(systemName: "paperclip")
                                        .foregroundStyle(.secondary)
                                    Text(attachment.name)
                                        .lineLimit(2)
                                    Spacer(minLength: 8)
                                    if attachment.downloadURL != nil {
                                        if downloadedAttachmentID == attachment.id,
                                           let downloadedAttachment {
                                            ShareLink(item: downloadedAttachment.fileURL) {
                                                Label(settings.text(.saveFile), systemImage: "square.and.arrow.down")
                                            }
                                            .buttonStyle(.bordered)
                                        } else if downloadingAttachmentID == attachment.id {
                                            ProgressView()
                                                .controlSize(.small)
                                        } else {
                                            Button {
                                                Task {
                                                    downloadingAttachmentID = attachment.id
                                                    attachmentError = nil
                                                    do {
                                                        downloadedAttachment = try await model.downloadMessageAttachment(attachment)
                                                        downloadedAttachmentID = attachment.id
                                                    } catch is CancellationError {
                                                    } catch {
                                                        attachmentError = error.localizedDescription
                                                    }
                                                    downloadingAttachmentID = nil
                                                }
                                            } label: {
                                                Label(settings.text(.download), systemImage: "arrow.down.circle")
                                            }
                                            .buttonStyle(.bordered)
                                            .disabled(downloadingAttachmentID != nil)
                                        }
                                    } else {
                                        Label(settings.text(.download), systemImage: "link.badge.plus")
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                .padding(12)
                                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
                            }
                        }
                    }
                } else {
                    EmptyState(title: settings.text(.noMessages), message: settings.text(.folderUnavailable), icon: "envelope.badge.exclamationmark")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
        }
        .navigationTitle(summary.subject)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task { await loadDetail() }
    }

    private func loadDetail() async {
        if summary.folder == .announcements {
            detail = MessageDetail(subject: summary.subject, sender: summary.sender, date: summary.date, content: summary.content)
        } else {
            detail = await model.loadMessage(summary)
        }
        isLoading = false
    }

}

struct MoreView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    @Binding var showSettings: Bool

    var body: some View {
        List {
                if let profile = model.data.profile {
                    StudentInfoCard(profile: profile, settings: settings)
                }
                Section(settings.text(.school)) {
                    NavigationLink { HomeworkView() } label: { Label(settings.text(.homework), systemImage: "doc.text.fill") }
                    NavigationLink { AttendanceView() } label: { Label(settings.text(.attendance), systemImage: "checkmark.circle") }
                }
                #if os(iOS)
                PhoneWatchStatusView(sync: model.watchSync)
                Section {
                    Link(destination: URL(string: "https://github.com/filosquared/librecap")!) {
                        Label(settings.text(.github), systemImage: "chevron.left.forwardslash.chevron.right")
                    }
                }
                #endif
                Section(settings.text(.account)) {
                    Button(role: .destructive, action: model.logout) {
                        Label(settings.text(.signOut), systemImage: "rectangle.portrait.and.arrow.right")
                    }
                }
            }
            .schoolListStyle()
            .navigationTitle(settings.text(.more))
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel(settings.text(.settings))
                }
        }
    }
}

private struct StudentInfoCard: View {
    let profile: StudentProfile
    let settings: AppSettings

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 12) {
                Text(profile.fullName)
                    .font(.title3.weight(.bold))
                HStack(alignment: .top, spacing: 18) {
                    ProfileField(label: settings.text(.className), value: profile.className)
                    ProfileField(label: settings.text(.tutor), value: profile.tutorName)
                }
                ProfileField(label: settings.text(.accountType), value: profile.type)
            }
            .padding(.vertical, 8)
        } header: {
            Text(settings.text(.studentInformation))
        }
    }
}

private struct ProfileField: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value.isEmpty ? "—" : value)
                .font(.subheadline.weight(.semibold))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct SchoolNoteEditor: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    let noteID: String
    @State private var text = ""
    @State private var reminds = false
    @State private var isNoLongerRelevant = false
    @State private var didSave = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextEditor(text: $text)
                .frame(minHeight: 90)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(.secondary.opacity(0.2)))
                .accessibilityLabel(settings.text(.note))
            Toggle(settings.text(.remindMe), isOn: $reminds)
            Toggle(settings.text(.noLongerRelevant), isOn: $isNoLongerRelevant)
            Button(action: save) {
                Label(didSave ? settings.text(.saved) : settings.text(.saveNote), systemImage: didSave ? "checkmark" : "square.and.arrow.down")
            }
            .buttonStyle(.bordered)
        }
        .onAppear(perform: load)
    }

    private func load() {
        guard let note = model.note(for: noteID) else { return }
        text = note.text
        reminds = note.reminds
        isNoLongerRelevant = note.isNoLongerRelevant
    }

    private func save() {
        model.saveNote(id: noteID, text: text, reminds: reminds, isNoLongerRelevant: isNoLongerRelevant)
        didSave = true
    }
}

struct EmptyState: View {
    let title: String
    let message: String
    let icon: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 42))
                .foregroundStyle(.secondary)
            Text(title)
                .font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(32)
    }
}

extension HomeworkRecord {
    var isAssessment: Bool {
        let searchable = "\(type) \(content)".folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .current)
        return ["test", "sprawdz", "kartkow", "klasow", "projekt", "project", "egzamin", "exam"].contains { searchable.contains($0) }
    }
}

extension MessageSummary {
    // Some Librus HTML responses include the sortable table header as a row.
    // Keep old caches from showing that header as if it were a real message.
    var isLikelyHeaderRow: Bool {
        let text = "\(sender) \(subject) \(date)"
            .folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .current)
        return ["temat", "subject", "wyslano", "sent", "napisz", "archiwum", "etykiety", "kosz"].contains { text.contains($0) }
    }
}

package com.filiplopes.librecap

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class LibrusErrorKind {
    ACCOUNT_TYPE,
    INVALID_CREDENTIALS,
    LOGIN_FLOW,
    ADDITIONAL_VERIFICATION,
    SESSION_EXPIRED,
    UNAVAILABLE,
    UNEXPECTED_RESPONSE,
    MALFORMED_DATA,
    LOCAL_STORAGE,
    GENERIC
}

class LibrusClientError(val kind: LibrusErrorKind, message: String) : Exception(message) {
    constructor(message: String) : this(LibrusErrorKind.GENERIC, message)
}

class LibrusClient {
    private val apiBase = "https://synergia.librus.pl/gateway/api/2.0/"
    private val portalBase = "https://synergia.librus.pl"
    private val oauthHost = "api.librus.pl"
    private val cookies = InMemoryCookieJar()
    private val http = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun login(username: String, password: String): StudentProfile {
        val loginName = username.trim()
        if (loginName.contains("@")) {
            throw LibrusClientError(LibrusErrorKind.ACCOUNT_TYPE, "Use the school-issued Synergia login, not an email address.")
        }
        if (loginName.isEmpty() || password.isEmpty()) {
            throw LibrusClientError(LibrusErrorKind.INVALID_CREDENTIALS, "Enter your Synergia login and password.")
        }

        val portal = request(
            "$portalBase/loguj/portalRodzina",
            headers = mapOf(
                "Referer" to "https://portal.librus.pl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        )
        if (portal.code != 200) throw responseError(portal, "starting the Librus login flow")
        val authUrl = authorizationUrl(portal.finalUrl)

        val loginResponse = request(
            authUrl,
            method = "POST",
            body = formBody(mapOf("action" to "login", "login" to loginName, "pass" to password)),
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/x-www-form-urlencoded"
            )
        )
        if (loginResponse.code == 401 || loginResponse.code == 403) {
            throw LibrusClientError(LibrusErrorKind.INVALID_CREDENTIALS, "Librus rejected this sign-in. Check the school-issued Synergia login and password.")
        }
        if (loginResponse.code != 200) throw responseError(loginResponse, "signing in")
        val loginJson = parseObject(loginResponse.bytes, "the sign-in response")
        val loginStatus = loginJson.string("status").lowercase(Locale.ROOT)
        if (loginStatus == "error") {
            throw LibrusClientError(LibrusErrorKind.INVALID_CREDENTIALS, "Librus rejected this sign-in. Check the school-issued Synergia login and password.")
        }
        if (loginStatus != "ok") {
            throw LibrusClientError(LibrusErrorKind.LOGIN_FLOW, "LibreCap could not complete the Librus login flow. Try again in a moment.")
        }
        val nextUrl = authorizationUrl(loginJson.string("goTo"))
        val continuation = request(nextUrl)
        if (continuation.code !in 200..399) throw responseError(continuation, "completing sign-in")
        if (URI(continuation.finalUrl).host != URI(portalBase).host) {
            throw LibrusClientError(LibrusErrorKind.ADDITIONAL_VERIFICATION, "Librus requires an additional verification step on the official Synergia website.")
        }

        val tokenInfo = apiJson("Auth/TokenInfo")
        val identifier = tokenInfo.string("UserIdentifier")
        if (identifier.isEmpty()) throw LibrusClientError(LibrusErrorKind.MALFORMED_DATA, "Librus returned an incomplete login session. Try signing in again.")
        val access = request("$apiBase/Auth/UserInfo/$identifier")
        if (access.code != 200) throw responseError(access, "authorizing access to your school data")
        return fetchProfile()
    }

    fun fetchProfile(): StudentProfile {
        val me = apiJson("Me")
        val userProfile = apiJson("UserProfile")
        val users = apiJson("Users")
        val classes = apiJson("Classes")

        val account = me.obj("Me").obj("Account")
        val schoolClass = classes.obj("Class")
        val tutorId = schoolClass.obj("ClassTutor").string("Id")
        val tutor = userMap(users)[tutorId] ?: JsonObject()
        val number = schoolClass.string("Number")
        val symbol = schoolClass.string("Symbol").uppercase(Locale.getDefault())
        val className = listOf(number, symbol).filter(String::isNotEmpty).joinToString(" ")
        return StudentProfile(
            firstName = account.string("FirstName", "Student"),
            lastName = account.string("LastName"),
            tutorFirstName = tutor.string("FirstName"),
            tutorLastName = tutor.string("LastName"),
            schoolYearStarts = schoolClass.string("BeginSchoolYear"),
            schoolYearMiddles = schoolClass.string("EndFirstSemester"),
            schoolYearEnds = schoolClass.string("EndSchoolYear"),
            type = userProfile.obj("UserProfile").string("UnitType", "Student").replaceFirstChar { it.uppercase() },
            className = className.ifEmpty { "Class" }
        )
    }

    fun fetchGrades(): List<GradeRecord> {
        val response = apiJson("Grades")
        val categories = gradeCategories(apiJson("Grades/Categories"))
        val comments = commentMap(apiJson("Grades/Comments"))
        val subjects = subjectMap(apiJson("Subjects"))
        val teachers = userMap(apiJson("Users"))
        return response.array("Grades").mapIndexed { index, raw ->
            val subjectId = raw.obj("Subject").string("Id")
            val categoryId = raw.obj("Category").string("Id")
            val addedById = raw.obj("AddedBy").string("Id")
            val category = categories[categoryId] ?: ("Grade" to "none")
            val commentId = raw.array("Comments").firstOrNull()?.asJsonObject?.string("Id") ?: ""
            val addedDate = raw.string("AddDate")
            GradeRecord(
                id = raw.string("Id").ifEmpty { "$subjectId-$addedDate-${raw.string("Grade")}-$index" },
                subject = subjects[subjectId]
                    ?: subjectName(raw.obj("Subject"), raw.firstString("SubjectName", "SubjectTitle"))
                        .ifBlank { "Unknown subject" },
                value = raw.string("Grade", "—"),
                weight = category.second,
                comment = comments[commentId] ?: "",
                category = category.first,
                isFinal = raw.bool("IsFinal") || raw.bool("IsFinalProposition"),
                isSemester = raw.bool("IsSemester") || raw.bool("IsSemesterProposition"),
                semester = raw.string("Semester"),
                addedDate = addedDate,
                teacher = teacherName(teachers[addedById] ?: JsonObject())
            )
        }.sortedBy { it.subject.lowercase(Locale.getDefault()) }
    }

    fun fetchTimetable(): TimetableData {
        val today = LocalDate.now()
        val pivot = today.plusDays(2)
        val weekStart = pivot.minusDays((pivot.dayOfWeek.value - 1).toLong())
        val weekEnd = weekStart.plusDays(6)
        val dateFrom = weekStart.format(DATE_FORMAT)
        val dateTo = weekEnd.format(DATE_FORMAT)
        val timetable = apiJson("Timetables?weekStart=$dateFrom").obj("Timetable")
        val activities = apiJson("Timetables/OtherActivitiesRegister?dateFrom=$dateFrom&dateTo=$dateTo&hideOutdatedEntries=false")
        val classrooms = classroomMap(apiJson("TimetableEntries"))
        val substitutionDetails = fetchTimetableSubstitutions(dateFrom, dateTo)
        val lessonsByDay = mutableMapOf<String, MutableList<TimetableLesson>>()
        val lessonByStart = mutableMapOf<String, String>()

        timetable.entrySet().forEach { (dateKey, rawDay) ->
            val date = parseDate(dateKey) ?: return@forEach
            val dayName = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
            if (!rawDay.isJsonArray) return@forEach
            rawDay.asJsonArray.forEachIndexed { index, rawEntry ->
                val entries = rawEntry.asJsonArray
                    .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
                if (entries.isEmpty()) return@forEachIndexed

                val hasSubstitution = entries.any { it.bool("IsSubstitutionClass") }
                // Librus sends the regular lesson first and the replacement lesson
                // after it. Keep both so the UI can explain what changed.
                val effectiveIndex = if (hasSubstitution && entries.size > 1) entries.lastIndex else 0
                val lesson = entries[effectiveIndex]
                val originalEntry = entries.firstOrNull().takeIf { effectiveIndex != 0 }
                val directOriginalSubject = lesson.firstString(
                    "OriginalSubjectName", "PreviousSubjectName", "OriginalLessonSubject"
                )
                val nestedOriginalSubject = lesson.firstNestedString(
                    "OriginalSubject", "PreviousSubject", "OriginalLessonSubject", field = "Name"
                )
                val originalSubject = (originalEntry?.obj("Subject")?.string("Name").orEmpty())
                    .ifBlank { directOriginalSubject }
                    .ifBlank { nestedOriginalSubject }
                    .takeIf { it.isNotBlank() && !it.equals(lesson.obj("Subject").string("Name"), ignoreCase = true) }
                val directOriginalTeacher = lesson.firstString(
                    "OriginalTeacherName", "PreviousTeacherName", "FormerTeacherName"
                )
                val nestedOriginalTeacher = lesson.firstNestedTeacherName(
                    "OriginalTeacher", "PreviousTeacher", "FormerTeacher"
                )
                val originalTeacher = teacherName(originalEntry?.obj("Teacher") ?: JsonObject())
                    .ifBlank { directOriginalTeacher }
                    .ifBlank { nestedOriginalTeacher }
                    .takeIf { it.isNotBlank() && !it.equals(teacherName(lesson.obj("Teacher")), ignoreCase = true) }
                val hourFrom = lesson.string("HourFrom")
                val lessonNumber = lesson.string("LessonNo", "-")
                val apiSubject = subjectName(lesson.obj("Subject"), "Lesson")
                val apiTeacher = teacherName(lesson.obj("Teacher"))
                val substitution = substitutionDetails["$dateKey-$hourFrom"]
                val replacementSubject = substitution?.subject?.takeIf(String::isNotBlank)
                val replacementTeacher = substitution?.teacher?.takeIf(String::isNotBlank)
                val replacementClassroom = substitution?.classroom?.takeIf(String::isNotBlank)
                val currentSubject = replacementSubject ?: apiSubject
                val currentTeacher = replacementTeacher ?: apiTeacher
                val resolvedOriginalSubject = substitution?.originalSubject?.takeIf(String::isNotBlank)
                    ?: originalSubject
                    ?: apiSubject.takeIf { replacementSubject != null && !it.equals(currentSubject, ignoreCase = true) }
                val resolvedOriginalTeacher = substitution?.originalTeacher?.takeIf(String::isNotBlank)
                    ?: originalTeacher
                    ?: apiTeacher.takeIf { replacementTeacher != null && !it.equals(currentTeacher, ignoreCase = true) }
                lessonByStart[hourFrom] = lessonNumber
                val classroomId = lesson.obj("Classroom").string("Id")
                lessonsByDay.getOrPut(dayName) { mutableListOf() }.add(
                    TimetableLesson(
                        id = "$dateKey-$lessonNumber-$index",
                        lessonNumber = lessonNumber,
                        subject = currentSubject,
                        isSubstitution = hasSubstitution || substitution != null || resolvedOriginalSubject != null || resolvedOriginalTeacher != null,
                        isCancelled = lesson.bool("IsCanceled"),
                        teacher = currentTeacher,
                        hourFrom = hourFrom,
                        hourTo = lesson.string("HourTo"),
                        classroom = replacementClassroom ?: classrooms[classroomId] ?: "—",
                        originalSubject = resolvedOriginalSubject,
                        originalTeacher = resolvedOriginalTeacher
                    )
                )
            }
        }

        activities.array("data").forEachIndexed { index, item ->
            val date = parseDate(item.string("date")) ?: return@forEachIndexed
            val dayName = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
            val teacherParts = item.string("teacherName").trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            val teacher = if (teacherParts.size > 1) teacherParts.drop(1).joinToString(" ") + " " + teacherParts.first() else teacherParts.firstOrNull() ?: "—"
            lessonsByDay.getOrPut(dayName) { mutableListOf() }.add(
                TimetableLesson(
                    id = "activity-${item.string("date")}-$index",
                    lessonNumber = lessonByStart[item.string("startTime")] ?: "-",
                    subject = item.string("title", "Activity"),
                    isSubstitution = false,
                    isCancelled = false,
                    teacher = teacher,
                    hourFrom = item.string("startTime"),
                    hourTo = item.string("endTime"),
                    classroom = item.obj("classroom").string("symbol", "—")
                )
            )
        }
        return TimetableData(
            nextWeek = today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) < weekStart.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR),
            days = lessonsByDay.mapValues { (_, value) -> value.sortedBy { it.hourFrom } },
            weekStart = dateFrom
        )
    }

    fun fetchAttendances(): List<AttendanceRecord> {
        val attendances = apiJson("Attendances")
        val teachers = userMap(apiJson("Users"))
        val lessons = lessonSubjectMap(apiJson("Lessons"))
        val subjects = subjectMap(apiJson("Subjects"))
        val types = attendanceTypeMap(apiJson("Attendances/Types"))
        return attendances.array("Attendances").mapIndexed { index, raw ->
            val type = types[raw.obj("Type").string("Id")] ?: AttendanceType("Attendance", "?", false)
            AttendanceRecord(
                id = raw.string("Id").ifEmpty { "attendance-$index" },
                subject = subjects[lessons[raw.obj("Lesson").string("Id")] ?: ""]
                    ?: subjectName(raw.obj("Lesson").obj("Subject"), "Lesson"),
                type = type.name,
                shortType = type.short,
                isPresence = type.isPresence,
                addedDate = raw.string("AddDate"),
                date = raw.string("Date"),
                teacher = teacherName(teachers[raw.obj("AddedBy").string("Id")] ?: JsonObject())
            )
        }.reversed()
    }

    fun fetchHomeworks(): List<HomeworkRecord> {
        val homeworks = apiJson("HomeWorks")
        val teachers = userMap(apiJson("Users"))
        val categories = homeworkCategoryMap(apiJson("HomeWorks/Categories"))
        val subjects = subjectMap(apiJson("Subjects"))
        return homeworks.array("HomeWorks").mapIndexed { index, raw ->
            val category = categories[raw.obj("Category").string("Id")] ?: "Homework"
            HomeworkRecord(
                id = raw.string("Id").ifEmpty { "homework-$index" },
                subject = subjects[raw.obj("Subject").string("Id")]
                    ?: subjectName(raw.obj("Subject"), "Lesson ${raw.string("LessonNo")}"),
                addedBy = teacherName(teachers[raw.obj("CreatedBy").string("Id")] ?: JsonObject()),
                type = category,
                startTime = raw.string("TimeFrom"),
                endTime = raw.string("TimeTo"),
                date = raw.string("Date"),
                addedDate = raw.string("AddDate"),
                content = raw.string("Content")
            )
        }.reversed()
    }

    fun fetchLuckyNumber(): Int? {
        val rawNumber = apiJson("LuckyNumbers")
            .obj("LuckyNumber")
            .string("LuckyNumber")
            .trim()
        return rawNumber.toIntOrNull()?.takeIf { it > 0 }
    }

    fun fetchMessages(): List<MessageSummary> {
        val inboxHtml = portalHtml("/wiadomosci")
        val pages = linkedMapOf(MessageFolder.INBOX to inboxHtml)
        messageFolderLinks(Jsoup.parse(inboxHtml)).forEach { (folder, path) ->
            if (folder == MessageFolder.INBOX) return@forEach
            runCatching { portalHtml(path) }
                .onSuccess { pages[folder] = it }
                .onFailure { Log.w(LOG_TAG, "Could not load " + folder.name + " messages", it) }
        }
        val messages = pages.flatMap { (folder, page) -> parseMessagePage(page, folder) }
            .distinctBy { it.folder to it.id }
        val notes = pages[MessageFolder.NOTES]?.let(::parseBehaviourNotes) ?: fetchBehaviourNotes()
        Log.d(LOG_TAG, "Messages pages parsed: pages=" + pages.size + ", messages=" + messages.size)
        return messages + fetchAnnouncements() + notes
    }

    fun fetchMessageRecipients(): List<MessageRecipient> {
        val composeHtml = portalHtml("/wiadomosci/2/5")
        val csrfToken = Regex("""var\s+csrfTokenValue\s*=\s*\"([^\"]+)\"""")
            .find(composeHtml)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        if (csrfToken.isBlank()) {
            throw LibrusClientError(LibrusErrorKind.MALFORMED_DATA, "Librus did not provide a message security token. Try again.")
        }

        val recipientTypes = listOf("wychowawca", "nauczyciel", "bibliotekarz", "sekretariat", "admin")
        val recipients = recipientTypes.flatMap { type ->
            val html = portalHtml(
                "/getRecipients",
                method = "POST",
                body = formBody(
                    mapOf(
                        "typAdresata" to type,
                        "poprzednia" to "5",
                        "tabZaznaczonych" to "",
                        "czyWirtualneKlasy" to "false",
                        "idGrupy" to "0"
                    )
                ),
                extraHeaders = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$portalBase/wiadomosci/2/5",
                    "requestkey" to csrfToken
                )
            )
            Jsoup.parse(html).select("tr").mapNotNull { row ->
                val id = row.selectFirst("input[name='DoKogo[]']")?.attr("value")?.trim().orEmpty()
                val name = row.selectFirst("label")?.text()?.trim().orEmpty()
                if (id.isBlank() || name.isBlank()) null else MessageRecipient(id, name, recipientTypeLabel(type))
            }
        }
        return recipients
            .groupBy { it.name.replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT) }
            .values
            .map { samePerson ->
                val primary = samePerson.first()
                val roles = samePerson.map { it.group }.distinct()
                primary.copy(group = roles.joinToString(" / "))
            }
    }

    fun sendMessage(recipientId: String, subject: String, content: String) {
        if (recipientId.isBlank()) throw LibrusClientError(LibrusErrorKind.MALFORMED_DATA, "Choose a message recipient.")
        if (subject.isBlank()) throw LibrusClientError(LibrusErrorKind.MALFORMED_DATA, "Enter a message subject.")
        if (content.isBlank()) throw LibrusClientError(LibrusErrorKind.MALFORMED_DATA, "Enter a message body.")

        val html = portalHtml(
            "/wiadomosci/5",
            method = "POST",
            body = formBody(
                mapOf(
                    "DoKogo" to recipientId,
                    "temat" to subject,
                    "tresc" to content,
                    "poprzednia" to "6",
                    "wyslij" to "Wyślij"
                )
            )
        )
        val confirmation = Jsoup.parse(html).select(".green.container").text().trim()
        if (!confirmation.contains("została wysłana", ignoreCase = true) && !confirmation.contains("zostala wyslana", ignoreCase = true)) {
            throw LibrusClientError(LibrusErrorKind.UNEXPECTED_RESPONSE, "Librus did not confirm that the message was sent. Try again.")
        }
    }

    private fun recipientTypeLabel(type: String): String = when (type) {
        "wychowawca" -> "Wychowawca"
        "nauczyciel" -> "Nauczyciel"
        "bibliotekarz" -> "Bibliotekarz"
        "sekretariat" -> "Sekretariat"
        "admin" -> "Administracja"
        else -> type
    }

    private fun parseMessagePage(html: String, folder: MessageFolder): List<MessageSummary> {
        val rows = Jsoup.parse(html).select("tr")
        return rows.mapNotNull { row ->
            val columns = row.select("td")
            if (columns.size <= 1) return@mapNotNull null
            val linkColumn = columns.firstOrNull { column ->
                column.select("a[href]").any { messageId(it.attr("href")).isNotEmpty() }
            } ?: return@mapNotNull null
            val columnIndex = columns.indexOf(linkColumn)
            val href = linkColumn.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            val id = messageId(href)
            if (id.isEmpty()) return@mapNotNull null
            val sender = linkColumn.text().substringBefore("(").trim()
            val subject = columns.getOrNull(columnIndex + 1)?.text()?.trim().orEmpty()
            val date = columns.getOrNull(columnIndex + 2)?.text()?.trim().orEmpty()
            if (subject.isEmpty() && date.isEmpty()) return@mapNotNull null
            val header = "$sender $subject $date".lowercase(Locale.getDefault())
            if (listOf("temat", "subject", "wyslano", "sent").any(header::contains)) return@mapNotNull null
            MessageSummary(id, sender, subject, date, folder = folder)
        }
    }

    private fun messageFolderLinks(document: org.jsoup.nodes.Document): Map<MessageFolder, String> {
        return document.select("a[href], a[data-href]").mapNotNull { link ->
            val label = link.text().trim().lowercase(Locale.getDefault())
            val folder = when {
                label.contains("wysł") || label.contains("wysl") || label.contains("sent") -> MessageFolder.SENT
                label.contains("uwag") || label.contains("notes") -> MessageFolder.NOTES
                label.contains("odebr") || label.contains("received") || label.contains("inbox") -> MessageFolder.INBOX
                else -> null
            }
            val href = link.attr("href").ifBlank { link.attr("data-href") }
            val path = portalPath(href)
            if (folder == null || path == null) null else folder to path
        }.toMap()
    }

    private fun portalPath(href: String): String? {
        val trimmed = href.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("javascript:", ignoreCase = true)) return null
        val url = runCatching { URI(portalBase).resolve(trimmed) }.getOrNull() ?: return null
        val portalHost = URI(portalBase).host
        if (url.scheme != "https" || url.host != portalHost || url.userInfo != null || url.port !in listOf(-1, 443)) return null
        return url.rawPath + (url.rawQuery?.let { "?" + it } ?: "")
    }

    private data class BehaviourNote(
        val date: String = "",
        val teacher: String = "",
        val category: String = "",
        val content: String = ""
    )

    private fun fetchBehaviourNotes(): List<MessageSummary> {
        val html = runCatching { portalHtml("/uwagi") }
            .getOrElse {
                Log.w(LOG_TAG, "Could not load behaviour notes", it)
                return emptyList()
            }
        return parseBehaviourNotes(html)
    }

    private fun parseBehaviourNotes(html: String): List<MessageSummary> {
        val document = Jsoup.parse(html)
        if (document.select("p.msgEmptyTable").any { it.text().contains("brak uwag", ignoreCase = true) }) {
            return emptyList()
        }

        val notes = mutableListOf<BehaviourNote>()
        document.select("table.decorated").forEach { table ->
            val headerKeys = table.selectFirst("thead")
                ?.select("th, td")
                ?.map { noteFieldKey(it.text()) }
                .orEmpty()

            if (headerKeys.any { it != null }) {
                val rows = table.select("tbody tr").ifEmpty { table.select("tr").drop(1) }
                rows.forEach { row ->
                    val cells = row.select("td, th").map { it.text().trim() }
                    val fields = headerKeys.mapIndexedNotNull { index, key ->
                        key?.let { field -> cells.getOrNull(index)?.let { field to it } }
                    }.toMap()
                    noteFromFields(fields)?.let(notes::add)
                }
            }

            val pairFields = table.select("tr").mapNotNull { row ->
                val cells = row.select("td, th")
                if (cells.size != 2) return@mapNotNull null
                val key = noteFieldKey(cells[0].text()) ?: return@mapNotNull null
                key to cells[1].text().trim()
            }.toMap()
            noteFromFields(pairFields)?.let(notes::add)
        }

        return notes
            .filter { it.content.isNotBlank() || it.category.isNotBlank() || it.teacher.isNotBlank() || it.date.isNotBlank() }
            .distinct()
            .mapIndexed { index, note ->
                MessageSummary(
                    id = "note-" + index,
                    sender = note.teacher.ifBlank { "Librus" },
                    subject = note.category.ifBlank { "Uwaga" },
                    date = note.date,
                    folder = MessageFolder.NOTES,
                    content = note.content
                )
            }
    }

    private fun noteFieldKey(label: String): String? {
        val normalized = label.trim().lowercase(Locale.getDefault())
        return when {
            normalized.startsWith("data") -> "date"
            normalized.startsWith("nauczyciel") -> "teacher"
            normalized.startsWith("rodzaj") -> "category"
            normalized.startsWith("treść") || normalized.startsWith("tresc") || normalized.startsWith("uwaga") -> "content"
            else -> null
        }
    }

    private fun noteFromFields(fields: Map<String, String>): BehaviourNote? {
        if (fields.isEmpty()) return null
        return BehaviourNote(
            date = fields["date"].orEmpty(),
            teacher = fields["teacher"].orEmpty(),
            category = fields["category"].orEmpty(),
            content = fields["content"].orEmpty()
        )
    }

    private fun fetchAnnouncements(): List<MessageSummary> {
        val notices = apiJson("SchoolNotices").array("SchoolNotices")
        val announcements = notices.mapNotNull { raw ->
            val noticeId = raw.string("Id")
            if (noticeId.isEmpty()) return@mapNotNull null
            val subject = raw.string("Subject").trim()
            val content = Jsoup.parse(raw.string("Content")).text().trim()
            MessageSummary(
                id = "announcement-$noticeId",
                sender = "Librus",
                subject = subject.ifEmpty { "Announcement" },
                date = raw.string("CreationDate").ifEmpty { raw.string("StartDate") }.trim(),
                folder = MessageFolder.ANNOUNCEMENTS,
                content = content
            )
        }
        Log.d(LOG_TAG, "School notices parsed: notices=" + notices.size + ", announcements=" + announcements.size)
        return announcements
    }

    fun fetchMessage(id: String): MessageDetail {
        val html = portalHtml("/wiadomosci/${id.replace('-', '/')}")
        val doc = Jsoup.parse(html)
        val content = doc.selectFirst(".container-message-content")?.text()?.trim().orEmpty()
        val subject = doc.selectFirst("table.stretch td")?.text()?.trim().orEmpty()
        return MessageDetail(subject.ifEmpty { "Message" }, content = content)
    }

    private data class SubstitutionDetails(
        val subject: String = "",
        val originalSubject: String = "",
        val teacher: String = "",
        val originalTeacher: String = "",
        val classroom: String = ""
    )

    private fun fetchTimetableSubstitutions(dateFrom: String, dateTo: String): Map<String, SubstitutionDetails> {
        val html = runCatching {
            portalHtml(
                "/przegladaj_plan_lekcji",
                method = "POST",
                body = formBody(mapOf("tydzien" to "${dateFrom}_${dateTo}"))
            )
        }.getOrElse {
            Log.w(LOG_TAG, "Could not load timetable substitution details")
            return emptyMap()
        }
        val details = Jsoup.parse(html).select("[data-date][data-time_from]").mapNotNull { cell ->
            val title = cell.selectFirst("a[title]")?.attr("title").orEmpty()
            val info = cell.selectFirst(".plan-lekcji-info")?.text().orEmpty()
            val (subject, originalSubject) = substitutionParts(substitutionValue(title, "Przedmiot"))
            val (teacherRaw, originalTeacherRaw) = substitutionParts(substitutionValue(title, "Nauczyciel"))
            val (classroom, _) = substitutionParts(substitutionValue(title, "Sala"))
            val teacher = normalizePortalTeacher(teacherRaw)
            val originalTeacher = normalizePortalTeacher(originalTeacherRaw)
            if (!info.contains("zastęp", ignoreCase = true) &&
                subject.isBlank() && teacher.isBlank() && classroom.isBlank()
            ) return@mapNotNull null
            val date = cell.attr("data-date").trim()
            val start = cell.attr("data-time_from").trim()
            if (date.isBlank() || start.isBlank()) return@mapNotNull null
            "$date-$start" to SubstitutionDetails(
                subject = subject,
                originalSubject = originalSubject,
                teacher = teacher,
                originalTeacher = originalTeacher,
                classroom = classroom
            )
        }.toMap()
        Log.d(LOG_TAG, "Timetable substitution details parsed: entries=${details.size}")
        return details
    }

    private fun substitutionValue(title: String, key: String): String = title
        .replace("<b>", "", ignoreCase = true)
        .replace("</b>", "", ignoreCase = true)
        .replace("&nbsp;", " ", ignoreCase = true)
        .split(Regex("<br\\s*/?>|\\r?\\n|\\|"))
        .firstOrNull { it.trimStart().startsWith("$key:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        .orEmpty()

    private fun substitutionParts(value: String): Pair<String, String> {
        val parts = value
            .split(Regex("\\s*(?:->|→)\\s*"))
            .map(String::trim)
            .filter(String::isNotBlank)
        return when {
            parts.size >= 2 -> parts.first() to parts.last()
            parts.size == 1 -> parts.first() to ""
            else -> "" to ""
        }
    }

    private fun normalizePortalTeacher(value: String): String {
        val parts = value.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return if (parts.size > 1) {
            parts.drop(1).joinToString(" ") + " " + parts.first()
        } else {
            value.trim()
        }
    }

    private fun apiJson(path: String): JsonObject {
        val response = request(apiBase + path, headers = mapOf("Accept" to "application/json"))
        if (isLoginRedirect(response)) throw LibrusClientError(LibrusErrorKind.SESSION_EXPIRED, "Your Librus session expired. LibreCap will try to sign in again.")
        if (response.code != 200) throw responseError(response, "loading $path")
        return parseObject(response.bytes, path)
    }

    private fun portalHtml(path: String, method: String = "GET", body: String? = null, extraHeaders: Map<String, String> = emptyMap()): String {
        val response = request(
            portalBase + path,
            method = method,
            body = body,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8") + extraHeaders
        )
        if (isLoginRedirect(response)) throw LibrusClientError(LibrusErrorKind.SESSION_EXPIRED, "Your Librus session expired. LibreCap will try to sign in again.")
        if (response.code != 200) throw responseError(response, "loading $path")
        return response.bytes.toString(Charsets.UTF_8)
    }

    private fun request(url: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap()): HttpResult {
        val attempts = when (method) {
            "GET" -> 3
            "POST" -> 2
            else -> 1
        }
        repeat(attempts) { attempt ->
            try {
                val builder = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                headers.forEach { (key, value) -> builder.header(key, value) }
                if (method == "POST") builder.post((body ?: "").toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                val response = http.newCall(builder.build()).execute()
                response.use {
                    val result = HttpResult(it.code, it.request.url.toString(), it.header("Content-Type").orEmpty(), it.body?.bytes() ?: ByteArray(0))
                    Log.d(LOG_TAG, "${method} ${endpoint(url)} -> ${result.code} ${endpoint(result.finalUrl)} ${result.contentType.substringBefore(';')}")
                    if (result.code < 500 || attempt + 1 >= attempts) return result
                    Log.w(LOG_TAG, "Retrying server error for " + method + " " + endpoint(url))
                }
            } catch (_: Exception) {
                Log.w(LOG_TAG, "Request failed: ${method} ${endpoint(url)} (attempt ${attempt + 1}/${attempts})")
                if (attempt + 1 < attempts) runCatching { Thread.sleep(250L * (attempt + 1)) }
            }
        }
        throw LibrusClientError(LibrusErrorKind.UNAVAILABLE, "Librus is currently unavailable. Check your internet connection and try again.")
    }

    private fun authorizationUrl(value: String): String {
        val url = try {
            URI("https://api.librus.pl/").resolve(value)
        } catch (_: Exception) {
            throw LibrusClientError(LibrusErrorKind.LOGIN_FLOW, "LibreCap could not complete the Librus login flow. Try again in a moment.")
        }
        val validPath = url.path == "/OAuth/Authorization" || url.path.startsWith("/OAuth/Authorization/")
        if (url.scheme != "https" || url.host != oauthHost || !validPath || url.userInfo != null || url.port !in listOf(-1, 443) || url.query?.contains("error", ignoreCase = true) == true) {
            throw LibrusClientError(LibrusErrorKind.LOGIN_FLOW, "LibreCap could not complete the Librus login flow. Try again in a moment.")
        }
        return url.toString()
    }

    private fun formBody(values: Map<String, String>): String = values.toSortedMap().entries.joinToString("&") {
        "${encode(it.key)}=${encode(it.value)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun parseObject(bytes: ByteArray, operation: String): JsonObject {
        val text = bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF").trim()
        if (text.isEmpty()) throw LibrusClientError(LibrusErrorKind.MALFORMED_DATA, "Librus returned an empty response while loading $operation. Try again.")
        return try {
            val parsed = JsonParser.parseString(text)
            if (!parsed.isJsonObject) throw IllegalStateException("Expected a JSON object")
            parsed.asJsonObject
        } catch (_: Exception) {
            throw LibrusClientError(LibrusErrorKind.MALFORMED_DATA, "Librus returned an unreadable response while loading $operation. Try again.")
        }
    }

    private fun isLoginRedirect(response: HttpResult): Boolean {
        val path = runCatching { URI(response.finalUrl).path.lowercase(Locale.ROOT) }.getOrDefault("")
        val body = response.bytesAsText().lowercase(Locale.ROOT)
        val isLoginForm = response.contentType.contains("text/html", ignoreCase = true) &&
            body.contains("type=\"password\"") &&
            (body.contains("name=\"login\"") || body.contains("name='login'"))
        return path.contains("/loguj") ||
            path.contains("/oauth/authorization") ||
            isLoginForm
    }

    private fun responseError(response: HttpResult, operation: String): LibrusClientError = when {
        response.code == 401 || response.code == 403 -> LibrusClientError(LibrusErrorKind.SESSION_EXPIRED, "Your Librus session expired. LibreCap will try to sign in again.")
        response.code == 429 -> LibrusClientError(LibrusErrorKind.UNAVAILABLE, "Librus is temporarily limiting requests. Wait a moment and try again.")
        response.code >= 500 -> LibrusClientError(LibrusErrorKind.UNAVAILABLE, "Librus is temporarily unavailable while $operation. Try again in a moment.")
        else -> LibrusClientError(LibrusErrorKind.UNEXPECTED_RESPONSE, "Librus returned an unexpected response while $operation. Try again.")
    }

    private fun endpoint(value: String): String = runCatching {
        val url = URI(value)
        val path = url.path
        val safePath = when {
            path.contains("/Auth/UserInfo/", ignoreCase = true) ->
                path.substringBefore("/Auth/UserInfo/") + "/Auth/UserInfo/<redacted>"
            path.contains("/wiadomosci/", ignoreCase = true) ->
                path.substringBefore("/wiadomosci/") + "/wiadomosci/<redacted>"
            else -> path
        }
        "${url.host}${safePath}"
    }.getOrDefault("unknown")

    private fun userMap(objectValue: JsonObject): Map<String, JsonObject> = objectValue.array("Users").associateBy { it.string("Id") }
    private fun subjectMap(objectValue: JsonObject): Map<String, String> = objectValue.array("Subjects")
        .mapNotNull { subject ->
            val id = subject.string("Id").trim()
            val name = subjectName(subject)
            if (id.isBlank() || name.isBlank()) null else id to name
        }
        .toMap()
    private fun subjectName(subject: JsonObject, fallback: String = ""): String = subject
        .firstString("Name", "FullName", "SubjectName", "ShortName", "Symbol", "Title")
        .takeUnless { it.equals("Subject", ignoreCase = true) }
        .orEmpty()
        .ifBlank { fallback }
    private fun gradeCategories(objectValue: JsonObject): Map<String, Pair<String, String>> = objectValue.array("Categories").associate { it.string("Id") to (it.string("Name", "Grade") to it.string("Weight", "none")) }
    private fun homeworkCategoryMap(objectValue: JsonObject): Map<String, String> = objectValue.array("Categories").associate { it.string("Id") to it.string("Name", "Homework") }
    private fun commentMap(objectValue: JsonObject): Map<String, String> = objectValue.array("Comments").associate { it.string("Id") to it.string("Text") }
    private fun lessonSubjectMap(objectValue: JsonObject): Map<String, String> = objectValue.array("Lessons").associate { it.string("Id") to it.obj("Subject").string("Id") }

    private data class AttendanceType(val name: String, val short: String, val isPresence: Boolean)
    private fun attendanceTypeMap(objectValue: JsonObject): Map<String, AttendanceType> = objectValue.array("Types").associate { it.string("Id") to AttendanceType(it.string("Name", "Attendance"), it.string("Short", "?"), it.bool("IsPresenceKind")) }
    private fun classroomMap(objectValue: JsonObject): Map<String, String> = objectValue.array("TimetableEntries").mapNotNull { entry ->
        val classroom = entry.obj("Classroom")
        classroom.string("Id").takeIf(String::isNotEmpty)?.let { it to classroom.string("Symbol", classroom.string("Name", "—")) }
    }.toMap()
    private fun JsonObject.firstString(vararg keys: String): String = keys.asSequence()
        .map { string(it).trim() }
        .firstOrNull(String::isNotEmpty)
        ?: ""
    private fun JsonObject.firstNestedString(vararg keys: String, field: String): String = keys.asSequence()
        .map { obj(it).string(field).trim() }
        .firstOrNull(String::isNotEmpty)
        ?: ""
    private fun JsonObject.firstNestedTeacherName(vararg keys: String): String = keys.asSequence()
        .map { teacherName(obj(it)).trim() }
        .firstOrNull(String::isNotEmpty)
        ?: ""
    private fun teacherName(teacher: JsonObject): String = listOf(teacher.string("FirstName"), teacher.string("LastName")).filter(String::isNotEmpty).joinToString(" ")
    private fun parseDate(value: String): LocalDate? = try { LocalDate.parse(value.take(10), DATE_FORMAT) } catch (_: DateTimeParseException) { null }
    private fun messageId(href: String): String = href.substringAfter("wiadomosci/", "").substringBefore('?').trim('/').replace('/', '-')

    private data class HttpResult(val code: Int, val finalUrl: String, val contentType: String, val bytes: ByteArray) {
        fun bytesAsText(): String = bytes.toString(Charsets.UTF_8)
    }
    private class InMemoryCookieJar : CookieJar {
        private val values = mutableListOf<Cookie>()
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(values) { values.filter { it.matches(url) } }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(values) {
            cookies.forEach { cookie ->
                values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (!cookie.expiresAt.let { it < System.currentTimeMillis() }) values.add(cookie)
            }
        }
    }

    companion object {
        private const val LOG_TAG = "LibreCapNetwork"
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}

private fun JsonObject.obj(key: String): JsonObject = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
private fun JsonObject.array(key: String): List<JsonObject> = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject } ?: emptyList()
private fun JsonObject.string(key: String, fallback: String = ""): String = get(key)?.let { value ->
    when {
        value.isJsonNull -> fallback
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
        value.isJsonPrimitive -> value.toString().trim('"')
        else -> fallback
    }
} ?: fallback
private fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

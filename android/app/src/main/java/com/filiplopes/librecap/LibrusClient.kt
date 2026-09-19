package com.filiplopes.librecap

import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.time.DayOfWeek
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
    private val messagesApiBase = "https://wiadomosci.librus.pl/api/"
    private val oauthHost = "api.librus.pl"
    private val oauthAuthorizationUrl = "https://api.librus.pl/OAuth/Authorization?client_id=46"
    private val oauthAuthorizationGrantUrl = "https://api.librus.pl/OAuth/Authorization/Grant?client_id=46"
    private val oauthAuthorizationWithScopeUrl = "$oauthAuthorizationUrl&response_type=code&scope=mydata"
    private val cookies = InMemoryCookieJar()
    private val http = OkHttpClient.Builder()
        .cookieJar(cookies)
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val messagesBootstrapHttp = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val attachmentNoCookiesHttp = http.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val attachmentHeaders = mapOf(
        "Accept" to "*/*",
        "Origin" to "https://api.librus.pl",
        "Referer" to oauthAuthorizationUrl,
        "X-Requested-With" to "XMLHttpRequest"
    )
    private var messagesApiInitialized = false
    private val oauthHttp = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    suspend fun login(username: String, password: String): StudentProfile {
        establishSession(username, password)
        return fetchProfile()
    }

    suspend fun establishSession(username: String, password: String) {
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
            ),
            client = oauthHttp
        )
        if (portal.code !in 200..399 || portal.location.isBlank()) throw responseError(portal, "starting the Librus login flow")
        val authUrl = authorizationUrl(portal.location)

        val authorization = request(authUrl, client = oauthHttp)
        if (authorization.code !in 200..399) throw responseError(authorization, "starting the Librus login flow")

        val loginResponse = request(
            oauthAuthorizationGrantUrl,
            method = "POST",
            body = formBody(mapOf("action" to "login", "login" to loginName, "pass" to password)),
            headers = mapOf(
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9,pl;q=0.8",
                "Cache-Control" to "no-cache",
                "Origin" to "https://api.librus.pl",
                "Pragma" to "no-cache",
                "Referer" to oauthAuthorizationGrantUrl,
                "Content-Type" to "application/x-www-form-urlencoded",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            client = oauthHttp
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
        var nextUrl = authorizationUrl(loginJson.string("goTo"), base = oauthAuthorizationWithScopeUrl)
        var continuation: HttpResult? = null
        for (redirect in 0 until 10) {
            val response = request(nextUrl, client = oauthHttp)
            if (response.code !in 200..399) throw responseError(response, "completing sign-in")
            continuation = response
            val location = response.location
            if (location.isBlank()) break
            nextUrl = safeOAuthRedirectUrl(location, response.finalUrl)
        }
        if (continuation == null || !cookies.hasCookie("oauth_token", URI(portalBase).host)) {
            throw LibrusClientError(LibrusErrorKind.LOGIN_FLOW, "Librus returned an incomplete login session. Try signing in again.")
        }

        val tokenInfo = apiJson("Auth/TokenInfo")
        val identifier = tokenInfo.string("UserIdentifier")
        if (identifier.isEmpty()) throw LibrusClientError(LibrusErrorKind.MALFORMED_DATA, "Librus returned an incomplete login session. Try signing in again.")
        val access = request("$apiBase/Auth/UserInfo/$identifier")
        if (access.code != 200) throw responseError(access, "authorizing access to your school data")
    }
    suspend fun fetchProfile(): StudentProfile = coroutineScope {
        val meRequest = async(Dispatchers.IO) { apiJson("Me") }
        val userProfileRequest = async(Dispatchers.IO) { apiJson("UserProfile") }
        val usersRequest = async(Dispatchers.IO) { apiJson("Users") }
        val classesRequest = async(Dispatchers.IO) { apiJson("Classes") }
        val me = meRequest.await()
        val userProfile = userProfileRequest.await()
        val users = usersRequest.await()
        val classes = classesRequest.await()

        val account = me.obj("Me").obj("Account")
        val schoolClass = classes.obj("Class")
        val tutorId = schoolClass.obj("ClassTutor").string("Id")
        val tutor = userMap(users)[tutorId] ?: JsonObject()
        val number = schoolClass.string("Number")
        val symbol = schoolClass.string("Symbol").uppercase(Locale.getDefault())
        val className = listOf(number, symbol).filter(String::isNotEmpty).joinToString(" ")
        StudentProfile(
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

    fun fetchTimetable(requestedWeekStart: LocalDate = LocalDate.now().with(DayOfWeek.MONDAY)): TimetableData {
        val today = LocalDate.now()
        val weekStart = requestedWeekStart.with(DayOfWeek.MONDAY)
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
                        originalTeacher = resolvedOriginalTeacher,
                        date = dateKey.take(10)
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
                    classroom = item.obj("classroom").string("symbol", "—"),
                    date = item.string("date").take(10)
                )
            )
        }
        return TimetableData(
            nextWeek = weekStart.isAfter(today.with(DayOfWeek.MONDAY)),
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
                content = raw.string("Content"),
                lessonNumber = raw.string("LessonNo")
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
        val announcements = runCatching { fetchAnnouncements() }
            .onFailure { Log.w(LOG_TAG, "Could not load school notices", it) }
            .getOrDefault(emptyList())
        return messages + announcements + notes
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

    fun fetchMessage(id: String, folder: MessageFolder = MessageFolder.INBOX): MessageDetail {
        if (folder == MessageFolder.INBOX || folder == MessageFolder.SENT) {
            runCatching {
                initializeMessagesApi()
                val box = if (folder == MessageFolder.SENT) "outbox" else "inbox"
                val responses = messageApiResponses(box, id)
                val response = responses.firstOrNull { it.code in 200..299 } ?: responses.last()
                if (isLoginRedirect(response)) throw LibrusClientError(LibrusErrorKind.SESSION_EXPIRED, "Your Librus session expired. LibreCap will try to sign in again.")
                if (response.code !in 200..299) throw responseError(response, "loading message")
                val raw = parseObject(response.bytes, "message")
                val data = raw.obj("data").takeIf { it.size() > 0 } ?: raw
                val remoteMessageId = data.firstString("messageId", "MessageId", "id", "Id").ifBlank { apiMessageId(id) }
                val content = decodeMessageContent(data.firstString("Message", "message", "Content", "content"))
                val attachments = data.firstArray("attachments", "Attachments").mapNotNull { attachment ->
                    val attachmentId = attachment.firstString("id", "Id").trim()
                    val name = attachment.firstString("name", "Name", "filename", "FileName").trim()
                    if (attachmentId.isBlank() || name.isBlank()) null else messageAttachment(
                        id = attachmentId,
                        name = name,
                        size = attachment.firstString("size", "Size").toLongOrNull(),
                        mimeType = mimeTypeFor(name),
                        messageId = remoteMessageId
                    )
                }
                MessageDetail(
                    subject = data.firstString("topic", "Topic", "subject", "Subject").ifBlank { "Message" },
                    sender = data.firstString("senderName", "SenderName", "sender", "Sender"),
                    date = data.firstString("sendDate", "SendDate", "date", "Date"),
                    content = content,
                    attachments = attachments
                )
            }.onSuccess { detail ->
                Log.d(LOG_TAG, "Message loaded from API: attachments=${detail.attachments.size}")
            }.getOrNull()?.let { return it }
        }

        val html = portalHtml("/wiadomosci/${id.replace('-', '/')}")
        val doc = Jsoup.parse(html)
        val content = doc.selectFirst(".container-message-content")?.text()?.trim().orEmpty()
        val subject = doc.selectFirst("table.stretch td")?.text()?.trim().orEmpty()
        return MessageDetail(
            subject.ifEmpty { "Message" },
            content = content,
            attachments = parsePortalMessageAttachments(doc)
        )
    }

    fun fetchMessageAttachment(messageId: String, folder: MessageFolder, attachment: MessageAttachment): ByteArray {
        if (folder != MessageFolder.INBOX && folder != MessageFolder.SENT) {
            throw LibrusClientError(LibrusErrorKind.MALFORMED_DATA, "This message does not have downloadable attachments.")
        }
        runCatching {
            initializeMessagesApi()
            val responses = messageApiAttachmentResponses(attachment.messageId.ifBlank { messageId }, attachment)
            val response = responses.firstOrNull { it.code in 200..299 } ?: responses.last()
            if (isLoginRedirect(response)) throw LibrusClientError(LibrusErrorKind.SESSION_EXPIRED, "Your Librus session expired. LibreCap will try to sign in again.")
            if (response.code !in 200..299) throw responseError(response, "downloading attachment")
            return resolveAttachmentDownload(response)
        }.getOrElse { apiError ->
            runCatching { fetchPortalMessageAttachment(messageId, attachment) }
                .getOrNull()
                ?.let { return it }
            if (attachment.url.isBlank()) throw apiError
            val response = request(attachment.url, headers = mapOf("Accept" to "*/*"))
            if (response.code !in 200..299 || response.bytes.isEmpty()) throw responseError(response, "downloading attachment")
            return response.bytes
        }
    }

    private fun fetchPortalMessageAttachment(messageId: String, attachment: MessageAttachment): ByteArray {
        val html = portalHtml("/wiadomosci/${messageId.replace('-', '/')}")
        val document = Jsoup.parse(html)
        val niceSources = portalAttachmentNiceValues(document, attachment)
            .map { "$portalBase/wiadomosci/pobierz_zalacznik/$it" }
        val exactSources = document.select("[onclick]").asSequence()
            .mapNotNull { element -> extractPortalAttachmentSource(element.attr("onclick")) }
            .distinct()
            .toList()
        val genericSources = (document.select("[onclick]").asSequence().map { it.attr("onclick") } +
            document.select("a[href]").asSequence().map { it.attr("href") })
            .mapNotNull { extractPortalAttachmentUrl(it) }
            .toList()
        val sources = (exactSources + niceSources + genericSources).toMutableList()
        sources += "$portalBase/wiadomosci/pobierz_zalacznik/${encode(attachment.id)}"
        var lastError: Throwable? = null
        for (source in sources.distinct()) {
            runCatching { resolvePortalAttachmentDownload(source) }
                .onSuccess { return it }
                .onFailure { lastError = it }
        }
        throw lastError ?: LibrusClientError(LibrusErrorKind.UNEXPECTED_RESPONSE, "Librus did not return a download link for this attachment.")
    }

    private fun extractPortalAttachmentSource(value: String): String? {
        val normalized = value
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\", "")
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'")
        val path = Regex("(?i)/[^\"'\\s,)]+pobierz[^\"'\\s,)]+")
            .find(normalized)
            ?.value
        if (!path.isNullOrBlank()) {
            val base = if (path.contains("GetFile/", ignoreCase = true)) "https://sandbox.librus.pl" else portalBase
            return base + path
        }
        val argument = Regex("(?i)otworz\\((?:\"|')([^\"']+)(?:\"|')")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: return null
        if (!argument.contains("pobierz", ignoreCase = true) && !argument.contains("GetFile", ignoreCase = true)) return null
        if (argument.startsWith("http", ignoreCase = true)) return argument
        if (!argument.startsWith('/')) return null
        val base = if (argument.contains("GetFile/", ignoreCase = true)) "https://sandbox.librus.pl" else portalBase
        return base + argument
    }

    private fun portalAttachmentNiceValues(document: org.jsoup.nodes.Document, attachment: MessageAttachment): List<String> {
        val rows = document.select("tr").asSequence()
        val matchingRows = rows.filter { row ->
            attachment.name.isNotBlank() && row.text().contains(attachment.name, ignoreCase = true)
        }.toList()
        val candidateRows = (matchingRows.asSequence() + document.select("tr").asSequence()).distinct()
        val rowValues = candidateRows
            .flatMap { row -> row.select("img[onclick], [onclick]").asSequence() }
            .mapNotNull { element -> extractPortalAttachmentNice(element.attr("onclick")) }
            .distinct()
            .toList()
        val allValues = document.select("[onclick]").asSequence()
            .mapNotNull { element -> extractPortalAttachmentNice(element.attr("onclick")) }
            .toList()
        return (rowValues + allValues).distinct()
    }

    private fun extractPortalAttachmentNice(value: String): String? {
        val normalized = value
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\", "")
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'")
            .replace(" ", "")
            .replace("\n", "")
        val callArgument = Regex("(?i)otworz\\((?:\"|')([^\"']+)(?:\"|')")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val candidate = callArgument.ifBlank { normalized }
        val pathToken = Regex("(?i)/pobierz_zalacznik/([^\"'\\s,)]+)")
            .find(candidate)
            ?.groupValues
            ?.getOrNull(1)
        if (!pathToken.isNullOrBlank()) return pathToken
        val marker = Regex("(?i)pobierz[^\\\"'\\s/(:=]*").find(candidate) ?: return null
        val afterMarker = candidate.substring(marker.range.last + 1)
        val token = when {
            afterMarker.startsWith("/") -> afterMarker.drop(1)
            else -> Regex("^[(/:=]+[\"']?([^\"'(),/]+)").find(afterMarker)?.groupValues?.getOrNull(1).orEmpty()
        }
        return token.takeWhile { it != '"' && it != '\'' && it != ')' && it != ',' }
            .takeIf { it.isNotBlank() }
    }

    private fun extractPortalAttachmentUrl(onClick: String): String? {
        val normalized = onClick
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'")
        val path = Regex("(?i)(?:https?://[^\\\"'\\s]+|/[^\\\"'\\s]+)(?:pobierz_zalacznik|GetFile)[^\\\"'\\s]*")
            .find(normalized)?.value ?: return null
        if (path.startsWith("http", ignoreCase = true)) return path
        val base = if (path.contains("GetFile/", ignoreCase = true)) "https://sandbox.librus.pl" else portalBase
        return base + if (path.startsWith('/')) path else "/$path"
    }

    private fun resolveSignedAttachmentRedirect(url: String): ByteArray? {
        val response = request(url, client = messagesBootstrapHttp, headers = attachmentHeaders)
        if (response.code !in 300..399) return null
        val location = response.location.trim()
        val target = runCatching { URI(response.finalUrl).resolve(location) }.getOrNull() ?: return null
        if (target.scheme != "https" || target.host != "sandbox.librus.pl" || !target.path.startsWith("/GetFile/", ignoreCase = true) || target.userInfo != null || target.port !in listOf(-1, 443)) return null
        val file = request(target.toString(), headers = attachmentHeaders, client = attachmentNoCookiesHttp)
        if (file.code !in 200..299 || file.bytes.isEmpty() || looksLikeHtml(file)) return null
        return file.bytes
    }

    private fun resolvePortalAttachmentDownload(source: String): ByteArray {
        resolveSignedAttachmentRedirect(source)?.let { return it }
        val redirect = request(source, headers = mapOf("Accept" to "*/*"))
        if (redirect.code !in 200..299) throw responseError(redirect, "opening attachment")
        if (redirect.bytes.isNotEmpty() && !looksLikeHtml(redirect)) return redirect.bytes
        var lastResponse = redirect
        val downloadUrls = attachmentHtmlUrls(redirect.bytes) + attachmentDownloadUrls(source, redirect.finalUrl)
        for (downloadUrl in downloadUrls.distinct()) {
            val file = runCatching { request(downloadUrl, headers = mapOf("Accept" to "*/*")) }.getOrNull() ?: continue
            lastResponse = file
            if (file.code in 200..299 && file.bytes.isNotEmpty() && !looksLikeHtml(file)) return file.bytes
        }
        throw responseError(lastResponse, "downloading attachment")
    }

    private fun initializeMessagesApi() {
        if (messagesApiInitialized) return
        var response = request(
            "$portalBase/wiadomosci3",
            headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
            client = messagesBootstrapHttp
        )
        var redirects = 0
        while (response.code in 300..399 && redirects < 4) {
            val target = messagesBootstrapRedirect(response.location, response.finalUrl)
                ?: throw LibrusClientError(LibrusErrorKind.UNEXPECTED_RESPONSE, "Librus returned an unsafe messages redirect.")
            response = request(
                target,
                headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
                client = messagesBootstrapHttp
            )
            redirects += 1
        }
        if (isLoginRedirect(response)) throw LibrusClientError(LibrusErrorKind.SESSION_EXPIRED, "Your Librus session expired. LibreCap will try to sign in again.")
        if (response.code !in 200..299) throw responseError(response, "opening messages")
        messagesApiInitialized = true
    }

    private fun messagesBootstrapRedirect(value: String, base: String): String? {
        val url = runCatching { URI(base).resolve(value) }.getOrNull() ?: return null
        val allowedHost = url.host == URI(portalBase).host || url.host == URI(messagesApiBase).host
        if (!allowedHost || url.userInfo != null || url.port !in listOf(-1, 443, 80)) return null
        val scheme = if (url.host == URI(messagesApiBase).host) "https" else url.scheme
        if (scheme != "https") return null
        return URI(scheme, url.userInfo, url.host, 443, url.path, url.query, url.fragment).toString()
    }

    private fun apiMessageId(id: String): String = id.substringAfterLast('-').ifBlank { id }

    private fun apiMessageIdCandidates(id: String): List<String> {
        val parts = id.split('-').filter(String::isNotBlank)
        val numericParts = parts.filter { part -> part.length >= 3 && part.all(Char::isDigit) }.reversed()
        return listOf(apiMessageId(id)) + numericParts + listOf(id, id.filter(Char::isDigit))
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun messageApiResponses(box: String, id: String): List<HttpResult> = apiMessageIdCandidates(id).map { candidate ->
        request(
            "$messagesApiBase$box/messages/${encode(candidate)}",
            headers = mapOf("Accept" to "application/json")
        )
    }

    private fun messageApiAttachmentResponses(messageId: String, attachment: MessageAttachment): List<HttpResult> =
        apiMessageIdCandidates(messageId).map { candidate ->
            request(
                "${messagesApiBase}attachments/${encode(attachment.id)}/messages/${encode(candidate)}",
                headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to "https://api.librus.pl",
                    "Referer" to oauthAuthorizationUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
        }

    private fun resolveAttachmentDownload(response: HttpResult): ByteArray {
        if (!response.contentType.contains("json", ignoreCase = true)) {
            if (response.bytes.isEmpty()) throw responseError(response, "downloading attachment")
            return response.bytes
        }
        val raw = parseObject(response.bytes, "attachment")
        val data = raw.obj("data").takeIf { it.size() > 0 } ?: raw
        val link = data.firstString("downloadLink", "DownloadLink", "url", "Url").trim()
        val url = runCatching { URI(link) }.getOrNull()
        if (url?.scheme != "https" || url.host.isNullOrBlank()) {
            throw LibrusClientError(LibrusErrorKind.UNEXPECTED_RESPONSE, "Librus returned an invalid attachment download link.")
        }
        resolveSignedAttachmentRedirect(link)?.let { return it }
        val download = request(link, headers = attachmentHeaders)
        if (download.code !in 200..299) throw responseError(download, "downloading attachment")
        if (download.bytes.isNotEmpty() && !looksLikeHtml(download)) return download.bytes

        val downloadUrls = attachmentHtmlUrls(download.bytes) + attachmentDownloadUrls(link, download.finalUrl)
        var lastResponse = download
        for (downloadUrl in downloadUrls) {
            val file = runCatching { request(downloadUrl, headers = mapOf("Accept" to "*/*")) }.getOrNull() ?: continue
            lastResponse = file
            if (file.code in 200..299 && file.bytes.isNotEmpty() && !looksLikeHtml(file)) return file.bytes
        }
        throw responseError(lastResponse, "downloading attachment")
    }

    private fun attachmentDownloadUrls(link: String, finalUrl: String): List<String> {
        return listOf(link, finalUrl).flatMap { value ->
            runCatching {
                val base = URI(value)
                val path = base.path.trimEnd('/')
                listOf(
                    URI(base.scheme, base.userInfo, base.host, base.port, "$path/get", base.query, base.fragment).toString(),
                    URI(base.scheme, base.userInfo, base.host, base.port, "/get", base.query, base.fragment).toString()
                )
            }.getOrElse { emptyList() }
        }.filter { it.startsWith("https://") }.distinct()
    }

    private fun attachmentHtmlUrls(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val html = bytes.toString(Charsets.UTF_8)
        val document = Jsoup.parse(html)
        val values = document.select("a[href], [onclick]").flatMap { element ->
            listOf(element.attr("href"), element.attr("onclick"))
        } + html
        return values.mapNotNull { extractPortalAttachmentUrl(it) }.distinct()
    }

    private fun looksLikeHtml(response: HttpResult): Boolean {
        val prefix = response.bytesAsText().trimStart().take(128).lowercase(Locale.ROOT)
        if (prefix.startsWith("%pdf-")) return false
        return response.contentType.contains("text/html", ignoreCase = true) ||
            prefix.startsWith("<!doctype html") || prefix.startsWith("<html")
    }

    private fun decodeMessageContent(value: String): String {
        if (value.isBlank()) return ""
        val candidate = value.trim()
        if (candidate.length % 4 == 0 && candidate.matches(Regex("[A-Za-z0-9+/=\\r\\n]+"))) {
            runCatching { Base64.decode(candidate, Base64.DEFAULT).toString(Charsets.UTF_8) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return Jsoup.parse(it).text().trim() }
        }
        return Jsoup.parse(candidate).text().trim()
    }

    private fun messageAttachment(id: String, name: String, size: Long?, mimeType: String, url: String = "", messageId: String = "") =
        MessageAttachment(id = id, name = name, size = size, url = url, mimeType = mimeType, messageId = messageId)

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun parsePortalMessageAttachments(document: org.jsoup.nodes.Document): List<MessageAttachment> {
        return document.select("a[href]").mapNotNull { link ->
            val href = link.attr("href").trim()
            val label = link.text().trim().ifBlank { link.attr("title").trim() }
            val lower = "$href $label".lowercase(Locale.ROOT)
            val looksLikeAttachment = listOf("załącz", "zalacz", "attachment", "plik", "file").any(lower::contains) ||
                Regex("\\.(pdf|docx?|xlsx?|pptx?|jpe?g|png|zip|rar)(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(href)
            val path = portalPath(href)
            if (!looksLikeAttachment || path == null) null else {
                val name = label.ifBlank { path.substringAfterLast('/').substringBefore('?') }.ifBlank { "attachment" }
                messageAttachment(
                    id = path.hashCode().toString(),
                    name = name,
                    size = null,
                    url = portalBase + path,
                    mimeType = mimeTypeFor(name)
                )
            }
        }.distinctBy { it.url.ifBlank { it.id } }
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

    private fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        client: OkHttpClient = http,
    ): HttpResult {
        val attempts = when (method) {
            "GET" -> 3
            "POST" -> 2
            else -> 1
        }
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                val builder = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                headers.forEach { (key, value) -> builder.header(key, value) }
                if (method == "POST") builder.post((body ?: "").toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
                waitForRequestSlot()
                val response = client.newCall(builder.build()).execute()
                response.use {
                    val result = HttpResult(it.code, it.request.url.toString(), it.header("Content-Type").orEmpty(), it.header("Location").orEmpty(), it.body?.bytes() ?: ByteArray(0))
                    Log.d(LOG_TAG, "${method} ${endpoint(url)} -> ${result.code} ${endpoint(result.finalUrl)} ${result.contentType.substringBefore(';')}")
                    val retryable = result.code == 429 || result.code >= 500
                    if (!retryable || attempt + 1 >= attempts) return result
                    Log.w(LOG_TAG, "Retrying server response ${result.code} for " + method + " " + endpoint(url))
                    Thread.sleep(if (result.code == 429) 5_000L * (attempt + 1) else 1_000L * (attempt + 1))
                }
            } catch (error: Exception) {
                lastError = error
                Log.w(LOG_TAG, "Request failed: ${method} ${endpoint(url)} (attempt ${attempt + 1}/${attempts})", error)
                if (attempt + 1 < attempts) runCatching { Thread.sleep(250L * (attempt + 1)) }
            }
        }
        throw lastError ?: LibrusClientError(LibrusErrorKind.UNAVAILABLE, "Librus is currently unavailable. Check your internet connection and try again.")
    }

    private fun authorizationUrl(value: String, base: String = "https://api.librus.pl/"): String {
        val url = try {
            URI(base).resolve(value)
        } catch (_: Exception) {
            throw LibrusClientError(LibrusErrorKind.LOGIN_FLOW, "LibreCap could not complete the Librus login flow. Try again in a moment.")
        }
        val validPath = url.path == "/OAuth/Authorization" || url.path.startsWith("/OAuth/Authorization/")
        if (url.scheme != "https" || url.host != oauthHost || !validPath || url.userInfo != null || url.port !in listOf(-1, 443) || url.query?.contains("error", ignoreCase = true) == true) {
            throw LibrusClientError(LibrusErrorKind.LOGIN_FLOW, "LibreCap could not complete the Librus login flow. Try again in a moment.")
        }
        return url.toString()
    }

    private fun safeOAuthRedirectUrl(value: String, base: String): String {
        val url = try {
            URI(base).resolve(value)
        } catch (_: Exception) {
            throw LibrusClientError(LibrusErrorKind.LOGIN_FLOW, "Librus returned an invalid login redirect. Try signing in again.")
        }
        val isApi = url.host == oauthHost && url.path.startsWith("/OAuth/")
        val isPortal = url.host == URI(portalBase).host
        if (url.scheme != "https" || (!isApi && !isPortal) || url.userInfo != null || url.port !in listOf(-1, 443)) {
            throw LibrusClientError(LibrusErrorKind.LOGIN_FLOW, "Librus returned an unsafe login redirect. Try signing in again.")
        }
        return url.toString()
    }

    private fun waitForRequestSlot() {
        synchronized(requestPacingLock) {
            val delay = nextRequestAt - System.currentTimeMillis()
            if (delay > 0) Thread.sleep(delay)
            nextRequestAt = System.currentTimeMillis() + REQUEST_INTERVAL_MS
        }
    }

    private fun formBody(values: Map<String, String>): String = values.toSortedMap().entries.joinToString("&") {
        "${encode(it.key)}=${encode(it.value)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

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
        response.bytesAsText().contains("The URL you requested has been blocked", ignoreCase = true) ->
            LibrusClientError(LibrusErrorKind.UNAVAILABLE, "Librus blocked the sign-in request on its security gateway. Try again later or switch network.")
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

    private data class HttpResult(val code: Int, val finalUrl: String, val contentType: String, val location: String, val bytes: ByteArray) {
        fun bytesAsText(): String = bytes.toString(Charsets.UTF_8)
    }
    private class InMemoryCookieJar : CookieJar {
        private val values = mutableListOf<Cookie>()
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(values) {
            values.filter { it.matches(url) }
        }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(values) {
            cookies.forEach { cookie ->
                values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (!cookie.expiresAt.let { it < System.currentTimeMillis() }) values.add(cookie)
            }
        }
        fun hasCookie(name: String, host: String): Boolean = synchronized(values) {
            val url = HttpUrl.Builder().scheme("https").host(host).build()
            values.any { it.name == name && it.matches(url) }
        }
    }

    companion object {
        private const val LOG_TAG = "LibreCapNetwork"
        private const val REQUEST_INTERVAL_MS = 180L
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private val requestPacingLock = Any()
        private var nextRequestAt = 0L
    }
}

private fun JsonObject.obj(key: String): JsonObject = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
private fun JsonObject.array(key: String): List<JsonObject> = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject } ?: emptyList()
private fun JsonObject.firstArray(vararg keys: String): List<JsonObject> = keys.asSequence().map(::array).firstOrNull { it.isNotEmpty() } ?: emptyList()
private fun JsonObject.string(key: String, fallback: String = ""): String = get(key)?.let { value ->
    when {
        value.isJsonNull -> fallback
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
        value.isJsonPrimitive -> value.toString().trim('"')
        else -> fallback
    }
} ?: fallback
private fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

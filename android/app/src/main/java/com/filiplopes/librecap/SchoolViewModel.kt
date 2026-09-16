package com.filiplopes.librecap

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant

data class SchoolUiState(
    val ready: Boolean = false,
    val authenticated: Boolean = false,
    val syncing: Boolean = false,
    val username: String = "",
    val data: CachedSchoolData = CachedSchoolData(),
    val notes: List<SchoolNote> = emptyList(),
    val error: String? = null,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val appearance: AppAppearance = AppAppearance.SYSTEM,
    val automaticSync: Boolean = true,
    val availableUpdate: AppRelease? = null,
    val checkingForUpdates: Boolean = false,
    val messageRecipients: List<MessageRecipient> = emptyList(),
    val loadingMessageRecipients: Boolean = false,
    val sendingMessage: Boolean = false,
    val messageActionError: String? = null,
    val messageActionSuccess: Boolean = false
)

class SchoolViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val localStore = LocalStore(context)
    private val credentials = CredentialStore(context)
    private val releaseChecker = GitHubReleaseChecker()
    private val currentAppVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    private val preferences = context.getSharedPreferences("settings", 0)
    private var client: LibrusClient? = null
    private var syncJob: Job? = null
    private var automaticJob: Job? = null
    private var generation = 0

    private companion object {
        const val AUTH_TIMEOUT_MS = 20_000L
        const val INITIAL_SYNC_TIMEOUT_MS = 30_000L
    }

    var state = androidx.compose.runtime.mutableStateOf(loadInitial())
        private set
    var currentMessage = androidx.compose.runtime.mutableStateOf<MessageDetail?>(null)
        private set

    init {
        checkForUpdates()
        val saved = credentials.load()
        if (saved != null) {
            state.value = state.value.copy(
                username = saved.username,
                ready = false,
                authenticated = state.value.data.profile != null,
                syncing = true
            )
            val currentGeneration = generation
            syncJob = viewModelScope.launch { restore(saved, currentGeneration) }
        } else {
            state.value = state.value.copy(ready = true)
        }
        startAutomaticSync()
    }

    fun checkForUpdates() {
        if (state.value.checkingForUpdates) return
        update { it.copy(checkingForUpdates = true) }
        viewModelScope.launch {
            val release = runCatching { releaseChecker.fetchLatest() }.getOrNull()
            val available = release?.takeIf { GitHubReleaseChecker.isNewer(it.tagName, currentAppVersion) }
            update { it.copy(availableUpdate = available, checkingForUpdates = false) }
        }
    }

    fun login(username: String, password: String) {
        val trimmed = username.trim()
        generation += 1
        val currentGeneration = generation
        client = null
        syncJob?.cancel()
        update { it.copy(syncing = true, error = null) }
        syncJob = viewModelScope.launch {
            try {
                val (result, profile) = authenticate(trimmed, password)
                if (currentGeneration != generation) return@launch
                if (!credentials.save(StoredCredentials(trimmed, password))) {
                    throw LibrusClientError(
                        LibrusErrorKind.LOCAL_STORAGE,
                        "Sign-in worked, but LibreCap could not securely save your login on this device. Check storage and try again."
                    )
                }
                client = result
                val fresh = CachedSchoolData(profile = profile)
                update { it.copy(ready = false, authenticated = true, syncing = true, username = trimmed, data = fresh) }
                initialSync(currentGeneration, profile)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentGeneration == generation) update { it.copy(ready = true, syncing = false, error = error.message ?: "Sign-in failed.") }
            }
        }
    }

    fun sync() {
        if (state.value.syncing) return
        if (client == null) {
            retry()
            return
        }
        val currentGeneration = generation
        syncJob?.cancel()
        syncJob = viewModelScope.launch { syncInternal(currentGeneration) }
    }

    fun retry() {
        if (state.value.syncing) return
        val currentGeneration = generation
        if (client != null) {
            syncJob?.cancel()
            syncJob = viewModelScope.launch { syncInternal(currentGeneration) }
            return
        }
        val saved = credentials.load() ?: return
        update { it.copy(username = saved.username, ready = false, syncing = true, error = null) }
        syncJob?.cancel()
        syncJob = viewModelScope.launch { restore(saved, currentGeneration) }
    }

    private suspend fun initialSync(currentGeneration: Int, profile: StudentProfile? = null) {
        val completed = withTimeoutOrNull(INITIAL_SYNC_TIMEOUT_MS) {
            syncInternal(currentGeneration, initialProfile = profile)
            true
        } ?: false
        if (!completed && currentGeneration == generation) {
            update {
                it.copy(
                    ready = true,
                    syncing = false,
                    error = "Librus is taking too long. Showing saved data. Try again later."
                )
            }
        }
    }

    private suspend fun authenticate(username: String, password: String): Pair<LibrusClient, StudentProfile> =
        withTimeoutOrNull(AUTH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                LibrusClient().let { newClient -> newClient to newClient.login(username, password) }
            }
        } ?: throw LibrusClientError(
            LibrusErrorKind.UNAVAILABLE,
            "Librus took too long to respond while signing in. Check your connection and try again."
        )

    private suspend fun syncInternal(
        currentGeneration: Int,
        allowSessionRecovery: Boolean = true,
        initialProfile: StudentProfile? = null
    ) {
        val activeClient = client ?: return
        update { it.copy(syncing = true, error = null) }
        var refreshed = state.value.data
        var firstError: Exception? = null
        var sessionExpired = false
        suspend fun <T> request(block: suspend () -> T): Result<T> {
            try {
                return Result.success(withContext(Dispatchers.IO) { block() })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return Result.failure(error)
            }
        }
        fun <T> applyResult(result: Result<T>, apply: (T) -> Unit) {
            result.onSuccess(apply).onFailure { error ->
                if (error.isSessionExpired()) sessionExpired = true
                if (firstError == null) firstError = error as? Exception ?: Exception(error)
            }
        }
        data class SyncResults(
            val profile: Result<StudentProfile>,
            val grades: Result<List<GradeRecord>>,
            val timetable: Result<TimetableData>,
            val attendances: Result<List<AttendanceRecord>>,
            val homeworks: Result<List<HomeworkRecord>>,
            val luckyNumber: Result<Int?>,
            val messages: Result<List<MessageSummary>>
        )
        val results = coroutineScope {
            val profile = async {
                if (initialProfile != null) Result.success(initialProfile)
                else request { activeClient.fetchProfile() }
            }
            val grades = async { request { activeClient.fetchGrades() } }
            val timetable = async { request { activeClient.fetchTimetable() } }
            val attendances = async { request { activeClient.fetchAttendances() } }
            val homeworks = async { request { activeClient.fetchHomeworks() } }
            val luckyNumber = async { request { activeClient.fetchLuckyNumber() } }
            val messages = async { request { activeClient.fetchMessages() } }
            SyncResults(
                profile = profile.await(),
                grades = grades.await(),
                timetable = timetable.await(),
                attendances = attendances.await(),
                homeworks = homeworks.await(),
                luckyNumber = luckyNumber.await(),
                messages = messages.await()
            )
        }
        applyResult(results.profile) { refreshed = refreshed.copy(profile = it) }
        applyResult(results.grades) { refreshed = refreshed.copy(grades = it, gradesUpdatedAt = Instant.now().toString()) }
        applyResult(results.timetable) { refreshed = refreshed.copy(timetable = it, timetableUpdatedAt = Instant.now().toString()) }
        applyResult(results.attendances) { refreshed = refreshed.copy(attendances = it) }
        applyResult(results.homeworks) { refreshed = refreshed.copy(homeworks = it, homeworksUpdatedAt = Instant.now().toString()) }
        applyResult(results.luckyNumber) { refreshed = refreshed.copy(luckyNumber = it) }
        applyResult(results.messages) { refreshed = refreshed.copy(messages = it) }
        if (currentGeneration != generation) return

        if (sessionExpired && allowSessionRecovery) {
            val saved = credentials.load()
            if (saved != null) {
                try {
                    val (restored, profile) = authenticate(saved.username, saved.password)
                    if (currentGeneration != generation) return
                    client = restored
                    update { it.copy(data = it.data.copy(profile = profile), authenticated = true) }
                    syncInternal(currentGeneration, allowSessionRecovery = false, initialProfile = profile)
                    return
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    client = null
                    if (error.isInvalidCredentials()) credentials.clear()
                    firstError = error
                }
            }
        }

        refreshed = refreshed.copy(lastSync = Instant.now().toString())
        val savedLocally = localStore.save(refreshed)
        val displayError = firstError?.let {
            friendlyError(
                it,
                "Some school data could not be refreshed. Try again.",
                hasCachedData = refreshed.profile != null || refreshed.grades.isNotEmpty() || refreshed.timetable != null
            )
        }
        update {
            it.copy(
                data = refreshed,
                authenticated = it.authenticated && !firstError.isInvalidCredentials(),
                ready = true,
                syncing = false,
                error = displayError ?: if (!savedLocally) "School data was refreshed, but LibreCap could not save it locally. Check device storage." else null
            )
        }
    }

    private suspend fun restore(saved: StoredCredentials, currentGeneration: Int) {
        try {
            val (restored, profile) = authenticate(saved.username, saved.password)
            if (currentGeneration != generation) return
            client = restored
            update {
                it.copy(
                    authenticated = true,
                    ready = false,
                    syncing = true,
                    error = null,
                    data = it.data.copy(profile = profile)
                )
            }
            initialSync(currentGeneration, profile)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (currentGeneration == generation) {
                val invalidCredentials = error.isInvalidCredentials()
                client = null
                if (invalidCredentials) credentials.clear()
                update {
                    it.copy(
                        ready = true,
                        authenticated = it.authenticated && !invalidCredentials,
                        syncing = false,
                        error = friendlyError(
                            error,
                            if (it.authenticated) "Saved data is available, but syncing failed. Try again." else "Please sign in again.",
                            hasCachedData = it.data.profile != null || it.data.grades.isNotEmpty() || it.data.timetable != null
                        )
                    )
                }
            }
        }
    }

    fun logout() {
        generation += 1
        client = null
        syncJob?.cancel()
        credentials.clear()
        localStore.clear()
        update {
            it.copy(
                ready = true,
                authenticated = false,
                syncing = false,
                username = "",
                data = CachedSchoolData(),
                notes = emptyList(),
                error = null,
                messageRecipients = emptyList(),
                loadingMessageRecipients = false,
                sendingMessage = false,
                messageActionError = null,
                messageActionSuccess = false
            )
        }
    }

    fun saveNote(id: String, text: String, reminds: Boolean, noLongerRelevant: Boolean) {
        val updated = state.value.notes.filterNot { it.id == id }.toMutableList()
        if (text.isNotBlank() || reminds || noLongerRelevant) updated += SchoolNote(id, text, reminds, noLongerRelevant)
        preferences.edit().putString("notes", com.google.gson.Gson().toJson(updated)).apply()
        update { it.copy(notes = updated) }
    }

    fun note(id: String): SchoolNote? = state.value.notes.firstOrNull { it.id == id }
    fun loadMessage(id: String) {
        currentMessage.value = null
        val activeClient = client ?: return
        viewModelScope.launch {
            currentMessage.value = runCatching { withContext(Dispatchers.IO) { activeClient.fetchMessage(id) } }.getOrNull()
        }
    }
    fun loadMessageRecipients() {
        if (state.value.loadingMessageRecipients || state.value.messageRecipients.isNotEmpty()) return
        val activeClient = client ?: return
        val currentGeneration = generation
        update { it.copy(loadingMessageRecipients = true, messageActionError = null) }
        viewModelScope.launch {
            try {
                val recipients = withContext(Dispatchers.IO) { activeClient.fetchMessageRecipients() }
                if (currentGeneration != generation) return@launch
                update { it.copy(messageRecipients = recipients, loadingMessageRecipients = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentGeneration == generation) {
                    update {
                        it.copy(
                            loadingMessageRecipients = false,
                            messageActionError = friendlyError(error, "Could not load message recipients. Try again.")
                        )
                    }
                }
            }
        }
    }
    fun sendMessage(recipientId: String, subject: String, content: String, onSent: () -> Unit) {
        val activeClient = client
        if (activeClient == null) {
            update { it.copy(messageActionError = "Your Librus session is not ready. Try syncing and send again.") }
            return
        }
        val currentGeneration = generation
        update { it.copy(sendingMessage = true, messageActionError = null, messageActionSuccess = false) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { activeClient.sendMessage(recipientId, subject.trim(), content.trim()) }
                if (currentGeneration != generation) return@launch
                update { it.copy(sendingMessage = false, messageActionSuccess = true) }
                onSent()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentGeneration == generation) {
                    update {
                        it.copy(
                            sendingMessage = false,
                            messageActionError = friendlyError(error, "Message could not be sent. Try again.")
                        )
                    }
                }
            }
        }
    }
    fun clearMessageAction() {
        update { it.copy(messageActionError = null, messageActionSuccess = false) }
    }
    fun setLanguage(language: AppLanguage) { preferences.edit().putString("language", language.name).apply(); update { it.copy(language = language) } }
    fun setAppearance(appearance: AppAppearance) { preferences.edit().putString("appearance", appearance.name).apply(); update { it.copy(appearance = appearance) } }
    fun setAutomaticSync(enabled: Boolean) { preferences.edit().putBoolean("automatic_sync", enabled).apply(); update { it.copy(automaticSync = enabled) }; startAutomaticSync() }

    private fun startAutomaticSync() {
        automaticJob?.cancel()
        automaticJob = viewModelScope.launch {
            while (isActive) {
                delay(45 * 60 * 1000L)
                if (state.value.automaticSync && state.value.authenticated && !state.value.syncing) {
                    if (client == null) retry() else syncInternal(generation)
                }
            }
        }
    }

    private fun update(block: (SchoolUiState) -> SchoolUiState) { state.value = block(state.value) }

    private fun Throwable?.isSessionExpired(): Boolean = this is LibrusClientError && kind == LibrusErrorKind.SESSION_EXPIRED

    private fun Throwable?.isInvalidCredentials(): Boolean = this is LibrusClientError && kind == LibrusErrorKind.INVALID_CREDENTIALS

    private fun friendlyError(error: Throwable, fallback: String, hasCachedData: Boolean = false): String = when {
        error is LibrusClientError && error.kind == LibrusErrorKind.UNAVAILABLE && hasCachedData ->
            "Librus is temporarily unavailable. Showing saved data from your last sync. Try again later."
        error is LibrusClientError -> error.message ?: fallback
        error is UnknownHostException -> "No internet connection. Check your network and try again."
        error is SocketTimeoutException -> "Librus took too long to respond. Check your connection and try again."
        else -> error.message?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun loadInitial(): SchoolUiState {
        val data = localStore.load()
        val notes = runCatching {
            com.google.gson.Gson().fromJson(preferences.getString("notes", "[]"), Array<SchoolNote>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
        return SchoolUiState(
            data = data,
            notes = notes,
            language = runCatching { AppLanguage.valueOf(preferences.getString("language", AppLanguage.ENGLISH.name)!!) }.getOrDefault(AppLanguage.ENGLISH),
            appearance = runCatching { AppAppearance.valueOf(preferences.getString("appearance", AppAppearance.SYSTEM.name)!!) }.getOrDefault(AppAppearance.SYSTEM),
            automaticSync = preferences.getBoolean("automatic_sync", true)
        )
    }
}

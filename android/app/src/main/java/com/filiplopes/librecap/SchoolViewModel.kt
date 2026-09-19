package com.filiplopes.librecap

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

@Immutable
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
    val loadingMessage: Boolean = false,
    val messageDetailError: String? = null,
    val sendingMessage: Boolean = false,
    val messageActionError: String? = null,
    val messageActionSuccess: Boolean = false,
    val scheduleLoading: Boolean = false,
    val scheduleError: String? = null
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
    private var startupLoadingJob: Job? = null
    private var automaticJob: Job? = null
    private var timetableJob: Job? = null
    private var scheduleReconnectAttemptedForWeek: String? = null
    private var generation = 0
    private var startupLoadingActive = false

    private companion object {
        const val AUTH_TIMEOUT_MS = 60_000L
        const val INITIAL_SYNC_TIMEOUT_MS = 90_000L
        const val STARTUP_LOADING_MS = 3_200L
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
            startStartupLoading(currentGeneration)
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
        startupLoadingJob?.cancel()
        startupLoadingActive = false
        update { it.copy(syncing = true, error = null) }
        syncJob = viewModelScope.launch {
            try {
                val result = authenticate(trimmed, password)
                if (currentGeneration != generation) return@launch
                if (!credentials.save(StoredCredentials(trimmed, password))) {
                    throw LibrusClientError(
                        LibrusErrorKind.LOCAL_STORAGE,
                        "Sign-in worked, but LibreCap could not securely save your login on this device. Check storage and try again."
                    )
                }
                client = result
                val fresh = CachedSchoolData()
                update { it.copy(ready = false, authenticated = true, syncing = true, username = trimmed, data = fresh) }
                initialSync(currentGeneration)
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
        update {
            it.copy(
                username = saved.username,
                ready = it.data.profile != null,
                syncing = true,
                error = null
            )
        }
        syncJob?.cancel()
        syncJob = viewModelScope.launch { restore(saved, currentGeneration) }
    }

    private suspend fun initialSync(currentGeneration: Int, profile: StudentProfile? = null) {
        val completed = withTimeoutOrNull(INITIAL_SYNC_TIMEOUT_MS) {
            syncInternal(currentGeneration, initialProfile = profile)
            true
        } ?: false
        if (!completed && currentGeneration == generation) {
            startupLoadingJob?.cancel()
            startupLoadingActive = false
            update {
                it.copy(
                    ready = true,
                    syncing = false,
                    error = "Librus is taking too long. Showing saved data. Try again later."
                )
            }
        }
    }

    private suspend fun authenticate(username: String, password: String): LibrusClient =
        withTimeoutOrNull(AUTH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                LibrusClient().also { newClient -> newClient.establishSession(username, password) }
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
        var refreshedSections = 0
        suspend fun <T> request(block: suspend () -> T): Result<T> {
            try {
                return Result.success(withContext(Dispatchers.IO) { block() })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return Result.failure(error)
            }
        }
        fun <T> applyResult(
            result: Result<T>,
            countsAsRefresh: Boolean = true,
            reportFailure: Boolean = true,
            apply: (T) -> Unit
        ) {
            result.onSuccess { value ->
                if (countsAsRefresh) refreshedSections += 1
                apply(value)
            }.onFailure { error ->
                if (error.isSessionExpired()) sessionExpired = true
                if (reportFailure && firstError == null) firstError = error as? Exception ?: Exception(error)
            }
        }
        applyResult(
            if (initialProfile != null) Result.success(initialProfile)
            else request { activeClient.fetchProfile() },
            countsAsRefresh = false
        ) { refreshed = refreshed.copy(profile = it) }
        if (!sessionExpired) {
            applyResult(request { activeClient.fetchGrades() }) { refreshed = refreshed.copy(grades = it, gradesUpdatedAt = Instant.now().toString()) }
        }
        if (!sessionExpired) {
            applyResult(request { activeClient.fetchTimetable() }) {
                val weekKey = it.weekStart.orEmpty()
                refreshed = refreshed.copy(
                    timetable = it,
                    timetableWeeks = if (weekKey.isBlank()) refreshed.timetableWeeks else refreshed.timetableWeeks + (weekKey to it),
                    timetableUpdatedAt = Instant.now().toString()
                )
            }
        }
        if (!sessionExpired) {
            applyResult(request { activeClient.fetchAttendances() }) { refreshed = refreshed.copy(attendances = it) }
        }
        if (!sessionExpired) {
            applyResult(request { activeClient.fetchHomeworks() }) { refreshed = refreshed.copy(homeworks = it, homeworksUpdatedAt = Instant.now().toString()) }
        }
        if (!sessionExpired) {
            applyResult(request { activeClient.fetchLuckyNumber() }, countsAsRefresh = false, reportFailure = false) { refreshed = refreshed.copy(luckyNumber = it) }
        }
        if (!sessionExpired) {
            applyResult(request { activeClient.fetchMessages() }) { refreshed = refreshed.copy(messages = it) }
        }
        if (currentGeneration != generation) return

        if (sessionExpired && allowSessionRecovery) {
            val saved = credentials.load()
            if (saved != null) {
                try {
                    val restored = authenticate(saved.username, saved.password)
                    if (currentGeneration != generation) return
                    client = restored
                    update { it.copy(authenticated = true) }
                    syncInternal(currentGeneration, allowSessionRecovery = false)
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
        val displayError = firstError?.takeIf { refreshedSections == 0 || it.isSessionExpired() }?.let {
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
                ready = if (startupLoadingActive) it.ready else true,
                syncing = false,
                error = displayError ?: if (!savedLocally) "School data was refreshed, but LibreCap could not save it locally. Check device storage." else null
            )
        }
    }

    private suspend fun restore(saved: StoredCredentials, currentGeneration: Int) {
        try {
            val restored = authenticate(saved.username, saved.password)
            if (currentGeneration != generation) return
            client = restored
            update {
                it.copy(
                    authenticated = true,
                    ready = if (startupLoadingActive) it.ready else it.data.profile != null,
                    syncing = true,
                    error = null
                )
            }
            initialSync(currentGeneration)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("LibreCapSync", "Saved-session restore failed: ${error.javaClass.simpleName}")
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


    fun loadTimetableWeek(date: LocalDate) {
        val weekStart = date.with(DayOfWeek.MONDAY)
        val weekKey = weekStart.toString()
        val currentData = state.value.data
        val cachedWeek = currentData.timetableWeeks[weekKey]
            ?: currentData.timetable?.takeIf { it.weekStart == weekKey }
        if (cachedWeek != null) {
            update {
                it.copy(
                    data = it.data.copy(timetable = cachedWeek),
                    scheduleLoading = false,
                    scheduleError = null
                )
            }
            return
        }

        val activeClient = client
        if (activeClient == null) {
            val savedCredentials = credentials.load()
            val canReconnect = savedCredentials != null &&
                scheduleReconnectAttemptedForWeek != weekKey &&
                !state.value.syncing
            if (canReconnect) {
                scheduleReconnectAttemptedForWeek = weekKey
                update { it.copy(scheduleLoading = true, scheduleError = null) }
                retry()
            } else {
                update {
                    it.copy(
                        scheduleLoading = false,
                        scheduleError = if (savedCredentials == null) {
                            "Sign in and sync Librus to load another week."
                        } else {
                            "Librus session is unavailable. Tap Try again to reconnect."
                        }
                    )
                }
            }
            return
        }
        scheduleReconnectAttemptedForWeek = null

        timetableJob?.cancel()
        val requestGeneration = generation
        update { it.copy(scheduleLoading = true, scheduleError = null) }
        timetableJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    activeClient.fetchTimetable(weekStart)
                }
                if (requestGeneration != generation) return@launch
                val latest = state.value.data
                val weeks = latest.timetableWeeks.toMutableMap()
                weeks[weekKey] = loaded
                val updated = latest.copy(
                    timetable = loaded,
                    timetableWeeks = weeks,
                    timetableUpdatedAt = Instant.now().toString()
                )
                localStore.save(updated)
                update {
                    it.copy(
                        data = updated,
                        scheduleLoading = false,
                        scheduleError = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration == generation) {
                    update {
                        it.copy(
                            scheduleLoading = false,
                            scheduleError = friendlyError(error, "This week could not be loaded. Try again.")
                        )
                    }
                }
            }
        }
    }

    fun logout() {
        generation += 1
        client = null
        syncJob?.cancel()
        startupLoadingJob?.cancel()
        startupLoadingActive = false
        timetableJob?.cancel()
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
        val activeClient = client
        if (activeClient == null) {
            update {
                it.copy(
                    loadingMessage = false,
                    messageDetailError = "Librus is not connected. Refresh school data before opening a message."
                )
            }
            return
        }
        update { it.copy(loadingMessage = true, messageDetailError = null) }
        viewModelScope.launch {
            try {
                currentMessage.value = withContext(Dispatchers.IO) { activeClient.fetchMessage(id) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                update {
                    it.copy(
                        messageDetailError = friendlyError(error, "Could not load this message. Refresh school data and try again.", hasCachedData = true)
                    )
                }
            } finally {
                update { it.copy(loadingMessage = false) }
            }
        }
    }

    private fun startStartupLoading(currentGeneration: Int) {
        startupLoadingJob?.cancel()
        startupLoadingActive = true
        startupLoadingJob = viewModelScope.launch {
            delay(STARTUP_LOADING_MS)
            if (currentGeneration != generation) return@launch
            startupLoadingActive = false
            update { it.copy(ready = true) }
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

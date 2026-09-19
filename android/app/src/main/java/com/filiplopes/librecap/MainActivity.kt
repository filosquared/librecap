package com.filiplopes.librecap

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.AssignmentLate
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<SchoolViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply {
            preferredRefreshRate = 120f
        }
        setContent { LibreCapRoot(viewModel) }
    }
}

private enum class Route { HOME, GRADES, SCHEDULE, MESSAGES, MORE, HOMEWORK, ATTENDANCE, SETTINGS, GRADE_DETAIL, LESSON_DETAIL, HOMEWORK_DETAIL, MESSAGE_DETAIL, NEW_MESSAGE }

private val mainRoutes = setOf(Route.HOME, Route.GRADES, Route.SCHEDULE, Route.MESSAGES, Route.MORE)

private fun AppLanguage.text(english: String, polish: String): String = if (this == AppLanguage.POLISH) polish else english

@Composable
private fun LibreCapTheme(appearance: AppAppearance, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        AppAppearance.DARK -> true
        AppAppearance.LIGHT -> false
        AppAppearance.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Color(0xFFB9C2FF), secondary = Color(0xFFB9DAD6)) else lightColorScheme(primary = Color(0xFF4657D6), secondary = Color(0xFF416A67)),
        content = {
            val view = LocalView.current
            val window = (view.context as? Activity)?.window
            val systemBarColor = MaterialTheme.colorScheme.surface

            SideEffect {
                window?.let {
                    it.statusBarColor = systemBarColor.toArgb()
                    it.navigationBarColor = systemBarColor.toArgb()
                    WindowCompat.getInsetsController(it, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
            }

            content()
        }
    )
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun LibreCapRoot(viewModel: SchoolViewModel) {
    val ui = viewModel.state.value
    LibreCapTheme(ui.appearance) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            val root = when {
                !ui.ready -> RootScreen.LOADING
                !ui.authenticated -> RootScreen.LOGIN
                else -> RootScreen.APP
            }
            var renderedRoot by remember { mutableStateOf(root) }
            var rootOpacity by remember { mutableStateOf(1f) }
            val animatedRootOpacity = animateFloatAsState(rootOpacity, tween(70), label = "root-fade")
            LaunchedEffect(root) {
                if (renderedRoot == root) return@LaunchedEffect
                rootOpacity = 0.86f
                withFrameNanos { }
                renderedRoot = root
                rootOpacity = 1f
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = animatedRootOpacity.value }
            ) {
                when (renderedRoot) {
                    RootScreen.LOADING -> LoadingScreen(ui.language)
                    RootScreen.LOGIN -> LoginScreen(ui, viewModel)
                    RootScreen.APP -> AuthenticatedApp(ui, viewModel)
                }
            }
        }
    }
}

private enum class RootScreen { LOADING, LOGIN, APP }

@Composable
private fun LoadingScreen(language: AppLanguage) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        Text(language.text("Connecting…", "Łączenie…"), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LoginScreen(ui: SchoolUiState, viewModel: SchoolViewModel) {
    var username by rememberSaveable { mutableStateOf(ui.username) }
    var password by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val lang = ui.language
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Icon(Icons.Default.School, null, Modifier.size(58.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(lang.text("Welcome to LibreCap", "Witaj w LibreCap"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(lang.text("Your local-first school journal", "Twój lokalny dziennik szkolny"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text(lang.text("School account", "Konto szkolne"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(lang.text("Use the school-issued Synergia login, not an email address.", "Użyj szkolnego loginu Synergia, nie adresu e-mail."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(lang.text("School login", "Login szkolny")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(lang.text("Password", "Hasło")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { focus.clearFocus(); viewModel.login(username, password) }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (ui.error != null) {
            item {
                ErrorCard(
                    message = ui.error,
                    language = lang,
                    onRetry = { if (password.isNotEmpty()) viewModel.login(username, password) else viewModel.retry() }
                )
            }
        }
        item {
            Button(
                onClick = { focus.clearFocus(); viewModel.login(username, password) },
                enabled = username.isNotBlank() && password.isNotEmpty() && !ui.syncing,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (ui.syncing) lang.text("Signing in…", "Logowanie…") else lang.text("Sign in", "Zaloguj się")) }
            Text(lang.text("Your password is stored only on this device in secure storage.", "Hasło jest przechowywane wyłącznie na tym urządzeniu w bezpiecznym magazynie."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
private fun AuthenticatedApp(ui: SchoolUiState, viewModel: SchoolViewModel) {
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = route !in mainRoutes) { route = Route.HOME }
    val go: (Route, String) -> Unit = remember {
        { next, id -> selectedId = id; route = next }
    }
    Scaffold(
        bottomBar = {
            if (route in mainRoutes) BottomBar(route, ui.language) { route = it }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                RouteHost(route, ui, viewModel, selectedId, go)
            }
            AnimatedVisibility(
                visible = ui.error != null,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(80))
            ) {
                ui.error?.let {
                    ErrorCard(
                        message = it,
                        language = ui.language,
                        onRetry = viewModel::retry,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(route: Route, language: AppLanguage, select: (Route) -> Unit) {
    val items = remember(language) {
        listOf(
            Triple(Route.HOME, language.text("Home", "Główna"), Icons.Default.Home),
            Triple(Route.GRADES, language.text("Grades", "Oceny"), Icons.Default.Grade),
            Triple(Route.SCHEDULE, language.text("Schedule", "Plan"), Icons.Default.CalendarMonth),
            Triple(Route.MESSAGES, language.text("Messages", "Wiadomości"), Icons.Default.Email),
            Triple(Route.MORE, language.text("More", "Więcej"), Icons.Default.MoreHoriz)
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(80.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
        items.forEach { (itemRoute, label, icon) ->
            val selected = route == itemRoute
            val selectionProgress = animateFloatAsState(
                targetValue = if (selected) 1f else 0f,
                animationSpec = tween(180),
                label = "navigation-selection"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { select(itemRoute) }
                    )
                    .semantics {
                        this.selected = selected
                        role = Role.Tab
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            val progress = selectionProgress.value
                            scaleX = 0.96f + (0.04f * progress)
                            scaleY = 0.96f + (0.04f * progress)
                        }
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = selectionProgress.value),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        icon,
                        label,
                        tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        label,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun RouteHost(
    route: Route,
    ui: SchoolUiState,
    viewModel: SchoolViewModel,
    selectedId: String,
    go: (Route, String) -> Unit
) {
    RouteContent(route, ui, viewModel, selectedId, go)
}

@Composable
private fun RouteContent(
    route: Route,
    ui: SchoolUiState,
    viewModel: SchoolViewModel,
    selectedId: String,
    go: (Route, String) -> Unit
) {
    when (route) {
        Route.HOME -> HomeScreen(ui, viewModel, { go(Route.HOMEWORK, "") }, { go(Route.ATTENDANCE, "") }, { go(Route.MESSAGES, "") }, { go(Route.LESSON_DETAIL, it) })
        Route.GRADES -> GradesScreen(ui, viewModel) { go(Route.GRADE_DETAIL, it) }
        Route.SCHEDULE -> ScheduleScreen(ui, viewModel) { go(Route.LESSON_DETAIL, it) }
        Route.MESSAGES -> MessagesScreen(ui, viewModel, { go(Route.MESSAGE_DETAIL, it) }) { go(Route.NEW_MESSAGE, "") }
        Route.NEW_MESSAGE -> NewMessageScreen(ui, viewModel, { go(Route.MESSAGES, "") }) {
            viewModel.clearMessageAction()
            go(Route.MESSAGES, "")
            viewModel.sync()
        }
        Route.MORE -> MoreScreen(ui, viewModel) { go(it, "") }
        Route.HOMEWORK -> HomeworkScreen(ui, viewModel) { go(Route.HOMEWORK_DETAIL, it) }
        Route.ATTENDANCE -> AttendanceScreen(ui, viewModel)
        Route.SETTINGS -> SettingsScreen(ui, viewModel) { go(Route.HOME, "") }
        Route.GRADE_DETAIL -> ui.data.grades.firstOrNull { it.id == selectedId }?.let { DetailScaffold(ui.language.text("Grade details", "Szczegóły oceny"), { go(Route.GRADES, "") }) { GradeDetail(it, ui.language) } }
        Route.LESSON_DETAIL -> findLesson(ui.data, selectedId)?.let { lesson -> DetailScaffold(ui.language.text("Lesson details", "Szczegóły lekcji"), { go(Route.SCHEDULE, "") }) { LessonDetail(lesson, ui, viewModel) } }
        Route.HOMEWORK_DETAIL -> ui.data.homeworks.firstOrNull { it.id == selectedId }?.let { DetailScaffold(ui.language.text("Homework details", "Szczegóły pracy domowej"), { go(Route.HOMEWORK, "") }) { HomeworkDetail(it, ui, viewModel) } }
        Route.MESSAGE_DETAIL -> ui.data.messages.firstOrNull { it.id == selectedId }?.let { MessageDetailScreen(it, ui, viewModel) { go(Route.MESSAGES, "") } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenTopBar(
    title: String,
    back: Boolean = false,
    onBack: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false,
    actions: @Composable (() -> Unit)? = null
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { if (back) IconButton(onClick = { onBack?.invoke() }) { Icon(Icons.Default.ArrowBack, "Back") } },
        actions = {
            actions?.invoke()
            onRefresh?.let { refresh ->
                IconButton(onClick = refresh, enabled = !refreshing) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

@Composable
private fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title, back = true, onBack = onBack)
        content()
    }
}

@Composable
private fun HomeScreen(ui: SchoolUiState, viewModel: SchoolViewModel, homework: () -> Unit, attendance: () -> Unit, messages: () -> Unit, openLesson: (String) -> Unit) {
    val lang = ui.language
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            ui.data.profile?.fullName ?: "LibreCap",
            onRefresh = viewModel::sync,
            refreshing = ui.syncing
        )
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(lang.text("Good to see you", "Dobrze Cię widzieć"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    Text(ui.data.profile?.firstName ?: "LibreCap", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    ui.data.profile?.let { profile ->
                        Text("${lang.text("Class", "Klasa")} ${profile.className}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            ui.availableUpdate?.let { release ->
                item { UpdateCard(release, lang) { uriHandler.openUri(release.htmlUrl) } }
            }
            item { Text(lang.text("Quick actions", "Szybkie akcje"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryCard(lang.text("Tests", "Kartkówki"), ui.data.homeworks.count { it.isAssessment }.toString(), Icons.Default.Checklist, Color(0xFF8455C7), homework, Modifier.weight(1f))
                    SummaryCard(lang.text("Homework", "Prace domowe"), ui.data.homeworks.size.toString(), Icons.Default.Assignment, Color(0xFFE78225), homework, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryCard(lang.text("Messages", "Wiadomości"), ui.data.messages.count { !it.isLikelyHeaderRow }.toString(), Icons.Default.Email, Color(0xFF299A91), messages, Modifier.weight(1f))
                    SummaryCard(lang.text("Attendance", "Frekwencja"), ui.data.attendances.count { !it.isPresence }.toString(), Icons.Default.EventAvailable, Color(0xFFD45151), attendance, Modifier.weight(1f))
                }
            }
            item { TodayCard(ui, viewModel, openLesson) }
        }
    }
}

@Composable
private fun ErrorCard(message: String, language: AppLanguage, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    message,
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            onRetry?.let {
                TextButton(onClick = it, modifier = Modifier.align(Alignment.End)) {
                    Text(language.text("Try again", "Spróbuj ponownie"))
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(release: AppRelease, language: AppLanguage, openRelease: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    language.text("A new LibreCap version is available", "Dostępna jest nowa wersja LibreCap"),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text("${release.displayName} (${release.tagName})", style = MaterialTheme.typography.bodyMedium)
            Text(
                language.text(
                    "Open the GitHub release page to see what's new and download the update.",
                    "Otwórz stronę wydania na GitHubie, aby zobaczyć zmiany i pobrać aktualizację."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = openRelease) { Text(language.text("View release", "Zobacz wydanie")) }
        }
    }
}

@Composable
private fun TodayCard(ui: SchoolUiState, viewModel: SchoolViewModel, openLesson: (String) -> Unit) {
    val lang = ui.language
    val today = LocalDate.now()
    val dayKey = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val now = LocalTime.now()
    val lessons = ui.data.timetable?.days?.get(dayKey).orEmpty()
    val remaining = lessons.filter { (it.endMinutes()?.let { m -> m > now.hour * 60 + now.minute } ?: true) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                        Text(lang.text("Today", "Dzi\u015b"))
                    Text(today.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                AnimatedContent(targetState = remaining.size, transitionSpec = { (fadeIn(tween(100)) togetherWith fadeOut(tween(70))).using(null) }, label = "remaining-lessons") { count ->
                    Text("$count ${lang.text("left", "pozostało")}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (remaining.isEmpty()) {
                Text(lang.text("No remaining lessons today", "Brak pozostałych lekcji"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                remaining.take(3).forEach { lesson -> LessonRow(lesson, ui, viewModel) { openLesson(lesson.id) } }
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String, icon: ImageVector, color: Color, onClick: () -> Unit, modifier: Modifier) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .12f))) {
        Column(Modifier.padding(16.dp).height(96.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, tint = color)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GradesScreen(ui: SchoolUiState, viewModel: SchoolViewModel, open: (String) -> Unit) {
    var semester by rememberSaveable { mutableStateOf(GradeSemester.FIRST) }
    val lang = ui.language
    val filtered = remember(ui.data.grades, semester) { ui.data.grades.filter { it.belongsTo(semester) } }
    val gradesBySubject = remember(filtered) { filtered.groupBy { it.subject }.toSortedMap() }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(lang.text("Grades", "Oceny"), onRefresh = viewModel::sync, refreshing = ui.syncing)
        ScrollableTabRow(selectedTabIndex = semester.ordinal, edgePadding = 16.dp) {
            listOf(lang.text("First semester", "Pierwsze półrocze"), lang.text("Second semester", "Drugie półrocze"), lang.text("All", "Wszystkie")).forEachIndexed { index, label ->
                Tab(selected = semester.ordinal == index, onClick = { semester = GradeSemester.entries[index] }, text = { Text(label) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(lang.text("Average", "Średnia"), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(filtered.averageAcrossSubjects()?.let { "%.2f".format(it) } ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }; Text("${filtered.size} ${lang.text("grades", "ocen")}", color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            if (filtered.isEmpty()) item { EmptyState(lang.text("No grades", "Brak ocen"), lang.text("No data for this semester.", "Brak danych dla tego półrocza."), Icons.Default.MenuBook) }
            gradesBySubject.forEach { (subject, grades) ->
                item { Text("$subject  ·  ${grades.numericAverage()?.let { "%.2f".format(it) } ?: "—"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                items(grades, key = { it.id }) { grade -> GradeRow(grade, lang) { open(grade.id) } }
            }
        }
    }
}

@Composable
private fun GradeRow(grade: GradeRecord, language: AppLanguage, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(if (grade.isFinal) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) { Text(grade.value, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (grade.category.isBlank()) language.text("Grade", "Ocena") else grade.category, fontWeight = FontWeight.Medium)
                Text(listOf(if (grade.weight == "none") language.text("No weight", "Bez wagi") else "${language.text("Weight", "Waga")} ${grade.weight}", grade.teacher).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (grade.isFinal) Icon(Icons.Default.Star, language.text("Final grade", "Ocena końcowa"), tint = Color(0xFFE59D24))
        }
    }
}

@Composable
private fun GradeDetail(grade: GradeRecord, language: AppLanguage) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { DetailRow(language.text("Grade", "Ocena"), grade.value) }
        item { DetailRow(language.text("Category", "Kategoria"), grade.category) }
        if (grade.weight != "none") item { DetailRow(language.text("Weight", "Waga"), grade.weight) }
        if (grade.semester.isNotBlank()) item { DetailRow(language.text("Semester", "Półrocze"), grade.semester) }
        if (grade.teacher.isNotBlank()) item { DetailRow(language.text("Teacher", "Nauczyciel"), grade.teacher) }
        if (grade.addedDate.isNotBlank()) item { DetailRow(language.text("Added", "Dodano"), grade.addedDate) }
        if (grade.comment.isNotBlank()) {
            item { Text(language.text("Note", "Notatka"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)) }
            item { Text(grade.comment) }
        }
    }
}

@Composable
private fun ScheduleScreen(ui: SchoolUiState, viewModel: SchoolViewModel, open: (String) -> Unit) {
    val lang = ui.language
    val locale = if (lang == AppLanguage.POLISH) Locale("pl") else Locale.ENGLISH
    val calendarToday = LocalDate.now()
    val today = when (calendarToday.dayOfWeek) {
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> calendarToday.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        else -> calendarToday
    }
    var selectedDateText by rememberSaveable { mutableStateOf(today.toString()) }
    val selectedDate = remember(selectedDateText) {
        runCatching { LocalDate.parse(selectedDateText) }.getOrDefault(today).let { date ->
            if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
                date.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            } else {
                date
            }
        }
    }
    val weekStart = selectedDate.with(DayOfWeek.MONDAY)
    val weekDays = remember(weekStart) { (0..4).map { weekStart.plusDays(it.toLong()) } }
    val month = YearMonth.from(selectedDate)
    var monthMenuExpanded by remember { mutableStateOf(false) }
    val monthChoices = remember(month) {
        (-6..6).map { month.plusMonths(it.toLong()) }
    }
    val weekLabelFormatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    val monthLabelFormatter = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    val timetable = ui.data.timetableWeeks[weekStart.toString()]
        ?: ui.data.timetable?.takeIf { it.weekStart == weekStart.toString() }
    val dayKey = selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val lessons = timetable?.days?.get(dayKey).orEmpty().filter {
        it.effectiveDate.isBlank() || it.effectiveDate == selectedDate.toString()
    }

    LaunchedEffect(weekStart, ui.authenticated, ui.ready, ui.syncing) {
        if (ui.ready && !ui.syncing) viewModel.loadTimetableWeek(weekStart)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(lang.text("Schedule", "Plan lekcji"), onRefresh = viewModel::sync, refreshing = ui.syncing)
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedDateText = selectedDate.minusWeeks(1).toString() }) {
                        Icon(Icons.Default.ChevronLeft, lang.text("Previous week", "Poprzedni tydzie\u0144"))
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(lang.text("Week", "Tydzie\u0144"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${weekStart.format(weekLabelFormatter)} - ${weekStart.plusDays(6).format(weekLabelFormatter)}",
                            fontWeight = FontWeight.SemiBold
                        )
                        Box(contentAlignment = Alignment.Center) {
                            TextButton(onClick = { monthMenuExpanded = true }) {
                                Text(month.format(monthLabelFormatter))
                            }
                            DropdownMenu(
                                expanded = monthMenuExpanded,
                                onDismissRequest = { monthMenuExpanded = false }
                            ) {
                                monthChoices.forEach { choice ->
                                    DropdownMenuItem(
                                        text = { Text(choice.format(monthLabelFormatter)) },
                                        onClick = {
                                            selectedDateText = choice.atDay(1).toString()
                                            monthMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = { selectedDateText = selectedDate.plusWeeks(1).toString() }) {
                        Icon(Icons.Default.ChevronRight, lang.text("Next week", "Nast\u0119pny tydzie\u0144"))
                    }
                    TextButton(onClick = { selectedDateText = today.toString() }) {
                        Text(lang.text("Today", "Dzi\u015b"))
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    weekDays.forEach { day ->
                        val selected = day == selectedDate
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable(role = Role.Tab) { selectedDateText = day.toString() }
                                .semantics {
                                    this.selected = selected
                                    role = Role.Tab
                                }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                day.dayOfMonth.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                            Box(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                                    )
                            )
                        }
                    }
                }
            }
            if (ui.scheduleLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(lang.text("Loading this week...", "\u0141adowanie tygodnia..."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            ui.scheduleError?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            if (lessons.isEmpty() && !ui.scheduleLoading) {
                item {
                    EmptyState(
                        lang.text("No lessons", "Brak lekcji"),
                        lang.text("Choose another day or week.", "Wybierz inny dzie\u0144 lub tydzie\u0144."),
                        Icons.Default.CalendarMonth
                    )
                }
            }
            items(lessons, key = { it.id }) { lesson ->
                LessonRow(lesson, ui, viewModel) { open(lesson.id) }
            }
        }
    }
}
@Composable
private fun LessonRow(lesson: TimetableLesson, ui: SchoolUiState, viewModel: SchoolViewModel, onClick: (() -> Unit)? = null) {
    val relatedHomeworks = ui.data.homeworks.filter { it.matchesLesson(lesson) }
    Card(onClick = { onClick?.invoke() }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(50.dp)) { Text(lesson.hourFrom, fontWeight = FontWeight.SemiBold); Text(lesson.hourTo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Divider(Modifier.height(42.dp).width(1.dp).padding(horizontal = 4.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    lesson.displaySubject,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else TextDecoration.None
                )
                val teacherText = when {
                    lesson.teacher.isNotBlank() && lesson.hasOriginalTeacher ->
                        "${lesson.teacher} > ${lesson.originalTeacher}"
                    lesson.teacher.isNotBlank() -> lesson.teacher
                    lesson.hasOriginalTeacher -> lesson.originalTeacher.orEmpty()
                    else -> ""
                }
                Text(listOf(teacherText, lesson.classroom.takeIf { it != "—" }.orEmpty()).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                relatedHomeworks.forEach { homework ->
                    Text(
                        homework.displayType,
                        color = Color(0xFF8455C7),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (lesson.isCancelled || lesson.isSubstitution) Text(if (lesson.isCancelled) ui.language.text("Cancelled", "Odwołana") else ui.language.text("Substitution", "Zastępstwo"), color = if (lesson.isCancelled) MaterialTheme.colorScheme.error else Color(0xFFE78225), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            if (viewModel.note(lesson.id) != null) Icon(Icons.Default.NoteAlt, ui.language.text("Note", "Notatka"), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun LessonDetail(lesson: TimetableLesson, ui: SchoolUiState, viewModel: SchoolViewModel) {
    val relatedHomeworks = ui.data.homeworks.filter { it.matchesLesson(lesson) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { DetailRow(ui.language.text("Lesson", "Lekcja"), lesson.lessonNumber) }
        item { DetailRow(ui.language.text("Subject", "Przedmiot"), lesson.displaySubject) }
        item { DetailRow(ui.language.text("Time", "Godzina"), "${lesson.hourFrom} – ${lesson.hourTo}") }
        item { DetailRow(ui.language.text("Classroom", "Sala"), lesson.classroom) }
        item { DetailRow(ui.language.text("Teacher", "Nauczyciel"), lesson.teacher) }
        if (lesson.hasOriginalTeacher) item { DetailRow(ui.language.text("Replaced teacher", "Zastąpiony nauczyciel"), lesson.originalTeacher.orEmpty()) }
        if (lesson.isCancelled || lesson.isSubstitution) item { Text(if (lesson.isCancelled) ui.language.text("Cancelled", "Odwołana") else ui.language.text("Substitution", "Zastępstwo"), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp)) }
        relatedHomeworks.forEach { homework ->
            item { DetailRow(ui.language.text("Event", "Wydarzenie"), homework.displayType) }
            if (homework.content.isNotBlank()) item {
                DetailRow(ui.language.text("Scope / teacher's information", "Zakres / informacja od nauczyciela"), homework.content)
            }
            if (homework.addedBy.isNotBlank()) item { DetailRow(ui.language.text("Added by", "Dodane przez"), homework.addedBy) }
        }
        item { Text(ui.language.text("Note", "Notatka"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp)) }
        item { NoteEditor(lesson.id, ui, viewModel) }
    }
}

@Composable
private fun HomeworkScreen(ui: SchoolUiState, viewModel: SchoolViewModel, open: (String) -> Unit) {
    var assessmentsOnly by rememberSaveable { mutableStateOf(false) }
    val lang = ui.language
    val records = ui.data.homeworks.filter { !assessmentsOnly || it.isAssessment }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(lang.text("Homework", "Prace domowe"), onRefresh = viewModel::sync, refreshing = ui.syncing)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !assessmentsOnly, onClick = { assessmentsOnly = false }, label = { Text(lang.text("All", "Wszystkie")) })
            FilterChip(selected = assessmentsOnly, onClick = { assessmentsOnly = true }, label = { Text(lang.text("Tests & classwork", "Kartkówki i klasówki")) })
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (records.isEmpty()) item { EmptyState(lang.text("No homework", "Brak prac domowych"), lang.text("No data available.", "Brak danych."), Icons.Default.Assignment) }
            items(records, key = { it.id }) { homework -> HomeworkRow(homework, ui, viewModel) { open(homework.id) } }
        }
    }
}

@Composable
private fun HomeworkRow(homework: HomeworkRecord, ui: SchoolUiState, viewModel: SchoolViewModel, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(if (homework.isAssessment) Icons.Default.Checklist else Icons.Default.Assignment, null, tint = if (homework.isAssessment) Color(0xFF8455C7) else Color(0xFFE78225))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row { Text(homework.subject, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(homework.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(if (homework.content.isBlank()) homework.type else homework.content, maxLines = 3)
                if (viewModel.note(homework.id)?.isNoLongerRelevant == true) Text(ui.language.text("No longer relevant", "Nieaktualne"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HomeworkDetail(homework: HomeworkRecord, ui: SchoolUiState, viewModel: SchoolViewModel) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { DetailRow(ui.language.text("Subject", "Przedmiot"), homework.subject) }
        item { DetailRow(ui.language.text("Date", "Data"), homework.date) }
        item { DetailRow(ui.language.text("Category", "Kategoria"), homework.type) }
        if (homework.addedBy.isNotBlank()) item { DetailRow(ui.language.text("Added by", "Dodane przez"), homework.addedBy) }
        item { Text(ui.language.text("Content", "Treść"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)) }
        item { Text(homework.content.ifBlank { homework.type }) }
        item { Text(ui.language.text("Note", "Notatka"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp)); NoteEditor(homework.id, ui, viewModel) }
    }
}

@Composable
private fun MessagesScreen(ui: SchoolUiState, viewModel: SchoolViewModel, open: (String) -> Unit, newMessage: () -> Unit) {
    var folder by rememberSaveable { mutableStateOf(MessageFolder.INBOX) }
    val lang = ui.language
    val labels = listOf(lang.text("Received", "Odebrane"), lang.text("Sent", "Wysłane"), lang.text("Announcements", "Ogłoszenia"), lang.text("Notes", "Uwagi"))
    val records = ui.data.messages.filter { it.folder == folder && !it.isLikelyHeaderRow }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(lang.text("Messages", "Wiadomości"), onRefresh = viewModel::sync, refreshing = ui.syncing)
            ScrollableTabRow(selectedTabIndex = folder.ordinal, edgePadding = 12.dp) { labels.forEachIndexed { index, label -> Tab(selected = folder.ordinal == index, onClick = { folder = MessageFolder.entries[index] }, text = { Text(label) }) } }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (records.isEmpty()) item { EmptyState(labels[folder.ordinal], lang.text("No messages in this folder.", "Brak wiadomości w tej kategorii."), Icons.Default.Email) }
                items(records, key = { it.id }) { message -> Card(onClick = { open(message.id) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Row { Text(message.sender, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(message.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(message.subject) } } }
            }
        }
        if (folder == MessageFolder.SENT) {
            FloatingActionButton(onClick = newMessage, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                Icon(Icons.Default.Add, contentDescription = lang.text("New message", "Nowa wiadomość"))
            }
        }
    }
}

@Composable
private fun NewMessageScreen(ui: SchoolUiState, viewModel: SchoolViewModel, onBack: () -> Unit, onSent: () -> Unit) {
    val lang = ui.language
    var recipientId by rememberSaveable { mutableStateOf("") }
    var recipientQuery by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var recipientMenuExpanded by remember { mutableStateOf(false) }
    val matchingRecipients = ui.messageRecipients.filter {
        (it.name + " " + it.group).contains(recipientQuery.trim(), ignoreCase = true)
    }.take(8)
    val canSend = recipientId.isNotBlank() && subject.isNotBlank() && content.isNotBlank() && !ui.sendingMessage

    LaunchedEffect(Unit) {
        viewModel.clearMessageAction()
        viewModel.loadMessageRecipients()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(lang.text("New message", "Nowa wiadomość"), back = true, onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(lang.text("Recipient", "Odbiorca"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedTextField(
                        value = recipientQuery,
                        onValueChange = {
                            recipientQuery = it
                            recipientId = ""
                            recipientMenuExpanded = it.isNotBlank()
                        },
                        label = { Text(lang.text("Choose a recipient", "Wybierz odbiorcę")) },
                        placeholder = { Text(lang.text("Type teacher's first name", "Wpisz imię nauczyciela")) },
                        singleLine = true,
                        enabled = !ui.loadingMessageRecipients && ui.messageRecipients.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (recipientMenuExpanded && recipientQuery.isNotBlank()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 72.dp)
                        ) {
                            Column {
                                if (matchingRecipients.isEmpty()) {
                                    Text(
                                        lang.text("No matching recipients.", "Brak pasujących odbiorców."),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                } else {
                                    matchingRecipients.forEach { recipient ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    recipientId = recipient.id
                                                    recipientQuery = recipient.name + " · " + recipient.group
                                                    recipientMenuExpanded = false
                                                }
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(recipient.name, fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    recipient.group,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (ui.loadingMessageRecipients) {
                    CircularProgressIndicator(Modifier.padding(top = 8.dp).size(20.dp), strokeWidth = 2.dp)
                } else if (ui.messageRecipients.isEmpty()) {
                    Text(lang.text("No recipients available.", "Brak dostępnych odbiorców."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text(lang.text("Subject", "Temat")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(lang.text("Message", "Treść wiadomości")) },
                    minLines = 7,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (ui.messageActionError != null) {
                item {
                    ErrorCard(
                        ui.messageActionError,
                        lang,
                        onRetry = if (ui.messageRecipients.isEmpty()) viewModel::loadMessageRecipients else null
                    )
                }
            }
            item {
                Button(
                    onClick = { viewModel.sendMessage(recipientId, subject, content, onSent) },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    if (ui.sendingMessage) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(lang.text("Send message", "Wyślij wiadomość"))
                }
            }
        }
    }
}

@Composable
private fun MessageDetailScreen(summary: MessageSummary, ui: SchoolUiState, viewModel: SchoolViewModel, onBack: () -> Unit) {
    LaunchedEffect(summary.id) {
        if (summary.folder == MessageFolder.INBOX || summary.folder == MessageFolder.SENT) viewModel.loadMessage(summary.id)
    }
    val detail = viewModel.currentMessage.value
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(ui.language.text("Message", "Wiadomość"), back = true, onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(summary.subject, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item { Text("${summary.sender} · ${summary.date}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Divider(Modifier.padding(vertical = 4.dp)) }
            item {
                if (summary.folder == MessageFolder.ANNOUNCEMENTS || summary.folder == MessageFolder.NOTES) {
                    Text(summary.content.ifBlank { ui.language.text("No content.", "Brak treści.") })
                } else if (ui.messageDetailError != null) {
                    Text(ui.messageDetailError, color = MaterialTheme.colorScheme.error)
                } else if (ui.loadingMessage || detail == null) {
                    Text(ui.language.text("Loading message…", "Wczytywanie wiadomości…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(detail.content.ifBlank { ui.language.text("No message content.", "Brak treści wiadomości.") })
                }
            }
        }
    }
}

@Composable
private fun AttendanceScreen(ui: SchoolUiState, viewModel: SchoolViewModel) {
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(ui.language.text("Attendance", "Frekwencja"), onRefresh = viewModel::sync, refreshing = ui.syncing)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.data.attendances.isEmpty()) item { EmptyState(ui.language.text("No attendance", "Brak frekwencji"), ui.language.text("No data available.", "Brak danych."), Icons.Default.EventAvailable) }
            items(ui.data.attendances, key = { it.id }) { attendance -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(attendance.subject, fontWeight = FontWeight.SemiBold); Text("${attendance.date} · ${attendance.teacher}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(attendance.shortType, color = if (attendance.isPresence) Color(0xFF2E8B57) else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) } } }
        }
    }
}

@Composable
private fun MoreScreen(ui: SchoolUiState, viewModel: SchoolViewModel, open: (Route) -> Unit) {
    val lang = ui.language
    val luckyDate = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(lang.text("More", "Więcej"), onRefresh = viewModel::sync, refreshing = ui.syncing)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { StudentInfoCard(ui) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                lang.text("Lucky number", "Szczęśliwy numerek"),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                luckyDate,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            ui.data.luckyNumber?.toString() ?: "—",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 24.dp)
                        )
                    }
                }
            }
            item { MoreRow(Icons.Default.Assignment, lang.text("Homework", "Prace domowe"), lang.text("Assignments and tests", "Zadania i sprawdziany")) { open(Route.HOMEWORK) } }
            item { MoreRow(Icons.Default.EventAvailable, lang.text("Attendance", "Frekwencja"), lang.text("Presence and absences", "Obecności i nieobecności")) { open(Route.ATTENDANCE) } }
            item { MoreRow(Icons.Default.Settings, lang.text("Settings", "Ustawienia"), lang.text("Language, appearance, and sync", "Język, wygląd i synchronizacja")) { open(Route.SETTINGS) } }
            item { MoreRow(Icons.Default.Info, lang.text("About LibreCap", "O LibreCap"), "LibreCap 1.0") {} }
        }
    }
}

@Composable
private fun StudentInfoCard(ui: SchoolUiState) {
    val profile = ui.data.profile ?: return
    val lang = ui.language
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                lang.text("Student information", "Informacje o uczniu"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(profile.fullName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ProfileField(lang.text("Class", "Klasa"), profile.className, Modifier.weight(1f), lang)
                ProfileField(lang.text("Tutor", "Wychowawca"), profile.tutorName, Modifier.weight(1f), lang)
            }
            ProfileField(lang.text("Account type", "Typ konta"), profile.type, Modifier.fillMaxWidth(), lang)
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String, modifier: Modifier, language: AppLanguage) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { language.text("Not available", "Brak danych") }, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MoreRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
}

@Composable
private fun SettingsScreen(ui: SchoolUiState, viewModel: SchoolViewModel, close: () -> Unit) {
    val lang = ui.language
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(lang.text("Settings", "Ustawienia"), true, close)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Text(lang.text("Appearance", "Wygląd"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item { SettingChoices(listOf(lang.text("System", "Systemowy"), lang.text("Light", "Jasny"), lang.text("Dark", "Ciemny")), ui.appearance.ordinal, listOf(Icons.Default.DarkMode, Icons.Default.LightMode, Icons.Default.DarkMode)) { viewModel.setAppearance(AppAppearance.entries[it]) } }
            item { Text(lang.text("Language", "Język"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item { SettingChoices(listOf("English", "Polski"), ui.language.ordinal, listOf(Icons.Default.Language, Icons.Default.Language)) { viewModel.setLanguage(AppLanguage.entries[it]) } }
            item { Divider() }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(lang.text("Automatic sync", "Automatyczna synchronizacja"), fontWeight = FontWeight.SemiBold); Text(lang.text("Refresh school data every 45 minutes while the app is open.", "Odświeżaj dane co 45 minut, gdy aplikacja jest otwarta."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = ui.automaticSync, onCheckedChange = viewModel::setAutomaticSync) } }
            item { Text(lang.text("Connection", "Połączenie"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item { Text(lang.text("LibreCap connects directly to Librus and keeps school data cached locally. No LibreCap server is required.", "LibreCap łączy się bezpośrednio z Librusem i przechowuje dane lokalnie. Serwer LibreCap nie jest wymagany."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Button(onClick = viewModel::logout, modifier = Modifier.fillMaxWidth()) { Text(lang.text("Sign out", "Wyloguj się")) } }
        }
    }
}

@Composable
private fun SettingChoices(labels: List<String>, selected: Int, icons: List<ImageVector>, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { labels.forEachIndexed { index, label -> FilterChip(selected = selected == index, onClick = { onSelect(index) }, label = { Text(label) }, leadingIcon = { Icon(icons[index], null, Modifier.size(18.dp)) }) } }
}

@Composable
private fun NoteEditor(id: String, ui: SchoolUiState, viewModel: SchoolViewModel) {
    val existing = viewModel.note(id)
    var text by remember(id) { mutableStateOf(existing?.text.orEmpty()) }
    var reminds by remember(id) { mutableStateOf(existing?.reminds == true) }
    var noLongerRelevant by remember(id) { mutableStateOf(existing?.isNoLongerRelevant == true) }
    var saved by remember(id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it; saved = false }, label = { Text(ui.language.text("Private note", "Prywatna notatka")) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Row(verticalAlignment = Alignment.CenterVertically) { Text(ui.language.text("Remind me", "Przypomnij mi"), Modifier.weight(1f)); Switch(checked = reminds, onCheckedChange = { reminds = it; saved = false }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text(ui.language.text("No longer relevant", "Nieaktualne"), Modifier.weight(1f)); Switch(checked = noLongerRelevant, onCheckedChange = { noLongerRelevant = it; saved = false }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Button(onClick = { viewModel.saveNote(id, text, reminds, noLongerRelevant); saved = true }) { Text(ui.language.text("Save note", "Zapisz notatkę")) }; if (saved) Text(ui.language.text("Saved", "Zapisano"), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp)) }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) { Text(label, Modifier.weight(.42f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value.ifBlank { "—" }, Modifier.weight(.58f), fontWeight = FontWeight.Medium) }
}

@Composable
private fun EmptyState(title: String, message: String, icon: ImageVector) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary); Text(title, fontWeight = FontWeight.SemiBold); Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

private fun findLesson(data: CachedSchoolData, id: String): TimetableLesson? = data.timetable?.days?.values?.flatten()?.firstOrNull { it.id == id }
private fun List<GradeRecord>.numericAverage(): Double? = mapNotNull { it.numericValue }.averageOrNull()
private fun List<GradeRecord>.averageAcrossSubjects(): Double? =
    groupBy { it.subject }.values.mapNotNull { it.numericAverage() }.averageOrNull()
private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

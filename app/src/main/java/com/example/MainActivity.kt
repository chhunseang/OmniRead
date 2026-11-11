package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.audio.AudiobookPlayerScreen
import com.example.ui.audio.AudiobookPlayerViewModel
import com.example.ui.components.PillNavItem
import com.example.ui.components.BottomFade
import com.example.ui.components.LocalNavBarClearance
import com.example.ui.components.PillNavigationBar
import com.example.ui.components.PillNavigationBarHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.ui.converter.ConverterScreen
import com.example.ui.converter.ConverterViewModel
import com.example.ui.library.BookshelfScreen
import com.example.ui.reader.EbookReaderScreen
import com.example.ui.reader.EbookReaderViewModel
import com.example.ui.theme.MyApplicationTheme
import dagger.hilt.android.AndroidEntryPoint

private const val TAB_LIBRARY = 0
private const val TAB_AUDIOBOOKS = 1
private const val TAB_STUDIO = 2

private val NAV_ITEMS = listOf(
    PillNavItem("Library", Icons.AutoMirrored.Outlined.LibraryBooks, Icons.AutoMirrored.Filled.LibraryBooks, "nav_bookshelf"),
    PillNavItem("Listen", Icons.Outlined.Headphones, Icons.Filled.Headphones, "nav_audiobook_player"),
    PillNavItem("Studio", Icons.Outlined.GraphicEq, Icons.Filled.GraphicEq, "nav_tts_studio")
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var appSettings: com.example.data.settings.AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by appSettings.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                com.example.data.settings.ThemeMode.LIGHT -> false
                com.example.data.settings.ThemeMode.DARK -> true
                com.example.data.settings.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            // Status / navigation bar icons follow the app's theme, not just the phone's
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) androidx.activity.SystemBarStyle.dark(transparent)
                else androidx.activity.SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            MyApplicationTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                OmniReadAppShell()
            }
        }
    }
}

@Composable
fun OmniReadAppShell(
    readerViewModel: EbookReaderViewModel = hiltViewModel(),
    audiobookViewModel: AudiobookPlayerViewModel = hiltViewModel(),
    converterViewModel: ConverterViewModel = hiltViewModel()
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_LIBRARY) }
    // The Reader is not a tab: it opens full-screen over the app when a book is opened.
    var readerOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val openSettings = { settingsOpen = true }

    // Conversion progress / completion is shown as a notification (Android 13+ needs permission)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Audiobook player: a mini player floats above the tab bar; it expands to a full "now playing" view
    val playback by audiobookViewModel.playbackState.collectAsStateWithLifecycle()
    val listenUi by audiobookViewModel.uiState.collectAsStateWithLifecycle()
    val playerOpen = listenUi.playerExpanded && playback.activeBook != null
    val showMiniPlayer = playback.activeBook != null && !playerOpen

    BackHandler(enabled = readerOpen) { readerOpen = false }
    BackHandler(enabled = !readerOpen && settingsOpen) { settingsOpen = false }
    BackHandler(enabled = !readerOpen && !settingsOpen && playerOpen) { audiobookViewModel.collapsePlayer() }
    BackHandler(enabled = !readerOpen && !settingsOpen && !playerOpen && selectedTab != TAB_LIBRARY) { selectedTab = TAB_LIBRARY }

    // Content runs edge-to-edge under the floating pill; screens add this much bottom space
    val gestureInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val miniPlayerSpace = if (showMiniPlayer) com.example.ui.audio.MiniPlayerHeight + 10.dp else 0.dp
    val navClearance = gestureInset + PillNavigationBarHeight + 16.dp + miniPlayerSpace

    // Surface supplies the theme background AND the default content (text/icon) colour
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalNavBarClearance provides navClearance) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "tabs",
                modifier = Modifier.fillMaxSize()
            ) { tab ->
                when (tab) {
                    TAB_LIBRARY -> BookshelfScreen(
                        modifier = Modifier.fillMaxSize(),
                        onOpenEbook = { book ->
                            readerViewModel.openBook(book.id)
                            readerOpen = true
                        },
                        onOpenAudiobook = { book ->
                            audiobookViewModel.openAudiobook(book.id)
                            selectedTab = TAB_AUDIOBOOKS
                        },
                        onConvertBookToAudiobook = { book ->
                            converterViewModel.selectBook(book)
                            selectedTab = TAB_STUDIO
                        },
                        onOpenSettings = openSettings
                    )

                    TAB_AUDIOBOOKS -> AudiobookPlayerScreen(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = audiobookViewModel,
                        onOpenInFullEbookReader = { bookId ->
                            readerViewModel.openBook(bookId)
                            readerOpen = true
                        },
                        onOpenStudio = { selectedTab = TAB_STUDIO },
                        onOpenSettings = openSettings
                    )

                    else -> ConverterScreen(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = converterViewModel,
                        onOpenGeneratedAudiobook = { bookId ->
                            audiobookViewModel.openAudiobook(bookId)
                            selectedTab = TAB_AUDIOBOOKS
                        },
                        onOpenSettings = openSettings
                    )
                }
            }
        }

        // Telegram-style fade behind the floating bar (and mini player), then the bar itself
        BottomFade(
            // Starts exactly at the top edge of the floating bar / mini player
            height = gestureInset + PillNavigationBarHeight + miniPlayerSpace,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = showMiniPlayer,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = gestureInset + PillNavigationBarHeight + 10.dp)
                .padding(horizontal = 12.dp)
        ) {
            com.example.ui.audio.MiniPlayer(
                playback = playback,
                onOpen = audiobookViewModel::expandPlayer,
                onPlayPause = audiobookViewModel::togglePlayPause
            )
        }
        // Status-bar scrim so covers scrolling under the clock stay legible
        com.example.ui.components.TopFade(modifier = Modifier.align(Alignment.TopCenter))
        PillNavigationBar(
            items = NAV_ITEMS,
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Full "now playing" player slides up over everything
        AnimatedVisibility(
            visible = playerOpen,
            enter = slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 420f)) { it },
            exit = slideOutVertically(androidx.compose.animation.core.tween(220)) { it }
        ) {
            com.example.ui.audio.NowPlayingScreen(
                viewModel = audiobookViewModel,
                onCollapse = audiobookViewModel::collapsePlayer,
                onOpenInFullEbookReader = { bookId ->
                    audiobookViewModel.collapsePlayer()
                    readerViewModel.openBook(bookId)
                    readerOpen = true
                }
            )
        }

        // Settings slide in over the tabs (and the mini player)
        AnimatedVisibility(
            visible = settingsOpen,
            enter = androidx.compose.animation.slideInHorizontally { it / 3 } + fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally { it / 3 } + fadeOut()
        ) {
            com.example.ui.settings.SettingsScreen(
                onBack = { settingsOpen = false },
                onOpenStudio = {
                    settingsOpen = false
                    selectedTab = TAB_STUDIO
                },
                onManageStudioAudiobooks = {
                    settingsOpen = false
                    audiobookViewModel.setShelfFilter(com.example.ui.audio.ShelfFilter.STUDIO)
                    selectedTab = TAB_AUDIOBOOKS
                }
            )
        }

        AnimatedVisibility(
            visible = readerOpen,
            enter = slideInVertically { it / 8 } + fadeIn(),
            exit = slideOutVertically { it / 8 } + fadeOut()
        ) {
            EbookReaderScreen(
                modifier = Modifier.fillMaxSize(),
                viewModel = readerViewModel,
                onBackToLibrary = { readerOpen = false }
            )
        }
    }
    }
}

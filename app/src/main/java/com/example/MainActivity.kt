package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.ui.audio.AudiobookPlayerScreen
import com.example.ui.audio.AudiobookPlayerViewModel
import com.example.ui.converter.ConverterScreen
import com.example.ui.converter.ConverterViewModel
import com.example.ui.library.BookshelfScreen
import com.example.ui.reader.EbookReaderScreen
import com.example.ui.reader.EbookReaderViewModel
import com.example.ui.theme.MyApplicationTheme
import dagger.hilt.android.AndroidEntryPoint

private enum class OmniReadTab(
    val index: Int,
    val label: String,
    val icon: ImageVector,
    val testTag: String
) {
    BOOKSHELF(0, "Library", Icons.AutoMirrored.Filled.LibraryBooks, "nav_bookshelf"),
    EBOOK_READER(1, "Reader", Icons.AutoMirrored.Filled.MenuBook, "nav_ebook_reader"),
    AUDIOBOOK_PLAYER(2, "Audiobooks", Icons.Default.Headphones, "nav_audiobook_player"),
    TTS_STUDIO(3, "TTS Studio", Icons.Default.GraphicEq, "nav_tts_studio")
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val useNavigationRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

            MyApplicationTheme {
                OmniReadAppShell(useNavigationRail = useNavigationRail)
            }
        }
    }
}

@Composable
fun OmniReadAppShell(
    useNavigationRail: Boolean,
    readerViewModel: EbookReaderViewModel = hiltViewModel(),
    audiobookViewModel: AudiobookPlayerViewModel = hiltViewModel(),
    converterViewModel: ConverterViewModel = hiltViewModel()
) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(OmniReadTab.BOOKSHELF.index) }

    BackHandler(enabled = selectedTabIndex != OmniReadTab.BOOKSHELF.index) {
        selectedTabIndex = OmniReadTab.BOOKSHELF.index
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!useNavigationRail) {
                NavigationBar {
                    OmniReadTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTabIndex == tab.index,
                            onClick = { selectedTabIndex = tab.index },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag(tab.testTag)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (useNavigationRail) {
                NavigationRail {
                    OmniReadTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = selectedTabIndex == tab.index,
                            onClick = { selectedTabIndex = tab.index },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag(tab.testTag)
                        )
                    }
                }
            }

            when (selectedTabIndex) {
                OmniReadTab.BOOKSHELF.index -> BookshelfScreen(
                    modifier = Modifier.fillMaxSize(),
                    onOpenEbook = { book ->
                        readerViewModel.openBook(book.id)
                        selectedTabIndex = OmniReadTab.EBOOK_READER.index
                    },
                    onOpenAudiobook = { book ->
                        audiobookViewModel.selectAndLoadAudiobook(book.id, autoPlay = true)
                        selectedTabIndex = OmniReadTab.AUDIOBOOK_PLAYER.index
                    },
                    onConvertBookToAudiobook = { book ->
                        converterViewModel.selectBook(book)
                        selectedTabIndex = OmniReadTab.TTS_STUDIO.index
                    }
                )

                OmniReadTab.EBOOK_READER.index -> EbookReaderScreen(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = readerViewModel,
                    onOpenAudiobookConverter = {
                        selectedTabIndex = OmniReadTab.TTS_STUDIO.index
                    }
                )

                OmniReadTab.AUDIOBOOK_PLAYER.index -> AudiobookPlayerScreen(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = audiobookViewModel,
                    onOpenInFullEbookReader = { bookId ->
                        readerViewModel.openBook(bookId)
                        selectedTabIndex = OmniReadTab.EBOOK_READER.index
                    }
                )

                else -> ConverterScreen(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = converterViewModel,
                    onOpenGeneratedAudiobook = { bookId ->
                        audiobookViewModel.selectAndLoadAudiobook(bookId, autoPlay = true)
                        selectedTabIndex = OmniReadTab.AUDIOBOOK_PLAYER.index
                    }
                )
            }
        }
    }
}

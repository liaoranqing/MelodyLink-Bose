package com.melody.melodylink.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melody.melodylink.ui.components.MainBottomNavigation
import com.melody.melodylink.ui.screens.HomeScreen
import com.melody.melodylink.ui.screens.SettingsScreen
import com.melody.melodylink.ui.theme.MelodyLinkTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class MainTab {
    Home,
    Settings
}

@Composable
fun App(
    darkMode: Boolean,
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit
) {
    MelodyLinkTheme(darkTheme = darkMode) {
        var selectedTab by remember { mutableIntStateOf(0) }
        val tabs = listOf(MainTab.Home, MainTab.Settings)
        val pagerState = rememberPagerState(
            initialPage = 0,
            pageCount = { tabs.size }
        )
        val coroutineScope = rememberCoroutineScope()
        val backgroundColor = MiuixTheme.colorScheme.surface

        LaunchedEffect(selectedTab) {
            if (pagerState.currentPage != selectedTab) {
                pagerState.animateScrollToPage(selectedTab)
            }
        }

        LaunchedEffect(pagerState.currentPage) {
            if (selectedTab != pagerState.currentPage) {
                selectedTab = pagerState.currentPage
            }
        }

        Scaffold(
            bottomBar = {
                MainBottomNavigation(
                    tabs = tabs,
                    selectedTab = tabs[selectedTab],
                    onTabClick = { tab ->
                        val index = tabs.indexOf(tab)
                        if (index != -1) {
                            selectedTab = index
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
                    .padding(PaddingValues(bottom = padding.calculateBottomPadding()))
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1
                ) { page ->
                    when (tabs[page]) {
                        MainTab.Home -> HomeScreen(
                            pageBottomContentPadding = 28.dp
                        )
                        MainTab.Settings -> SettingsScreen(
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            pageBottomContentPadding = 28.dp
                        )
                    }
                }
            }
        }
    }
}

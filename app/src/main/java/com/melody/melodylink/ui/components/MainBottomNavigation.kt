package com.melody.melodylink.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import com.melody.melodylink.R
import com.melody.melodylink.ui.MainTab
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MainBottomNavigation(
    tabs: List<MainTab>,
    selectedTab: MainTab,
    onTabClick: (MainTab) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.zIndex(2f),
        color = MiuixTheme.colorScheme.surface,
        showDivider = false,
    ) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onTabClick(tab) },
                icon = when (tab) {
                    MainTab.Home -> Icons.Default.Home
                    MainTab.Settings -> Icons.Default.Settings
                },
                label = when (tab) {
                    MainTab.Home -> stringResource(R.string.home)
                    MainTab.Settings -> stringResource(R.string.settings)
                },
            )
        }
    }
}

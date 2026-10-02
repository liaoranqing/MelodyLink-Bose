package com.melody.melodylink.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.melody.melodylink.BuildConfig
import com.melody.melodylink.R
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit,
    pageBottomContentPadding: Dp
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 12.dp,
            bottom = pageBottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SmallTitle(
                text = stringResource(R.string.appearance),
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
            )
        }

        item {
            Card(
                onClick = { showThemeDialog = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.theme_mode),
                            style = MiuixTheme.textStyles.body1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (themeMode) {
                                1 -> stringResource(R.string.theme_light)
                                2 -> stringResource(R.string.theme_dark)
                                else -> stringResource(R.string.theme_system)
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    }
                }
            }
        }

        item {
            SmallTitle(
                text = stringResource(R.string.about),
                modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp)
            )
        }

        item {
            Card(
                onClick = { showAboutDialog = true }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.app_info),
                        style = MiuixTheme.textStyles.body1
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.version_format, BuildConfig.VERSION_NAME),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }
            }
        }
    }

    // 主题选择对话框
    OverlayDialog(
        title = stringResource(R.string.theme_mode),
        show = showThemeDialog,
        onDismissRequest = { showThemeDialog = false }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            ThemeOption(
                text = stringResource(R.string.theme_system),
                selected = themeMode == 0,
                onClick = {
                    onThemeModeChange(0)
                    showThemeDialog = false
                }
            )
            ThemeOption(
                text = stringResource(R.string.theme_light),
                selected = themeMode == 1,
                onClick = {
                    onThemeModeChange(1)
                    showThemeDialog = false
                }
            )
            ThemeOption(
                text = stringResource(R.string.theme_dark),
                selected = themeMode == 2,
                onClick = {
                    onThemeModeChange(2)
                    showThemeDialog = false
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    // 关于对话框
    OverlayDialog(
        title = stringResource(R.string.about_melodylink),
        show = showAboutDialog,
        onDismissRequest = { showAboutDialog = false }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.version_format, BuildConfig.VERSION_NAME),
                style = MiuixTheme.textStyles.body2
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_description),
                style = MiuixTheme.textStyles.body2
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.supported_brands_title),
                style = MiuixTheme.textStyles.body2
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("• Sony", style = MiuixTheme.textStyles.body2)
            Text("• Samsung", style = MiuixTheme.textStyles.body2)
            Text("• Huawei", style = MiuixTheme.textStyles.body2)
            Text("• Bose", style = MiuixTheme.textStyles.body2)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ThemeOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1
        )
        RadioButton(
            selected = selected,
            onClick = onClick
        )
    }
}

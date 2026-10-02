package com.melody.melodylink.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.melody.melodylink.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen(
    pageBottomContentPadding: Dp
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 12.dp,
            bottom = pageBottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // 模块状态卡片
            Card {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.module_status),
                        style = MiuixTheme.textStyles.title3
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = MiuixTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(8.dp)
                        ) {}
                        Text(
                            text = stringResource(R.string.xposed_activated),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        item {
            // 欢迎信息
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.welcome_title),
                        style = MiuixTheme.textStyles.headline2
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.welcome_subtitle),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }
            }
        }

        item {
            // 支持的设备品牌
            Card {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.supported_devices),
                        style = MiuixTheme.textStyles.title3
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    DeviceBrandItem(stringResource(R.string.device_sony))
                    DeviceBrandItem(stringResource(R.string.device_samsung))
                    DeviceBrandItem(stringResource(R.string.device_huawei))
                    DeviceBrandItem(stringResource(R.string.device_bose))
                }
            }
        }

        item {
            // 使用说明
            Card {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.instructions),
                        style = MiuixTheme.textStyles.title3
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    InstructionItem("1", stringResource(R.string.instruction_1))
                    InstructionItem("2", stringResource(R.string.instruction_2))
                    InstructionItem("3", stringResource(R.string.instruction_3))
                    InstructionItem("4", stringResource(R.string.instruction_4))
                }
            }
        }

        item {
            // 功能特性
            Card {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.features),
                        style = MiuixTheme.textStyles.title3
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    FeatureItem(stringResource(R.string.feature_1))
                    FeatureItem(stringResource(R.string.feature_2))
                    FeatureItem(stringResource(R.string.feature_3))
                    FeatureItem(stringResource(R.string.feature_4))
                }
            }
        }
    }
}

@Composable
private fun DeviceBrandItem(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2
        )
    }
}

@Composable
private fun InstructionItem(number: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            color = MiuixTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(24.dp)
        ) {
            androidx.compose.foundation.layout.Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = number,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun FeatureItem(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.primary
        )
    }
}

package com.example.ddlreminder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RecoveryPage(onHome: () -> Unit, initializationFailed: Boolean = false) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("页面加载失败", style = MaterialTheme.typography.titleLarge)
            Text(if (initializationFailed) "本机组件初始化失败，请完全关闭应用后重试。" else "请返回首页重试。")
            Button(onClick = onHome) { Text("返回首页") }
        }
    }
}

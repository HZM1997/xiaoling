package com.xiaoling.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoling.core.AppState
import com.xiaoling.core.Screen

@Composable
fun GuardianScreen(vm: AppState) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(ui.brainUrl) }

    Column(
        modifier = Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { vm.showScreen(Screen.Home) }) { Text("‹ 返回") }
            Text("家人看护", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(48.dp))
        }
        Text(
            "女儿的手机 · 与老人实时同步",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
        )

        StatCard("本周为老人拦截诈骗", "${ui.fraudBlocked} 次")
        StatCard("紧急呼救记录", ui.sosLabel)
        StatCard("今日用药提醒", if (ui.medsOk) "已按时 ✓" else "待提醒")
        StatCard("同步状态", if (ui.familySynced) "已同步给家人 ✓" else "未同步")

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { vm.syncFamily() },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Text("同步给家人", fontSize = 20.sp) }

        Spacer(Modifier.height(24.dp))
        Text("服务器地址(大脑)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "改成你电脑的局域网地址,例如 http://192.168.1.20:8000",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.setBrainUrl(url) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("保存地址", fontSize = 18.sp) }
    }
}

@Composable
private fun StatCard(title: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 18.sp)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

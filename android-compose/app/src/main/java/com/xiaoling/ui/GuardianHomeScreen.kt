package com.xiaoling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoling.core.AppState
import com.xiaoling.core.Screen
import com.xiaoling.ui.theme.AccentBlue
import com.xiaoling.ui.theme.BgBottom
import com.xiaoling.ui.theme.BgMid
import com.xiaoling.ui.theme.BgTop
import com.xiaoling.ui.theme.DimColor
import com.xiaoling.ui.theme.InkColor

/** 家人端:看护老人的独立视图(实时事件流 + 状态 + 快捷操作) */
@Composable
fun GuardianHomeScreen(vm: AppState) {
    val ui by vm.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, BgMid, BgBottom)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("家人看护", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = InkColor)
                    Text("实时守护家中的老人", fontSize = 13.sp, color = DimColor)
                }
                Surface(
                    onClick = { vm.showScreen(Screen.Settings) },
                    shape = CircleShape, color = Color.White.copy(alpha = 0.7f), shadowElevation = 2.dp,
                    modifier = Modifier.size(44.dp)
                ) { Box(contentAlignment = Alignment.Center) { Text("⚙", fontSize = 20.sp) } }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE9F7EE))
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("👴", fontSize = 34.sp)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("爸爸 · 一切安好", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B7A44))
                        Text("本周拦截诈骗 ${ui.fraudBlocked} 次 · 呼救 ${ui.sosLabel}", fontSize = 13.sp, color = DimColor)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.callElder() }, modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp)) { Text("拨打老人电话") }
                OutlinedButton(onClick = { vm.showScreen(Screen.Settings) }, modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp)) { Text("看护设置") }
            }

            Spacer(Modifier.height(18.dp))
            Text("实时提醒", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = InkColor)
            Spacer(Modifier.height(8.dp))
            if (ui.familyEvents.isEmpty()) {
                Card(
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.82f))
                ) {
                    Text("暂无提醒。老人端遇到诈骗/呼救时,这里会实时收到。",
                        fontSize = 14.sp, color = DimColor, modifier = Modifier.padding(18.dp))
                }
            } else {
                ui.familyEvents.forEach { ev ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp), shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (ev.contains("诈骗") || ev.contains("呼救"))
                                Color(0xFFFDEBEC) else Color.White.copy(alpha = 0.85f)
                        )
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (ev.contains("诈骗")) "⚠" else if (ev.contains("呼救")) "🆘" else "🔔", fontSize = 22.sp)
                            Spacer(Modifier.size(10.dp))
                            Text(ev, fontSize = 15.sp, color = InkColor, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

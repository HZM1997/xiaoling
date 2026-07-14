package com.xiaoling.ui

import android.app.role.RoleManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoling.R
import com.xiaoling.core.AppState
import com.xiaoling.core.Screen
import com.xiaoling.ui.theme.AccentBlue
import com.xiaoling.ui.theme.BgBottom
import com.xiaoling.ui.theme.BgTop
import com.xiaoling.ui.theme.DimColor

@Composable
fun SettingsScreen(vm: AppState) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(ui.brainUrl) }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }
    fun requestScreenRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", fontSize = 30.sp, color = AccentBlue,
                    modifier = Modifier.clickable { vm.showScreen(Screen.Home) })
                Spacer(Modifier.size(10.dp))
                Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))

            // 用户信息
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.avatar),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.size(56.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text("用户", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("ID: 100086", fontSize = 14.sp, color = DimColor)
                    }
                }
            }

            // 会员中心
            SettingCard {
                Text("小灵会员", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                listOf("无限畅聊", "明星/亲人语音包", "健康用药管家", "亲情守护看护", "专属人工客服")
                    .forEach { Text("· $it", fontSize = 15.sp, color = DimColor,
                        modifier = Modifier.padding(vertical = 3.dp)) }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("开通会员", fontSize = 18.sp) }
            }

            // 家人看护
            SettingCard {
                Text("家人看护", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                StatRow("累计拦截诈骗", "${ui.fraudBlocked} 次")
                StatRow("紧急呼救记录", ui.sosLabel)
                StatRow("同步状态", if (ui.familySynced) "已同步 ✓" else "未同步")
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { vm.syncFamily() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("同步给家人") }
            }

            // 来电防诈
            SettingCard {
                Text("来电防诈", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("把小灵设为「来电识别/防骚扰」应用,可疑来电自动预警",
                    fontSize = 13.sp, color = DimColor, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                Button(
                    onClick = { requestScreenRole() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("设为来电防诈助手") }
            }

            // 服务器地址(高级)
            SettingCard {
                Text("服务器地址(高级)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("联调用:填电脑局域网地址,如 http://192.168.1.20:8000",
                    fontSize = 13.sp, color = DimColor, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                OutlinedTextField(value = url, onValueChange = { url = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.setBrainUrl(url) },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("保存地址") }
            }

            Spacer(Modifier.height(20.dp))
            Text("小灵 · v1.0", fontSize = 13.sp, color = DimColor,
                modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SettingCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun StatRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
    }
}


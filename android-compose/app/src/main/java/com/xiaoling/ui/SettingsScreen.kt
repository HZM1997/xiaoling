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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    var payPlan by remember { mutableStateOf<String?>(null) }

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

            // 模块二:会员权益中心
            SettingCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("会员权益中心", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "当前:" + membershipLabel(ui.membership),
                        fontSize = 14.sp,
                        color = if (ui.membership.isEmpty()) DimColor else AccentBlue,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(10.dp))
                PlanCard("基础会员", "¥29.9 / 月",
                    listOf("无限畅聊陪伴", "健康用药提醒", "来电 + 短信防诈"),
                    plan = "basic", current = ui.membership == "basic", onBuy = { payPlan = it })
                Spacer(Modifier.height(10.dp))
                PlanCard("高级会员", "¥299 / 年",
                    listOf("基础会员全部权益", "明星 / 亲人语音包", "亲情守护 · 家人看护", "专属人工客服"),
                    plan = "premium", highlight = true, current = ui.membership == "premium", onBuy = { payPlan = it })
            }

            // 形象:Live2D 开关
            SettingCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("3D 形象(Live2D)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("需先放入模型与运行时,详见 NATIVE.md;未就绪时保持关闭",
                            fontSize = 12.sp, color = DimColor, modifier = Modifier.padding(top = 2.dp))
                    }
                    Switch(checked = ui.live2d, onCheckedChange = { vm.setLive2d(it) })
                }
            }

            // 模块三:家人看护
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
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { requestScreenRole() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("设为来电防诈助手") }
            }

            // 开发者:服务器地址(联调用,不属于三大模块)
            SettingCard {
                Text("开发者 · 服务器地址", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DimColor)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = url, onValueChange = { url = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.setBrainUrl(url) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("保存地址") }
            }

            Spacer(Modifier.height(20.dp))
            Text("小灵 · v1.0", fontSize = 13.sp, color = DimColor,
                modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(20.dp))
        }

        val p = payPlan
        if (p != null) {
            AlertDialog(
                onDismissRequest = { payPlan = null },
                title = { Text("选择支付方式") },
                text = { Text("开通 " + membershipLabel(p) + "(" + (if (p == "basic") "¥29.9/月" else "¥299/年") + ")") },
                confirmButton = {
                    TextButton(onClick = { vm.buyPlan(p, "微信支付"); payPlan = null }) { Text("微信支付") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.buyPlan(p, "支付宝"); payPlan = null }) { Text("支付宝") }
                }
            )
        }
    }
}

private fun membershipLabel(tier: String): String = when (tier) {
    "basic" -> "基础会员"
    "premium" -> "高级会员"
    else -> "未开通"
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

@Composable
private fun PlanCard(
    name: String,
    price: String,
    benefits: List<String>,
    plan: String,
    highlight: Boolean = false,
    current: Boolean = false,
    onBuy: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (highlight) Color(0xFFFFF6E6) else Color(0xFFF1F6FF))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(price, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = if (highlight) Color(0xFFB8860B) else AccentBlue)
        }
        Spacer(Modifier.height(6.dp))
        benefits.forEach {
            Text("· $it", fontSize = 14.sp, color = DimColor, modifier = Modifier.padding(vertical = 2.dp))
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { if (!current) onBuy(plan) },
            enabled = !current,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp)
        ) { Text(if (current) "已开通" else "立即开通", fontSize = 16.sp) }
    }
}


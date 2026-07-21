package com.xiaoling.ui

import android.app.role.RoleManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoling.R
import com.xiaoling.BuildConfig
import com.xiaoling.core.AppState
import com.xiaoling.core.Screen
import com.xiaoling.ui.theme.AccentBlue
import com.xiaoling.ui.theme.AccentGlow
import com.xiaoling.ui.theme.BgBottom
import com.xiaoling.ui.theme.BgMid
import com.xiaoling.ui.theme.BgTop
import com.xiaoling.ui.theme.DimColor
import com.xiaoling.ui.theme.InkColor

private val TABS = listOf("用户信息", "会员权益", "家人看护")

@Composable
fun SettingsScreen(vm: AppState) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var payPlan by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var realNameDialog by remember { mutableStateOf(false) }

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
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, BgMid, BgBottom)))
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 18.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", fontSize = 32.sp, color = AccentBlue,
                    modifier = Modifier.clickable { vm.showScreen(Screen.Home) }.padding(horizontal = 12.dp))
                Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = InkColor)
            }
            // 页签
            TabBar(tab) { tab = it }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(6.dp))
                when (tab) {
                    0 -> UserTab(ui, showText = { title, body -> dialog = title to body },
                        onLogin = { vm.showScreen(Screen.Login) }, onLogout = { vm.logout() },
                        onRealName = { realNameDialog = true })
                    1 -> MemberTab(ui) { payPlan = it }
                    2 -> CareTab(ui, onSync = { vm.syncFamily() }, onRole = { requestScreenRole() },
                        onLive2d = { vm.setLive2d(it) }, onUpgrade = { tab = 1 })
                }
                Spacer(Modifier.height(28.dp))
                Text("小灵 · v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = DimColor,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
            }
        }

        // 支付方式
        payPlan?.let { p ->
            AlertDialog(
                onDismissRequest = { payPlan = null },
                title = { Text("选择支付方式") },
                text = { Text("开通 " + memberLabel(p) + "(" + (if (p == "basic") "¥29.9/月" else "¥299/年") + ")") },
                confirmButton = { TextButton(onClick = { vm.buyPlan(p, "微信支付"); payPlan = null }) { Text("微信支付") } },
                dismissButton = { TextButton(onClick = { vm.buyPlan(p, "支付宝"); payPlan = null }) { Text("支付宝") } }
            )
        }
        // 文本弹窗(隐私摘要/反馈等)
        dialog?.let { (title, body) ->
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(title) },
                text = { Text(body, fontSize = 15.sp, lineHeight = 23.sp) },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("知道了") } }
            )
        }
        if (realNameDialog) {
            RealNameDialog(
                onDismiss = { realNameDialog = false },
                onSubmit = { name, idNo ->
                    vm.verifyRealName(name, idNo)
                    realNameDialog = false
                }
            )
        }
    }
}

@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp)
            .clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.6f)).padding(4.dp)
    ) {
        TABS.forEachIndexed { i, t ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(13.dp))
                    .background(if (on) AccentBlue else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(t, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) Color.White else DimColor)
            }
        }
    }
}

/* ---------------- 模块一:用户信息 ---------------- */
@Composable
private fun UserTab(
    ui: com.xiaoling.core.UiState,
    showText: (String, String) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onRealName: () -> Unit
) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.avatar),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.size(60.dp).clip(CircleShape).background(Color.White)
            )
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (ui.loggedIn) "已登录" else "未登录", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    (if (ui.loggedIn) maskPhone(ui.phone) else "点击登录同步会员与家人看护") + " · " + memberLabel(ui.membership),
                    fontSize = 13.sp, color = DimColor
                )
            }
            if (ui.loggedIn) {
                Text("退出", fontSize = 14.sp, color = AccentBlue,
                    modifier = Modifier.clickable { onLogout() }.padding(6.dp))
            } else {
                Text("登录", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentBlue,
                    modifier = Modifier.clickable { onLogin() }.padding(6.dp))
            }
        }
    }
    GlassCard {
        StatRow("服务状态", if (ui.online) "在线" else "离线 · 本地可用")
        Divider()
        RowItem("账号与安全") { if (ui.loggedIn) showText("账号与安全", "· 手机号:" + maskPhone(ui.phone) + "\n· 登录设备管理\n· 注销账号") else onLogin() }
        Divider()
        RowItem(if (ui.realNameVerified) "实名认证 · 已完成" else "实名认证") {
            if (!ui.loggedIn) onLogin()
            else if (ui.realNameVerified) showText(
                "实名认证",
                "已认证用户:${ui.displayName}\n已获赠永久无限畅聊陪伴。\n证件号码不会保存在手机中。"
            ) else onRealName()
        }
        if (ui.realNameStatus.isNotBlank()) {
            Text(ui.realNameStatus, fontSize = 13.sp,
                color = if (ui.realNameVerified) AccentBlue else DimColor,
                modifier = Modifier.padding(vertical = 8.dp))
        }
        Divider()
        RowItem("隐私保护设置") { showText("隐私保护设置", "· 通话/短信仅用于本机防诈判定,默认不上传\n· 麦克风仅在唤起对话时使用\n· 可随时在系统设置撤销各项权限") }
        Divider()
        RowItem("意见反馈") { showText("意见反馈", "感谢您的使用!反馈请发送至 feedback@xiaoling.ai,我们会认真对待每一条建议。") }
        Divider()
        RowItem("隐私政策摘要") { showText("隐私政策摘要", "我们仅收集为您提供防诈、呼救、陪伴所必需的最少信息;敏感数据尽量在本机处理;不向第三方出售您的个人信息。完整政策见官网。") }
    }
    ProfileCard()
}

/** 个人资料:称呼/偏好/常联系人 —— 随请求传给智能大脑,让它更懂这位老人 */
@Composable
private fun ProfileCard() {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(com.xiaoling.core.Profile.name(ctx)) }
    var prefs by remember { mutableStateOf(com.xiaoling.core.Profile.prefs(ctx)) }
    var contacts by remember { mutableStateOf(com.xiaoling.core.Profile.contacts(ctx)) }
    var saved by remember { mutableStateOf(false) }
    GlassCard {
        Text("个人资料 · 让小灵更懂您", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("告诉小灵怎么称呼您、爱好、常联系的人,它会更贴心", fontSize = 12.sp, color = DimColor,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        OutlinedTextField(value = name, onValueChange = { name = it; saved = false },
            label = { Text("称呼(如 王奶奶)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = prefs, onValueChange = { prefs = it; saved = false },
            label = { Text("爱好(如 爱听京剧、喜欢散步)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = contacts, onValueChange = { contacts = it; saved = false },
            label = { Text("常联系人(如 女儿小芳、老伴)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Button(onClick = {
            com.xiaoling.core.Profile.save(ctx, name, prefs, contacts); saved = true
        }, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (saved) "已保存 ✓" else "保存资料")
        }
    }
}

private fun maskPhone(p: String): String =
    if (p.length == 11) p.substring(0, 3) + "****" + p.substring(7) else p


/* ---------------- 模块二:会员权益 ---------------- */
@Composable
private fun MemberTab(ui: com.xiaoling.core.UiState, onBuy: (String) -> Unit) {
    GlassCard {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("会员权益中心", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("当前:" + memberLabel(ui.membership), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = if (ui.membership.isEmpty()) DimColor else AccentBlue)
        }
        if (ui.chatEntitlement == "lifetime_unlimited") {
            Spacer(Modifier.height(10.dp))
            Text("永久无限畅聊陪伴 · 已赠送", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                color = AccentBlue, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        PlanCard("基础会员", "¥29.9 / 月", listOf("无限畅聊陪伴 · 实名免费", "健康用药提醒", "来电 + 短信防诈"),
            plan = "basic", current = ui.membership == "basic", onBuy = onBuy)
        Spacer(Modifier.height(12.dp))
        PlanCard("高级会员", "¥299 / 年",
            listOf("基础会员全部权益", "明星 / 亲人语音包", "3D 数字人形象", "亲情守护 · 家人看护", "专属人工客服"),
            plan = "premium", highlight = true, current = ui.membership == "premium", onBuy = onBuy)
    }
}

@Composable
private fun RealNameDialog(onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var idNo by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("实名认证") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("真实姓名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = idNo,
                    onValueChange = { idNo = it.uppercase().filter { ch -> ch.isDigit() || ch == 'X' }.take(18) },
                    label = { Text("身份证号码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(name.trim(), idNo.trim()) },
                enabled = name.trim().length >= 2 && idNo.length == 18
            ) { Text("绑定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/* ---------------- 模块三:家人看护 ---------------- */
@Composable
private fun CareTab(
    ui: com.xiaoling.core.UiState, onSync: () -> Unit, onRole: () -> Unit,
    onLive2d: (Boolean) -> Unit, onUpgrade: () -> Unit
) {
    val premium = ui.membership == "premium"
    GlassCard {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("家人看护", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (!premium) LockTag(onUpgrade)
        }
        Spacer(Modifier.height(8.dp))
        StatRow("恶意来电及短信拦截", "${ui.fraudBlocked} 次")   // 防诈拦截:免费
        StatRow("紧急呼救记录", ui.sosLabel)
        StatRow("跨设备同步", if (!premium) "高级会员" else if (ui.familySynced) "已同步 ✓" else "未同步")
        StatRow("智能体能力", if (ui.agentCapabilityCount > 0) "${ui.agentCapabilityCount} 项 · 自动更新" else "等待服务连接")
        Spacer(Modifier.height(12.dp))
        Button(onClick = onSync, modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)) { Text(if (premium) "同步给家人" else "同步给家人(高级会员)", fontSize = 16.sp) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRole, modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)) { Text("设为来电防诈助手") }
    }
    GlassCard {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("3D 数字人形象", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (premium) "放入 VRM 模型后开启,详见 NATIVE.md" else "高级会员专享 · 开通后解锁",
                    fontSize = 12.sp, color = DimColor, modifier = Modifier.padding(top = 2.dp))
            }
            if (premium) Switch(checked = ui.live2d, onCheckedChange = onLive2d)
            else LockTag(onUpgrade)
        }
    }
    GlassCard {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("明星 / 亲人语音包", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (premium) "已解锁,可在语音设置中挑选" else "高级会员专享 · 换成明星或亲人的声音陪伴",
                    fontSize = 12.sp, color = DimColor, modifier = Modifier.padding(top = 2.dp))
            }
            if (!premium) LockTag(onUpgrade) else Text("›", fontSize = 20.sp, color = DimColor)
        }
    }
}

@Composable
private fun LockTag(onUpgrade: () -> Unit) {
    Text("🔒 去开通", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB8860B),
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFFFFF0CC))
            .clickable { onUpgrade() }.padding(horizontal = 12.dp, vertical = 6.dp))
}


/* ---------------- 复用组件 ---------------- */
@Composable
private fun GlassCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.82f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) { Column(Modifier.padding(18.dp), content = content) }
}

@Composable
private fun RowItem(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 16.sp, color = InkColor)
        Text("›", fontSize = 20.sp, color = DimColor)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x11000000)))
}

@Composable
private fun StatRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), Arrangement.SpaceBetween) {
        Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
    }
}

@Composable
private fun PlanCard(
    name: String, price: String, benefits: List<String>, plan: String,
    highlight: Boolean = false, current: Boolean = false, onBuy: (String) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(
                if (highlight) Brush.verticalGradient(listOf(Color(0xFFFFF3DC), Color(0xFFFFF9EF)))
                else Brush.verticalGradient(listOf(Color(0xFFEDF1FF), Color(0xFFF7F9FF)))
            ).padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (highlight) {
                    Spacer(Modifier.size(6.dp))
                    Text("推荐", fontSize = 11.sp, color = Color(0xFF8A5A00),
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFFFE0A3)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            Text(price, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = if (highlight) Color(0xFFB8860B) else AccentBlue)
        }
        Spacer(Modifier.height(6.dp))
        benefits.forEach { Text("· $it", fontSize = 14.sp, color = DimColor, modifier = Modifier.padding(vertical = 2.dp)) }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { if (!current) onBuy(plan) }, enabled = !current,
            modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(13.dp)
        ) { Text(if (current) "已开通" else "立即开通", fontSize = 16.sp) }
    }
}

private fun memberLabel(tier: String): String = when (tier) {
    "basic" -> "基础会员"; "premium" -> "高级会员"; else -> "未开通"
}

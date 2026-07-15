package com.xiaoling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoling.core.AppState
import com.xiaoling.core.Screen
import com.xiaoling.ui.theme.AccentBlue
import com.xiaoling.ui.theme.BgBottom
import com.xiaoling.ui.theme.BgMid
import com.xiaoling.ui.theme.BgTop
import com.xiaoling.ui.theme.DimColor
import com.xiaoling.ui.theme.InkColor

@Composable
fun LoginScreen(vm: AppState) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, BgMid, BgBottom)))
    ) {
        Text("‹ 返回", fontSize = 16.sp, color = AccentBlue,
            modifier = Modifier.align(Alignment.TopStart).padding(20.dp)
                .clickable { vm.showScreen(Screen.Settings) })

        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("🐝", fontSize = 56.sp)
            Spacer(Modifier.height(10.dp))
            Text("登录小灵", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = InkColor)
            Text("会员与家人看护将跟随你的账号", fontSize = 13.sp, color = DimColor,
                modifier = Modifier.padding(top = 4.dp, bottom = 28.dp))

            OutlinedTextField(
                value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
                label = { Text("手机号") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code, onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("验证码(演示:1234)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { if (phone.length >= 6) { vm.sendCode(phone); sent = true } },
                modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(14.dp)
            ) { Text(if (sent) "已发送验证码(演示 1234)" else "获取验证码") }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { if (phone.length >= 6 && code.isNotBlank()) vm.login(phone, code) },
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)
            ) { Text("登录 / 注册", fontSize = 18.sp) }

            Spacer(Modifier.height(14.dp))
            Text("— 或 —", fontSize = 12.sp, color = DimColor)
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { vm.wxLogin() },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF07C160), contentColor = androidx.compose.ui.graphics.Color.White
                )
            ) { Text("微信一键登录", fontSize = 18.sp) }

            if (ui.caption.contains("验证码") || ui.caption.contains("登录")) {
                Spacer(Modifier.height(12.dp))
                Text(ui.caption, fontSize = 13.sp, color = DimColor, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(20.dp))
            Text("登录即同意《隐私政策》与《用户协议》", fontSize = 11.sp, color = DimColor)
        }
    }
}

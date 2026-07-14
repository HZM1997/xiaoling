# 小灵 · 原生 Android App(Jetpack Compose)

这是一个**真正的原生应用**(Kotlin + Compose),不是网页/演示视频。交互模型 = **免手动·常听式**:
进 App 就在听,直接开口说 → 系统语音识别(中文)→ 云端「大脑」理解 → 系统 TTS 回应 → 真实执行(拨号/导航/呼救)。
断网时,**呼救 / 红线诈骗词**由本地兜底(`LocalSafetyNet`),不依赖网络。

## 换成你的 3D 角色形象(重要)
主界面的角色用的是 `res/drawable/avatar`。**把你那张 3D 图存成 `avatar.png`,放到
`android-compose/app/src/main/res/drawable/`,并删除同目录下的占位 `avatar.xml`**(两者同名会冲突),
然后重新出包即可。图建议正方形、半身(头+肩)、背景干净。

## 界面(极简)
- **主界面**只有:**角色半身像** + 右上角一个**设置**齿轮。没有"点击说话"按钮——**免手动,常听**。
- **设置**里包含:**用户信息、会员中心、家人看护(拦截/呼救/同步)、来电防诈、服务器地址(高级)**。
- 浅色亲民 + 高级极简风。

## 能力(v1)
- 语音打电话:「打电话给女儿」→ 查通讯录 → 拉起拨号
- 紧急呼救:「我胸口疼 / 救命」→ 直接拨 120(离线也生效)
- 语音导航:「导航到人民医院」→ 拉起手机地图
- 防诈检测 + 陪伴:识别到红线诈骗词 → 形象变红报警 + 语音提醒;闲聊温暖回应
- 子女端·看护:第二个页面,拦截诈骗次数 / 呼救记录 / 用药,「同步给家人」

## 架构
```
点按 → SpeechController(SpeechRecognizer zh-CN)
     → AppState(ViewModel 编排)
         → 本地优先:LocalSafetyNet(呼救/红线,离线即时)
         → 否则 BrainClient → POST {服务器}/dialogue → {speech,action,skill,risk}
     → Tts 朗读 + ActionDispatcher 执行 Intent
     → BeeMascot 按状态变表情(Idle/Listening/Thinking/Talking/Alarm/Caring)
```
包名 `com.xiaoling`。云端大脑复用仓库里的 `server/`(FastAPI)。

## 出 APK(云端,推荐)
推到 GitHub 后:Actions → **Build Compose APK** → Run workflow → 跑完在 Artifacts 下载
`xiaoling-native-debug-apk`(即 `app-debug.apk`)→ 安卓手机允许「未知来源」安装。

## 本地出 APK(需 Android Studio / SDK)
用 Android Studio 打开 `android-compose/` 目录,直接 Run;或命令行:
```bash
cd android-compose
gradle wrapper --gradle-version 8.7   # 首次生成 wrapper(或用 Android Studio 自带 gradle)
./gradlew assembleDebug
# 产物:app/build/outputs/apk/debug/app-debug.apk
```

## 连大脑联调(真机 + 电脑本地 server)
1. 电脑跑大脑:`cd server && pip install -r requirements.txt && uvicorn main:app --host 0.0.0.0 --port 8000`
   (或零依赖 Node 版:`cd demo && node xiaoling.js --serve`)
2. 手机与电脑**同一 WiFi**;查电脑局域网 IP;**防火墙放行 8000**。
3. App 里进「家人看护」页 → 填服务器地址 `http://<电脑IP>:8000` → 保存。
   (也可改 `app/build.gradle` 里的 `BRAIN_URL` 默认值后重新出包。)
4. 回主页点「点我说话」,说:「打电话给张三」「导航到人民医院」「我胸口疼」→ 验证语音回应 + 真实拉起拨号/地图/120。
5. **关掉服务器**再说「我胸口疼」或「让你屏幕共享…」→ 本地兜底仍拨 120 / 弹红色防诈警告。

## 首次装机会申请的权限
麦克风(识别)、电话(拨号/呼救)、通讯录(找联系人)、定位(呼救发位置)。全部允许。

## 角色动态表现(强视觉反馈)
角色按状态给不同动效,**单张图也能有强反馈**:
- **危险(高危来电/短信)**:红光暴闪 + 抖动 + 红框 + 头顶⚠ + 语音警告 + 震动 —— 一眼看懂"警惕"。
- 聆听:蓝色涟漪;思考:蓝光;说话:轻微口型抖;陪伴:暖粉光 + 轻摆;待机:缓慢呼吸。

**真正换"表情"(脸变/会动)** 有两条路:
- **A|动态图(现在就支持)**:把角色做成一小段**循环动画**(眨眼/说话/警惕),导出**动态 WebP 或 GIF**,放到
  `android-compose/app/src/main/assets/`,命名 `avatar.webp`(或分表情 `avatar_alarm.webp / avatar_happy.webp /
  avatar_listening.webp / avatar_thinking.webp / avatar_talking.webp`)。App 会**播放真动态**,没有就回退静态图。
- **B|Live2D 骨骼(最像"活的",可后续接)**:把角色绑成 Live2D 模型(需美术/建模),再接 WebView 运行时——工程量更大,想上我再帮你接。

## 极致反应(说完约 1~2 秒就回)
- 语音识别**停顿 0.8 秒即定稿**,不空等;
- **打电话/导航/提醒/听** 等高频指令走**端侧快通道**,本地即时回、不等网络;
- 只有复杂对话才走云端,且云端**超时压到 3.5 秒**,超时立即本地兜底;
- 常听循环重启延时压到 0.4 秒。

## 收到高危短信/来电 → 角色即时警惕
- **短信**:App 端侧判定进来的短信(红线词/高频诈骗词),高危 → 角色当场变红报警 + 高优先级通知(需授 `RECEIVE_SMS`;首次进 App 会一并申请)。
- **来电**:设为「来电防诈助手」后,可疑号码来电 → 通知预警 + 打开 App 时角色警惕。
- App 没开着时:靠通知先顶上,**下次打开 App 会读取 2 分钟内的诈骗事件再警惕一次**。
> 诚实说明:①第三方 App 拿不到**通话音频内容**,来电只能按号码判;②读短信权限敏感,上架商店受限,自测 APK 没问题。

## 后续(可选,需额外依赖)
- **常驻语音唤醒词**(说「小灵小灵」免点按):接 Picovoice Porcupine(需 AccessKey + 依赖)或自训 KWS + 常驻前台服务。
- **来电内容级防诈**:Android 不开放第三方读取通话音频,需厂商/系统级能力,普通 App 做不到。
- **Live2D 精致形象**:在原生里用 WebView 承载 `demo/live2d.js` + 模型,或用 GL 渲染;较重,按需接。

# 小灵 · 原生 Android App(Jetpack Compose)

这是一个**真正的原生应用**(Kotlin + Compose),不是网页/演示视频。交互模型 = **点按说话**:
开口说 → 系统语音识别(中文)→ 云端「大脑」理解 → 系统 TTS 回应 → 真实执行(拨号/导航/呼救)。
断网时,**呼救 / 红线诈骗词**由本地兜底(`LocalSafetyNet`),不依赖网络。

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

## 说明与后续
- 普通电话用 `ACTION_DIAL`(免额外授权、更安全);紧急呼救用 `ACTION_CALL` 直接拨 120。
- 中文 TTS 依赖系统语音包;老人机若没有,系统会引导安装,没有则仅显示字幕。
- 二期:常驻语音唤醒词、来电实时拦截(CallScreeningService)、跨设备家人推送、Live2D 精致形象。

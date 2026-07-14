# 安卓客户端 · 构建与真机运行指南

> 目标:在真机上跑通 **唤醒 →(方言)识别 → 云端大脑 → 播报 → 调起 App** 的闭环,
> 并演示 **来电防诈** 与 **一键呼救**。

## 0. 前置
- Android Studio(Koala/2024.1+),JDK 17
- 一部 Android 7.0(API 24)以上真机,开启「USB 调试」
- 云端大脑已启动(二选一):
  - Node 版(本机最省事):`cd demo && node xiaoling.js --serve` → `http://<你电脑IP>:8000`
  - Python 版:`cd server && uvicorn main:app --host 0.0.0.0 --port 8000`

## 1. 打开工程
用 Android Studio 打开 `android/` 目录(不是仓库根)。首次会自动下载 Gradle 8.5 / AGP 8.5。
> 若无 gradle wrapper:Android Studio 会提示生成,或本机执行 `gradle wrapper --gradle-version 8.5`。

## 2. 配置大脑地址
编辑 [app/src/main/java/com/jingling/VoiceAgent.kt](app/src/main/java/com/jingling/VoiceAgent.kt) 的
`brainUrl`,改成你电脑的局域网地址,例如 `http://192.168.1.20:8000/dialogue`。
> 真机与电脑需同一 WiFi。若用 http 明文,`AndroidManifest` 的 application 加
> `android:usesCleartextTraffic="true"`(仅调试用)。

## 3. 接入方言 ASR(讯飞,可选但推荐)
1. 到讯飞开放平台创建应用,拿到 **APPID**,下载「语音听写(流式版)」Android SDK。
2. 把 SDK 的 `.aar` 放到 `app/libs/`,`so` 放到 `app/src/main/jniLibs/`。
3. 在 `app/build.gradle` 依赖加:`implementation(name:'Msc', ext:'aar')`(名字以实际 aar 为准)。
4. 在 `Application.onCreate` 里 `SpeechUtility.createUtility(this, "appid=你的APPID")`。
5. 填充 [IflytekAsr.kt](app/src/main/java/com/jingling/IflytekAsr.kt) 里的伪代码,
   `accent` 用 `IflytekAsr.ACCENTS` 里的映射(粤语/四川话/河南话…)。
> **不接也能跑**:VoiceAgent 默认用系统 `SpeechRecognizer` 降级(仅普通话)。

## 4. 端侧离线唤醒(可选)
[WakeService.kt](app/src/main/java/com/jingling/WakeService.kt) 里接 Picovoice Porcupine
(自定义唤醒词"小灵小灵"),命中回调触发 `VoiceAgent.onWake()`。
> 未接时:用主界面的「按住说话」大按钮兜底,一样能走完整闭环。

## 5. 运行 & 授权
1. Run ▶ 安装到真机。
2. 首次进入会申请:录音 / 电话 / 通讯录 / 定位 —— 全部允许。
3. 演示 **防诈**:设置 → 默认应用 → 来电识别,选「小灵」,授权 `FraudCallScreeningService`。
4. 说「打电话给张三」「导航到人民医院」「我胸口疼」验证闭环。

## 6. 出包瘦身(APK < 15MB)
- `build.gradle` 已开 `minifyEnabled + shrinkResources`。
- 只保留 `armeabi-v7a`/`arm64-v8a` 两个 abi;不打包大模型(全在云端)。
- Release 出包:`./gradlew assembleRelease`,产物在 `app/build/outputs/apk/release/`。

## 常见问题
- **调不通大脑**:确认真机能 ping 通电脑 IP、防火墙放行 8000、用同一 WiFi。
- **中文乱码**:请求体用 UTF-8(客户端已处理;命令行测试别在 Windows 终端内联中文)。
- **服务被杀**:老年机厂商(如某些定制系统)需手动加「自启动/后台保活」白名单。

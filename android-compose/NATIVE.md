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
## 跨设备实时推送(家人看护)
老人机遇到诈骗/呼救 → 上报服务器 `POST /push/emit` → 服务器广播给**家庭组** → 家人设备用 **SSE 长连接**
(`GET /push/subscribe?family_id=...`)**实时收到** + 语音播报。App 在线即时生效,断线自动重连。
> 送达「**关着的** App」需叠加**厂商推送**(极光/个推/华为/小米/APNs):`PushClient.emit` 里已标注接入位置。
> 家庭组 id 现为占位 `family-100086`,接账号体系后与真实家庭组绑定。

## 退出后瞬间唤起 / 离线语音唤起
- `WakeService` 是**常驻前台服务**(进 App 即启动),让「回到手机主屏后也能被唤起」有了载体;通知栏常驻,点通知即回 App。
- **离线唤醒词**(说「小灵小灵」免手动、且不依赖网络)需接**离线唤醒引擎**——已留可插拔位:
  - Picovoice Porcupine(需 AccessKey);或 Vosk(离线模型)。在 `WakeService.startWakeEngine()` 接上,命中 → `wakeUp()` 拉起 App 并开听。
- 唤醒**离线可用**;唤醒后复杂对话若离线,走**本地快通道/兜底**(打电话/呼救/红线防诈等)。
> 诚实说明:①常驻服务真实生效,但部分厂商定制系统需手动把 App 加入「自启动/后台保活」白名单;②"离线语音唤起"这一步必须接唤醒引擎,纯系统能力做不到常驻离线监听。

## 3D 数字人(真 3D,非 Live2D)
`assets/avatar3d/index.html` 用 **three.js + three-vrm** 渲染 **VRM 数字人**:表情 blendshape(高兴/惊讶/警惕/难过/眨眼)、口型、头部微动;原生按状态/说话经 JS 桥驱动。
- **联网即用**:默认从 CDN 加载运行时 + 一个**免费样例 VRM**,「设置 → 家人看护 → 3D 数字人形象」开启即可看到真 3D 数字人动起来(证明管线通)。⚠ 样例模型仅供测试,商用请换你自研/授权的模型。
- **换成你的角色**:把你的 `.vrm` 放到 `assets/avatar3d/model.vrm`(单张 PNG 生成不了 VRM,需美术/建模或用 VRoid 生成)。
- **离线化**:`cd android-compose && bash fetch-avatar3d.sh` 把运行时 + 样例模型下载到 `assets/`,再按脚本提示把 index.html 的 importmap 改成本地路径。
> 注:VRM 渲染依赖手机 WebView 版本(需较新 Chromium WebView,支持 ES 模块/importmap);过旧 WebView 会显示提示,原生自动回退透明 PNG 形象。

## 账号体系(手机号 / 微信登录 · 会员/家庭组跟账号走)
- 「设置 → 用户信息 → 登录」:**手机号 + 验证码**(演示验证码 **1234**),或 **微信一键登录**。
- 登录后:**会员**与**家庭组**跟账号走;退出登录清除会话。
- **会员权益真正生效**(高级会员专享,未开通上锁+去开通引导):
  - **3D 数字人形象**(免费/基础不可开启);**明星/亲人语音包**;**家人看护**(同步给家人 + 跨设备实时推送 emit/subscribe)。
  - 安全类(呼救、来电/短信防诈拦截)**免费不设墙**(不拿老人安全做付费点)。
- 服务器:`/auth/send_code`、`/auth/login`、`/auth/wx_login`(内存用户库);`/pay/create` 已登录时把会员记到账号。
> **接真实登录**(代码已标 TODO):①手机号→短信服务(阿里云/腾讯云)发真 OTP;②微信→开放平台 AppID + OpenSDK(`IWXAPI.sendReq(SendAuth.Req)`+`WXEntryActivity` 收 code)+ 后端 `code2session`;③token 换 JWT/OAuth,用户库换数据库。

## 会员与支付(演示闭环 · 未真实扣款)
「设置 → 会员权益中心」两档:**基础 ¥29.9/月**、**高级 ¥299/年**。点「立即开通」→ 选微信/支付宝 →
客户端调后端 `POST /pay/create` 下单 →(占位:此处接官方 SDK 拉起收银台)→ 视为支付成功 → 本地记为已开通。
> **接真实支付要做**:①申请微信支付/支付宝**商户号**;②App 接**官方 SDK**(WeChat OpenSDK / Alipay SDK)用后端返回的
> prepay 参数拉起收银台;③后端实现**统一下单 + 异步回调验签**(`/pay/notify`),验签通过再发放会员。代码里已标好接入位置。

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

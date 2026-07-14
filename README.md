# 小灵 · AI手机精灵(MVP 可运行工程)

一个"张口就能用"的手机精灵。MVP 楔子:**老人防诈骗 + 一键呼救 + 情感陪伴**。
架构:**瘦客户端(安卓,<15MB)+ 云端大脑(意图理解/技能路由)**,精灵是"语音遥控器",
调起已有 App(高德/微信/美团),不重造轮子。

## 目录结构
```
xiaoling/
├─ server/                 云端大脑(Python / FastAPI · 生产参考实现)
│  ├─ main.py              入口:规则层优先 → 大模型兜底
│  ├─ skills.py            技能注册表(打电话/导航/呼救/防诈/提醒/听戏…)
│  ├─ fraud.py             防诈骗风控引擎(规则层,0 延迟,可热更新)
│  ├─ llm.py               大模型兜底(无 KEY 自动降级,开箱即跑)
│  ├─ models.py            请求/响应数据结构
│  └─ tests/test_dialogue.py  最小回归测试
├─ demo/
│  ├─ xiaoling.js          ★零依赖 Node 版大脑(逻辑同 server,本机可直接跑/联调)
│  └─ player.html          ★可播放的产品演示(常驻可爱形象,表情传达危险信号,带中文语音)
├─ deck/
│  └─ index.html           投资人 Pitch Deck(14 页,浏览器放映/导出 PDF)
└─ android/                安卓瘦客户端骨架
   └─ app/src/main/…       VoiceAgent(唤醒→ASR→大脑→TTS→调起App)/ Manifest / build.gradle
```

## ▶ 最直观:打开产品演示(无需安装,手机也能看)
双击 **`demo/player.html`**(或用手机浏览器打开),点大播放键 —— 一个**常驻的可爱形象「小灵」**
用表情和动作跟你对话:高危来电时它**变红、举手作"停"、头顶冒⚠并抖动**,不识字的老人小孩一眼看懂"危险"。
剧本:**语音呼出小蜜蜂** → 防诈来电 → 防诈短信 → 紧急呼救 → 一句话打电话 → **微信视频通话** → **短视频语音换台/点赞/静音** → **调手机自带地图导航** → 用药提醒 → **子女远程看护** → 情感陪伴。
每条语音指令都有 **<0.3s 即时反馈**(⚡毫秒读数:识别中→已执行),UI 走**极简高级**风;来电/短信时形象常驻不被遮挡,小灵会用**中文语音**朗读,带播放/暂停/章节跳转/倍速。
> 短视频场景支持**换下一个 / 上一个 / 点赞+收藏 / 快进 / 静音**等语音手势;形象可选接 **Live2D 精致模型**(`player.html?live2d=1`,见 [demo/LIVE2D.md](demo/LIVE2D.md),缺模型自动退回 SVG 小蜜蜂)。
> **手机上看**:player.html 是单文件、零依赖,直接发到手机(微信/邮件)用浏览器打开即可。
> **录成 MP4**:见 [demo/RECORD.md](demo/RECORD.md) —— 最省事用 Edge 打开 `player.html?record=1` 再按 `Win+G` 录屏(带语音),或 `cd demo && npm install && node record.js` 无头自动录。
> 投资人路演也可直接开 **`deck/index.html`**(`→` 翻页,`P` 导出 PDF)。

## 30 秒跑起来(本机只装了 Node 也能演示)
```bash
cd demo

# 1) 一键演示剧本:防诈拦截 / 胸口疼呼救 / 打电话 / 导航 / 提醒 / 听戏 / 陪伴
node xiaoling.js

# 2) 交互模式(打字模拟"对精灵说话";call: 开头模拟诈骗来电)
node xiaoling.js --repl

# 3) 起 HTTP 大脑,给安卓客户端联调(POST /dialogue)
node xiaoling.js --serve            # http://localhost:8000
```
> 联调示例(注意用 UTF-8 body,别在 Windows 终端内联中文):
> `curl -X POST localhost:8000/dialogue -H "Content-Type: application/json" --data-binary @req.json`

## 生产版(Python / FastAPI)
```bash
cd server
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000     # 文档 http://localhost:8000/docs
python tests/test_dialogue.py                    # 跑测试(需已装 Python)
```
接大模型:`pip install anthropic` 并配 `ANTHROPIC_API_KEY`;国内落地把 `llm.py` 里的
`_call_anthropic` 换成 通义千问 / 豆包 / 文心 的 Function-Calling 即可,其余不动。

## 安卓客户端要点
- `VoiceAgent.kt`:完整语音闭环。生产把系统 `SpeechRecognizer` 换成**讯飞/阿里流式方言 SDK**,
  唤醒换 **Picovoice Porcupine / 自训 KWS**(端侧、低功耗、离线)。
- 瘦身:`build.gradle` 已开 `minifyEnabled + shrinkResources`,不打包模型 → APK 目标 <15MB。
- 降级:无网/低端机自动回退系统 ASR/TTS,永不白屏。
- 防诈:`FraudCallScreeningService`(Android 10+)监听来电 → 实时转写送 `/dialogue` 判风险。

## 扩展一个新技能(只改一处)
在 `skills.py`(或 `xiaoling.js`)加一个函数:命中返回动作,不命中返回 null。主流程不用动。
```python
@skill("查天气", priority=50)
def weather(u):
    if "天气" not in u.text: return None
    return Reply(speech="今天多云,记得带伞。", action={"type":"OPEN_URI","uri":"..."})
```

## 设计取舍(为什么这样落地)
| 目标 | 做法 |
|---|---|
| 软件小、跑老年机 | 瘦客户端 + 云端算力;系统 ASR/TTS 降级 |
| 全程语音、零手动 | 端侧唤醒 + 流式识别 + 意图缓存,规则层 0 延迟 |
| 听懂方言 | 直接用讯飞/阿里方言 ASR,做编排不造轮子 |
| 防诈骗(核心壁垒) | 规则层秒拦高频套路 + 号码信誉 + 大模型判复杂话术 |
| 不重造 App | Android Intent / DeepLink 调起高德/微信/美团 |
```

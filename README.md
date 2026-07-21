# 小灵 AI 语音助手

小灵是一款面向老年人的 Android 原生语音助手。打开首页后自动进入连续语音对话;
麦克风按钮只保留为可选的长按说话入口,并支持在 AI 播报时打断。

## 目录

- `android-compose/`: 当前唯一 Android App 工程(Kotlin + Jetpack Compose)。
- `server/`: AI 大脑、技能、防诈、提醒、记忆和多模型路由。
- `.github/workflows/build-release-apk.yml`: 签名 Release APK 构建。
- `tools/`: 软件著作权材料生成工具,不进入 APK。

早期网页演示、Capacitor 包装和旧 XML Android 骨架已删除,避免误构建旧包。

## 语音链路

1. 首页获得麦克风授权后自动开始系统 ASR。
2. 高频安全指令在手机本地立即处理。
3. 其余对话发送到 `/dialogue`,由长驻 Agent 运行时编排。
4. 服务端按顺序执行技能、记忆召回、动态上下文和多模型回退。
5. 网络或模型不可用时,手机使用随当前话题变化的本地陪伴回复。
6. TTS 播报结束后自动进入下一轮监听。

后台唤醒由 `WakeService` 接管。受 Android 厂商后台限制影响,需要允许麦克风、通知、
自启动并关闭对小灵的电池限制。

## 服务端

```bash
cd server
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

模型密钥通过环境变量配置,支持 `DEEPSEEK_API_KEY`、`OPENAI_API_KEY`、
`DASHSCOPE_API_KEY`、`ARK_API_KEY`、`MOONSHOT_API_KEY`,也支持
`XL_LLM_KEY` + `XL_LLM_BASE_URL` + `XL_LLM_MODEL` 自定义兼容端点。

多个密钥同时存在时会在总时限内顺序回退。可用 `XL_LLM_PROVIDERS` 指定顺序,
例如 `qwen,deepseek,openai`。记忆默认写到 `server/data/memory.sqlite3`,生产环境可通过
`XL_MEMORY_DB` 指定持久卷路径。API 密钥、身份证号和验证码不会写入记忆库。

## 构建

签名包由 GitHub Actions 构建。仓库 Secrets 需要配置 `KEYSTORE_BASE64`、`KS_PASS`、
`KEY_ALIAS`、`KEY_PASS`。构建产物同时包含 APK、ZIP 和 SHA-256 文件,通过微信传输时
优先使用 ZIP,避免 APK 文件名被追加异常后缀。

详细发布说明见 `android-compose/RELEASE.md`。

## 第三方说明

Agent 架构参考与许可证说明见 `THIRD_PARTY_NOTICES.md`。

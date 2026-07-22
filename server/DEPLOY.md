# 把小灵「云端大脑」部署到公网(手机直连,不用连电脑)

部署后你会得到一个公网地址(如 `https://xiaoling-brain.onrender.com`),把它填进 App
「设置 → 服务器地址」保存,手机在任意网络下都能连上大脑(不用再和电脑同一 WiFi)。

> 注意:App 目前默认允许明文(仅方便局域网联调)。公网请务必用 **HTTPS**(下列平台默认给 HTTPS)。

> **限流准确性(反代后必看)**:防火墙按 IP 限流。若部署在 Nginx/Cloudflare/云负载均衡后面,直连来源是反代 IP,需把**反代/负载均衡的出口 IP**配到环境变量 `XL_TRUSTED_PROXIES`(逗号分隔),防火墙才会信任 `X-Forwarded-For` 取真实客户端 IP;**不配则默认不信任转发头**(以直连 IP 限流,防伪造)。Render/Railway/Fly 这类平台通常自动处理转发,一般无需配置。

---

## 方式一:Render(最省事,免费档即可)
1. 把仓库推到 GitHub(已完成)。
2. 打开 https://render.com → New → **Web Service** → 连接你的 GitHub 仓库。
3. 关键设置:
   - **Root Directory**: `server`
   - **Runtime**: Docker(它会自动识别 `server/Dockerfile`)
   - 或不用 Docker:**Build Command** `pip install -r requirements.txt`,**Start Command** `uvicorn main:app --host 0.0.0.0 --port $PORT`
4. Create → 等构建完成 → 得到公网 `https://xxx.onrender.com`。
5. 手机 App「设置 → 服务器地址」填这个地址 → 保存。
> Render 支持 SSE 长连接(跨设备推送 `/push/subscribe`)。免费档会休眠,首次请求会慢几秒。

## 方式二:Railway
1. https://railway.app → New Project → Deploy from GitHub。
2. 设置 **Root Directory** = `server`;Railway 识别 Dockerfile 自动构建。
3. 生成域名(Settings → Networking → Generate Domain)→ 填进 App。

## 方式三:Fly.io(命令行)
```bash
cd server
# 安装 flyctl 后:
fly launch --dockerfile Dockerfile   # 按提示选区域、生成 app 名
fly deploy
# 得到 https://<app>.fly.dev
```

## 方式四:自己的服务器 / 云主机(Docker)
```bash
cd server
docker build -t xiaoling-brain .
docker run -d -p 8000:8000 --name xiaoling-brain xiaoling-brain
# 用 Nginx/Caddy 反代加 HTTPS,或云厂商负载均衡开 TLS
# 手机填:https://你的域名
```

## 本地快速自测(不部署)
```bash
cd server
docker build -t xiaoling-brain . && docker run -p 8000:8000 xiaoling-brain
# 浏览器打开 http://localhost:8000/docs 看接口
```

---

## 部署后建议
- **接大模型(智能应用体)**:配一个环境变量即启用大模型行为理解(会思考、给多选、理解上下文)。
  用 OpenAI 兼容格式,配哪个 KEY 就用哪个(设一个即可):
  - `DEEPSEEK_API_KEY` — 推荐:国内可直连、性价比高、支持 function calling
  - `OPENAI_API_KEY` — GPT 文本、ASR 兼容入口与 Realtime 服务端鉴权
  - `XL_REALTIME_MODEL` — Realtime 模型,默认 `gpt-realtime`
  - `XL_REALTIME_VOICE` — Realtime 声音,默认 `marin`
  - `XL_DELEGATE_MODEL` — 后台复杂任务使用的强模型,生产环境应显式配置
  - `XL_DELEGATE_PROVIDER` — 可选,限定后台任务使用 `openai/deepseek/qwen/doubao/kimi/custom` 中的一个
  - `XL_REALTIME_CLIENT_TOKEN` — 可选的 App 到自建服务 WebSocket 令牌
  - `DASHSCOPE_API_KEY` — 阿里通义千问
  - `ARK_API_KEY` — 火山豆包 · `MOONSHOT_API_KEY` — Kimi
  - 自定义端点:`XL_LLM_KEY` + `XL_LLM_BASE_URL` + `XL_LLM_MODEL`
  未配任何 KEY 时自动降级为规则+离线兜底,不影响运行。`GET /health` 的 `llm:true/false` 可查是否已启用。
- **会话记忆**:短期对话上下文仍保存在进程内存,多实例部署建议接 Redis。
- **账号与永久权益**:默认使用 SQLite `xiaoling_accounts.db`;生产环境设置 `ACCOUNT_DB_PATH`
  并把所在目录挂载到持久化磁盘。大规模部署可替换为 PostgreSQL。
- **官方气象预警**:取得主管部门授权的数据源后设置 `OFFICIAL_WEATHER_ALERT_URL`。接口需返回
  `{ "alerts": [{ "id", "category", "speech", "source", "url" }] }`,其中 `category` 支持
  `typhoon/rainstorm/sandstorm/weather/earthquake`。未配置时服务返回空列表,不会生成假预警。
- **亲情语音直发**:生产部署设置 `PUBLIC_BASE_URL=https://你的公网域名`;留言保存在
  `FAMILY_AUDIO_DIR`(默认 `server/family_audio`)。亲人端可调用 `/family/remote/reminder` 和
  `/family/remote/audio`,老人端会直接创建提醒或播放音频。
- **实名认证**:生产环境必须配置合法服务商的 `REAL_NAME_VERIFY_URL` 和
  `REAL_NAME_VERIFY_TOKEN`,并设置随机 `IDENTITY_HASH_SALT`。服务端不保存身份证明文。
  仅开发联调时可显式设置 `REAL_NAME_DEMO=true`,正式环境禁止开启。
- **智能体能力自动更新**:设置 HTTPS `SKILL_CATALOG_URL`、共享签名密钥
  `SKILL_CATALOG_SIGNING_KEY`、允许调用的域名列表 `SKILL_ENDPOINT_ALLOWLIST`。管理端刷新还需
  `AGENT_ADMIN_TOKEN`。远程目录只能声明标准 JSON 能力,不能下发或执行代码。
- **安全**:登录/支付/推送目前是演示实现;生产需 JWT、支付验签、鉴权、限流(见 NATIVE.md 里的 TODO 标注)。

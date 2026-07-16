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
- **接大模型**:把 `requirements.txt` 里的 `anthropic` 取消注释、装上,并在平台配置环境变量 `ANTHROPIC_API_KEY`(或换成通义/豆包/文心的 SDK)。
- **持久化**:当前用户库/事件是内存态,重启即清空。上线请接数据库(Postgres 等)。
- **安全**:登录/支付/推送目前是演示实现;生产需 JWT、支付验签、鉴权、限流(见 NATIVE.md 里的 TODO 标注)。

"""
小灵 · 后端防火墙(零额外依赖,纯 Starlette 中间件)
提供:
  - 限流(令牌桶,按客户端 IP,全局 + 敏感端点更严)
  - 请求体大小上限(防超大 body 打爆)
  - 安全响应头(HSTS / nosniff / 防点击劫持等)
  - 慢速/异常请求日志钩子(预留)
生产建议:前面再叠一层 Nginx/Cloudflare/云 WAF;本模块是应用层兜底。
"""
from __future__ import annotations
import os
import time
from collections import defaultdict, deque

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

# ---- 配置 ----
MAX_BODY_BYTES = 64 * 1024          # 单请求体上限 64KB(对话/登录足够)
MAX_AUDIO_BYTES = 13 * 1024 * 1024  # 亲情留言上限 12MB + multipart 开销
GLOBAL_RATE = (120, 60)             # 每 IP:每 60s 最多 120 次
SENSITIVE_RATE = (10, 60)           # 敏感端点(登录/发码/支付):每 60s 最多 10 次
SENSITIVE_PREFIXES = ("/auth/", "/pay/", "/family/audio/")
# SSE 长连接端点不计体积、不限流退出
STREAM_PREFIXES = ("/push/subscribe",)
# 可信反代 IP 白名单(环境变量 XL_TRUSTED_PROXIES=1.2.3.4,10.0.0.1)。
# 只有直连来源在此白名单内,才信任 X-Forwarded-For;否则用真实 client.host,
# 防止外部伪造 XFF 头绕过限流。留空 = 不信任任何转发头(默认最安全)。
TRUSTED_PROXIES = frozenset(
    p.strip() for p in os.getenv("XL_TRUSTED_PROXIES", "").split(",") if p.strip()
)


class _Bucket:
    """滑动窗口计数器"""
    def __init__(self):
        self.hits: dict[str, deque] = defaultdict(deque)

    def allow(self, key: str, limit: int, window: int, now: float) -> bool:
        q = self.hits[key]
        cutoff = now - window
        while q and q[0] < cutoff:
            q.popleft()
        if len(q) >= limit:
            return False
        q.append(now)
        return True


_global = _Bucket()
_sensitive = _Bucket()
# 简单防内存膨胀:定期清理过期键
_last_gc = [0.0]


def _client_ip(request: Request) -> str:
    """
    取真实客户端 IP,抗 X-Forwarded-For 伪造:
      - 直连来源(request.client.host)不在可信反代白名单 → 直接用它,忽略 XFF(外部伪造无效);
      - 直连来源是可信反代 → 从 XFF 链自右向左取第一个"非可信反代"的 IP(即真实客户端)。
    """
    peer = request.client.host if request.client else "unknown"
    if peer not in TRUSTED_PROXIES:
        return peer   # 非可信来源:XFF 不可信,以直连 IP 为准
    xff = request.headers.get("x-forwarded-for")
    if not xff:
        return peer
    chain = [p.strip() for p in xff.split(",") if p.strip()]
    for ip in reversed(chain):        # 自右向左,跳过我们自己的可信反代
        if ip not in TRUSTED_PROXIES:
            return ip
    return chain[0] if chain else peer


class Firewall(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        now = time.time()
        ip = _client_ip(request)
        path = request.url.path

        # 1) 请求体大小(靠 Content-Length 预检,非流式端点)
        if not path.startswith(STREAM_PREFIXES):
            cl = request.headers.get("content-length")
            limit = MAX_AUDIO_BYTES if path == "/family/audio/upload" else MAX_BODY_BYTES
            if cl and cl.isdigit() and int(cl) > limit:
                return JSONResponse({"ok": False, "error": "request too large"}, status_code=413)

        # 2) 限流:全局 + 敏感端点更严
        g_limit, g_win = GLOBAL_RATE
        if not _global.allow(ip, g_limit, g_win, now):
            return _rate_limited(g_win)
        if path.startswith(SENSITIVE_PREFIXES):
            s_limit, s_win = SENSITIVE_RATE
            if not _sensitive.allow(ip, s_limit, s_win, now):
                return _rate_limited(s_win)

        # 3) 周期性 GC,防限流字典无限增长
        if now - _last_gc[0] > 300:
            _last_gc[0] = now
            _gc(_global, now, g_win)
            _gc(_sensitive, now, SENSITIVE_RATE[1])

        resp: Response = await call_next(request)
        _harden(resp)
        return resp


def _rate_limited(window: int) -> JSONResponse:
    r = JSONResponse({"ok": False, "error": "too many requests"}, status_code=429)
    r.headers["Retry-After"] = str(window)
    _harden(r)
    return r


def _harden(resp: Response) -> None:
    resp.headers.setdefault("X-Content-Type-Options", "nosniff")
    resp.headers.setdefault("X-Frame-Options", "DENY")
    resp.headers.setdefault("Referrer-Policy", "no-referrer")
    resp.headers.setdefault("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
    resp.headers.setdefault("Cache-Control", "no-store")


def _gc(bucket: _Bucket, now: float, window: int) -> None:
    cutoff = now - window
    dead = [k for k, q in bucket.hits.items() if not q or q[-1] < cutoff]
    for k in dead:
        bucket.hits.pop(k, None)


def install(app) -> None:
    """在 main.py 里 install(app) 即启用防火墙"""
    app.add_middleware(Firewall)

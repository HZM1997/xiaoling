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
import time
from collections import defaultdict, deque

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

# ---- 配置 ----
MAX_BODY_BYTES = 64 * 1024          # 单请求体上限 64KB(对话/登录足够)
GLOBAL_RATE = (120, 60)             # 每 IP:每 60s 最多 120 次
SENSITIVE_RATE = (10, 60)           # 敏感端点(登录/发码/支付):每 60s 最多 10 次
SENSITIVE_PREFIXES = ("/auth/", "/pay/")
# SSE 长连接端点不计体积、不限流退出
STREAM_PREFIXES = ("/push/subscribe",)


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
    # 若前置反代,信任 X-Forwarded-For 第一段(部署时确保反代已校验)
    xff = request.headers.get("x-forwarded-for")
    if xff:
        return xff.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


class Firewall(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        now = time.time()
        ip = _client_ip(request)
        path = request.url.path

        # 1) 请求体大小(靠 Content-Length 预检,非流式端点)
        if not path.startswith(STREAM_PREFIXES):
            cl = request.headers.get("content-length")
            if cl and cl.isdigit() and int(cl) > MAX_BODY_BYTES:
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

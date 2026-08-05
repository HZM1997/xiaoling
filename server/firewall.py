"""Application-layer firewall for Xiaoling's HTTP and realtime APIs."""
from __future__ import annotations

import hashlib
import hmac
import logging
import os
import time
from collections import defaultdict, deque
from threading import Lock

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

MAX_BODY_BYTES = 64 * 1024
MAX_AUDIO_BYTES = 13 * 1024 * 1024
GLOBAL_RATE = (120, 60)
SENSITIVE_RATE = (10, 60)
SENSITIVE_PREFIXES = ("/auth/", "/pay/", "/family/audio/", "/agent/admin/")
STREAM_PREFIXES = ("/push/subscribe",)
PUBLIC_PATHS = frozenset({"/health"})
PUBLIC_PREFIXES = ("/family/audio/files/",)
TRUSTED_PROXIES = frozenset(
    value.strip() for value in os.getenv("XL_TRUSTED_PROXIES", "").split(",") if value.strip()
)
logger = logging.getLogger("xiaoling.security")


class _Bucket:
    def __init__(self) -> None:
        self.hits: dict[str, deque[float]] = defaultdict(deque)
        self.lock = Lock()

    def allow(self, key: str, limit: int, window: int, now: float) -> bool:
        with self.lock:
            queue = self.hits[key]
            cutoff = now - window
            while queue and queue[0] < cutoff:
                queue.popleft()
            if len(queue) >= limit:
                return False
            queue.append(now)
            return True

    def gc(self, now: float, window: int) -> None:
        cutoff = now - window
        with self.lock:
            dead = [key for key, queue in self.hits.items() if not queue or queue[-1] < cutoff]
            for key in dead:
                self.hits.pop(key, None)


_global = _Bucket()
_sensitive = _Bucket()
_realtime_handshakes = _Bucket()
_realtime_active: dict[str, int] = defaultdict(int)
_realtime_lock = Lock()
_last_gc = 0.0


def production() -> bool:
    return os.getenv("XL_ENV", "development").strip().lower() in {"prod", "production"}


def client_token() -> str:
    return os.getenv("XL_CLIENT_TOKEN", "").strip() or os.getenv("XL_REALTIME_CLIENT_TOKEN", "").strip()


def security_status() -> dict:
    return {
        "firewall": True,
        "client_auth": bool(client_token()),
        "fail_closed": production(),
        "realtime_limits": True,
    }


def _peer_ip(request: Request) -> str:
    peer = request.client.host if request.client else "unknown"
    if peer not in TRUSTED_PROXIES:
        return peer
    chain = [part.strip() for part in request.headers.get("x-forwarded-for", "").split(",") if part.strip()]
    for ip in reversed(chain):
        if ip not in TRUSTED_PROXIES:
            return ip
    return chain[0] if chain else peer


def anonymous_ip(ip: str) -> str:
    salt = os.getenv("XL_LOG_HASH_SALT", "xiaoling-security-log")
    return hashlib.sha256(f"{salt}:{ip}".encode()).hexdigest()[:12]


def token_valid(headers) -> bool:
    expected = client_token()
    if not expected:
        return not production()
    supplied = headers.get("x-xiaoling-token", "").strip()
    return bool(supplied) and hmac.compare_digest(expected, supplied)


def acquire_realtime(ip: str) -> bool:
    now = time.monotonic()
    if not _realtime_handshakes.allow(ip, 12, 60, now):
        return False
    with _realtime_lock:
        if _realtime_active.get(ip, 0) >= 3:
            return False
        _realtime_active[ip] = _realtime_active.get(ip, 0) + 1
        return True


def release_realtime(ip: str) -> None:
    with _realtime_lock:
        current = _realtime_active.get(ip, 0)
        if current <= 1:
            _realtime_active.pop(ip, None)
        else:
            _realtime_active[ip] = current - 1


class Firewall(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        global _last_gc
        now = time.monotonic()
        ip = _peer_ip(request)
        path = request.url.path

        if request.method not in {"GET", "POST", "HEAD", "OPTIONS"}:
            return _error(405, "method_not_allowed")
        if any(part in path for part in ("..", "\\", "\x00")):
            _blocked(ip, path, "invalid_path")
            return _error(400, "invalid_request")

        if not path.startswith(STREAM_PREFIXES):
            length = request.headers.get("content-length", "")
            limit = MAX_AUDIO_BYTES if path == "/family/audio/upload" else (
                1024 * 1024 + 8192 if path == "/asr" else MAX_BODY_BYTES
            )
            if length.isdigit() and int(length) > limit:
                _blocked(ip, path, "body_too_large")
                return _error(413, "request_too_large")
            if request.method == "POST" and not length.isdigit():
                _blocked(ip, path, "missing_content_length")
                return _error(411, "content_length_required")

        if not _global.allow(ip, *GLOBAL_RATE, now):
            _blocked(ip, path, "global_rate")
            return _rate_limited(GLOBAL_RATE[1])
        if path.startswith(SENSITIVE_PREFIXES) and not _sensitive.allow(ip, *SENSITIVE_RATE, now):
            _blocked(ip, path, "sensitive_rate")
            return _rate_limited(SENSITIVE_RATE[1])

        is_public = path in PUBLIC_PATHS or path.startswith(PUBLIC_PREFIXES)
        if not is_public and path != "/agent/admin/refresh" and not token_valid(request.headers):
            _blocked(ip, path, "client_auth")
            return _error(401 if client_token() else 503, "unauthorized")

        if now - _last_gc > 300:
            _last_gc = now
            _global.gc(now, GLOBAL_RATE[1])
            _sensitive.gc(now, SENSITIVE_RATE[1])
            _realtime_handshakes.gc(now, 60)

        try:
            response: Response = await call_next(request)
        except Exception:
            logger.exception("request_failed ip=%s path=%s", anonymous_ip(ip), path[:120])
            raise
        _harden(response)
        return response


def _blocked(ip: str, path: str, reason: str) -> None:
    logger.warning("blocked ip=%s path=%s reason=%s", anonymous_ip(ip), path[:120], reason)


def _error(status: int, code: str) -> JSONResponse:
    response = JSONResponse({"ok": False, "error": code}, status_code=status)
    _harden(response)
    return response


def _rate_limited(window: int) -> JSONResponse:
    response = _error(429, "too_many_requests")
    response.headers["Retry-After"] = str(window)
    return response


def _harden(response: Response) -> None:
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    response.headers.setdefault("Permissions-Policy", "camera=(), geolocation=(), payment=()")
    response.headers.setdefault("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
    response.headers.setdefault("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
    response.headers.setdefault("Cache-Control", "no-store")


def install(app) -> None:
    app.add_middleware(Firewall)

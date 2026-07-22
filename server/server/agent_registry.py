"""受控的 Skill / Agent 能力目录。

远程目录必须使用 HMAC-SHA256 签名,能力端点必须是 HTTPS 且位于环境变量
SKILL_ENDPOINT_ALLOWLIST 中。这里只更新声明式能力元数据并调用标准 JSON API,
绝不下载或执行远程代码。
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import threading
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass, asdict

from models import Reply


@dataclass(frozen=True)
class Capability:
    id: str
    name: str
    version: str
    description: str
    authority: str
    keywords: tuple[str, ...]
    endpoint: str = ""
    priority: int = 100


_BUILTINS = [
    Capability("voice.dialogue", "连续语音对话", "1.0.10", "唤醒、插话打断与多轮对话", "official", ("对话", "聊天"), priority=1),
    Capability("safety.fraud", "端侧反诈", "1.0.10", "来电短信与口语反诈咨询", "official", ("诈骗", "验证码"), priority=1),
    Capability("alerts.earthquake.usgs", "USGS地震信息", "1.0.10", "官方地震公开数据", "official", ("地震",), priority=5),
    Capability("weather.current", "实时天气", "1.0.10", "免密钥实时天气查询", "verified", ("天气", "气温", "下雨"), priority=10),
    Capability("family.remote", "亲情远程协助", "1.0.10", "远程提醒与音频推送", "official", ("家人", "远程提醒"), priority=20),
]

_lock = threading.RLock()
_capabilities: dict[str, Capability] = {item.id: item for item in _BUILTINS}
_last_refresh = 0.0
_revision = "builtin-1.0.10"


def _version(value: str) -> tuple[int, ...]:
    parts = []
    for item in value.strip().lstrip("v").split("."):
        try:
            parts.append(int(item))
        except ValueError:
            parts.append(0)
    return tuple(parts)


def _canonical(payload: dict) -> bytes:
    unsigned = {k: v for k, v in payload.items() if k != "signature"}
    return json.dumps(unsigned, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _allowed_endpoint(endpoint: str) -> bool:
    parsed = urllib.parse.urlparse(endpoint)
    allowed = {item.strip().lower() for item in os.getenv("SKILL_ENDPOINT_ALLOWLIST", "").split(",") if item.strip()}
    return parsed.scheme == "https" and bool(parsed.hostname) and parsed.hostname.lower() in allowed


def refresh(force: bool = False) -> dict:
    global _last_refresh, _revision
    with _lock:
        interval = int(os.getenv("SKILL_REFRESH_SECONDS", "21600"))
        if not force and time.time() - _last_refresh < max(interval, 300):
            return status()
        _last_refresh = time.time()

    catalog_url = os.getenv("SKILL_CATALOG_URL", "").strip()
    signing_key = os.getenv("SKILL_CATALOG_SIGNING_KEY", "").strip()
    if not catalog_url or not signing_key or not catalog_url.startswith("https://"):
        return status()
    try:
        request = urllib.request.Request(catalog_url, headers={"Accept": "application/json", "User-Agent": "Xiaoling-Agent/1.0"})
        with urllib.request.urlopen(request, timeout=2.0) as response:
            raw = response.read(512 * 1024)
        payload = json.loads(raw.decode("utf-8"))
        signature = str(payload.get("signature", ""))
        expected = hmac.new(signing_key.encode("utf-8"), _canonical(payload), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(signature.lower(), expected.lower()):
            return {**status(), "refresh_error": "invalid_signature"}

        accepted: list[Capability] = []
        for item in payload.get("skills", [])[:200]:
            endpoint = str(item.get("endpoint", "")).strip()
            authority = str(item.get("authority", "community")).strip()
            if authority not in {"official", "verified"} or not _allowed_endpoint(endpoint):
                continue
            capability = Capability(
                id=str(item.get("id", "")).strip()[:80],
                name=str(item.get("name", "")).strip()[:80],
                version=str(item.get("version", "0")).strip()[:24],
                description=str(item.get("description", "")).strip()[:300],
                authority=authority,
                keywords=tuple(str(k).strip()[:30] for k in item.get("keywords", [])[:30] if str(k).strip()),
                endpoint=endpoint,
                priority=max(1, min(int(item.get("priority", 100)), 1000)),
            )
            if capability.id and capability.name and capability.keywords:
                accepted.append(capability)

        with _lock:
            for capability in accepted:
                current = _capabilities.get(capability.id)
                if current is None or _version(capability.version) > _version(current.version):
                    _capabilities[capability.id] = capability
            _revision = str(payload.get("revision", payload.get("version", "remote")))[:80]
        return status()
    except Exception:
        return {**status(), "refresh_error": "catalog_unavailable"}


def status() -> dict:
    with _lock:
        items = sorted(_capabilities.values(), key=lambda item: (item.priority, item.name))
        return {
            "revision": _revision,
            "last_refresh": int(_last_refresh),
            "count": len(items),
            "capabilities": [asdict(item) | {"keywords": list(item.keywords), "endpoint": "configured" if item.endpoint else "builtin"}
                             for item in items],
        }


def answer(query: str) -> Reply | None:
    refresh(force=False)
    with _lock:
        candidates = [item for item in _capabilities.values()
                      if item.endpoint and any(keyword in query for keyword in item.keywords)]
    if not candidates:
        return None
    capability = sorted(candidates, key=lambda item: (item.priority, -len(item.keywords)))[0]
    try:
        body = json.dumps({"query": query, "locale": "zh-CN"}, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            capability.endpoint,
            data=body,
            headers={"Content-Type": "application/json", "Accept": "application/json", "User-Agent": "Xiaoling-Agent/1.0"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=1.3) as response:
            result = json.loads(response.read(256 * 1024).decode("utf-8"))
        speech = str(result.get("speech", "")).strip()
        sources = [source for source in result.get("sources", [])[:8]
                   if isinstance(source, dict) and str(source.get("name", "")).strip()]
        if not speech:
            return None
        if sources:
            source_name = str(sources[0].get("name", "")).strip()[:60]
            if source_name and source_name not in speech:
                speech = f"据{source_name}最新信息,{speech}"
        return Reply(speech=speech, action=result.get("action"),
                     skill=f"agent:{capability.id}", risk=float(result.get("risk", 0.0)),
                     sources=sources)
    except Exception:
        return None

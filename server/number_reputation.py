"""
小灵 · 号码信誉引擎
把"号码判定"从简单前缀规则,升级为可对接三方 API + 自建黑白名单 + 号段特征的分级信誉。
输出 (信誉分基线 0~1, 标签, 原因),供 fraud.analyze 作为 L0 基线。
设计:本地即时(黑白名单/号段特征)优先,三方 API 作为可选增强(留 hook,缺失不影响)。
黑白名单可热更新(线上放配置中心/数据库/Redis)。
"""
from __future__ import annotations
import json
import os
import re
import time
import urllib.parse
import urllib.request

_PATH = os.path.join(os.path.dirname(__file__), "number_reputation.json")

# 三方号码信誉查询缓存(内存):{ number: (查询时间戳, 结果或None) }
_CACHE: dict[str, tuple[float, object]] = {}
_CACHE_TTL = 3600      # 1 小时
_CACHE_MAX = 10000


def _load() -> dict:
    try:
        with open(_PATH, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {"blacklist": [], "whitelist": [], "risky_segments": [], "gov_service": []}


_DB = _load()
# 运行期动态黑白名单(用户举报/家人标记即时生效,不必改文件)
_RUNTIME_BLACK: set[str] = set()
_RUNTIME_WHITE: set[str] = set()


def reload_db() -> None:
    global _DB
    _DB = _load()


def _norm(caller: str) -> str:
    return re.sub(r"[^\d+]", "", caller or "")


def assess(caller: str) -> tuple[float, str, str]:
    """
    返回 (基线风险分 0~1, 标签, 白话原因)。
    标签: white(可信) / black(已知诈骗) / risky(可疑) / unknown。
    """
    c = _norm(caller)
    if not c:
        return 0.0, "unknown", ""

    # —— 白名单(官方服务号/家人自定义):强可信,直接压到 0,并附可信标记 ——
    if c in _RUNTIME_WHITE or c in set(_DB.get("whitelist", [])):
        return 0.0, "white", "该号码在您的可信名单中"
    for seg in _DB.get("gov_service", []):          # 10086/95588/12345 等官方服务号段
        if c.startswith(seg):
            return 0.0, "white", "官方服务号码"

    # —— 黑名单:已知诈骗号,直接高基线 ——
    if c in _RUNTIME_BLACK or c in set(_DB.get("blacklist", [])):
        return 0.6, "black", "该号码已被标记为诈骗号码"

    # —— 号段/结构特征:境外、改号、虚商、异常长度 → 中等基线 ——
    risky = _risky_reason(c)
    if risky:
        return 0.15, "risky", risky

    # —— 三方号码信誉 API(可选增强):配了 KEY 才调,失败静默 ——
    ext = _third_party(c)
    if ext is not None:
        return ext

    return 0.0, "unknown", ""


def _risky_reason(c: str) -> str:
    for seg in _DB.get("risky_segments", []):
        if c.startswith(seg["prefix"]) and not c.startswith("+86"):
            return seg.get("reason", "可疑号段")
    digits = c.lstrip("+")
    if not (7 <= len(digits) <= 12):
        return "号码长度异常(疑似改号)"
    return ""


def _third_party(c: str):
    """
    三方号码信誉 API 真实接入(如运营商/安全厂商反诈号码库)。
    配置环境变量:
      XL_NUMBER_API_KEY  必填,启用开关
      XL_NUMBER_API_URL  可选,默认见下;用 {phone} 占位号码
    带内存缓存(TTL 1 小时)+ 1.5s 超时降级;失败返回 None,不影响本地判定与响应速度。
    返回 (基线分, 标签, 原因)。
    """
    key = os.getenv("XL_NUMBER_API_KEY")
    if not key:
        return None

    now = time.time()
    cached = _CACHE.get(c)
    if cached and now - cached[0] < _CACHE_TTL:
        return cached[1]

    result = None
    try:
        url = os.getenv("XL_NUMBER_API_URL", "https://api.example-antifraud.com/number?phone={phone}")
        url = url.replace("{phone}", urllib.parse.quote(c))
        req = urllib.request.Request(url, headers={"Authorization": "Bearer " + key, "Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=1.5) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        # 兼容常见返回:{"risk":"high|mid|low"} 或 {"score":0~100}
        level = str(data.get("risk", "")).lower()
        score = data.get("score")
        if level in ("high", "fraud", "black") or (isinstance(score, (int, float)) and score >= 80):
            result = (0.6, "black", "第三方号码库标记为高危")
        elif level in ("mid", "medium", "risky") or (isinstance(score, (int, float)) and score >= 50):
            result = (0.2, "risky", "第三方号码库标记为可疑")
        else:
            result = None  # 三方认为安全 → 交回本地默认(unknown)
    except Exception:
        result = None      # 超时/异常 → 静默降级,不拖慢主流程

    _CACHE[c] = (now, result)
    # 简单防膨胀:超上限清理最旧
    if len(_CACHE) > _CACHE_MAX:
        for k, _ in sorted(_CACHE.items(), key=lambda kv: kv[1][0])[: len(_CACHE) - _CACHE_MAX]:
            _CACHE.pop(k, None)
    return result


# —— 用户举报 / 家人标记(动态名单,即时生效)——
def report_fraud_number(caller: str) -> None:
    """用户/家人举报某号码为诈骗 → 即时加入黑名单(数据飞轮)。"""
    c = _norm(caller)
    if c:
        _RUNTIME_BLACK.add(c)
        _RUNTIME_WHITE.discard(c)


def trust_number(caller: str) -> None:
    """把号码标为可信(如子女号码)→ 加入白名单,不再误报。"""
    c = _norm(caller)
    if c:
        _RUNTIME_WHITE.add(c)
        _RUNTIME_BLACK.discard(c)


def stats() -> dict:
    return {
        "blacklist": len(_DB.get("blacklist", [])) + len(_RUNTIME_BLACK),
        "whitelist": len(_DB.get("whitelist", [])) + len(_RUNTIME_WHITE),
        "risky_segments": len(_DB.get("risky_segments", [])),
        "third_party": bool(os.getenv("XL_NUMBER_API_KEY")),
    }

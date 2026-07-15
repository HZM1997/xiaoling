"""
小灵 · 防诈会话管理器
按会话 id(优先 context.session_id,否则 caller+scene)维护 ConversationTracker,
让"整通电话/整段短信"跨句综合研判。带 TTL 自动过期,防内存膨胀。
线上多进程/多实例部署时应换成 Redis 等共享存储。
"""
from __future__ import annotations
import time
from fraud import ConversationTracker, FraudResult, analyze

_SESSIONS: dict[str, tuple[float, ConversationTracker]] = {}
_TTL = 180          # 会话 3 分钟无活动即过期
_MAX = 5000         # 最多保留会话数(超出清理最旧)


def _session_id(context: dict, caller: str, scene: str) -> str:
    sid = (context or {}).get("session_id")
    if sid:
        return str(sid)
    return f"{scene}:{caller or 'unknown'}"


def _gc(now: float) -> None:
    dead = [k for k, (ts, _) in _SESSIONS.items() if now - ts > _TTL]
    for k in dead:
        _SESSIONS.pop(k, None)
    if len(_SESSIONS) > _MAX:
        # 清理最旧的一批
        for k, _ in sorted(_SESSIONS.items(), key=lambda kv: kv[1][0])[: len(_SESSIONS) - _MAX]:
            _SESSIONS.pop(k, None)


def analyze_session(text: str, caller: str, scene: str, context: dict | None = None) -> FraudResult:
    """
    多轮:同一会话内跨句累积研判。非来电/短信场景(无会话语义)退回单句 analyze。
    """
    if scene not in ("incoming_call", "sms", "incoming_sms"):
        return analyze(text, caller, scene)

    now = time.time()
    _gc(now)
    sid = _session_id(context or {}, caller, scene)
    entry = _SESSIONS.get(sid)
    if entry is None:
        tr = ConversationTracker(caller=caller, scene=scene)
        _SESSIONS[sid] = (now, tr)
    else:
        tr = entry[1]
        _SESSIONS[sid] = (now, tr)
    return tr.add(text)


def end_session(context: dict | None, caller: str = "", scene: str = "incoming_call") -> None:
    """通话/短信结束时可调用,主动释放会话。"""
    sid = _session_id(context or {}, caller, scene)
    _SESSIONS.pop(sid, None)

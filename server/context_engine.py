"""每轮只选择相关记忆和安全设备摘要,避免把所有历史无差别塞给模型。"""
from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

from memory_store import MemoryStore


_DEVICE_KEYS = {
    "local_time_ms", "timezone", "foreground", "android", "manufacturer", "model",
    "microphone", "microphone_permission", "network", "metered", "battery_percent", "charging",
}


def build_context(
    store: MemoryStore,
    user_id: str,
    text: str,
    request_context: dict | None,
) -> dict:
    incoming = request_context if isinstance(request_context, dict) else {}
    device_in = incoming.get("device") if isinstance(incoming.get("device"), dict) else {}
    device = {key: device_in[key] for key in _DEVICE_KEYS if key in device_in}
    timezone = str(device.get("timezone") or "Asia/Shanghai")
    try:
        local_time = datetime.now(ZoneInfo(timezone)).isoformat(timespec="minutes")
    except Exception:
        local_time = datetime.now().astimezone().isoformat(timespec="minutes")

    profile = incoming.get("profile") if isinstance(incoming.get("profile"), dict) else {}
    memories = store.recall(user_id, text, limit=6)
    for item in memories:
        if item["kind"] == "profile" and item["memory_key"] not in profile:
            profile[item["memory_key"]] = item["value"]

    return {
        "scene": str(incoming.get("scene") or "voice_chat")[:48],
        "local_time": local_time,
        "profile": profile,
        "memories": [
            {"kind": item["kind"], "key": item["memory_key"], "value": item["value"]}
            for item in memories
        ],
        "recent_turns": store.recent_turns(user_id, limit=6),
        "device": device,
    }

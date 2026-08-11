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
    vision_in = incoming.get("vision") if isinstance(incoming.get("vision"), dict) else {}
    vision = {
        "lens": str(vision_in.get("lens") or "")[:16],
        "observation": str(vision_in.get("observation") or "")[:900],
        "scene_hint": str(vision_in.get("scene_hint") or "")[:500],
    }
    vision = {key: value for key, value in vision.items() if value}
    memories = store.recall(user_id, text, limit=8)
    local_memories = incoming.get("local_memories") if isinstance(incoming.get("local_memories"), list) else []
    known = {(item["kind"], item["memory_key"], item["value"]) for item in memories}
    for item in local_memories[:8]:
        if not isinstance(item, dict):
            continue
        key = str(item.get("key") or "")[:64]
        value = str(item.get("value") or "")[:120]
        marker = ("device", key, value)
        if key and value and marker not in known:
            memories.append({"kind": "device", "memory_key": key, "value": value})
            known.add(marker)

    recent = store.recent_turns(user_id, limit=20)
    if not recent:
        incoming_turns = incoming.get("recent_turns") if isinstance(incoming.get("recent_turns"), list) else []
        recent = [
            {"role": str(item.get("role")), "content": str(item.get("content"))[:600]}
            for item in incoming_turns[-20:]
            if isinstance(item, dict) and item.get("role") in {"user", "assistant"} and item.get("content")
        ]
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
        "recent_turns": recent,
        "device": device,
        "vision": vision,
    }

"""
小灵 · 大模型兜底(意图理解 + 温暖闲聊)
设计要点:
  - 规则层拿不准时才调用,省 60%+ 的大模型费用。
  - 不装 anthropic / 没配 KEY 时自动降级为"离线兜底话术",保证开箱即跑。
  - 国内落地把 _call_anthropic 换成 通义/豆包/文心 的 Function-Calling 即可,结构不变。
"""
from __future__ import annotations
import os

from models import Utterance, Reply

# 意图 → 客户端动作类型
_INTENT_TO_ACTION = {
    "call": "CALL", "navigate": "OPEN_URI", "play_music": "PLAY",
    "translate": "TRANSLATE", "chat": None, "unknown": None,
}

_TOOLS = [{
    "name": "dispatch",
    "description": "把用户口语指令转成精灵动作",
    "input_schema": {
        "type": "object",
        "properties": {
            "speech": {"type": "string",
                       "description": "用温暖、口语、简短的中文回复老人,像孙辈说话"},
            "intent": {"type": "string",
                       "enum": ["chat", "call", "navigate", "play_music",
                                "translate", "unknown"]},
            "slots": {"type": "object", "description": "如 {'target':'张医生'}"},
        },
        "required": ["speech", "intent"],
    },
}]

_SYSTEM = ("你是老年人的贴心手机精灵'小灵'。说话简短、温暖、易懂,"
           "像孙辈跟爷爷奶奶说话。识别用户意图并调用 dispatch。"
           "遇到闲聊就用 intent=chat,好好陪老人说话。")


def llm_reply(u: Utterance) -> Reply:
    try:
        data = _call_anthropic(u.text)
    except Exception:
        return _offline_fallback(u)      # 没网/没KEY/没装库 → 降级
    intent = data.get("intent", "chat")
    slots = data.get("slots", {}) or {}
    action = None
    atype = _INTENT_TO_ACTION.get(intent)
    if atype:
        action = {"type": atype, **slots}
    return Reply(speech=data.get("speech", "我在呢。"), action=action, skill=f"llm:{intent}")


def _call_anthropic(text: str) -> dict:
    import anthropic  # 未安装则抛异常 → 走降级
    if not os.getenv("ANTHROPIC_API_KEY"):
        raise RuntimeError("no api key")
    client = anthropic.Anthropic()
    msg = client.messages.create(
        model="claude-opus-4-8",         # 国内换 qwen-max / doubao-pro
        max_tokens=512,
        system=_SYSTEM,
        tools=_TOOLS,
        tool_choice={"type": "tool", "name": "dispatch"},
        messages=[{"role": "user", "content": text}],
    )
    for block in msg.content:
        if getattr(block, "type", "") == "tool_use":
            return block.input
    return {"speech": "我没太听清,您再说一遍好吗?", "intent": "unknown"}


def _offline_fallback(u: Utterance) -> Reply:
    """无大模型时的兜底:保证系统永远有温暖回应,不白屏。"""
    return Reply(
        speech="我在听着呢。您可以跟我说『打电话给女儿』『导航到医院』,"
               "或者陪我聊聊天。有需要随时喊我。",
        skill="offline_fallback",
    )

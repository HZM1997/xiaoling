"""
小灵 · 大模型兜底(意图理解 + 温暖闲聊)
设计要点:
  - 规则层拿不准时才调用,省 60%+ 的大模型费用。
  - 不装 anthropic / 没配 KEY 时自动降级为"离线兜底话术",保证开箱即跑。
  - 国内落地把 _call_anthropic 换成 通义/豆包/文心 的 Function-Calling 即可,结构不变。
"""
from __future__ import annotations
import hashlib
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


def llm_reply(u: Utterance, runtime_context: dict | None = None) -> Reply:
    try:
        data = _call_anthropic(u.text)
    except Exception:
        return _offline_fallback(u, runtime_context)      # 没网/没KEY/没装库 → 降级
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


def _offline_fallback(u: Utterance, runtime_context: dict | None = None) -> Reply:
    """无可用模型时按当前话题生成短回复,避免每轮重复同一句。"""
    text = " ".join(u.text.strip().split())[:36]
    if any(word in text for word in ("你好", "您好", "在吗")):
        speech = "在呢,您慢慢说,我一直听着。"
    elif any(word in text for word in ("孤单", "寂寞", "睡不着", "陪我")):
        speech = "我陪着您。今天有什么事一直放在心里?"
    elif any(word in text for word in ("不开心", "难过", "担心", "烦")):
        speech = "听起来这件事让您不好受。您慢慢讲,我在听。"
    elif "我喜欢" in text or "我爱听" in text or "我爱看" in text:
        speech = "记住了。以后聊到这方面,我会先照顾您的喜好。"
    elif any(word in text for word in ("什么", "怎么", "为什么", "多少", "哪里", "哪儿")):
        speech = f"您问的是“{text}”。我现在没拿到可靠资料,不想随口说错。稍后我再认真帮您查。"
    else:
        recent = (runtime_context or {}).get("recent_turns") or []
        seed = f"{u.user_id}:{text}:{len(recent)}".encode("utf-8")
        index = int(hashlib.sha256(seed).hexdigest()[:8], 16) % 4
        speech = (
            f"我听见您说“{text}”了。您是想聊聊它,还是要我帮您办件事?",
            f"好,这句话我记下了。关于“{text}”,您再多说一点好吗?",
            f"我在认真听。您说的“{text}”,最想让我帮您解决哪一部分?",
            f"明白一些了。您可以接着说“{text}”后面要做什么。",
        )[index]
    return Reply(speech=speech, skill="offline:contextual")


# ---------- 防诈二次研判(仅规则中危时调用,降误报) ----------
_FRAUD_TOOL = [{
    "name": "judge_fraud",
    "description": "判断一通电话/短信内容是否为电信诈骗",
    "input_schema": {
        "type": "object",
        "properties": {
            "is_fraud": {"type": "boolean", "description": "是否为诈骗"},
            "confidence": {"type": "number", "description": "0~1 置信度"},
            "reason": {"type": "string", "description": "给老人听的一句白话理由"},
        },
        "required": ["is_fraud", "confidence"],
    },
}]

_FRAUD_SYSTEM = (
    "你是资深反诈专家。判断给定的来电/短信内容是否电信诈骗。"
    "典型诈骗:冒充公检法/客服/银行/子女领导、要求转账/验证码/屏幕共享、"
    "贷款征信、刷单返利、虚拟币荐股、养老理财、中奖。"
    "正常内容(家人问候、挂号缴费、快递物业通知)判 is_fraud=false。只调用 judge_fraud。"
)


def judge_fraud(text: str, category: str = "") -> dict | None:
    """
    大模型二次研判。返回 {is_fraud, confidence, reason} 或 None(无大模型时,让规则判定生效)。
    只在规则中危(拿不准)时调用,省成本、降误报。
    """
    try:
        import anthropic
        if not os.getenv("ANTHROPIC_API_KEY"):
            return None
        client = anthropic.Anthropic()
        msg = client.messages.create(
            model="claude-opus-4-8",     # 国内换 qwen-max / doubao-pro
            max_tokens=256,
            system=_FRAUD_SYSTEM,
            tools=_FRAUD_TOOL,
            tool_choice={"type": "tool", "name": "judge_fraud"},
            messages=[{"role": "user", "content": f"[疑似类型:{category}] 内容:{text}"}],
        )
        for block in msg.content:
            if getattr(block, "type", "") == "tool_use":
                return block.input
    except Exception:
        return None
    return None


# ---------- 翻译兜底(端侧词库未命中时) ----------
_LANG_CN = {"english": "英语", "cantonese": "粤语", "mandarin": "普通话"}


def llm_translate(content: str, lang: str) -> str | None:
    """大模型翻译。无大模型/无 KEY 返回 None,让上层给降级提示。"""
    try:
        import anthropic
        if not os.getenv("ANTHROPIC_API_KEY"):
            return None
        target = _LANG_CN.get(lang, "英语")
        client = anthropic.Anthropic()
        msg = client.messages.create(
            model="claude-opus-4-8",
            max_tokens=200,
            system=f"你是翻译助手。把用户的话翻译成{target},只输出译文,粤语可附拼音,不要解释。",
            messages=[{"role": "user", "content": content}],
        )
        for block in msg.content:
            if getattr(block, "type", "") == "text":
                return block.text.strip()
    except Exception:
        return None
    return None

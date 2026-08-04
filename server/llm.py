"""
小灵 · 大模型兜底(意图理解 + 温暖闲聊)
设计要点:
  - 规则层拿不准时才调用,降低模型费用。
  - 统一走 Kimi-first 国内模型网关,未配 KEY 时自动降级为离线兜底话术。
"""
from __future__ import annotations
import hashlib
import json

import llm_gateway
from models import Utterance, Reply

# 意图 → 客户端动作类型
_INTENT_TO_ACTION = {
    "call": "CALL", "navigate": "OPEN_URI", "play_music": "PLAY",
    "translate": "TRANSLATE", "chat": None, "unknown": None,
}

_SYSTEM = ("你是老年人的贴心手机精灵'小灵'。说话简短、温暖、易懂,"
           "像孙辈跟爷爷奶奶说话。识别用户意图并调用 dispatch。"
           "遇到闲聊就用 intent=chat,好好陪老人说话。")


def llm_reply(u: Utterance, runtime_context: dict | None = None) -> Reply:
    data = _call_agent(u.text, runtime_context)
    if not data:
        return _offline_fallback(u, runtime_context)
    intent = data.get("intent", "chat")
    slots = data.get("slots", {}) or {}
    action = None
    atype = _INTENT_TO_ACTION.get(intent)
    if atype:
        action = {"type": atype, **slots}
    return Reply(speech=data.get("speech", "我在呢。"), action=action, skill=f"llm:{intent}")


def _json_object(content: str) -> dict | None:
    text = (content or "").strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
    try:
        value = json.loads(text)
        return value if isinstance(value, dict) else None
    except (TypeError, ValueError):
        start, end = text.find("{"), text.rfind("}")
        if start >= 0 and end > start:
            try:
                value = json.loads(text[start:end + 1])
                return value if isinstance(value, dict) else None
            except ValueError:
                return None
    return None


def _call_agent(text: str, runtime_context: dict | None = None) -> dict | None:
    recent = (runtime_context or {}).get("recent_turns") or []
    context = json.dumps(recent[-6:], ensure_ascii=False)[:1200]
    message = llm_gateway.chat(
        messages=[
            {"role": "system", "content": _SYSTEM +
             "只输出JSON对象,格式为{\"speech\":\"回复\",\"intent\":\"chat|call|navigate|play_music|translate|unknown\",\"slots\":{}}。"},
            {"role": "user", "content": f"最近对话:{context}\n当前用户:{text}"},
        ],
        temperature=0.25,
        max_tokens=360,
        timeout=5.0,
    )
    return _json_object((message or {}).get("content", ""))


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
    message = llm_gateway.chat(
        messages=[
            {"role": "system", "content": _FRAUD_SYSTEM +
             "只输出JSON对象:{\"is_fraud\":true,\"confidence\":0.0,\"reason\":\"一句白话理由\"}。"},
            {"role": "user", "content": f"疑似类型:{category}\n内容:{text}"},
        ],
        temperature=0.05,
        max_tokens=180,
        timeout=4.0,
    )
    result = _json_object((message or {}).get("content", ""))
    if not result or not isinstance(result.get("is_fraud"), bool):
        return None
    try:
        result["confidence"] = max(0.0, min(float(result.get("confidence", 0)), 1.0))
    except (TypeError, ValueError):
        result["confidence"] = 0.0
    return result


# ---------- 翻译兜底(端侧词库未命中时) ----------
_LANG_CN = {
    "english": "英语", "cantonese": "粤语", "mandarin": "普通话",
    "japanese": "日语", "korean": "韩语", "spanish": "西班牙语",
    "french": "法语", "german": "德语", "russian": "俄语",
    "portuguese": "葡萄牙语", "arabic": "阿拉伯语", "thai": "泰语",
    "vietnamese": "越南语",
}


def llm_translate(content: str, lang: str) -> str | None:
    """大模型翻译。无大模型/无 KEY 返回 None,让上层给降级提示。"""
    target = _LANG_CN.get(lang)
    if not target:
        return None
    message = llm_gateway.chat(
        messages=[
            {"role": "system", "content":
             f"你是实时口译员。把用户原话准确翻译成{target},保留姓名、数字和语气。只输出译文,不要解释。"},
            {"role": "user", "content": content},
        ],
        temperature=0.1,
        max_tokens=260,
        timeout=4.5,
    )
    translated = (message or {}).get("content", "")
    return translated.strip() or None

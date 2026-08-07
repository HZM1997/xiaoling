"""
小灵 · 大模型兜底(意图理解 + 温暖闲聊)
设计要点:
  - 规则层拿不准时才调用,降低模型费用。
  - 统一走 Kimi-first 国内模型网关,未配 KEY 时自动降级为离线兜底话术。
"""
from __future__ import annotations
import hashlib
import json
from difflib import SequenceMatcher

import llm_gateway
from models import Utterance, Reply

# 意图 → 客户端动作类型
_INTENT_TO_ACTION = {
    "call": "CALL", "navigate": "OPEN_URI", "play_music": "PLAY",
    "translate": "TRANSLATE", "chat": None, "unknown": None,
}

_SYSTEM = (
    "你是老年人的贴心手机智能体“小灵”。你不是命令复读机，要结合最近对话、用户偏好和当前语气，"
    "理解省略、指代、口语混乱和话题跳转。先在内部判断真实意图、已知事实、缺失信息、情绪和安全风险，"
    "再给自然回答，但不要输出思维过程。能直接回答就直接回答，不要每句话都追问，也不要机械复述用户原话。"
    "事实问题清楚可靠，情绪话题先共情再回应，轻松话题自然活泼，风险场景坚定简短。"
    "识别设备操作意图；闲聊和开放问题使用 intent=chat。"
)


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


def _reasoning_effort(text: str) -> str:
    normalized = " ".join(text.split())
    score = int(len(normalized) >= 48) + int(len(normalized) >= 100)
    score += sum(
        marker in normalized
        for marker in ("分析", "比较", "原因", "为什么", "怎么办", "计划", "方案", "利弊", "如果", "帮我想")
    )
    score += int(sum(normalized.count(mark) for mark in ("，", ",", "；", ";")) >= 3)
    return "medium" if score >= 2 else "low"


def _ordered_messages(text: str, recent: list[dict]) -> list[dict]:
    messages = [{
        "role": "system",
        "content": _SYSTEM + (
            "只输出JSON对象，格式为"
            "{\"speech\":\"自然口语回复\",\"intent\":\"chat|call|navigate|play_music|translate|unknown\","
            "\"slots\":{},\"tone\":\"neutral|warm|cheerful|serious|concerned\"}。"
        ),
    }]
    for item in recent[-20:]:
        if not isinstance(item, dict) or item.get("role") not in {"user", "assistant"}:
            continue
        content = str(item.get("content") or "").strip()
        if content:
            messages.append({"role": item["role"], "content": content[:800]})
    messages.append({"role": "user", "content": text[:2000]})
    return messages


def _too_similar_to_recent(speech: str, recent: list[dict]) -> bool:
    clean = "".join(speech.split())
    if len(clean) < 10:
        return False
    assistants = [
        "".join(str(item.get("content") or "").split())
        for item in recent[-8:]
        if isinstance(item, dict) and item.get("role") == "assistant"
    ]
    return any(previous and SequenceMatcher(None, clean, previous).ratio() >= 0.82 for previous in assistants)


def _call_agent(text: str, runtime_context: dict | None = None) -> dict | None:
    recent = (runtime_context or {}).get("recent_turns") or []
    recent = recent if isinstance(recent, list) else []
    messages = _ordered_messages(text, recent)
    effort = _reasoning_effort(text)
    message = llm_gateway.chat(
        messages=messages,
        temperature=0.68,
        max_tokens=700,
        timeout=8.0,
        reasoning_effort=effort,
    )
    result = _json_object((message or {}).get("content", ""))
    speech = str((result or {}).get("speech") or "").strip()
    if result and _too_similar_to_recent(speech, recent):
        retry_messages = [
            *messages,
            {"role": "assistant", "content": json.dumps(result, ensure_ascii=False)},
            {"role": "user", "content": "这段回答与前面太像。保留事实，但换一种自然说法并推进话题，不要重复开场。只输出JSON。"},
        ]
        rewritten = llm_gateway.chat(
            messages=retry_messages,
            temperature=0.82,
            max_tokens=700,
            timeout=6.0,
            reasoning_effort="medium",
        )
        retry_result = _json_object((rewritten or {}).get("content", ""))
        if retry_result and str(retry_result.get("speech") or "").strip():
            result = retry_result
    return result


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

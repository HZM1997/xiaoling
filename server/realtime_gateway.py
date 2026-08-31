"""Provider-neutral Realtime voice proxy with tools, memory, and delegation."""
from __future__ import annotations

import asyncio
import audioop
import base64
import binascii
import inspect
import json
import os
import secrets
from contextlib import suppress
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import websockets
from fastapi import WebSocket, WebSocketDisconnect

import fraud
import firewall
import llm_gateway
import pipecat_bridge
from agent_runtime import runtime
from brain import _system_prompt
from context_engine import build_context
from llm import _reasoning_effort, _too_similar_to_recent


_REALTIME_TOOLS = [
    {
        "type": "function",
        "name": "open_camera",
        "description": "Open the user's camera only after an explicit spoken request to look at or identify something. Also use for 'look again' or switching front/back while the camera is visible. If the user asks for a photographic style at the same time, pass the requested filter. Automatically inspect one frame and answer by voice.",
        "parameters": {
            "type": "object",
            "properties": {
                "lens": {"type": "string", "enum": ["front", "back"]},
                "prompt": {"type": "string"},
                "filter": {"type": "string", "enum": ["natural", "warm", "cream", "mist", "cool", "vivid", "sunset", "forest", "teal_orange", "vintage", "film", "hong_kong", "mono", "noir"]},
                "filter_strength": {"type": "number", "minimum": 0.25, "maximum": 1.0},
                "exposure": {"type": "number", "minimum": -0.35, "maximum": 0.35},
                "saturation": {"type": "number", "minimum": 0.35, "maximum": 1.65},
                "whitening": {"type": "number", "minimum": 0.0, "maximum": 1.0},
                "smoothing": {"type": "number", "minimum": 0.0, "maximum": 1.0},
                "style_description": {"type": "string", "description": "Short Chinese description of the interpreted visual mood."},
                "capture": {"type": "boolean", "description": "True when the same utterance explicitly asks to take the photo."},
            },
            "required": ["prompt"],
        },
    },
    {
        "type": "function",
        "name": "close_camera",
        "description": "Close the camera and return to the main assistant when the user says return, exit, or close camera while the camera is visible.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "type": "function",
        "name": "switch_camera",
        "description": "Switch the already open Android camera to the front or back lens while keeping the realtime voice conversation active.",
        "parameters": {
            "type": "object",
            "properties": {
                "lens": {"type": "string", "enum": ["front", "back"]},
                "prompt": {"type": "string"},
            },
            "required": ["lens"],
        },
    },
    {
        "type": "function",
        "name": "set_camera_filter",
        "description": "Interpret the user's natural photographic mood request and immediately change the open camera. Infer a preset and grading values for descriptions such as influencer portrait, clear cool tone, fallen ancient princess, cinematic, whitening, or skin smoothing. The user does not need to name a filter.",
        "parameters": {
            "type": "object",
            "properties": {
                "filter": {"type": "string", "enum": ["natural", "warm", "cream", "mist", "cool", "vivid", "sunset", "forest", "teal_orange", "vintage", "film", "hong_kong", "mono", "noir"]},
                "filter_strength": {"type": "number", "minimum": 0.25, "maximum": 1.0},
                "exposure": {"type": "number", "minimum": -0.35, "maximum": 0.35},
                "saturation": {"type": "number", "minimum": 0.35, "maximum": 1.65},
                "whitening": {"type": "number", "minimum": 0.0, "maximum": 1.0},
                "smoothing": {"type": "number", "minimum": 0.0, "maximum": 1.0},
                "style_description": {"type": "string"},
            },
            "required": [],
        },
    },
    {
        "type": "function",
        "name": "call_contact",
        "description": "Call a contact on the Android phone after the user clearly asks to call.",
        "parameters": {
            "type": "object",
            "properties": {"target": {"type": "string"}},
            "required": ["target"],
        },
    },
    {
        "type": "function",
        "name": "set_reminder",
        "description": "Set an alarm, medication reminder, or other spoken reminder on the phone.",
        "parameters": {
            "type": "object",
            "properties": {"raw": {"type": "string", "description": "Complete Chinese reminder request including time and content."}},
            "required": ["raw"],
        },
    },
    {
        "type": "function",
        "name": "play_media",
        "description": "Play opera, music, storytelling, radio, or another requested audio/video item.",
        "parameters": {
            "type": "object",
            "properties": {"keyword": {"type": "string"}},
            "required": ["keyword"],
        },
    },
    {
        "type": "function",
        "name": "check_fraud",
        "description": "Assess suspicious calls, messages, transfers, links, remote-control requests, or scam claims.",
        "parameters": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "type": "function",
        "name": "ask_kimi",
        "description": "Use Kimi K3 only when the user explicitly asks for deeper reasoning or the question genuinely needs multi-step analysis. Answer ordinary companionship, life questions, translation, and short explanations directly in the realtime model to avoid latency.",
        "parameters": {
            "type": "object",
            "properties": {"question": {"type": "string"}},
            "required": ["question"],
        },
    },
    {
        "type": "function",
        "name": "delegate_complex_task",
        "description": "Delegate a complex research, planning, comparison, or multi-step reasoning task to a stronger background model while continuing the live conversation.",
        "parameters": {
            "type": "object",
            "properties": {
                "task": {"type": "string"},
                "success_criteria": {"type": "string"},
            },
            "required": ["task"],
        },
    },
]


def _env_number(name: str, default: float, minimum: float, maximum: float) -> float:
    try:
        value = float(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(value, maximum))


def _env_bool(name: str, default: bool = False) -> bool:
    raw = os.getenv(name, "").strip().lower()
    if not raw:
        return default
    return raw in {"1", "true", "yes", "on"}


def _with_model(url: str, model: str) -> str:
    parts = urlsplit(url)
    query = parse_qsl(parts.query, keep_blank_values=True)
    if not any(name == "model" for name, _ in query):
        query.append(("model", model))
    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))


def _qwen_config() -> dict[str, Any] | None:
    key = os.getenv("DASHSCOPE_API_KEY", "").strip()
    workspace_id = os.getenv("XL_QWEN_WORKSPACE_ID", "").strip()
    # The API-Key page displays a compatible endpoint prefixed with "llm-".
    # Realtime expects the raw workspace ID, so accept either form in config.
    if workspace_id.startswith("llm-"):
        workspace_id = workspace_id.removeprefix("llm-")
    custom_url = os.getenv("XL_QWEN_REALTIME_URL", "").strip()
    if not key or not (workspace_id or custom_url):
        return None
    model = os.getenv("XL_QWEN_REALTIME_MODEL", "").strip() or "qwen3.5-omni-plus-realtime"
    base_url = custom_url or f"wss://{workspace_id}.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime"
    return {
        "name": "qwen",
        "key": key,
        "model": model,
        "url": _with_model(base_url, model),
        "headers": {"Authorization": f"Bearer {key}"},
        "input_rate": 16000,
    }


def _provider_candidates() -> list[dict[str, Any]]:
    qwen = _qwen_config()
    return [qwen] if qwen is not None else []


def available() -> bool:
    return bool(_provider_candidates())


def status() -> dict[str, Any]:
    providers = _provider_candidates()
    primary = providers[0] if providers else None
    return {
        "available": bool(providers),
        "provider": primary["name"] if primary else "unconfigured",
        "providers": [item["name"] for item in providers],
        "fallback_enabled": len(providers) > 1,
        "model": primary["model"] if primary else "unconfigured",
        "delegation": llm_gateway.available(),
        "agent_model": "kimi-k3" if llm_gateway.has_provider("kimi") else "unconfigured",
        "delegate_model_configured": (
            llm_gateway.has_provider("kimi") or bool(os.getenv("XL_DELEGATE_MODEL", "").strip())
        ),
        "orchestrator": pipecat_bridge.status(),
        "full_duplex": True,
        "backchannel": True,
    }


def _instructions(user_id: str, context: dict, latest_text: str = "") -> str:
    dynamic = build_context(runtime.memory, user_id, latest_text, context)
    base = _system_prompt(dynamic.get("profile"), "realtime_voice", dynamic)
    return (
        base
        + "\n你正在进行自然的全双工中文语音对话。不要重复固定欢迎语。"
        + "老人插话时立即停止当前回答并听新指令，不要抱怨被打断。"
        + "相机已经打开时，用户要求前置、后置或切换摄像头，必须调用 switch_camera，并保持当前语音会话。"
        + "回答先说结论，通常一到三句；用户持续讲述较长内容时，可偶尔用很短的‘嗯’或‘我在听’回应，但不要频繁打断。"
        + "自动识别用户说的语言。用户要求翻译或口译时直接使用目标语言回答，保留姓名和数字，不添加解释，也不要为了翻译调用后台复杂任务。"
        + "用户说得不完整时先结合最近上下文补全意图；仍有两个以上可能含义时，只追问一个最关键的问题。"
        + "打电话、提醒、播放和反诈研判必须调用对应工具；相机已经打开时，用户用任何自然语言描述想要的照片感觉、色调、美白或磨皮，立即理解审美意图并调用 set_camera_filter，不要让用户先打开或选择滤镜库，也不要要求用户说滤镜名称。"
        + "普通陪伴、生活问答和翻译由你直接自然回答；短解释也不要调用工具，只有确实需要多步推理或专业分析时才调用 ask_kimi。"
        + "若 ask_kimi 暂不可用，基于已有上下文自行回答，不要重复任何固定的服务故障话术。"
        + "耗时研究、复杂比较或多步方案调用 delegate_complex_task；工具返回已受理后，简短告知后台正在处理，然后继续正常聊天。"
        + "按对话内容自然变化语气：事实问题直接、情绪话题温和、喜事轻快、风险场景坚定；"
        + "声音保持温柔自然的年轻女性口吻，语速舒缓但不拖沓，避免播音腔和机械客服腔。"
        + "避免重复固定开场，允许简短的嗯、我在听等反馈，但不要抢话。"
        + "每轮先在内部判断用户真正想表达什么、上文指代什么、该直接回答还是调用工具；不要输出思维过程。"
        + "不要把每句陈述都变成反问。能推进话题时补充一个有用的新信息或自然回应。"
    )


def _qwen_tools() -> list[dict]:
    return [
        {
            "type": "function",
            "function": {
                "name": tool["name"],
                "description": tool["description"],
                "parameters": tool["parameters"],
            },
        }
        for tool in _REALTIME_TOOLS
    ]


def _session_update(
    user_id: str,
    context: dict,
    latest_text: str = "",
) -> dict:
    instructions = _instructions(user_id, context, latest_text)
    return {
        "type": "session.update",
        "session": {
            "modalities": ["text", "audio"],
            "voice": os.getenv("XL_QWEN_REALTIME_VOICE", "").strip() or "Tina",
            "input_audio_format": "pcm",
            "output_audio_format": "pcm",
            "instructions": instructions,
            "turn_detection": {
                "type": "semantic_vad",
                "threshold": 0.20,
                # Keep normal breathing and hesitant speech in one turn. The
                # Android client still confirms barge-in locally within about
                # 60-100 ms, so this does not slow interruption onset.
                "silence_duration_ms": 900,
                "create_response": True,
                # Qwen's server VAD also hears the phone speaker.  Xiaoling
                # confirms barge-in with the Android local VAD before it
                # cancels a response, so server-side interruption is opt-in.
                "interrupt_response": _env_bool("XL_QWEN_SERVER_INTERRUPT", False),
            },
            "tools": _qwen_tools(),
        },
    }


def _resample_pcm24_to_16(encoded: str, state: Any = None) -> tuple[str, Any]:
    try:
        pcm = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise ValueError("invalid PCM base64") from exc
    if not pcm or len(pcm) % 2:
        raise ValueError("invalid PCM16 frame")
    converted, next_state = audioop.ratecv(pcm, 2, 1, 24000, 16000, state)
    return base64.b64encode(converted).decode("ascii"), next_state


def _safe_json(value: str) -> dict:
    try:
        parsed = json.loads(value or "{}")
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        return {}


def _bounded_float(value: Any, minimum: float, maximum: float) -> float | None:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    if not (minimum <= parsed <= maximum):
        return None
    return parsed


def _tool_call(event: dict) -> tuple[str, str, dict]:
    item = event.get("item") if isinstance(event.get("item"), dict) else event
    return (
        str(item.get("call_id") or item.get("id") or ""),
        str(item.get("name") or ""),
        _safe_json(str(item.get("arguments") or "{}")),
    )


def _action_for(name: str, args: dict) -> tuple[dict | None, dict]:
    if name == "open_camera":
        lens = "front" if str(args.get("lens") or "back").strip().lower() == "front" else "back"
        prompt = str(args.get("prompt") or "识别相机画面中的物品，并用简洁中文说明").strip()[:1200]
        requested_filter = str(args.get("filter") or "").strip().lower()
        action = {"type": "OPEN_CAMERA", "lens": lens, "prompt": prompt}
        allowed_filters = {"natural", "warm", "cream", "mist", "cool", "vivid", "sunset", "forest", "teal_orange", "vintage", "film", "hong_kong", "mono", "noir"}
        if requested_filter in allowed_filters:
            action["filter"] = requested_filter
        strength = _bounded_float(args.get("filter_strength"), 0.25, 1.0)
        exposure = _bounded_float(args.get("exposure"), -0.35, 0.35)
        saturation = _bounded_float(args.get("saturation"), 0.35, 1.65)
        if strength is not None:
            action["filter_strength"] = strength
        if exposure is not None:
            action["exposure"] = exposure
        if saturation is not None:
            action["saturation"] = saturation
        whitening = _bounded_float(args.get("whitening"), 0.0, 1.0)
        smoothing = _bounded_float(args.get("smoothing"), 0.0, 1.0)
        if whitening is not None:
            action["whitening"] = whitening
        if smoothing is not None:
            action["smoothing"] = smoothing
        description = str(args.get("style_description") or "").strip()[:32]
        if description:
            action["style_description"] = description
        if bool(args.get("capture")):
            action["capture"] = True
        return action, {
            "ok": True, "lens": lens, "prompt": prompt, "filter": action.get("filter", ""),
            "filter_strength": action.get("filter_strength"), "exposure": action.get("exposure"),
            "saturation": action.get("saturation"), "whitening": action.get("whitening"),
            "smoothing": action.get("smoothing"), "style_description": action.get("style_description", ""),
            "capture": bool(action.get("capture")),
        }
    if name == "close_camera":
        return {"type": "CLOSE_CAMERA"}, {"ok": True}
    if name == "switch_camera":
        lens = "front" if str(args.get("lens") or "back").strip().lower() == "front" else "back"
        prompt = str(args.get("prompt") or f"切换到{'前置' if lens == 'front' else '后置'}摄像头").strip()[:300]
        return {"type": "SWITCH_CAMERA", "lens": lens, "prompt": prompt}, {"ok": True, "lens": lens}
    if name == "set_camera_filter":
        value = str(args.get("filter") or "").strip().lower()
        allowed_filters = {"natural", "warm", "cream", "mist", "cool", "vivid", "sunset", "forest", "teal_orange", "vintage", "film", "hong_kong", "mono", "noir"}
        filter_name = value if value in allowed_filters else ""
        action = {"type": "SET_CAMERA_FILTER"}
        if filter_name:
            action["filter"] = filter_name
        strength = _bounded_float(args.get("filter_strength"), 0.25, 1.0)
        exposure = _bounded_float(args.get("exposure"), -0.35, 0.35)
        saturation = _bounded_float(args.get("saturation"), 0.35, 1.65)
        if strength is not None:
            action["filter_strength"] = strength
        if exposure is not None:
            action["exposure"] = exposure
        if saturation is not None:
            action["saturation"] = saturation
        whitening = _bounded_float(args.get("whitening"), 0.0, 1.0)
        smoothing = _bounded_float(args.get("smoothing"), 0.0, 1.0)
        if whitening is not None:
            action["whitening"] = whitening
        if smoothing is not None:
            action["smoothing"] = smoothing
        description = str(args.get("style_description") or "").strip()[:32]
        if description:
            action["style_description"] = description
        return action, {
            "ok": True, "filter": filter_name,
            "filter_strength": action.get("filter_strength"), "exposure": action.get("exposure"),
            "saturation": action.get("saturation"), "whitening": action.get("whitening"),
            "smoothing": action.get("smoothing"), "style_description": action.get("style_description", ""),
        }
    if name == "call_contact":
        target = str(args.get("target") or "").strip()[:80]
        return {"type": "CALL", "target": target}, {"ok": bool(target), "target": target}
    if name == "set_reminder":
        raw = str(args.get("raw") or "").strip()[:300]
        return {"type": "REMIND", "raw": raw}, {"ok": bool(raw), "raw": raw}
    if name == "play_media":
        keyword = str(args.get("keyword") or "戏曲").strip()[:120]
        return {"type": "PLAY", "keyword": keyword}, {"ok": True, "keyword": keyword}
    if name == "check_fraud":
        text = str(args.get("text") or "").strip()[:800]
        result = fraud.analyze(text, scene="voice_chat").to_dict()
        result["response_protocol"] = {
            "pause": "停止转账、验证码、共享屏幕和安装软件",
            "verify": "挂断后只通过官方应用或原来保存的号码独立核验",
            "escalate": "已经付款或泄露信息时立即联系银行和当地警方",
        }
        action = {"type": "FRAUD_WARN"} if result.get("level") in {"medium", "high"} else None
        return action, {"ok": True, **result}
    return None, {"ok": False, "error": "unsupported_tool"}


def _delegate(task: str, success_criteria: str, context: dict) -> str:
    provider = os.getenv("XL_DELEGATE_PROVIDER", "").strip().lower() or None
    if provider is None and llm_gateway.has_provider("kimi"):
        provider = "kimi"
    model = os.getenv("XL_DELEGATE_MODEL", "").strip() or ("kimi-k3" if provider == "kimi" else None)
    messages = [
        {
            "role": "system",
            "content": (
                "你是小灵的后台高级任务执行器。请独立完成任务，核对关键条件，输出可直接给老年用户播报的中文结论。"
                "先给结论，再给必要步骤；不要声称已执行现实世界中未执行的动作。"
            ),
        },
        {
            "role": "user",
            "content": json.dumps(
                {"task": task[:1800], "success_criteria": success_criteria[:600], "context": context},
                ensure_ascii=False,
            ),
        },
    ]
    message = llm_gateway.chat(
        messages,
        temperature=0.25,
        max_tokens=1400,
        timeout=45.0,
        model_override=model,
        provider_override=provider,
        reasoning_effort="max" if provider == "kimi" else None,
    )
    if not message:
        return "后台任务暂时没有完成，网络恢复后我会再试。"
    content = message.get("content")
    if isinstance(content, list):
        content = "".join(str(item.get("text", "")) for item in content if isinstance(item, dict))
    return str(content or "后台任务已经完成，但没有生成可播报的结果。").strip()[:5000]


def _ask_kimi(question: str, context: dict) -> str:
    messages = [
        {
            "role": "system",
            "content": (
                "你是小灵，一位面向老年人的中文实时语音智能体。理解口语省略、语序混乱、指代和话题跳转。"
                "回答前在内部判断真实意图、相关记忆、情绪、缺失信息和安全风险，但不要输出思维过程。"
                "先给结论，再用短句自然展开；能直接回答就回答，不要机械复述，也不要每句话都追问。"
                "按话题调整语气：日常轻松、情绪温和、喜事活泼、风险坚定。"
                "不重复固定开场，不声称已执行未执行的现实操作。"
            ),
        },
        {
            "role": "user",
            "content": json.dumps({"question": question[:1800], "context": context}, ensure_ascii=False),
        },
    ]
    # Preserve role order across the latest twenty stored turns.  Realtime
    # sessions record the current user transcript before invoking this tool.
    recent = context.get("recent_turns") if isinstance(context, dict) else []
    history_messages = []
    if isinstance(recent, list):
        for item in recent[-20:]:
            if not isinstance(item, dict) or item.get("role") not in {"user", "assistant"}:
                continue
            content = str(item.get("content") or "").strip()
            if content:
                history_messages.append({"role": item["role"], "content": content[:1000]})
    messages = [messages[0], *history_messages]
    if not history_messages or history_messages[-1]["role"] != "user" or history_messages[-1]["content"] != question[:1000]:
        messages.append({"role": "user", "content": question[:1800]})

    effort = _reasoning_effort(question)
    message = llm_gateway.chat(
        messages,
        max_tokens=1100,
        timeout=10.0 if effort == "high" else 7.0,
        model_override="kimi-k3",
        provider_override="kimi",
        reasoning_effort=effort,
    )
    if not message:
        # The realtime model can still answer naturally. Returning a fixed
        # spoken sentence here made every failed delegation sound identical.
        return ""
    content = message.get("content")
    if isinstance(content, list):
        content = "".join(str(item.get("text", "")) for item in content if isinstance(item, dict))
    answer = str(content or "").strip()[:3000]
    if answer and _too_similar_to_recent(answer, history_messages):
        retry = llm_gateway.chat(
            [
                *messages,
                {"role": "assistant", "content": answer},
                {"role": "user", "content": "这段回答和前文太像。保留事实，换一种自然表达，并真正推进当前话题。"},
            ],
            max_tokens=1100,
            timeout=10.0,
            model_override="kimi-k3",
            provider_override="kimi",
            reasoning_effort="medium",
        )
        rewritten = (retry or {}).get("content", "")
        if isinstance(rewritten, list):
            rewritten = "".join(
                str(item.get("text", "")) for item in rewritten if isinstance(item, dict)
            )
        if str(rewritten or "").strip():
            answer = str(rewritten).strip()[:3000]
    return answer


async def handle(websocket: WebSocket) -> None:
    if not firewall.token_valid(websocket.headers):
        await websocket.close(code=4401)
        return
    peer = websocket.client.host if websocket.client else "unknown"
    if not firewall.acquire_realtime(peer):
        await websocket.close(code=4429)
        return
    try:
        await _handle_session(websocket)
    finally:
        firewall.release_realtime(peer)


async def _handle_session(websocket: WebSocket) -> None:
    if not available():
        await websocket.accept()
        await websocket.send_json({"type": "error", "code": "realtime_not_configured"})
        await websocket.close(code=1013)
        return

    await websocket.accept()
    try:
        first = await asyncio.wait_for(websocket.receive_json(), timeout=8.0)
    except Exception:
        await websocket.close(code=4400)
        return
    if first.get("type") != "session.start":
        await websocket.close(code=4400)
        return
    if len(json.dumps(first, separators=(",", ":"))) > 32_768:
        await websocket.close(code=4409)
        return

    user_id = str(first.get("user_id") or "guest")[:64]
    context = first.get("context") if isinstance(first.get("context"), dict) else {}
    candidates = _provider_candidates()

    send_lock = asyncio.Lock()
    client_lock = asyncio.Lock()
    handled_calls: set[str] = set()
    background: set[asyncio.Task] = set()
    delegation_slots = asyncio.Semaphore(
        int(_env_number("XL_DELEGATION_CONCURRENCY", 2, 1, 4))
    )
    state = {
        "response_active": False,
        "response_cancel_pending": False,
        "user_speaking": False,
        "confirmed_user_speaking": False,
        "response_sequence": 0,
        "pending_text_response": False,
    }

    async def send_upstream(upstream, payload: dict) -> None:
        async with send_lock:
            await upstream.send(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))

    async def send_client(payload: dict) -> None:
        async with client_lock:
            await websocket.send_json(payload)

    async def cancel_active_response(upstream) -> None:
        # speech_started and the Android local VAD can arrive for the same
        # interruption. Realtime rejects duplicate/no-op cancels, so serialize
        # them against the response lifecycle instead of dropping the session.
        if not state["response_active"] or state["response_cancel_pending"]:
            return
        state["response_cancel_pending"] = True
        await send_upstream(upstream, {"type": "response.cancel"})

    async def start_pending_text_response(upstream) -> None:
        """Begin a camera/client text answer after the previous turn ends."""
        if (not state["pending_text_response"] or state["response_active"] or
                state["user_speaking"]):
            return
        state["pending_text_response"] = False
        await send_upstream(upstream, {"type": "response.create"})

    async def submit_tool_output(upstream, call_id: str, output: dict) -> None:
        await send_upstream(
            upstream,
            {
                "type": "conversation.item.create",
                "item": {
                    "type": "function_call_output",
                    "call_id": call_id,
                    "output": json.dumps(output, ensure_ascii=False),
                },
            },
        )
        await send_upstream(upstream, {"type": "response.create"})

    async def finish_delegation(upstream, job_id: str, task_text: str, criteria: str) -> None:
        dynamic = build_context(runtime.memory, user_id, task_text, context)
        try:
            async with delegation_slots:
                result = await asyncio.wait_for(
                    asyncio.to_thread(_delegate, task_text, criteria, dynamic),
                    timeout=_env_number("XL_DELEGATION_TIMEOUT", 45, 10, 120),
                )
        except asyncio.TimeoutError:
            result = "这个后台任务还需要更多时间，我已经保留问题，稍后可以继续处理。"
        runtime.memory.record_turn(user_id, "assistant", f"[后台任务 {job_id}] {result}")
        with suppress(Exception):
            await send_client({"type": "delegation.completed", "job_id": job_id, "text": result})
        for _ in range(120):
            if not state["response_active"] and not state["user_speaking"]:
                break
            await asyncio.sleep(0.25)
        with suppress(Exception):
            await send_upstream(
                upstream,
                {
                    "type": "conversation.item.create",
                    "item": {
                        "type": "message",
                        "role": "user",
                        "content": [{
                            "type": "input_text",
                            "text": f"[后台任务 {job_id} 已完成]\n请用两三句自然中文告诉用户最重要的结果。\n{result}",
                        }],
                    },
                },
            )
            await send_upstream(upstream, {"type": "response.create"})

    async def handle_tool(upstream, item: dict) -> None:
        call_id, name, args = _tool_call(item)
        if not call_id or call_id in handled_calls:
            return
        handled_calls.add(call_id)
        if name == "ask_kimi":
            question = str(args.get("question") or "").strip()[:1800]
            dynamic = build_context(runtime.memory, user_id, question, context)
            result = await asyncio.to_thread(_ask_kimi, question, dynamic)
            await submit_tool_output(upstream, call_id, {"ok": bool(result), "answer": result})
            return
        if name == "delegate_complex_task":
            task_text = str(args.get("task") or "").strip()[:1800]
            criteria = str(args.get("success_criteria") or "").strip()[:600]
            active_jobs = sum(not item.done() for item in background)
            if active_jobs >= 4:
                await submit_tool_output(upstream, call_id, {
                    "ok": False,
                    "status": "busy",
                    "message": "后台已有多个任务，先继续当前对话。",
                })
                return
            job_id = secrets.token_hex(4)
            await submit_tool_output(upstream, call_id, {"ok": True, "status": "started", "job_id": job_id})
            await send_client({"type": "delegation.started", "job_id": job_id, "task": task_text[:160]})
            task = asyncio.create_task(finish_delegation(upstream, job_id, task_text, criteria))
            background.add(task)
            task.add_done_callback(background.discard)
            return
        action, output = _action_for(name, args)
        if action:
            await send_client({"type": "tool.action", "action": action})
        await submit_tool_output(upstream, call_id, output)

    async def connect_provider(config: dict[str, Any]):
        connect_args = {
            "open_timeout": 5,
            "ping_interval": 20,
            "ping_timeout": 20,
            "max_size": 4 * 1024 * 1024,
        }
        # websockets 14 renamed extra_headers to additional_headers. Pipecat
        # currently requires a newer websockets release, so support both APIs.
        header_name = (
            "additional_headers"
            if "additional_headers" in inspect.signature(websockets.connect).parameters
            else "extra_headers"
        )
        connect_args[header_name] = config["headers"]
        upstream = await websockets.connect(config["url"], **connect_args)
        try:
            await send_upstream(
                upstream,
                _session_update(user_id, context),
            )
            deadline = asyncio.get_running_loop().time() + 5.0
            while True:
                remaining = deadline - asyncio.get_running_loop().time()
                if remaining <= 0:
                    raise TimeoutError(f"{config['name']} session handshake timed out")
                event = json.loads(await asyncio.wait_for(upstream.recv(), timeout=remaining))
                kind = str(event.get("type") or "")
                if kind == "session.updated":
                    return upstream
                if kind == "error":
                    error = event.get("error") if isinstance(event.get("error"), dict) else {}
                    message = str(error.get("message") or event.get("message") or "Realtime session rejected")
                    raise RuntimeError(message[:300])
        except BaseException:
            with suppress(Exception):
                await upstream.close()
            raise

    async def run_provider(upstream, config: dict[str, Any]) -> bool:
        provider = config["name"]
        model = config["model"]
        vad = pipecat_bridge.DuplexVad()
        backchannel = pipecat_bridge.BackchannelPolicy(
            delay_seconds=_env_number("XL_BACKCHANNEL_DELAY", 1.8, 1.2, 4.0)
        )
        handled_calls.clear()
        state.update({
            "response_active": False,
            "response_cancel_pending": False,
            "user_speaking": False,
            "confirmed_user_speaking": False,
            "response_sequence": 0,
            "pending_text_response": False,
        })
        await send_client({"type": "session.ready", "provider": provider, "model": model})

        async def set_user_speaking(speaking_now: bool, confirmed: bool = False) -> None:
            """Track speech without letting speaker echo cancel a response.

            The upstream semantic VAD is useful for transcripts, but it cannot
            distinguish a phone speaker from a person's voice in PiP or on a
            noisy handset.  Only an explicit client VAD confirmation (or a
            manual UI event) is allowed to cancel the active response.
            """
            if speaking_now:
                was_speaking = state["user_speaking"]
                state["user_speaking"] = True
                if confirmed:
                    first_confirmation = not state["confirmed_user_speaking"]
                    state["confirmed_user_speaking"] = True
                    if first_confirmation:
                        await cancel_active_response(upstream)
                if not was_speaking:
                    backchannel.speech_started()
                    await send_client({
                        "type": "input.speech_started",
                        "source": "client_vad" if confirmed else "server_vad",
                    })
                return
            if not state["user_speaking"] and not state["confirmed_user_speaking"]:
                return
            state["user_speaking"] = False
            state["confirmed_user_speaking"] = False
            backchannel.speech_stopped()
            await send_client({"type": "input.speech_stopped"})
            await start_pending_text_response(upstream)

        async def decision_loop() -> None:
            # Five control decisions per second. This loop never waits for the
            # reasoning model and therefore cannot block the live conversation.
            while True:
                await asyncio.sleep(0.2)
                text = backchannel.take_if_due(bool(state["response_active"]))
                if text and state["user_speaking"]:
                    await send_client({"type": "backchannel", "text": text})

        async def client_reader() -> None:
            resample_state = None
            traffic_started = asyncio.get_running_loop().time()
            traffic_messages = 0
            traffic_chars = 0
            while True:
                incoming = await websocket.receive_json()
                now = asyncio.get_running_loop().time()
                if now - traffic_started >= 1.0:
                    traffic_started = now
                    traffic_messages = 0
                    traffic_chars = 0
                traffic_messages += 1
                traffic_chars += len(json.dumps(incoming, separators=(",", ":")))
                if traffic_messages > 120 or traffic_chars > 512_000:
                    await websocket.close(code=4408, reason="realtime traffic limit")
                    return
                kind = incoming.get("type")
                if kind == "audio.append":
                    audio = incoming.get("audio")
                    if isinstance(audio, str) and 0 < len(audio) <= 96_000:
                        try:
                            audio, resample_state = _resample_pcm24_to_16(audio, resample_state)
                        except ValueError:
                            continue
                        await send_upstream(upstream, {"type": "input_audio_buffer.append", "audio": audio})
                        if vad.enabled:
                            pcm = base64.b64decode(audio)
                            transition = await vad.feed(pcm)
                            if transition == "started":
                                await set_user_speaking(True)
                            elif transition == "stopped":
                                await set_user_speaking(False)
                elif kind == "response.cancel":
                    await cancel_active_response(upstream)
                elif kind == "input.speech_candidate":
                    # The phone has paused playback on a possible voice onset,
                    # but ASR has not confirmed a new instruction yet. Track
                    # speech for backchannels without cancelling the answer.
                    await set_user_speaking(True, confirmed=False)
                elif kind == "input.speech_started":
                    # Explicit local VAD confirmation.  This is the only
                    # client event that is trusted to interrupt TTS.
                    await set_user_speaking(True, confirmed=True)
                elif kind == "input.speech_stopped":
                    await set_user_speaking(False)
                elif kind == "conversation.text":
                    text = str(incoming.get("text") or "").strip()[:2000]
                    if text:
                        # Camera observations can arrive while the previous
                        # answer is still unwinding. Queue the new response so
                        # an old response.done cannot terminate it midway.
                        if state["response_active"]:
                            await cancel_active_response(upstream)
                            state["pending_text_response"] = True
                        runtime.memory.record_turn(user_id, "user", text)
                        await send_upstream(
                            upstream,
                            {
                                "type": "conversation.item.create",
                                "item": {"type": "message", "role": "user", "content": [{"type": "input_text", "text": text}]},
                            },
                        )
                        if not state["response_active"]:
                            await send_upstream(upstream, {"type": "response.create"})
                elif kind == "session.context":
                    update = incoming.get("context")
                    if isinstance(update, dict):
                        context.update(update)
                        await send_upstream(
                            upstream,
                            _session_update(user_id, context),
                        )

        async def upstream_reader() -> None:
            assistant_text: list[str] = []
            async for raw in upstream:
                event = json.loads(raw)
                kind = str(event.get("type") or "")
                if kind == "session.updated":
                    continue
                elif kind == "input_audio_buffer.speech_started":
                    await set_user_speaking(True, confirmed=False)
                elif kind == "input_audio_buffer.speech_stopped":
                    await set_user_speaking(False)
                elif kind in {
                    "conversation.item.input_audio_transcription.delta",
                    "input_audio_transcription.delta",
                }:
                    delta = str(event.get("delta") or "")
                    if delta:
                        await send_client({"type": "input.transcript.delta", "text": delta})
                elif kind in {
                    "conversation.item.input_audio_transcription.completed",
                    "input_audio_transcription.completed",
                }:
                    transcript = str(event.get("transcript") or "").strip()
                    if transcript:
                        runtime.memory.extract_facts(user_id, transcript)
                        runtime.memory.record_turn(user_id, "user", transcript)
                        await send_client({"type": "input.transcript.done", "text": transcript})
                        await send_upstream(
                            upstream,
                            _session_update(user_id, context, transcript),
                        )
                        response_sequence = state["response_sequence"]

                        async def ensure_response(sequence: int) -> None:
                            await asyncio.sleep(0.7)
                            if (sequence == state["response_sequence"] and
                                    not state["response_active"] and not state["user_speaking"]):
                                await send_upstream(upstream, {"type": "response.create"})

                        task = asyncio.create_task(ensure_response(response_sequence))
                        background.add(task)
                        task.add_done_callback(background.discard)
                elif kind == "response.created":
                    state["response_sequence"] += 1
                    state["response_active"] = True
                    state["response_cancel_pending"] = False
                    await send_client({"type": "output.started"})
                elif kind == "response.output_item.added":
                    if not state["response_active"]:
                        state["response_active"] = True
                        await send_client({"type": "output.started"})
                elif kind in {"response.output_audio.delta", "response.audio.delta"}:
                    delta = event.get("delta")
                    if not state["response_cancel_pending"] and isinstance(delta, str) and delta:
                        await send_client({"type": "output.audio.delta", "audio": delta})
                elif kind in {"response.output_audio_transcript.delta", "response.audio_transcript.delta", "response.text.delta"}:
                    delta = str(event.get("delta") or "")
                    if not state["response_cancel_pending"] and delta:
                        assistant_text.append(delta)
                        await send_client({"type": "output.transcript.delta", "text": delta})
                elif kind in {"response.output_audio_transcript.done", "response.audio_transcript.done", "response.text.done"}:
                    transcript = str(event.get("transcript") or event.get("text") or "").strip()
                    if not state["response_cancel_pending"] and transcript:
                        assistant_text[:] = [transcript]
                        await send_client({"type": "output.transcript.done", "text": transcript})
                elif kind == "response.function_call_arguments.done":
                    await handle_tool(upstream, event)
                elif kind == "response.output_item.done":
                    item = event.get("item") if isinstance(event.get("item"), dict) else {}
                    if item.get("type") in {"function_call", "tool_call"}:
                        await handle_tool(upstream, event)
                elif kind in {"response.done", "response.cancelled"}:
                    was_cancelled = state["response_cancel_pending"] or kind == "response.cancelled"
                    state["response_active"] = False
                    state["response_cancel_pending"] = False
                    complete = "".join(assistant_text).strip()
                    assistant_text.clear()
                    # A cancelled partial reply is neither a memory turn nor
                    # a UI completion. Delivering it after the user has
                    # started a new request lets stale state overwrite the
                    # new response, especially in camera conversations.
                    if complete and not was_cancelled:
                        runtime.memory.record_turn(user_id, "assistant", complete)
                    if not was_cancelled:
                        await send_client({"type": "output.done", "text": complete})
                    await start_pending_text_response(upstream)
                elif kind == "error":
                    error = event.get("error") if isinstance(event.get("error"), dict) else {}
                    message = str(error.get("message") or event.get("message") or "Realtime error")
                    lower_message = message.lower()
                    if "cancel" in lower_message and (
                        "not active" in lower_message or "no active" in lower_message or "already" in lower_message
                    ):
                        state["response_active"] = False
                        state["response_cancel_pending"] = False
                    else:
                        raise RuntimeError(message[:300])

        client_task = asyncio.create_task(client_reader())
        upstream_task = asyncio.create_task(upstream_reader())
        control_task = asyncio.create_task(decision_loop())
        try:
            done, pending = await asyncio.wait(
                {client_task, upstream_task}, return_when=asyncio.FIRST_COMPLETED
            )
            for task in pending:
                task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)
            if client_task in done:
                with suppress(WebSocketDisconnect, asyncio.CancelledError):
                    client_task.result()
                return False
            upstream_task.result()
            raise ConnectionError(f"{provider} realtime connection closed")
        finally:
            control_task.cancel()
            await asyncio.gather(control_task, return_exceptions=True)
            await vad.close()

    last_error: Exception | None = None
    try:
        for config in candidates:
            upstream = None
            try:
                upstream = await connect_provider(config)
                client_closed = not await run_provider(upstream, config)
                if client_closed:
                    return
            except WebSocketDisconnect:
                return
            except Exception as exc:
                last_error = exc
            finally:
                if upstream is not None:
                    with suppress(Exception):
                        await upstream.close()
            for task in list(background):
                task.cancel()
            background.clear()
        message = str(last_error or "Realtime provider unavailable")[:180]
        with suppress(Exception):
            await send_client({"type": "error", "code": "realtime_unavailable", "message": message})
    except WebSocketDisconnect:
        pass
    except Exception as exc:
        with suppress(Exception):
            await send_client({"type": "error", "code": "realtime_unavailable", "message": str(exc)[:180]})
    finally:
        for task in background:
            task.cancel()
        with suppress(Exception):
            await websocket.close()

"""Provider-neutral Realtime voice proxy with tools, memory, and delegation."""
from __future__ import annotations

import asyncio
import audioop
import base64
import binascii
import json
import os
import secrets
from contextlib import suppress
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import websockets
from fastapi import WebSocket, WebSocketDisconnect

import fraud
import llm_gateway
from agent_runtime import runtime
from brain import _system_prompt
from context_engine import build_context


_REALTIME_TOOLS = [
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


def _openai_config() -> dict[str, Any] | None:
    key = os.getenv("XL_REALTIME_API_KEY", "").strip() or os.getenv("OPENAI_API_KEY", "").strip()
    if not key:
        return None
    model = os.getenv("XL_REALTIME_MODEL", "").strip() or "gpt-realtime"
    base_url = os.getenv("XL_REALTIME_URL", "").strip() or "wss://api.openai.com/v1/realtime"
    return {
        "name": "openai",
        "key": key,
        "model": model,
        "url": _with_model(base_url, model),
        "headers": {"Authorization": f"Bearer {key}", "OpenAI-Beta": "realtime=v1"},
        "input_rate": 24000,
    }


def _provider_candidates() -> list[dict[str, Any]]:
    configured = {
        "qwen": _qwen_config(),
        "openai": _openai_config(),
    }
    requested: list[str] = []
    for item in os.getenv("XL_REALTIME_PROVIDER", "qwen,openai").split(","):
        name = item.strip().lower()
        if name in configured and name not in requested:
            requested.append(name)
    order = requested + [name for name in ("qwen", "openai") if name not in requested]
    return [configured[name] for name in order if configured[name] is not None]


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
        "delegate_model_configured": bool(os.getenv("XL_DELEGATE_MODEL", "").strip()),
    }


def _instructions(user_id: str, context: dict, latest_text: str = "") -> str:
    dynamic = build_context(runtime.memory, user_id, latest_text, context)
    base = _system_prompt(dynamic.get("profile"), "realtime_voice", dynamic)
    return (
        base
        + "\n你正在进行自然的全双工中文语音对话。不要重复固定欢迎语。"
        + "老人插话时立即停止当前回答并听新指令，不要抱怨被打断。"
        + "回答先说结论，通常一到三句；用户持续讲述较长内容时，可偶尔用很短的‘嗯’或‘我在听’回应，但不要频繁打断。"
        + "打电话、提醒、播放和反诈研判必须调用对应工具。"
        + "耗时研究、复杂比较或多步方案调用 delegate_complex_task；工具返回已受理后，简短告知后台正在处理，然后继续正常聊天。"
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
    legacy: bool = False,
    provider: str = "openai",
) -> dict:
    instructions = _instructions(user_id, context, latest_text)
    if provider == "qwen":
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
                    "threshold": 0.32,
                    "silence_duration_ms": 520,
                },
                "tools": _qwen_tools(),
            },
        }
    voice = os.getenv("XL_REALTIME_VOICE", "").strip() or "marin"
    if legacy:
        return {
            "type": "session.update",
            "session": {
                "modalities": ["text", "audio"],
                "instructions": instructions,
                "voice": voice,
                "input_audio_format": "pcm16",
                "output_audio_format": "pcm16",
                "input_audio_transcription": {"model": "gpt-4o-mini-transcribe", "language": "zh"},
                "turn_detection": {
                    "type": "server_vad",
                    "threshold": 0.32,
                    "prefix_padding_ms": 480,
                    "silence_duration_ms": 420,
                    "create_response": True,
                },
                "tools": _REALTIME_TOOLS,
                "tool_choice": "auto",
                "temperature": 0.7,
            },
        }
    return {
        "type": "session.update",
        "session": {
            "type": "realtime",
            "model": os.getenv("XL_REALTIME_MODEL", "gpt-realtime"),
            "instructions": instructions,
            "output_modalities": ["audio"],
            "audio": {
                "input": {
                    "format": {"type": "audio/pcm", "rate": 24000},
                    "transcription": {"model": "gpt-4o-mini-transcribe", "language": "zh"},
                    "noise_reduction": {"type": "far_field"},
                    "turn_detection": {
                        "type": "server_vad",
                        "threshold": 0.32,
                        "prefix_padding_ms": 480,
                        "silence_duration_ms": 420,
                        "create_response": True,
                        "interrupt_response": True,
                    },
                },
                "output": {
                    "format": {"type": "audio/pcm", "rate": 24000},
                    "voice": voice,
                    "speed": 1.0,
                },
            },
            "tools": _REALTIME_TOOLS,
            "tool_choice": "auto",
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


def _tool_call(event: dict) -> tuple[str, str, dict]:
    item = event.get("item") if isinstance(event.get("item"), dict) else event
    return (
        str(item.get("call_id") or item.get("id") or ""),
        str(item.get("name") or ""),
        _safe_json(str(item.get("arguments") or "{}")),
    )


def _action_for(name: str, args: dict) -> tuple[dict | None, dict]:
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
        action = {"type": "FRAUD_WARN"} if result.get("level") in {"medium", "high"} else None
        return action, {"ok": True, **result}
    return None, {"ok": False, "error": "unsupported_tool"}


def _delegate(task: str, success_criteria: str, context: dict) -> str:
    model = os.getenv("XL_DELEGATE_MODEL", "").strip() or None
    provider = os.getenv("XL_DELEGATE_PROVIDER", "").strip().lower() or None
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
    )
    if not message:
        return "后台任务暂时没有完成，网络恢复后我会再试。"
    content = message.get("content")
    if isinstance(content, list):
        content = "".join(str(item.get("text", "")) for item in content if isinstance(item, dict))
    return str(content or "后台任务已经完成，但没有生成可播报的结果。").strip()[:5000]


async def handle(websocket: WebSocket) -> None:
    expected = os.getenv("XL_REALTIME_CLIENT_TOKEN", "").strip()
    supplied = websocket.headers.get("x-xiaoling-token", "").strip()
    if expected and not secrets.compare_digest(expected, supplied):
        await websocket.close(code=4401)
        return
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

    user_id = str(first.get("user_id") or "guest")[:64]
    context = first.get("context") if isinstance(first.get("context"), dict) else {}
    candidates = _provider_candidates()

    send_lock = asyncio.Lock()
    client_lock = asyncio.Lock()
    handled_calls: set[str] = set()
    background: set[asyncio.Task] = set()
    state = {
        "response_active": False,
        "response_cancel_pending": False,
        "user_speaking": False,
        "legacy": False,
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
        result = await asyncio.to_thread(_delegate, task_text, criteria, dynamic)
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
        if name == "delegate_complex_task":
            task_text = str(args.get("task") or "").strip()[:1800]
            criteria = str(args.get("success_criteria") or "").strip()[:600]
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
        upstream = await websockets.connect(
            config["url"],
            extra_headers=config["headers"],
            open_timeout=5,
            ping_interval=20,
            ping_timeout=20,
            max_size=4 * 1024 * 1024,
        )
        state["legacy"] = False
        try:
            await send_upstream(
                upstream,
                _session_update(user_id, context, provider=config["name"]),
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
                    if config["name"] == "openai" and not state["legacy"] and "session" in message.lower():
                        state["legacy"] = True
                        await send_upstream(
                            upstream,
                            _session_update(user_id, context, legacy=True, provider="openai"),
                        )
                        continue
                    raise RuntimeError(message[:300])
        except BaseException:
            with suppress(Exception):
                await upstream.close()
            raise

    async def run_provider(upstream, config: dict[str, Any]) -> bool:
        provider = config["name"]
        model = config["model"]
        handled_calls.clear()
        state.update({
            "response_active": False,
            "response_cancel_pending": False,
            "user_speaking": False,
        })
        await send_client({"type": "session.ready", "provider": provider, "model": model})

        async def client_reader() -> None:
            resample_state = None
            while True:
                incoming = await websocket.receive_json()
                kind = incoming.get("type")
                if kind == "audio.append":
                    audio = incoming.get("audio")
                    if isinstance(audio, str) and 0 < len(audio) <= 96_000:
                        if provider == "qwen":
                            try:
                                audio, resample_state = _resample_pcm24_to_16(audio, resample_state)
                            except ValueError:
                                continue
                        await send_upstream(upstream, {"type": "input_audio_buffer.append", "audio": audio})
                elif kind == "response.cancel":
                    await cancel_active_response(upstream)
                elif kind == "conversation.text":
                    text = str(incoming.get("text") or "").strip()[:2000]
                    if text:
                        await send_upstream(
                            upstream,
                            {
                                "type": "conversation.item.create",
                                "item": {"type": "message", "role": "user", "content": [{"type": "input_text", "text": text}]},
                            },
                        )
                        await send_upstream(upstream, {"type": "response.create"})
                elif kind == "session.context":
                    update = incoming.get("context")
                    if isinstance(update, dict):
                        context.update(update)
                        await send_upstream(
                            upstream,
                            _session_update(
                                user_id,
                                context,
                                legacy=state["legacy"],
                                provider=provider,
                            ),
                        )

        async def upstream_reader() -> None:
            assistant_text: list[str] = []
            async for raw in upstream:
                event = json.loads(raw)
                kind = str(event.get("type") or "")
                if kind == "session.updated":
                    continue
                elif kind == "input_audio_buffer.speech_started":
                    state["user_speaking"] = True
                    await send_client({"type": "input.speech_started"})
                elif kind == "input_audio_buffer.speech_stopped":
                    state["user_speaking"] = False
                    await send_client({"type": "input.speech_stopped"})
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
                            _session_update(
                                user_id,
                                context,
                                transcript,
                                state["legacy"],
                                provider,
                            ),
                        )
                elif kind == "response.created":
                    state["response_active"] = True
                    state["response_cancel_pending"] = False
                    await send_client({"type": "output.started"})
                elif kind == "response.output_item.added":
                    if not state["response_active"]:
                        state["response_active"] = True
                        await send_client({"type": "output.started"})
                elif kind in {"response.output_audio.delta", "response.audio.delta"}:
                    delta = event.get("delta")
                    if isinstance(delta, str) and delta:
                        await send_client({"type": "output.audio.delta", "audio": delta})
                elif kind in {"response.output_audio_transcript.delta", "response.audio_transcript.delta", "response.text.delta"}:
                    delta = str(event.get("delta") or "")
                    if delta:
                        assistant_text.append(delta)
                        await send_client({"type": "output.transcript.delta", "text": delta})
                elif kind in {"response.output_audio_transcript.done", "response.audio_transcript.done", "response.text.done"}:
                    transcript = str(event.get("transcript") or event.get("text") or "").strip()
                    if transcript:
                        assistant_text[:] = [transcript]
                        await send_client({"type": "output.transcript.done", "text": transcript})
                elif kind == "response.function_call_arguments.done":
                    await handle_tool(upstream, event)
                elif kind == "response.output_item.done":
                    item = event.get("item") if isinstance(event.get("item"), dict) else {}
                    if item.get("type") in {"function_call", "tool_call"}:
                        await handle_tool(upstream, event)
                elif kind in {"response.done", "response.cancelled"}:
                    state["response_active"] = False
                    state["response_cancel_pending"] = False
                    complete = "".join(assistant_text).strip()
                    assistant_text.clear()
                    if complete:
                        runtime.memory.record_turn(user_id, "assistant", complete)
                    await send_client({"type": "output.done", "text": complete})
                elif kind == "error":
                    error = event.get("error") if isinstance(event.get("error"), dict) else {}
                    message = str(error.get("message") or event.get("message") or "Realtime error")
                    lower_message = message.lower()
                    if "cancel" in lower_message and (
                        "not active" in lower_message or "no active" in lower_message or "already" in lower_message
                    ):
                        state["response_active"] = False
                        state["response_cancel_pending"] = False
                    elif provider == "openai" and not state["legacy"] and "session" in lower_message:
                        state["legacy"] = True
                        await send_upstream(
                            upstream,
                            _session_update(user_id, context, legacy=True, provider="openai"),
                        )
                    else:
                        raise RuntimeError(message[:300])

        client_task = asyncio.create_task(client_reader())
        upstream_task = asyncio.create_task(upstream_reader())
        done, pending = await asyncio.wait({client_task, upstream_task}, return_when=asyncio.FIRST_COMPLETED)
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)
        if client_task in done:
            with suppress(WebSocketDisconnect, asyncio.CancelledError):
                client_task.result()
            return False
        upstream_task.result()
        raise ConnectionError(f"{provider} realtime connection closed")

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

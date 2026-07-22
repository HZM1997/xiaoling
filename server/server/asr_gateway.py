"""Cloud ASR gateway for OpenAI-compatible /audio/transcriptions endpoints."""
from __future__ import annotations

import json
import os
import secrets
import urllib.request


def _resolve() -> tuple[str, str, str] | None:
    key = os.getenv("XL_ASR_KEY", "").strip() or os.getenv("OPENAI_API_KEY", "").strip()
    if not key:
        return None
    base = os.getenv("XL_ASR_BASE_URL", "https://api.openai.com/v1").strip()
    model = os.getenv("XL_ASR_MODEL", "whisper-1").strip()
    return key, base, model


def available() -> bool:
    return _resolve() is not None


def transcribe(audio: bytes, filename: str = "speech.wav", content_type: str = "audio/wav") -> str | None:
    config = _resolve()
    if config is None or not audio:
        return None
    key, base, model = config
    boundary = "----xiaoling" + secrets.token_hex(12)
    safe_name = "".join(ch for ch in filename if ch.isalnum() or ch in "._-")[:80] or "speech.wav"

    def field(name: str, value: str) -> bytes:
        return (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n"
            f"{value}\r\n"
        ).encode("utf-8")

    body = bytearray()
    body.extend(field("model", model))
    body.extend(field("language", "zh"))
    body.extend(field("response_format", "json"))
    body.extend(
        (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
            f"filename=\"{safe_name}\"\r\nContent-Type: {content_type}\r\n\r\n"
        ).encode("utf-8")
    )
    body.extend(audio)
    body.extend(f"\r\n--{boundary}--\r\n".encode("ascii"))
    endpoint = base if base.rstrip("/").endswith("/audio/transcriptions") else base.rstrip("/") + "/audio/transcriptions"
    request = urllib.request.Request(
        endpoint,
        data=bytes(body),
        headers={
            "Authorization": "Bearer " + key,
            "Content-Type": "multipart/form-data; boundary=" + boundary,
            "Accept": "application/json",
            "User-Agent": "Xiaoling-ASR/1.0",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=float(os.getenv("XL_ASR_TIMEOUT", "18"))) as response:
            payload = json.loads(response.read(512 * 1024).decode("utf-8"))
        text = str(payload.get("text", "")).strip()
        return text or None
    except Exception:
        return None

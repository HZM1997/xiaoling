"""OpenAI-compatible multi-provider gateway with bounded sequential fallback."""
from __future__ import annotations

import json
import os
import time
import urllib.request


_PROVIDERS = [
    ("deepseek", "DEEPSEEK_API_KEY", "https://api.deepseek.com/v1", "deepseek-chat"),
    ("openai", "OPENAI_API_KEY", "https://api.openai.com/v1", "gpt-4o-mini"),
    ("qwen", "DASHSCOPE_API_KEY", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    ("doubao", "ARK_API_KEY", "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-4k"),
    ("kimi", "MOONSHOT_API_KEY", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
]


def _provider_configs() -> list[dict]:
    configs: list[dict] = []
    custom_key = os.getenv("XL_LLM_KEY", "").strip()
    if custom_key:
        configs.append({
            "name": "custom",
            "key": custom_key,
            "base": os.getenv("XL_LLM_BASE_URL", "https://api.deepseek.com/v1"),
            "model": os.getenv("XL_LLM_MODEL", "deepseek-chat"),
        })
    for name, key_env, base, model in _PROVIDERS:
        key = os.getenv(key_env, "").strip()
        if key:
            configs.append({
                "name": name,
                "key": key,
                "base": os.getenv(
                    f"XL_{name.upper()}_BASE_URL", os.getenv("XL_LLM_BASE_URL", base)
                ),
                "model": os.getenv(
                    f"XL_{name.upper()}_MODEL", os.getenv("XL_LLM_MODEL", model)
                ),
            })

    priority = [item.strip().lower() for item in os.getenv("XL_LLM_PROVIDERS", "").split(",") if item.strip()]
    if priority:
        order = {name: index for index, name in enumerate(priority)}
        configs.sort(key=lambda item: order.get(item["name"], len(order)))
    seen = set()
    result = []
    for item in configs:
        identity = (item["base"].rstrip("/"), item["model"], item["key"])
        if identity not in seen:
            seen.add(identity)
            result.append(item)
    return result


def available() -> bool:
    return bool(_provider_configs())


def provider_status() -> dict:
    providers = _provider_configs()
    return {
        "available": bool(providers),
        "providers": [item["name"] for item in providers],
        "fallback_enabled": len(providers) > 1,
    }


def _request(config: dict, body: dict, timeout: float) -> dict | None:
    req = urllib.request.Request(
        config["base"].rstrip("/") + "/chat/completions",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Authorization": "Bearer " + config["key"], "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as response:
        data = json.loads(response.read().decode("utf-8"))
    message = data["choices"][0]["message"]
    if isinstance(message, dict):
        message["_provider"] = config["name"]
    return message


def chat(
    messages: list[dict],
    tools: list[dict] | None = None,
    temperature: float = 0.5,
    max_tokens: int = 600,
    timeout: float = 6.0,
    model_override: str | None = None,
    provider_override: str | None = None,
) -> dict | None:
    """在总时限内依次尝试已配置模型;密钥只从环境变量读取。"""
    providers = _provider_configs()
    if provider_override:
        providers = [item for item in providers if item["name"] == provider_override]
    if not providers:
        return None
    max_providers = max(1, min(int(os.getenv("XL_LLM_MAX_PROVIDERS", "3")), len(providers)))
    deadline = time.monotonic() + max(1.0, timeout)
    for config in providers[:max_providers]:
        config = dict(config)
        if model_override:
            config["model"] = model_override
        remaining = deadline - time.monotonic()
        if remaining < 0.5:
            break
        body = {
            "model": config["model"],
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        if tools:
            body["tools"] = tools
            body["tool_choice"] = "auto"
        try:
            per_provider = min(remaining, float(os.getenv("XL_LLM_PROVIDER_TIMEOUT", "3.2")))
            message = _request(config, body, timeout=max(0.5, per_provider))
            if message:
                return message
        except Exception:
            continue
    return None

"""
小灵 · 大模型统一网关(多厂商可切换,零额外依赖)
支持:DeepSeek / OpenAI(GPT) / 通义千问 / 豆包 / Kimi 等 —— 它们都兼容 OpenAI 的
/chat/completions 接口格式,所以用同一套代码,配哪个环境变量就用哪个。

配置(设一个即可,优先级从上到下):
  DEEPSEEK_API_KEY      # 推荐:国内可直连、性价比高、支持 function calling
  OPENAI_API_KEY        # GPT
  DASHSCOPE_API_KEY     # 阿里通义千问
  ARK_API_KEY           # 火山豆包
  MOONSHOT_API_KEY      # Kimi
可选覆盖:XL_LLM_BASE_URL / XL_LLM_MODEL / XL_LLM_KEY(自定义任意 OpenAI 兼容端点)

设计:纯 urllib 调用,1.8s 超时(急速反应),失败返回 None → 上层走规则/兜底,不拖慢也不崩。
"""
from __future__ import annotations
import json
import os
import urllib.request

# 厂商预设:env_key -> (base_url, default_model)
_PROVIDERS = [
    ("DEEPSEEK_API_KEY", "https://api.deepseek.com/v1", "deepseek-chat"),
    ("OPENAI_API_KEY", "https://api.openai.com/v1", "gpt-4o-mini"),
    ("DASHSCOPE_API_KEY", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    ("ARK_API_KEY", "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-4k"),
    ("MOONSHOT_API_KEY", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
]


def _resolve() -> tuple[str, str, str] | None:
    """返回 (api_key, base_url, model) 或 None(未配置任何 KEY)。"""
    # 自定义端点优先
    if os.getenv("XL_LLM_KEY"):
        return (
            os.getenv("XL_LLM_KEY"),
            os.getenv("XL_LLM_BASE_URL", "https://api.deepseek.com/v1"),
            os.getenv("XL_LLM_MODEL", "deepseek-chat"),
        )
    for env, base, model in _PROVIDERS:
        key = os.getenv(env)
        if key:
            return key, os.getenv("XL_LLM_BASE_URL", base), os.getenv("XL_LLM_MODEL", model)
    return None


def available() -> bool:
    return _resolve() is not None


def chat(messages: list[dict], tools: list[dict] | None = None,
         temperature: float = 0.5, max_tokens: int = 600, timeout: float = 6.0) -> dict | None:
    """
    调用大模型 /chat/completions。
    @param messages OpenAI 格式消息列表
    @param tools    可选 function calling 工具定义
    @return 首个 choice 的 message dict(含 content 和可选 tool_calls)或 None(失败/未配置)
    """
    cfg = _resolve()
    if cfg is None:
        return None
    key, base, model = cfg
    body = {"model": model, "messages": messages, "temperature": temperature, "max_tokens": max_tokens}
    if tools:
        body["tools"] = tools
        body["tool_choice"] = "auto"
    try:
        req = urllib.request.Request(
            base.rstrip("/") + "/chat/completions",
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        return data["choices"][0]["message"]
    except Exception:
        return None

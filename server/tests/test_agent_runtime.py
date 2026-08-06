import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import llm_gateway
from agent_runtime import AgentRuntime
from context_engine import build_context
from memory_store import MemoryStore
from models import Utterance


def test_memory_persists_and_is_recalled(tmp_path):
    path = tmp_path / "memory.sqlite3"
    first = MemoryStore(path)
    first.extract_facts("elder-1", "我喜欢听京剧")
    first.record_turn("elder-1", "user", "今天想听点东西")

    reopened = MemoryStore(path)
    memories = reopened.recall("elder-1", "给我放京剧")
    assert any(item["value"] == "听京剧" for item in memories)
    assert reopened.recent_turns("elder-1")[-1]["content"] == "今天想听点东西"


def test_sensitive_values_are_not_memorized(tmp_path):
    store = MemoryStore(tmp_path / "memory.sqlite3")
    assert store.extract_facts("elder-2", "我的身份证是11010519491231002X") == []
    store.record_turn("elder-2", "user", "验证码是123456")
    assert store.recent_turns("elder-2")[-1]["content"] == "[敏感内容已省略]"


def test_dynamic_context_only_keeps_safe_device_summary(tmp_path):
    store = MemoryStore(tmp_path / "memory.sqlite3")
    store.remember("elder-3", "preference", "likes", "越剧")
    context = build_context(store, "elder-3", "放点越剧", {
        "device": {"network": "wifi", "battery_percent": 72, "secret_files": ["a.txt"]},
        "scene": "voice_chat",
    })
    assert context["device"] == {"network": "wifi", "battery_percent": 72}
    assert context["memories"][0]["value"] == "越剧"


def test_dynamic_context_recovers_device_memory_and_turns(tmp_path):
    store = MemoryStore(tmp_path / "memory.sqlite3")
    context = build_context(store, "elder-local", "我喜欢什么", {
        "local_memories": [{"key": "likes", "value": "听评书", "updated": 1}],
        "recent_turns": [
            {"role": "user", "content": "我喜欢听评书"},
            {"role": "assistant", "content": "我记住了"},
        ],
    })
    assert any(item["key"] == "likes" and item["value"] == "听评书" for item in context["memories"])
    assert context["recent_turns"][-1]["content"] == "我记住了"


def test_dynamic_context_keeps_latest_twenty_turns_in_order(tmp_path):
    store = MemoryStore(tmp_path / "memory.sqlite3")
    for index in range(25):
        store.record_turn("elder-window", "user" if index % 2 == 0 else "assistant", f"turn-{index}")

    context = build_context(store, "elder-window", "current", {})

    assert len(context["recent_turns"]) == 20
    assert [item["content"] for item in context["recent_turns"]] == [
        f"turn-{index}" for index in range(5, 25)
    ]


def test_multi_provider_falls_back_in_order(monkeypatch):
    providers = [
        {"name": "first", "key": "a", "base": "https://first.invalid", "model": "m1"},
        {"name": "second", "key": "b", "base": "https://second.invalid", "model": "m2"},
    ]
    calls = []

    monkeypatch.setattr(llm_gateway, "_provider_configs", lambda: providers)

    def fake_request(config, body, timeout):
        calls.append(config["name"])
        if config["name"] == "first":
            raise TimeoutError()
        return {"content": "成功"}

    monkeypatch.setattr(llm_gateway, "_request", fake_request)
    result = llm_gateway.chat([{"role": "user", "content": "你好"}], timeout=2.0)
    assert calls == ["first", "second"]
    assert result["content"] == "成功"


def test_kimi_k3_uses_reasoning_parameters(monkeypatch):
    providers = [{"name": "kimi", "key": "k", "base": "https://api.moonshot.cn/v1", "model": "kimi-k3"}]
    captured = {}
    monkeypatch.setattr(llm_gateway, "_provider_configs", lambda: providers)

    def fake_request(config, body, timeout):
        captured.update(body)
        return {"content": "完成"}

    monkeypatch.setattr(llm_gateway, "_request", fake_request)
    result = llm_gateway.chat(
        [{"role": "user", "content": "分析"}], max_tokens=900, reasoning_effort="max"
    )
    assert result["content"] == "完成"
    assert captured["model"] == "kimi-k3"
    assert captured["reasoning_effort"] == "max"
    assert captured["max_completion_tokens"] == 900
    assert "temperature" not in captured


def test_runtime_records_turn_and_returns_contextual_fallback(tmp_path, monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.setattr(llm_gateway, "_provider_configs", lambda: [])
    runtime = AgentRuntime(MemoryStore(tmp_path / "memory.sqlite3"))
    reply = runtime.process(Utterance(user_id="elder-4", text="我喜欢听评书"))
    assert reply.speech != "我在听着呢。您可以跟我说『打电话给女儿』『导航到医院』,或者陪我聊聊天。有需要随时喊我。"
    assert runtime.status()["turns"] == 1
    assert len(runtime.memory.recent_turns("elder-4")) == 2

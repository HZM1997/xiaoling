import base64
import struct

import pytest

import realtime_gateway


@pytest.fixture(autouse=True)
def clean_realtime_environment(monkeypatch):
    for name in (
        "DASHSCOPE_API_KEY",
        "XL_QWEN_WORKSPACE_ID",
        "XL_QWEN_REALTIME_URL",
        "XL_QWEN_REALTIME_MODEL",
        "XL_QWEN_REALTIME_VOICE",
        "OPENAI_API_KEY",
        "XL_REALTIME_API_KEY",
        "XL_REALTIME_PROVIDER",
        "XL_REALTIME_URL",
        "XL_REALTIME_MODEL",
        "XL_REALTIME_VOICE",
    ):
        monkeypatch.delenv(name, raising=False)


def test_realtime_status_requires_server_key(monkeypatch):
    assert realtime_gateway.available() is False
    monkeypatch.setenv("XL_REALTIME_API_KEY", "server-only-test-key")
    assert realtime_gateway.available() is True
    assert realtime_gateway.status()["provider"] == "openai"


def test_qwen_is_primary_and_openai_remains_fallback(monkeypatch):
    monkeypatch.setenv("DASHSCOPE_API_KEY", "qwen-test-key")
    monkeypatch.setenv("XL_QWEN_WORKSPACE_ID", "ws-test-123")
    monkeypatch.setenv("OPENAI_API_KEY", "openai-test-key")

    providers = realtime_gateway._provider_candidates()
    assert [item["name"] for item in providers] == ["qwen", "openai"]
    assert providers[0]["model"] == "qwen3.5-omni-plus-realtime"
    assert providers[0]["url"] == (
        "wss://ws-test-123.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime"
        "?model=qwen3.5-omni-plus-realtime"
    )
    assert realtime_gateway.status()["fallback_enabled"] is True


def test_gpt_realtime_can_still_be_selected_as_primary(monkeypatch):
    monkeypatch.setenv("DASHSCOPE_API_KEY", "qwen-test-key")
    monkeypatch.setenv("XL_QWEN_WORKSPACE_ID", "ws-test-123")
    monkeypatch.setenv("XL_REALTIME_API_KEY", "openai-test-key")
    monkeypatch.setenv("XL_REALTIME_PROVIDER", "openai,qwen")

    providers = realtime_gateway._provider_candidates()
    assert [item["name"] for item in providers] == ["openai", "qwen"]
    assert providers[0]["model"] == "gpt-realtime"


def test_custom_qwen_url_keeps_existing_model_query(monkeypatch):
    monkeypatch.setenv("DASHSCOPE_API_KEY", "qwen-test-key")
    monkeypatch.setenv(
        "XL_QWEN_REALTIME_URL",
        "wss://example.invalid/realtime?region=cn&model=custom-realtime",
    )
    monkeypatch.setenv("XL_REALTIME_PROVIDER", "qwen,qwen")

    providers = realtime_gateway._provider_candidates()
    assert [item["name"] for item in providers] == ["qwen"]
    assert providers[0]["url"].count("model=") == 1
    assert "model=custom-realtime" in providers[0]["url"]


def test_qwen_workspace_accepts_compatible_endpoint_prefix(monkeypatch):
    monkeypatch.setenv("DASHSCOPE_API_KEY", "qwen-test-key")
    monkeypatch.setenv("XL_QWEN_WORKSPACE_ID", "llm-dbgkqp4zfzaak74i")

    provider = realtime_gateway._provider_candidates()[0]

    assert provider["url"].startswith(
        "wss://dbgkqp4zfzaak74i.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime"
    )


def test_realtime_session_enables_server_vad_and_interruption(tmp_path, monkeypatch):
    monkeypatch.setenv("XL_MEMORY_DB", str(tmp_path / "memory.sqlite3"))
    update = realtime_gateway._session_update(
        "elder-realtime",
        {"device": {"network": "wifi", "microphone_permission": True}},
    )
    session = update["session"]
    detection = session["audio"]["input"]["turn_detection"]
    assert session["model"] == "gpt-realtime"
    assert session["audio"]["input"]["noise_reduction"] == {"type": "far_field"}
    assert detection["threshold"] <= 0.35
    assert detection["create_response"] is True
    assert detection["interrupt_response"] is True
    assert {tool["name"] for tool in session["tools"]} >= {
        "call_contact", "set_reminder", "play_media", "check_fraud", "delegate_complex_task"
    }


def test_qwen_session_uses_semantic_vad_and_nested_tools(tmp_path, monkeypatch):
    monkeypatch.setenv("XL_MEMORY_DB", str(tmp_path / "memory.sqlite3"))
    update = realtime_gateway._session_update(
        "elder-qwen",
        {"device": {"network": "4g", "microphone_permission": True}},
        provider="qwen",
    )
    session = update["session"]
    assert session["voice"] == "Tina"
    assert session["input_audio_format"] == "pcm"
    assert session["output_audio_format"] == "pcm"
    assert session["turn_detection"]["type"] == "semantic_vad"
    assert session["turn_detection"]["threshold"] <= 0.35
    assert session["turn_detection"]["silence_duration_ms"] <= 600
    assert "tool_choice" not in session
    assert "parallel_tool_calls" not in session
    assert {tool["function"]["name"] for tool in session["tools"]} >= {
        "call_contact", "set_reminder", "play_media", "check_fraud", "delegate_complex_task"
    }


def test_qwen_input_audio_is_streamed_from_24khz_to_16khz():
    samples = [1000 if index % 2 else -1000 for index in range(2400)]
    encoded = base64.b64encode(struct.pack(f"<{len(samples)}h", *samples)).decode("ascii")
    converted, state = realtime_gateway._resample_pcm24_to_16(encoded)
    pcm = base64.b64decode(converted)

    assert state is not None
    assert len(pcm) % 2 == 0
    assert 1590 <= len(pcm) // 2 <= 1600


def test_qwen_function_call_event_is_parsed():
    call_id, name, args = realtime_gateway._tool_call({
        "type": "response.function_call_arguments.done",
        "call_id": "call-qwen-1",
        "name": "set_reminder",
        "arguments": '{"raw":"晚上八点提醒我吃药"}',
    })
    assert call_id == "call-qwen-1"
    assert name == "set_reminder"
    assert args == {"raw": "晚上八点提醒我吃药"}


def test_realtime_tools_map_to_android_actions():
    action, result = realtime_gateway._action_for("call_contact", {"target": "女儿"})
    assert action == {"type": "CALL", "target": "女儿"}
    assert result["ok"] is True

    action, result = realtime_gateway._action_for("set_reminder", {"raw": "每天晚上八点提醒我吃药"})
    assert action["type"] == "REMIND"
    assert "吃药" in result["raw"]

    action, result = realtime_gateway._action_for("check_fraud", {"text": "让我报验证码并转账"})
    assert action == {"type": "FRAUD_WARN"}
    assert result["level"] == "high"

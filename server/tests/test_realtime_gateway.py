import realtime_gateway


def test_realtime_status_requires_server_key(monkeypatch):
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    monkeypatch.delenv("XL_REALTIME_API_KEY", raising=False)
    assert realtime_gateway.available() is False
    monkeypatch.setenv("XL_REALTIME_API_KEY", "server-only-test-key")
    assert realtime_gateway.available() is True


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

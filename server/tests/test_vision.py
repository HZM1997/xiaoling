import base64

import main


def test_vision_request_includes_previous_observation(monkeypatch):
    captured = {}
    monkeypatch.setattr(main.llm_gateway, "available", lambda: True)

    def fake_chat(messages, **kwargs):
        captured["messages"] = messages
        return {"content": "这是一个保温杯。", "_provider": "test"}

    monkeypatch.setattr(main.llm_gateway, "chat", fake_chat)
    raw = b"fake-jpeg-content-for-contract-test"
    result = main.vision_analyze(main.VisionRequest(
        image_base64=base64.b64encode(raw).decode("ascii"),
        prompt="那这个怎么用",
        lens="back",
        previous_observation="刚才看到一个玻璃杯",
    ))

    assert result["ok"] is True
    user_text = captured["messages"][1]["content"][0]["text"]
    assert "上一帧摘要：刚才看到一个玻璃杯" in user_text
    assert "用户当前问题：那这个怎么用" in user_text
    assert captured["messages"][1]["content"][1]["type"] == "image_url"

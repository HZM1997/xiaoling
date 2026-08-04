import json

import llm


def test_translation_uses_kimi_first_gateway(monkeypatch):
    captured = {}

    def fake_chat(**kwargs):
        captured.update(kwargs)
        return {"content": "こんにちは"}

    monkeypatch.setattr(llm.llm_gateway, "chat", fake_chat)
    assert llm.llm_translate("你好", "japanese") == "こんにちは"
    assert "日语" in captured["messages"][0]["content"]


def test_fraud_review_parses_gateway_json(monkeypatch):
    monkeypatch.setattr(
        llm.llm_gateway,
        "chat",
        lambda **_: {"content": json.dumps({
            "is_fraud": True,
            "confidence": 0.93,
            "reason": "对方要求使用不可追回的付款方式",
        }, ensure_ascii=False)},
    )
    result = llm.judge_fraud("对方让我购买礼品卡")
    assert result and result["is_fraud"] is True
    assert result["confidence"] == 0.93

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


def test_agent_keeps_latest_twenty_ordered_turns_and_uses_reasoning(monkeypatch):
    captured = {}

    def fake_chat(**kwargs):
        captured.update(kwargs)
        return {"content": json.dumps({
            "speech": "我先帮您比较两种方案，再说最稳妥的一种。",
            "intent": "chat",
            "slots": {},
        }, ensure_ascii=False)}

    monkeypatch.setattr(llm.llm_gateway, "chat", fake_chat)
    recent = [
        {"role": "user" if index % 2 == 0 else "assistant", "content": f"turn-{index}"}
        for index in range(24)
    ]

    result = llm._call_agent("帮我分析比较这两个方案的利弊，然后给一个计划", {"recent_turns": recent})

    assert result["intent"] == "chat"
    assert captured["reasoning_effort"] == "high"
    messages = captured["messages"]
    assert [item["content"] for item in messages[1:-1]] == [f"turn-{index}" for index in range(4, 24)]
    assert messages[-1]["content"].startswith("帮我分析")


def test_agent_rewrites_answer_that_repeats_recent_reply(monkeypatch):
    calls = []
    repeated = "我在呢，您慢慢说，我会一直陪着您。"

    def fake_chat(**kwargs):
        calls.append(kwargs)
        speech = repeated if len(calls) == 1 else "听起来您今天有点累，我们先坐下歇一会儿。"
        return {"content": json.dumps({"speech": speech, "intent": "chat", "slots": {}}, ensure_ascii=False)}

    monkeypatch.setattr(llm.llm_gateway, "chat", fake_chat)
    result = llm._call_agent(
        "我今天感觉有点累",
        {"recent_turns": [{"role": "assistant", "content": repeated}]},
    )

    assert len(calls) == 2
    assert result["speech"].startswith("听起来")
    assert calls[1]["reasoning_effort"] == "medium"


def test_money_safety_uses_max_reasoning():
    assert llm._reasoning_effort("对方让我转账，还要我告诉他验证码") == "max"


def test_multiple_complex_instructions_use_high_reasoning():
    text = "请分析原因和利弊，然后比较两个方案，同时给出计划，另外说明风险"
    assert llm._reasoning_effort(text) == "high"


def test_fraud_model_review_uses_max_reasoning(monkeypatch):
    captured = {}

    def fake_chat(**kwargs):
        captured.update(kwargs)
        return {"content": '{"is_fraud":true,"confidence":0.96,"reason":"要求秘密转账"}'}

    monkeypatch.setattr(llm.llm_gateway, "chat", fake_chat)
    result = llm.judge_fraud("客服让我保密并转账")
    assert result and result["is_fraud"] is True
    assert captured["reasoning_effort"] == "max"

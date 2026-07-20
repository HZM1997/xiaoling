"""
最小回归测试:核心技能命中 + 防诈风险分。
运行: cd server && python -m pytest tests/ -q   (或直接 python tests/test_dialogue.py)
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from main import handle


def test_sos():
    r = handle("我胸口疼快不行了")
    assert r.action and r.action["type"] == "SOS"


def test_fraud_high_risk():
    r = handle("您涉嫌洗钱,请把钱转到安全账户",
               context={"scene": "incoming_call", "caller": "0085211111"})
    assert r.action and r.action["type"] == "FRAUD_WARN"
    assert r.risk >= 0.85


def test_fraud_redline_screenshare():
    r = handle("请打开屏幕共享,念一下验证码",
               context={"scene": "incoming_call", "caller": "123"})
    assert r.risk >= 0.9


def test_normal_call_not_flagged_as_fraud():
    # 正常来电内容不应误报
    r = handle("儿子啊,晚上回家吃饭吗",
               context={"scene": "incoming_call", "caller": "13800138000"})
    assert not (r.action and r.action.get("type") == "FRAUD_WARN")


def test_call_phone():
    r = handle("打电话给女儿")
    assert r.action and r.action["type"] == "CALL" and r.action["target"] == "女儿"


def test_navigate():
    r = handle("导航到人民医院")
    assert r.action and r.action["type"] == "OPEN_URI" and "人民医院" in r.action["uri"]


def test_health_remind():
    r = handle("提醒我每天早上八点吃药")
    assert r.action and r.action["type"] == "REMIND"


def test_elderly_reordered_call():
    r = handle("那个 给女儿打个电话")
    assert r.action and r.action["type"] == "CALL" and r.action["target"] == "女儿"


def test_local_multi_command():
    r = handle("给女儿打电话然后提醒我晚上八点吃药")
    assert r.action and r.action["type"] == "TASKS"
    assert [step["type"] for step in r.action["steps"]] == ["CALL", "REMIND"]


if __name__ == "__main__":
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    passed = 0
    for fn in fns:
        try:
            fn(); passed += 1
            print(f"  PASS  {fn.__name__}")
        except AssertionError as e:
            print(f"  FAIL  {fn.__name__}: {e}")
    print(f"\n{passed}/{len(fns)} passed")

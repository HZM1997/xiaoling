"""
防诈引擎准确率自测。读 fraud_corpus.json,跑 fraud.analyze / ConversationTracker,
输出 precision/recall/F1 与混淆矩阵。改规则后必跑,防止"改好一处漏三处"。
运行: cd server && python -m pytest tests/test_fraud_accuracy.py -q
或直接: python tests/test_fraud_accuracy.py
"""
import sys, os, json
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from fraud import analyze, ConversationTracker

_CORPUS = os.path.join(os.path.dirname(__file__), "..", "fraud_corpus.json")


def _load():
    with open(_CORPUS, encoding="utf-8") as f:
        return json.load(f)


def _pred_level(text, caller):
    return analyze(text, caller).level


def _conv_level(turns, caller):
    tr = ConversationTracker(caller=caller)
    lv = "safe"
    for t in turns:
        lv = tr.add(t).level
    return lv


def evaluate():
    data = _load()
    # 二分类:danger = high|medium 视为"该拦";safe 视为"该放"
    tp = fp = tn = fn = 0
    details = []
    for c in data["cases"]:
        want_danger = c["label"] in ("high", "medium")
        got = _pred_level(c["text"], c.get("caller", ""))
        got_danger = got in ("high", "medium")
        _tally = _bump(want_danger, got_danger)
        tp += _tally[0]; fp += _tally[1]; tn += _tally[2]; fn += _tally[3]
        if want_danger != got_danger:
            details.append(f"  MISS want={c['label']} got={got} :: {c['text'][:30]}")
    for cv in data.get("conversations", []):
        want_danger = cv["label"] in ("high", "medium")
        got = _conv_level(cv["turns"], cv.get("caller", ""))
        got_danger = got in ("high", "medium")
        _tally = _bump(want_danger, got_danger)
        tp += _tally[0]; fp += _tally[1]; tn += _tally[2]; fn += _tally[3]
        if want_danger != got_danger:
            details.append(f"  MISS(conv) want={cv['label']} got={got}")

    precision = tp / (tp + fp) if (tp + fp) else 1.0
    recall = tp / (tp + fn) if (tp + fn) else 1.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
    return dict(tp=tp, fp=fp, tn=tn, fn=fn, precision=precision, recall=recall, f1=f1, details=details)


def _bump(want, got):
    if want and got: return (1, 0, 0, 0)
    if not want and got: return (0, 1, 0, 0)   # 误报
    if not want and not got: return (0, 0, 1, 0)
    return (0, 0, 0, 1)                         # 漏报


def test_recall_high():
    """漏报是最危险的:高危话术必须几乎全抓到"""
    r = evaluate()
    assert r["recall"] >= 0.9, f"召回过低(漏报多): {r}"


def test_precision_ok():
    """误报别太多,否则老人会烦到关掉"""
    r = evaluate()
    assert r["precision"] >= 0.8, f"精确率过低(误报多): {r}"


if __name__ == "__main__":
    r = evaluate()
    print("=" * 48)
    print("防诈引擎准确率")
    print(f"  精确率 precision = {r['precision']:.3f}  (拦的里面多少真是诈骗)")
    print(f"  召回率 recall    = {r['recall']:.3f}  (真诈骗里拦到多少)")
    print(f"  F1              = {r['f1']:.3f}")
    print(f"  混淆矩阵: TP={r['tp']} FP={r['fp']} TN={r['tn']} FN={r['fn']}")
    if r["details"]:
        print("  未命中:")
        print("\n".join(r["details"]))
    print("=" * 48)

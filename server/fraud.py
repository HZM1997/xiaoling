"""
小灵 · 防诈骗风控引擎 v2
分层研判:
  L0 号码信誉  —— 改号/境外/虚商基线加分(可接三方号码库)
  L1 规则分类  —— 按诈骗类型累加权重,命中红线词直接极高危(0 延迟,抓 90% 高频套路)
  L2 大模型研判 —— 规则中等风险时再上大模型判语义(见 llm.py，可降级)
输出结构化结果:风险分 / 等级 / 类型 / 命中词 / 建议动作 / 可上报载荷。
规则库来自 fraud_rules.json,支持热更新(线上定时重载)。
"""
from __future__ import annotations
import json
import os
from dataclasses import dataclass, field, asdict
from typing import Optional

_RULES_PATH = os.path.join(os.path.dirname(__file__), "fraud_rules.json")


def _load_rules() -> dict:
    with open(_RULES_PATH, encoding="utf-8") as f:
        return json.load(f)


_RULES = _load_rules()


def reload_rules() -> str:
    """热更新入口:线上定时/收到配置变更时调用,不重启服务。"""
    global _RULES
    _RULES = _load_rules()
    return _RULES.get("version", "")


@dataclass
class FraudResult:
    risk: float = 0.0                 # 0~1
    level: str = "safe"               # safe / medium / high
    category: str = ""                # 命中的诈骗类型 label
    reason: str = ""                  # 给老人听的白话原因
    hits: list[str] = field(default_factory=list)
    suggest_hangup: bool = False
    report: Optional[dict] = None     # 可上报反诈中心的结构化载荷

    def to_dict(self) -> dict:
        return asdict(self)


def analyze(text: str, caller: str = "", scene: str = "incoming_call") -> FraudResult:
    text = text or ""
    cats = _RULES["categories"]
    th = _RULES["thresholds"]

    # —— L1a 红线词:命中即极高危,直接短路 ——
    red = cats["redline"]
    red_hits = [w for w in red["words"] if w in text]
    if red_hits:
        return _finalize(0.96, cats["redline"]["label"], red_hits,
                         f"对方要求「{red_hits[0]}」,这是诈骗分子最典型的手法", caller, text, scene, th)

    # —— L0 号码信誉基线 ——
    base = 0.15 if _suspicious_number(caller) else 0.0

    # —— L1b 分类累加:同类多次命中不重复叠满,取该类权重 + 命中密度 ——
    best_cat, best_score, all_hits = "", 0.0, []
    for key, c in cats.items():
        if key == "redline":
            continue
        hits = [w for w in c["words"] if w in text]
        if not hits:
            continue
        all_hits += hits
        score = c["weight"] + 0.12 * (len(hits) - 1)   # 命中越多越危
        if score > best_score:
            best_score, best_cat = score, c["label"]

    risk = min(base + best_score, 0.99) if all_hits else base
    if not all_hits:
        return FraudResult(risk=risk, level="safe")

    reason = "对方提到「" + "、".join(all_hits[:3]) + "」,是典型诈骗话术"
    return _finalize(risk, best_cat, all_hits, reason, caller, text, scene, th)


def _finalize(risk, category, hits, reason, caller, text, scene, th) -> FraudResult:
    level = "high" if risk >= th["high"] else "medium" if risk >= th["medium"] else "safe"
    report = None
    if level != "safe":
        report = {  # 可对接公安反诈/家人告警的结构化载荷
            "scene": scene, "caller": caller, "category": category,
            "risk": round(risk, 2), "hits": hits, "snippet": text[:60],
        }
    return FraudResult(risk=round(risk, 2), level=level, category=category,
                       reason=reason, hits=hits,
                       suggest_hangup=(level == "high"), report=report)


def _suspicious_number(caller: str) -> bool:
    if not caller:
        return False
    c = caller.replace("-", "").replace(" ", "")
    for p in _RULES["number_reputation"]["suspicious_prefixes"]:
        if c.startswith(p) and not c.startswith("+86"):
            return True
    digits = c.lstrip("+")
    return not (7 <= len(digits) <= 12)


# —— 兼容旧接口(skills.py 仍可用)——
def analyze_fraud(caller: str, text: str) -> tuple[float, str]:
    r = analyze(text, caller)
    return r.risk, r.reason

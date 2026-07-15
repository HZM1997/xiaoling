"""
小灵 · 防诈骗风控引擎 v3
分层研判(单句):
  归一化(去空格/同音变体,抗混淆)
  → L1a 红线词命中即极高危(短路)
  → L0 号码信誉基线
  → L1b 分类累加(取最高危类 + 命中密度)
  → 放大因子(紧迫/保密/资金动作/权威)+ 抑制因子(善意软词,降误报)
  → 阈值判级
多轮会话:ConversationTracker 跨句累积(骗子分多句铺垫),窗口内取峰值。
输出结构化结果:风险分 / 等级 / 类型 / 命中词 / 建议动作 / 可上报载荷。
规则库来自 fraud_rules.json,支持热更新。
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
    amplifiers: list[str] = field(default_factory=list)  # 命中的放大信号
    suggest_hangup: bool = False
    report: Optional[dict] = None     # 可上报反诈中心的结构化载荷

    def to_dict(self) -> dict:
        return asdict(self)


def normalize(text: str) -> str:
    """归一化:同音/形近/加空格混淆 → 标准词,抗骗子刻意规避。"""
    t = text or ""
    nm = _RULES.get("normalize", {}).get("map", {})
    # 先替换含空格的变体,再去掉词内空格影响
    for variant, std in nm.items():
        if variant in t:
            t = t.replace(variant, std)
    # 去掉中文之间的空白(骗子常用"验 证 码"绕过),保留必要结构
    compact = t.replace(" ", "").replace("　", "")
    for variant, std in nm.items():
        cv = variant.replace(" ", "")
        if cv in compact:
            compact = compact.replace(cv, std)
    return compact


def analyze(text: str, caller: str = "", scene: str = "incoming_call") -> FraudResult:
    raw = text or ""
    t = normalize(raw)
    cats = _RULES["categories"]
    th = _RULES["thresholds"]

    # —— L1a 红线词:命中即极高危,直接短路(但仍附带放大信号列表)——
    red = cats["redline"]
    red_hits = [w for w in red["words"] if w in t]
    if red_hits:
        amps = _amplifier_hits(t)
        return _finalize(0.96, red["label"], red_hits, amps,
                         f"对方要求「{red_hits[0]}」,这是诈骗分子最典型的手法", caller, raw, scene, th)

    # —— L0 号码信誉基线 ——
    base = 0.15 if _suspicious_number(caller) else 0.0

    # —— L1b 分类累加:取最高危类 + 命中密度 ——
    best_cat, best_score, all_hits = "", 0.0, []
    for key, c in cats.items():
        if key == "redline":
            continue
        hits = [w for w in c["words"] if w in t]
        if not hits:
            continue
        all_hits += hits
        score = c["weight"] + 0.1 * (len(hits) - 1)
        if score > best_score:
            best_score, best_cat = score, c["label"]

    risk = base + best_score

    # —— 放大 / 抑制因子 ——
    amp_hits = _amplifier_hits(t)
    risk += sum(_RULES["amplifiers"]["signals"][k]["add"] for k in amp_hits)
    supp = _suppressor_delta(t)
    risk -= supp

    risk = max(0.0, min(risk, 0.99))

    if not all_hits and risk < th["medium"]:
        return FraudResult(risk=round(risk, 2), level="safe", amplifiers=amp_hits)

    cat = best_cat or "疑似诈骗"
    reason = ("对方提到「" + "、".join(all_hits[:3]) + "」" if all_hits else "话术结构可疑") + \
             ("," + "、".join(_amp_labels(amp_hits)) if amp_hits else "") + ",请提高警惕"
    return _finalize(risk, cat, all_hits, amp_hits, reason, caller, raw, scene, th)


def _amplifier_hits(t: str) -> list[str]:
    out = []
    for name, sig in _RULES.get("amplifiers", {}).get("signals", {}).items():
        if any(w in t for w in sig["words"]):
            out.append(name)
    return out


def _amp_labels(keys: list[str]) -> list[str]:
    label = {"urgency": "制造紧迫", "secrecy": "要求保密", "money_action": "涉及转账/验证码", "authority": "假冒权威"}
    return [label.get(k, k) for k in keys]


def _suppressor_delta(t: str) -> float:
    total = 0.0
    for name, sig in _RULES.get("suppressors", {}).get("signals", {}).items():
        if any(w in t for w in sig["words"]):
            total += sig["sub"]
    return total


def _finalize(risk, category, hits, amps, reason, caller, text, scene, th) -> FraudResult:
    risk = max(0.0, min(risk, 0.99))
    level = "high" if risk >= th["high"] else "medium" if risk >= th["medium"] else "safe"
    report = None
    if level != "safe":
        report = {
            "scene": scene, "caller": caller, "category": category,
            "risk": round(risk, 2), "hits": hits, "amplifiers": amps, "snippet": text[:60],
        }
    return FraudResult(risk=round(risk, 2), level=level, category=category,
                       reason=reason, hits=hits, amplifiers=amps,
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


class ConversationTracker:
    """
    多轮会话累积:一通电话/一段短信里,骗子常分多句铺垫,单句都不够高危,
    连起来才是铁诈骗。这里在一次会话内跨句累加(旧句按 decay 衰减),取峰值判级。
    用法:每来一句 add(text),返回当前累积后的 FraudResult。
    """
    def __init__(self, caller: str = "", scene: str = "incoming_call"):
        self.caller = caller
        self.scene = scene
        self.cum_risk = 0.0
        self.all_hits: list[str] = []
        self.all_amps: set[str] = set()
        self.category = ""
        self.turns = 0

    def add(self, text: str) -> FraudResult:
        conv = _RULES.get("conversation", {})
        decay = conv.get("decay_per_turn", 0.85)
        one = analyze(text, self.caller, self.scene)
        self.turns += 1
        # 旧证据衰减,叠加本句风险的增量(取本句风险与历史衰减的融合)
        self.cum_risk = max(one.risk, self.cum_risk * decay + one.risk * 0.5)
        self.cum_risk = min(self.cum_risk, 0.99)
        for h in one.hits:
            if h not in self.all_hits:
                self.all_hits.append(h)
        self.all_amps.update(one.amplifiers)
        if one.category and (not self.category or one.risk >= 0.5):
            self.category = one.category

        th = _RULES["thresholds"]
        reason = one.reason if one.risk >= self.cum_risk else \
            ("整通电话综合研判可疑:" + ("、".join(self.all_hits[:3]) if self.all_hits else "话术结构异常"))
        return _finalize(self.cum_risk, self.category or one.category or "疑似诈骗",
                         self.all_hits, list(self.all_amps), reason,
                         self.caller, text, self.scene, th)


# —— 兼容旧接口(skills.py 仍可用)——
def analyze_fraud(caller: str, text: str) -> tuple[float, str]:
    r = analyze(text, caller)
    return r.risk, r.reason

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
import re
from dataclasses import dataclass, field, asdict
from typing import Optional

import number_reputation

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


def status() -> dict:
    return {
        "version": _RULES.get("version", "unknown"),
        "categories": len(_RULES.get("categories", {})),
        "local_url_analysis": True,
        "external_lookup_required": False,
    }


@dataclass
class FraudResult:
    risk: float = 0.0                 # 0~1
    level: str = "safe"               # safe / medium / high
    category: str = ""                # 命中的诈骗类型 label
    reason: str = ""                  # 给老人听的白话原因
    hits: list[str] = field(default_factory=list)
    amplifiers: list[str] = field(default_factory=list)  # 命中的放大信号
    suggest_hangup: bool = False
    verification_steps: list[str] = field(default_factory=list)
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


def _hits(words: list[str], text: str) -> list[str]:
    folded = text.casefold()
    compact = folded.replace(" ", "")
    return [word for word in words
            if word.casefold() in folded or word.casefold().replace(" ", "") in compact]


def _script_pattern(text: str) -> tuple[str, float, str] | None:
    """Recognize relationships between scam stages, not isolated words."""
    patterns = (
        (r"(?:陌生人|网友|老师|导师|客服).{0,16}(?:投资|理财|炒股|虚拟币|带单|赚钱)",
         "投资理财诱导", 0.48, "陌生关系诱导投资"),
        (r"(?:投入|充值|投资).{0,16}(?:提现不了|解冻|保证金|认证金|税费)",
         "虚假投资提现", 0.55, "先入金后以提现为由继续收费"),
        (r"(?:儿子|女儿|孙子|孙女|领导).{0,14}(?:换号|借钱|转账|汇款|出事)",
         "冒充亲友/领导", 0.48, "冒充熟人并提出资金要求"),
        (r"(?:客服|平台).{0,16}(?:退款|理赔|自动扣费).{0,20}(?:验证码|下载|共享|转账|银行卡)",
         "冒充客服退款", 0.52, "客服理由衔接敏感操作"),
        (r"(?:警察|公安|检察院|法院|公检法).{0,20}(?:涉案|洗钱|通缉).{0,24}(?:转账|安全账户|保密)",
         "冒充公检法", 0.62, "权威恐吓后要求资金或保密操作"),
    )
    for pattern, category, score, evidence in patterns:
        if re.search(pattern, text, flags=re.I):
            return category, score, evidence
    return None


def _url_risk(text: str) -> tuple[float, list[str]]:
    """纯本地识别链接混淆信号,不把用户网址上传给第三方。"""
    urls = re.findall(r"(?:https?://|www\.)[^\s，。！？]+", text, flags=re.I)
    score, signals = 0.0, []
    shorteners = ("bit.ly", "tinyurl.com", "t.co/", "cutt.ly", "is.gd", "rebrand.ly", "shorturl.at")
    risky_tlds = (".top", ".xyz", ".click", ".work", ".loan", ".zip", ".mov")
    for raw in urls[:4]:
        value = raw.casefold()
        if "xn--" in value:
            score += 0.28; signals.append("国际化域名混淆")
        if re.search(r"https?://(?:\d{1,3}\.){3}\d{1,3}(?:[:/]|$)", value):
            score += 0.25; signals.append("直接使用IP地址")
        if re.search(r"https?://[^/\s]*@", value):
            score += 0.28; signals.append("链接隐藏真实地址")
        if any(item in value for item in shorteners):
            score += 0.18; signals.append("短链接隐藏目标")
        host = re.sub(r"^https?://", "", value).split("/", 1)[0].split(":", 1)[0]
        if host.endswith(risky_tlds):
            score += 0.12; signals.append("高滥用风险域名")
    return min(score, 0.6), list(dict.fromkeys(signals))


def analyze(text: str, caller: str = "", scene: str = "incoming_call") -> FraudResult:
    raw = text or ""
    t = normalize(raw)
    cats = _RULES["categories"]
    th = _RULES["thresholds"]

    # —— L0 号码信誉:分级基线 + 可信/黑名单标记 ——
    num_base, num_tag, num_reason = number_reputation.assess(caller)

    # —— L1a 红线词:命中即极高危,直接短路(白名单也拦,红线优先于可信)——
    red = cats["redline"]
    red_hits = _hits(red["words"], t)
    if red_hits:
        amps = _amplifier_hits(t)
        return _finalize(0.96, red["label"], red_hits, amps,
                         f"对方要求「{red_hits[0]}」,这是诈骗分子最典型的手法", caller, raw, scene, th)

    base = num_base

    # —— L1b 分类累加:取最高危类 + 命中密度 ——
    best_cat, best_score, all_hits = "", 0.0, []
    matched_cats = 0
    for key, c in cats.items():
        if key == "redline":
            continue
        hits = _hits(c["words"], t)
        if not hits:
            continue
        all_hits += hits
        matched_cats += 1
        score = c["weight"] + 0.1 * (len(hits) - 1)
        if score > best_score:
            best_score, best_cat = score, c["label"]

    script = _script_pattern(t)
    if script:
        script_cat, script_score, script_evidence = script
        matched_cats += 1
        all_hits.append(script_evidence)
        if script_score > best_score:
            best_score, best_cat = script_score, script_cat

    risk = base + best_score
    # Fraud scripts often span categories: identity bait, urgency, and a
    # payment step may each look harmless alone. Multiple categories are
    # therefore evidence of a coordinated script rather than keyword density.
    if matched_cats >= 2:
        risk += min(0.24, 0.08 * (matched_cats - 1))

    url_score, url_hits = _url_risk(raw)
    risk += url_score
    if url_hits:
        all_hits.extend(url_hits)
        if not best_cat:
            best_cat = "可疑链接/仿冒网站"

    # —— 放大 / 抑制因子 ——
    amp_hits = _amplifier_hits(t)
    risk += sum(_RULES["amplifiers"]["signals"][k]["add"] for k in amp_hits)
    supp = _suppressor_delta(t)
    risk -= supp

    # —— 号码信誉修正:黑名单加成;可信白名单在无红线时压制中危误报 ——
    if num_tag == "black":
        risk += 0.25
    elif num_tag == "white" and matched_cats < 2 and not amp_hits:
        # 可信号码仅在"无红线、命中类目<2、无放大信号"时压制,防误报;
        # 若命中多类诈骗话术或有转账/紧迫等放大信号 → 号码疑似被伪造,不压制。
        risk = min(risk, th["medium"] - 0.01)

    risk = max(0.0, min(risk, 0.99))

    if not all_hits and risk < th["medium"]:
        return FraudResult(risk=round(risk, 2), level="safe", amplifiers=amp_hits)

    cat = best_cat or "疑似诈骗"
    parts = []
    if all_hits:
        parts.append("对方提到「" + "、".join(all_hits[:3]) + "」")
    elif num_tag == "black":
        parts.append(num_reason)
    else:
        parts.append("话术结构可疑")
    if amp_hits:
        parts.append("、".join(_amp_labels(amp_hits)))
    reason = ",".join(parts) + ",请提高警惕"
    return _finalize(risk, cat, all_hits, amp_hits, reason, caller, raw, scene, th)


def _amplifier_hits(t: str) -> list[str]:
    out = []
    for name, sig in _RULES.get("amplifiers", {}).get("signals", {}).items():
        if _hits(sig["words"], t):
            out.append(name)
    return out


def _amp_labels(keys: list[str]) -> list[str]:
    label = {"urgency": "制造紧迫", "secrecy": "要求保密", "money_action": "涉及转账/验证码", "authority": "假冒权威"}
    return [label.get(k, k) for k in keys]


def _suppressor_delta(t: str) -> float:
    total = 0.0
    for name, sig in _RULES.get("suppressors", {}).get("signals", {}).items():
        if _hits(sig["words"], t):
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
    verification_steps = []
    if level != "safe":
        verification_steps = [
            "立即暂停转账、验证码、共享屏幕和下载软件等操作",
            "挂断当前通话，只用官方应用或原来保存的号码独立核验",
        ]
        if level == "high":
            verification_steps.append("如已付款或泄露信息，立即联系银行并向当地警方报案；在中国可拨打110或96110")
        else:
            verification_steps.append("让可信家人一起核对，在确认前不要继续操作")
    return FraudResult(risk=round(risk, 2), level=level, category=category,
                       reason=reason, hits=hits, amplifiers=amps,
                       suggest_hangup=(level == "high"),
                       verification_steps=verification_steps, report=report)


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

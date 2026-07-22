"""
小灵 · 技能注册表
每个技能 = 一个函数,输入 Utterance,命中则返回 Reply,不命中返回 None。
新增功能只需在这里加一个 @skill 函数 + 一条 DeepLink,不改主流程。
高频指令走这里(正则/关键词),0 大模型延迟;拿不准的才落到 llm.py。
"""
from __future__ import annotations
import re
from typing import Callable, Optional

from models import Utterance, Reply
from fraud import analyze as analyze_fraud_result
from fraud_session import analyze_session
from llm import judge_fraud, llm_translate
from translate import parse_translate, translate_phrase, LANGS
from clarify import clarify, resolve_choice

# (name, priority, fn):priority 越小越先判,防诈/呼救优先级最高
_REGISTRY: list[tuple[str, int, Callable[[Utterance], Optional[Reply]]]] = []


def skill(name: str, priority: int = 100):
    def deco(fn: Callable[[Utterance], Optional[Reply]]):
        _REGISTRY.append((name, priority, fn))
        _REGISTRY.sort(key=lambda x: x[1])
        return fn
    return deco


def _match_one(u: Utterance) -> Optional[Reply]:
    for name, _, fn in _REGISTRY:
        r = fn(u)
        if r:
            r.skill = r.skill or name
            return r
    return None


def _normalize_spoken(text: str) -> str:
    """清理老人常见填充词,保留人物、时间和动作。"""
    text = text.strip().replace("；", "，").replace(";", "，")
    text = re.sub(r"[。.!！]+$", "", text)
    text = re.sub(r"^(嗯+|呃+|那个|就是|这个|你看|麻烦你?|请你?|我想让你|我想要你|你帮我|帮我)\s*", "", text)
    return re.sub(r"\s+", " ", text).strip()


def match(u: Utterance) -> Optional[Reply]:
    """先拆连续指令,再按优先级匹配单条口语意图。"""
    normalized = _normalize_spoken(u.text)
    parts = [p.strip(" ，") for p in re.split(r"\s*(?:然后|接着|顺便|并且|同时|还有|再)\s*", normalized) if p.strip(" ，")]
    if len(parts) >= 2:
        replies = [_match_one(u.model_copy(update={"text": p})) for p in parts]
        if all(r is not None and r.action for r in replies):
            concrete = [r for r in replies if r is not None]
            speech = "然后".join(r.speech.rstrip("。.,，") for r in concrete)
            return Reply(speech=speech,
                         action={"type": "TASKS", "steps": [r.action for r in concrete]},
                         skill="端侧连续多指令")
    return _match_one(u.model_copy(update={"text": normalized}))


# ========================= 技能定义 =========================

@skill("紧急呼救", priority=1)
def sos(u: Utterance) -> Optional[Reply]:
    if not re.search(r"救命|摔倒了?|喘不上气|胸口疼|心脏|不行了|打?120|急救|晕", u.text):
        return None
    return Reply(
        speech="别怕,我马上帮您呼叫120,同时通知您的家人,并把位置发过去,您坚持住。",
        action={"type": "SOS", "call": "120", "notify_family": True, "send_location": True},
    )


@skill("防诈骗预警", priority=2)
def anti_fraud(u: Utterance) -> Optional[Reply]:
    """来电/短信场景:context 带 caller/scene/session_id,做多轮累积的实时研判。"""
    ctx = u.context or {}
    scene = ctx.get("scene")
    incoming_scene = scene in ("incoming_call", "sms", "incoming_sms")
    consultation = bool(re.search(
        r"是不是诈骗|会不会是骗子|像不像诈骗|能不能转账|要不要转账|怎么防诈|如何防诈|反诈|"
        r"(?:有人|对方).{0,18}(?:让我|叫我|要求我)",
        u.text,
    ))
    if not incoming_scene and not consultation:
        return None
    # 来电/短信走多轮累积;主动反诈咨询也进入同一规则引擎,不再被普通聊天漏掉。
    r = analyze_session(u.text, ctx.get("caller", ""), scene, ctx) if incoming_scene else \
        analyze_fraud_result(u.text, ctx.get("caller", ""), "voice_chat")
    if r.level == "safe":
        if consultation:
            return Reply(
                speech="目前没发现明确的诈骗红线,但拿不准就先不转账、不说验证码、不点陌生链接。把对方原话再告诉我,我继续帮您判断。",
                skill="防诈咨询",
                risk=r.risk,
            )
        return None
    # 中危(规则拿不准)→ 大模型二次研判,降误报;无大模型则按规则判定
    if r.level == "medium":
        verdict = judge_fraud(u.text, r.category)
        if verdict is not None:
            if not verdict.get("is_fraud", True) and verdict.get("confidence", 0) >= 0.6:
                return None   # 大模型确信非诈骗 → 不打扰
            if verdict.get("is_fraud") and verdict.get("confidence", 0) >= 0.85:
                r.level = "high"   # 大模型高置信确认 → 升级
    level_cn = "极高" if r.level == "high" else "较高"
    subject = "这通电话" if incoming_scene else "您描述的情况"
    return Reply(
        speech=(f"注意!{subject}诈骗风险{level_cn}(疑似{r.category}):{r.reason}。"
                f"千万不要转账、不要提供验证码、不要按对方说的操作。"
                f"要不要我帮您挂断,并打给您的子女核实?"),
        action={"type": "FRAUD_WARN", "level": r.level, "category": r.category,
                "hangup_suggest": r.suggest_hangup, "report": r.report},
        risk=r.risk,
    )


@skill("实时翻译", priority=8)
def translate(u: Utterance) -> Optional[Reply]:
    """实时翻译:普通话/英语/粤语。端侧词库即时命中,未命中走大模型兜底。"""
    parsed = parse_translate(u.text)
    if not parsed:
        return None
    content, lang = parsed
    lang_cn = LANGS.get(lang, "英语")
    hit = translate_phrase(content, lang)
    if hit:
        return Reply(speech=f"{content} 的{lang_cn}是:{hit}",
                     action={"type": "SPEAK", "text": hit, "lang": lang}, skill="实时翻译")
    # 未命中 → 大模型翻译(无 KEY 时降级提示)
    out = llm_translate(content, lang)
    if out:
        return Reply(speech=f"{content} 的{lang_cn}是:{out}",
                     action={"type": "SPEAK", "text": out, "lang": lang}, skill="实时翻译")
    return Reply(speech=f"这句我暂时翻不了,联网后再试试吧。", skill="实时翻译")


@skill("打电话", priority=10)
def call_phone(u: Utterance) -> Optional[Reply]:
    m = (re.search(r"(?:给|帮我给)\s*(.+?)\s*(?:打|拨)(?:个)?电话", u.text)
         or re.search(r"(.+?)\s*(?:电话打一个|电话拨一个)", u.text)
         or re.search(r"(?:打(?:个)?电话给?|呼叫|拨打?给?)\s*(.+)", u.text))
    if not m:
        return None
    target = re.sub(r"[的吧呢啊,。!]+$", "", m.group(1).strip()) or "对方"
    return Reply(
        speech=f"好的,正在帮您给{target}打电话。",
        action={"type": "CALL", "target": target},  # 客户端查通讯录后拨号
    )


@skill("导航", priority=20)
def navigate(u: Utterance) -> Optional[Reply]:
    m = re.search(r"(?:导航到?|去|怎么去|怎么走到?)\s*(.+)", u.text)
    if not m:
        return None
    dest = re.sub(r"(怎么走|怎么去)$", "", m.group(1).strip())
    dest = re.sub(r"[的吧呢啊,。!]+$", "", dest)
    if not dest:
        return None
    return Reply(
        speech=f"正在为您导航到{dest},请跟着语音走。",
        # 高德 DeepLink,直接调起高德,不自建地图
        action={"type": "OPEN_URI",
                "uri": f"androidamap://poi?sourceApplication=xiaoling&keywords={dest}&dev=0"},
    )


@skill("听戏听歌", priority=30)
def play_media(u: Utterance) -> Optional[Reply]:
    m = re.search(r"(?:听|放|播放|来一?段?|唱)\s*(.+)", u.text)
    if not m or not re.search(r"歌|戏|剧|曲|评书|相声|音乐|唱", u.text):
        return None
    kw = re.sub(r"[的吧呢啊,。!]+$", "", m.group(1).strip())
    return Reply(
        speech=f"好嘞,这就给您放{kw}。",
        action={"type": "PLAY", "keyword": kw},
    )


@skill("健康用药提醒", priority=40)
def health_remind(u: Utterance) -> Optional[Reply]:
    if not re.search(r"提醒|闹钟|别忘了|记一下", u.text):
        return None
    m = re.search(r"(每天|明天|今天)?\s*([0-9一二三四五六七八九十]+点(?:半|[0-9]+分?)?)", u.text)
    when = m.group(0).strip() if m else "到点"
    return Reply(
        speech=f"好的,我会{when}提醒您,放心。",
        action={"type": "REMIND", "raw": u.text},
    )


@skill("智能澄清", priority=50)
def smart_clarify(u: Utterance) -> Optional[Reply]:
    """意图模糊(有多种合理处理)时,返回多个选项让用户选,而非武断执行。
    在确定性技能之后、大模型兜底之前;确定的指令已被前面技能处理。"""
    # 用户正在从上一轮选项中做选择?
    ctx = u.context or {}
    pending = ctx.get("pending_options")
    if pending:
        chosen = resolve_choice(u.text, pending)
        if chosen:
            return Reply(speech=chosen.get("speech", "好的。"),
                         action=chosen.get("action"), skill="智能澄清·已选")
    return clarify(u.text)

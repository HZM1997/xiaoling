"""
小灵 · 智能澄清(多选建议)
把"一句话→武断执行一个动作"升级为"理解意图→模糊时给几个选项让用户选"。
例:说"给女儿" → 不知道要打电话/视频/导航,返回 3 个选项让老人选。
返回带 options 的 Reply;客户端渲染成大按钮/语音报选项,用户说"第一个/打电话"即可。
"""
from __future__ import annotations
import re
from models import Reply


def _opt(label: str, speech: str, action: dict | None) -> dict:
    """一个可选项:label 显示/语音播报,action 选中后执行"""
    return {"label": label, "speech": speech, "action": action}


def clarify(text: str) -> Reply | None:
    """
    识别到"有多种合理处理"的模糊意图时,返回带 options 的 Reply;否则 None(交给确定性技能)。
    """
    t = text.strip()

    # —— 对某人:打电话 / 视频 / 导航去TA家,三种都合理 → 让用户选 ——
    m = re.search(r"(?:找|联系|给)\s*([^\s,，。的]{1,6})(?:$|吧|呢|啊|嘛)?", t)
    if m and not re.search(r"打电话|视频|导航|发消息|发短信", t):
        who = m.group(1)
        if who and who not in ("我", "你", "他", "她", "它"):
            return Reply(
                speech=f"您想怎么联系{who}?",
                skill="智能澄清",
                action={"type": "CHOICES", "options": [
                    _opt(f"给{who}打电话", f"好的,给{who}打电话。",
                         {"type": "CALL", "target": who}),
                    _opt(f"和{who}视频", f"好的,和{who}视频通话。",
                         {"type": "OPEN_URI", "uri": "weixin://"}),
                    _opt(f"导航去{who}家", f"好的,导航去{who}家。",
                         {"type": "OPEN_URI", "uri": f"androidamap://poi?sourceApplication=xiaoling&keywords={who}家&dev=0"}),
                ]}
            )

    # —— "我不舒服/难受":可能就医/呼救/联系家人,先温和确认严重程度 ——
    if re.search(r"不舒服|难受|不太好", t) and not re.search(r"胸口|喘不上气|不行了|救命|晕", t):
        return Reply(
            speech="您哪里不舒服?我可以帮您这几样:",
            skill="智能澄清",
            action={"type": "CHOICES", "options": [
                _opt("呼叫120急救", "好的,马上帮您呼叫120。",
                     {"type": "SOS", "call": "120", "notify_family": True}),
                _opt("给家人打电话", "好的,帮您给家人打电话。",
                     {"type": "CALL", "target": "家人"}),
                _opt("查附近医院", "好的,帮您找附近的医院。",
                     {"type": "OPEN_URI", "uri": "androidamap://poi?sourceApplication=xiaoling&keywords=医院&dev=0"}),
                _opt("先歇会儿,不用了", "那您先好好休息,有需要随时喊我。", None),
            ]}
        )

    # —— "无聊/没事干":推荐几种陪伴方式 ——
    if re.search(r"无聊|没事干|闷|没意思", t):
        return Reply(
            speech="要不我陪您做点什么?",
            skill="智能澄清",
            action={"type": "CHOICES", "options": [
                _opt("听段戏曲/评书", "好嘞,给您放段戏。",
                     {"type": "PLAY", "keyword": "戏曲"}),
                _opt("和我聊聊天", "好呀,咱聊聊。今天过得怎么样?", None),
                _opt("给孩子打个电话", "好的,给孩子打个电话。",
                     {"type": "CALL", "target": "孩子"}),
            ]}
        )

    return None


def resolve_choice(text: str, options: list[dict]) -> dict | None:
    """
    用户从上一轮选项里做出选择(说"第一个/打电话/第二个")。返回选中项的 dict,或 None。
    客户端也可直接点按钮,不走这里。
    """
    t = text.strip()
    # 序号选择
    ordinal = {"第一": 0, "第1": 0, "一": 0, "第二": 1, "第2": 1, "二": 1,
               "第三": 2, "第3": 2, "三": 2, "第四": 3, "第4": 3, "四": 3}
    for k, i in ordinal.items():
        if k in t and i < len(options):
            return options[i]
    # 关键词匹配 label
    for opt in options:
        core = re.sub(r"[给和去打的]", "", opt["label"])
        if any(c and c in t for c in [core, opt["label"]]):
            return opt
    return None

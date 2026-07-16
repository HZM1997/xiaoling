"""
小灵 · 实时翻译(普通话 / 英语 / 粤语)
端侧常用短语词库即时命中(0 延迟),未命中走大模型兜底(llm)。
设计:老人日常高频短句直接查表,秒回;整句/生僻交给大模型。
"""
from __future__ import annotations
import re

# 目标语言标识
LANGS = {"mandarin": "普通话", "english": "英语", "cantonese": "粤语"}

# 高频短语词库:key=普通话,value={english, cantonese}
_PHRASES: dict[str, dict[str, str]] = {
    "你好": {"english": "Hello", "cantonese": "你好(nei5 hou2)"},
    "谢谢": {"english": "Thank you", "cantonese": "多谢(do1 ze6)"},
    "再见": {"english": "Goodbye", "cantonese": "拜拜(baai1 baai3)"},
    "多少钱": {"english": "How much is it?", "cantonese": "几多钱(gei2 do1 cin2)"},
    "厕所在哪里": {"english": "Where is the toilet?", "cantonese": "厕所喺边度(ci3 so2 hai2 bin1 dou6)"},
    "我不舒服": {"english": "I don't feel well", "cantonese": "我唔舒服(ngo5 m4 syu1 fuk6)"},
    "帮帮我": {"english": "Please help me", "cantonese": "帮帮我(bong1 bong1 ngo5)"},
    "我要去医院": {"english": "I need to go to the hospital", "cantonese": "我要去医院(ngo5 jiu3 heoi3 ji1 jyun2)"},
    "叫救护车": {"english": "Call an ambulance", "cantonese": "叫救护车(giu3 gau3 wu6 ce1)"},
    "我听不懂": {"english": "I don't understand", "cantonese": "我听唔明(ngo5 teng1 m4 ming4)"},
    "请慢一点说": {"english": "Please speak slower", "cantonese": "请讲慢啲(cing2 gong2 maan6 di1)"},
    "吃饭了吗": {"english": "Have you eaten?", "cantonese": "食咗饭未(sik6 zo2 faan6 mei6)"},
    "早上好": {"english": "Good morning", "cantonese": "早晨(zou2 san4)"},
    "晚安": {"english": "Good night", "cantonese": "晚安(maan5 on1)"},
    "多保重": {"english": "Take care", "cantonese": "保重(bou2 zung6)"},
    "现在几点": {"english": "What time is it now?", "cantonese": "而家几点(ji4 gaa1 gei2 dim2)"},
    "怎么走": {"english": "How do I get there?", "cantonese": "点去(dim2 heoi3)"},
    "太贵了": {"english": "That's too expensive", "cantonese": "太贵啦(taai3 gwai3 laa1)"},
    "便宜一点": {"english": "Can it be cheaper?", "cantonese": "平啲得唔得(peng4 di1 dak1 m4 dak1)"},
    "我头晕": {"english": "I feel dizzy", "cantonese": "我头晕(ngo5 tau4 wan4)"},
    "我胸口疼": {"english": "My chest hurts", "cantonese": "我心口痛(ngo5 sam1 hau2 tung3)"},
    "报警": {"english": "Call the police", "cantonese": "报警(bou3 ging2)"},
    "请再说一遍": {"english": "Could you say that again?", "cantonese": "请再讲多次(cing2 zoi3 gong2 do1 ci3)"},
    "多喝热水": {"english": "Drink more hot water", "cantonese": "多啲饮暖水(do1 di1 jam2 nyun5 seoi2)"},
    "想你了": {"english": "I miss you", "cantonese": "挂住你(gwaa3 zyu6 nei5)"},
    "我爱你": {"english": "I love you", "cantonese": "我爱你(ngo5 oi3 nei5)"},
    "祝你健康": {"english": "Wish you good health", "cantonese": "祝你身体健康(zuk1 nei5 san1 tai2 gin6 hong1)"},
}


def parse_translate(text: str) -> tuple[str, str] | None:
    """
    识别翻译意图与目标语言。返回 (待翻译内容, 目标语言key) 或 None。
    例:'翻译成英语 你好' / '用粤语怎么说 谢谢' / '把我不舒服翻译成英文'
    """
    t = text.strip()
    if not re.search(r"翻译|怎么说|用.{0,3}语|译成|译为", t):
        return None
    lang = "english"
    if re.search(r"粤语|广东话|白话", t):
        lang = "cantonese"
    elif re.search(r"英语|英文", t):
        lang = "english"
    elif re.search(r"普通话|中文|国语", t):
        lang = "mandarin"
    # 抽取待翻译内容:去掉翻译指令词
    content = re.sub(r"(请)?(帮我)?(把)?|翻译(成|为)?|用.{0,3}语(怎么说|说)?|怎么说|译成|译为|英语|英文|粤语|广东话|白话|普通话|中文|国语|[,。?!]", "", t).strip()
    return (content or t, lang)


def translate_phrase(content: str, lang: str) -> str | None:
    """端侧词库即时翻译;命中返回译文,未命中返回 None(交给大模型)。"""
    c = content.strip().rstrip("。.!?,")
    if lang == "mandarin":
        return c   # 目标是普通话,原样(方言→普通话可后续接 ASR 侧)
    entry = _PHRASES.get(c)
    if entry:
        return entry.get(lang)
    # 宽松匹配:去标点后再查
    for k, v in _PHRASES.items():
        if k in c or c in k:
            return v.get(lang)
    return None

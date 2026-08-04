"""
小灵 · 实时翻译(普通话 / 英语 / 粤语)
端侧常用短语词库即时命中(0 延迟),未命中走大模型兜底(llm)。
词库来自 translate_phrases.json(与 android assets 保持一致),支持热更新。
"""
from __future__ import annotations
import json
import os
import re

# 目标语言标识。常用中英粤支持离线词库,其余语言由 Kimi-first 网关实时翻译。
LANGS = {
    "mandarin": "普通话", "english": "英语", "cantonese": "粤语",
    "japanese": "日语", "korean": "韩语", "spanish": "西班牙语",
    "french": "法语", "german": "德语", "russian": "俄语",
    "portuguese": "葡萄牙语", "arabic": "阿拉伯语", "thai": "泰语",
    "vietnamese": "越南语",
}
_LANG_PATTERNS = (
    ("cantonese", r"粤语|广东话|白话"),
    ("mandarin", r"普通话|中文|国语|汉语"),
    ("japanese", r"日语|日文|日本话"),
    ("korean", r"韩语|韩文|韩国话|朝鲜语"),
    ("spanish", r"西班牙语|西语"),
    ("french", r"法语|法文"),
    ("german", r"德语|德文"),
    ("russian", r"俄语|俄文"),
    ("portuguese", r"葡萄牙语|葡语"),
    ("arabic", r"阿拉伯语|阿语"),
    ("thai", r"泰语|泰文"),
    ("vietnamese", r"越南语|越南文"),
    ("english", r"英语|英文"),
)
_LANG_NAMES = "|".join(pattern for _, pattern in _LANG_PATTERNS)

_PATH = os.path.join(os.path.dirname(__file__), "translate_phrases.json")


def _load() -> dict[str, dict[str, str]]:
    try:
        with open(_PATH, encoding="utf-8") as f:
            return json.load(f).get("phrases", {})
    except Exception:
        return {
            "你好": {"english": "Hello", "cantonese": "你好"},
            "谢谢": {"english": "Thank you", "cantonese": "多谢"},
            "帮帮我": {"english": "Please help me", "cantonese": "帮帮我"},
            "叫救护车": {"english": "Call an ambulance", "cantonese": "叫白车"},
        }


# 高频短语词库:key=普通话,value={english, cantonese}
_PHRASES: dict[str, dict[str, str]] = _load()


def reload_phrases() -> int:
    """热更新词库,返回条数。"""
    global _PHRASES
    _PHRASES = _load()
    return len(_PHRASES)


def parse_translate(text: str) -> tuple[str, str] | None:
    """
    识别翻译意图与目标语言。返回 (待翻译内容, 目标语言key) 或 None。
    例:'翻译成英语 你好' / '用粤语怎么说 谢谢' / '把我不舒服翻译成英文'
    """
    t = text.strip()
    if not re.search(r"翻译|怎么说|用.{0,3}语|译成|译为", t):
        return None
    lang = next((key for key, pattern in _LANG_PATTERNS if re.search(pattern, t)), "english")
    # 抽取待翻译内容:去掉翻译指令词
    content = re.sub(rf"{_LANG_NAMES}", "", t)
    content = re.sub(r"翻译(?:成|为)?|译成|译为", "", content)
    content = re.sub(r"^(?:请)?(?:帮我)?(?:把)?", "", content)
    content = re.sub(r"^用", "", content)
    content = re.sub(r"^(?:怎么说|说|讲)", "", content)
    content = re.sub(r"[,。?!]", "", content).strip()
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

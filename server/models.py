"""
小灵 · 云端大脑 —— 数据模型
所有模块共享的请求/响应结构。
"""
from __future__ import annotations
from typing import Optional, Any
from pydantic import BaseModel, Field


class Utterance(BaseModel):
    """一次用户语音输入(ASR 已转写成文本)"""
    user_id: str = "guest"
    text: str = Field(..., description="ASR 转写后的文本,方言已转普通话")
    context: Optional[dict[str, Any]] = Field(
        default=None,
        description="场景信息,如 {'scene':'incoming_call','caller':'400xxxx'}",
    )


class Reply(BaseModel):
    """精灵的一轮回复:先播报 speech,再执行 action"""
    speech: str = Field(..., description="交给 TTS 播报的话")
    action: Optional[dict[str, Any]] = Field(
        default=None, description="客户端要执行的动作,如调起 App / 拨号"
    )
    skill: str = Field(default="", description="命中的技能名,便于日志与埋点")
    risk: float = Field(default=0.0, description="风险分(防诈场景用),0~1")

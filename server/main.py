"""
小灵 · 云端大脑入口
链路:先规则层(快)→ 再大模型(准),规则命中即 0 延迟返回。
运行: cd server && uvicorn main:app --host 0.0.0.0 --port 8000
文档: http://localhost:8000/docs
"""
from __future__ import annotations
from fastapi import FastAPI

from models import Utterance, Reply
import skills          # 导入即注册所有技能
from llm import llm_reply

app = FastAPI(title="小灵 · AI手机精灵大脑", version="0.1.0")


@app.get("/health")
def health():
    return {"ok": True, "skills": [name for name, _, _ in skills._REGISTRY]}


@app.post("/dialogue", response_model=Reply)
def dialogue(u: Utterance) -> Reply:
    """一轮对话:规则层优先(含防诈/呼救),兜底走大模型。"""
    r = skills.match(u)
    if r:
        return r
    return llm_reply(u)


# 供 CLI / 测试 直接进程内调用,免起服务
def handle(text: str, context: dict | None = None, user_id: str = "guest") -> Reply:
    return dialogue(Utterance(text=text, context=context, user_id=user_id))

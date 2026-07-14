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


# ---------- 支付(演示用下单;真实微信/支付宝需接官方 SDK + 商户号 + 验签) ----------
from pydantic import BaseModel
import time


class Order(BaseModel):
    plan: str          # basic / premium
    method: str        # 微信支付 / 支付宝


@app.post("/pay/create")
def pay_create(o: Order):
    """下单:真实场景这里调微信统一下单/支付宝下单,返回 prepay_id/orderInfo 给客户端拉起收银台。"""
    price = "29.9" if o.plan == "basic" else "299"
    return {"ok": True, "orderId": f"XL{int(time.time())}", "plan": o.plan,
            "method": o.method, "amount": price}


@app.post("/pay/notify")
def pay_notify(body: dict):
    """支付回调(真实场景由支付平台异步回调并验签,验签通过后给用户发放会员)。"""
    return {"ok": True, "paid": True}


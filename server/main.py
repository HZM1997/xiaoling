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
    phone: str = ""    # 已登录用户手机号(会员跟账号走)


# 内存用户库(demo):{ phone: {membership, family_id, uid} }。真实场景用数据库。
_users: dict[str, dict] = {}


class SendCode(BaseModel):
    phone: str


class LoginReq(BaseModel):
    phone: str
    code: str


@app.post("/auth/send_code")
def auth_send_code(s: SendCode):
    """发送验证码。真实场景接短信服务(阿里云/腾讯云)。demo:固定验证码 1234。"""
    return {"ok": True, "hint": "演示验证码为 1234(真实场景通过短信下发)"}


@app.post("/auth/login")
def auth_login(r: LoginReq):
    """手机号 + 验证码登录/注册。demo:验证码 1234 即通过。会员/家庭组跟账号走。"""
    if r.code != "1234":
        return {"ok": False, "msg": "验证码错误(演示请输入 1234)"}
    u = _users.setdefault(r.phone, {})
    u.setdefault("uid", "u" + str(abs(hash(r.phone)) % 1000000))
    u.setdefault("family_id", "fam-" + str(abs(hash(r.phone)) % 100000))
    u.setdefault("membership", "")
    return {"ok": True, "token": "demo-" + u["uid"], "uid": u["uid"],
            "family_id": u["family_id"], "membership": u["membership"]}


class WxLogin(BaseModel):
    code: str = ""


@app.post("/auth/wx_login")
def auth_wx_login(w: WxLogin):
    """微信一键登录。真实场景:后端用 code 调微信 code2session 换 openid,再建会话。demo:返回一个微信演示账号。"""
    phone = "wx-" + (w.code[-6:] if w.code else "demo")
    u = _users.setdefault(phone, {})
    u.setdefault("uid", "wx" + str(abs(hash(phone)) % 1000000))
    u.setdefault("family_id", "fam-" + str(abs(hash(phone)) % 100000))
    u.setdefault("membership", "")
    return {"ok": True, "token": "demo-" + u["uid"], "uid": u["uid"], "phone": phone,
            "family_id": u["family_id"], "membership": u["membership"]}


@app.post("/pay/create")
def pay_create(o: Order):
    """下单:真实场景这里调微信统一下单/支付宝下单,返回 prepay_id/orderInfo 给客户端拉起收银台。"""
    price = "29.9" if o.plan == "basic" else "299"
    if o.phone:                       # 已登录 → 会员跟账号走(服务器端记录)
        _users.setdefault(o.phone, {}).update(membership=o.plan)
    return {"ok": True, "orderId": f"XL{int(time.time())}", "plan": o.plan,
            "method": o.method, "amount": price}


@app.post("/pay/notify")
def pay_notify(body: dict):
    """支付回调(真实场景由支付平台异步回调并验签,验签通过后给用户发放会员)。"""
    return {"ok": True, "paid": True}


# ---------- 跨设备实时推送(家人看护) ----------
# 事件总线 + SSE 订阅:老人机上报事件 → 家人设备实时收到。
# 真实生产建议叠加厂商推送(极光/个推/华为/小米/APNs)以送达「关着的 App」;此处 SSE 覆盖「App 在线」实时场景。
import asyncio
import json as _json
from collections import defaultdict
from fastapi import Request
from fastapi.responses import StreamingResponse

_subscribers: dict[str, list[asyncio.Queue]] = defaultdict(list)


class Event(BaseModel):
    family_id: str          # 家庭组 id(老人与家人共用)
    sender: str = ""        # 发送设备 id(用于家人设备忽略自己发的事件)
    type: str               # fraud_call / fraud_sms / sos / meds / sync
    text: str = ""
    at: int = 0


@app.post("/push/emit")
async def push_emit(e: Event):
    """老人机上报事件,广播给该家庭组所有在线家人设备。"""
    payload = _json.dumps(e.model_dump(), ensure_ascii=False)
    for q in list(_subscribers.get(e.family_id, [])):
        try:
            q.put_nowait(payload)
        except Exception:
            pass
    return {"ok": True, "delivered": len(_subscribers.get(e.family_id, []))}


@app.get("/push/subscribe")
async def push_subscribe(family_id: str, request: Request):
    """家人设备订阅本家庭组事件(SSE 长连接,实时下发)。"""
    q: asyncio.Queue = asyncio.Queue()
    _subscribers[family_id].append(q)

    async def gen():
        try:
            yield "event: ready\ndata: {}\n\n"
            while True:
                if await request.is_disconnected():
                    break
                try:
                    data = await asyncio.wait_for(q.get(), timeout=15)
                    yield f"data: {data}\n\n"
                except asyncio.TimeoutError:
                    yield ": keep-alive\n\n"   # 心跳
        finally:
            try:
                _subscribers[family_id].remove(q)
            except ValueError:
                pass

    return StreamingResponse(gen(), media_type="text/event-stream")



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
import brain           # 大模型驱动的行为理解(智能应用体)
import firewall        # 后端防火墙(限流/体积限制/安全头)

app = FastAPI(title="小灵 · AI手机精灵大脑", version="0.1.0")
firewall.install(app)  # 启用防火墙中间件

# 家庭语音留言文件。生产环境建议改为对象存储并设置 PUBLIC_BASE_URL。
import os as _os
from pathlib import Path as _Path
from fastapi.staticfiles import StaticFiles
_FAMILY_AUDIO_DIR = _Path(_os.getenv("FAMILY_AUDIO_DIR", "family_audio"))
_FAMILY_AUDIO_DIR.mkdir(parents=True, exist_ok=True)
app.mount("/family/audio/files", StaticFiles(directory=str(_FAMILY_AUDIO_DIR)), name="family-audio")


@app.get("/health")
def health():
    return {"ok": True, "llm": brain.llm_gateway.available(),
            "skills": [name for name, _, _ in skills._REGISTRY]}


@app.get("/alerts")
def alerts(lat: float | None = None, lon: float | None = None):
    """聚合已配置的官方台风/暴雨/沙尘暴预警源;未配置时返回空列表。"""
    from alerts import collect_alerts
    return {"alerts": collect_alerts(lat, lon)}


@app.post("/dialogue", response_model=Reply)
def dialogue(u: Utterance) -> Reply:
    """
    分层链路(快+省+智能):
      1) 规则层:防诈/呼救/打电话/导航/翻译等 高频+安全指令,0 延迟,离线可用;
      2) 智能大脑:规则拿不准时,大模型带 [对话上下文+用户画像+场景] 理解行为,
         调技能 / 生成多选 / 陪伴闲聊;
      3) 兜底:未配大模型 KEY 时退回原简易 llm/离线兜底,保证永远有温暖回应。
    """
    r = skills.match(u)
    if r:
        return r
    ctx = u.context or {}
    smart = brain.understand(u.text, user_id=u.user_id,
                             profile=ctx.get("profile"), scene=ctx.get("scene", "chat"))
    if smart:
        return smart
    return llm_reply(u)


# 供 CLI / 测试 直接进程内调用,免起服务
def handle(text: str, context: dict | None = None, user_id: str = "guest") -> Reply:
    return dialogue(Utterance(text=text, context=context, user_id=user_id))


# ---------- 支付(演示用下单;真实微信/支付宝需接官方 SDK + 商户号 + 验签) ----------
from pydantic import BaseModel, Field
import time


class Order(BaseModel):
    plan: str = Field(default="basic", pattern="^(basic|premium)$")
    method: str = Field(default="", max_length=16)
    phone: str = Field(default="", max_length=32)


# 内存用户库(demo):{ phone: {membership, family_id, uid} }。真实场景用数据库。
_users: dict[str, dict] = {}


class SendCode(BaseModel):
    phone: str = Field(..., min_length=6, max_length=20, pattern=r"^\+?\d{6,20}$")


class LoginReq(BaseModel):
    phone: str = Field(..., min_length=6, max_length=20, pattern=r"^\+?\d{6,20}$")
    code: str = Field(..., min_length=4, max_length=8, pattern=r"^\d{4,8}$")


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
    code: str = Field(default="", max_length=128)


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
from fastapi import File, Form, Request, UploadFile
from fastapi.responses import StreamingResponse

_subscribers: dict[str, list[asyncio.Queue]] = defaultdict(list)


class Event(BaseModel):
    family_id: str = Field(..., max_length=64)          # 家庭组 id(老人与家人共用)
    sender: str = Field(default="", max_length=64)      # 发送设备 id(用于家人设备忽略自己发的事件)
    type: str = Field(default="", max_length=32)        # fraud_call / fraud_sms / sos / meds / sync
    text: str = Field(default="", max_length=500)
    at: int = 0
    data: dict = Field(default_factory=dict)               # remote_reminder.raw / remote_audio.url 等扩展负载


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


class RemoteReminder(BaseModel):
    family_id: str = Field(..., max_length=64)
    sender: str = Field(default="family", max_length=64)
    raw: str = Field(..., min_length=2, max_length=300)


class RemoteAudio(BaseModel):
    family_id: str = Field(..., max_length=64)
    sender: str = Field(default="family", max_length=64)
    url: str = Field(..., max_length=1000, pattern=r"^https?://")
    text: str = Field(default="家人发来一段语音", max_length=200)


@app.post("/family/remote/reminder")
async def family_remote_reminder(item: RemoteReminder):
    """亲人端远程创建语音提醒;老人端收到后直接写入本机 AlarmManager。"""
    return await push_emit(Event(family_id=item.family_id, sender=item.sender,
                                 type="remote_reminder", text=item.raw,
                                 at=int(time.time()), data={"raw": item.raw}))


@app.post("/family/remote/audio")
async def family_remote_audio(item: RemoteAudio):
    """亲人端推送音频;老人端收到后在 App 内直接播放。"""
    return await push_emit(Event(family_id=item.family_id, sender=item.sender,
                                 type="remote_audio", text=item.text,
                                 at=int(time.time()), data={"url": item.url}))


@app.post("/family/audio/upload")
async def family_audio_upload(
    request: Request,
    family_id: str = Form(..., max_length=64),
    sender: str = Form(default="", max_length=64),
    target: str = Form(default="家人", max_length=64),
    audio: UploadFile = File(...),
):
    """老人端上传留言后直接广播到家庭组,不经过系统联系人选择器。"""
    import os
    import uuid

    folder = _FAMILY_AUDIO_DIR
    name = f"{uuid.uuid4().hex}.m4a"
    path = folder / name
    total = 0
    try:
        with path.open("wb") as output:
            while chunk := await audio.read(64 * 1024):
                total += len(chunk)
                if total > 12 * 1024 * 1024:
                    raise ValueError("audio too large")
                output.write(chunk)
    except Exception:
        path.unlink(missing_ok=True)
        return {"ok": False, "msg": "音频上传失败"}
    public_base = os.getenv("PUBLIC_BASE_URL", str(request.base_url).rstrip("/"))
    url = f"{public_base}/family/audio/files/{name}"
    result = await push_emit(Event(
        family_id=family_id,
        sender=sender,
        type="remote_audio",
        text=f"家人发来一段给{target}的语音",
        at=int(time.time()),
        data={"url": url, "target": target},
    ))
    return {"ok": True, "url": url, **result}


# ---------- 号码举报 / 信任(数据飞轮) ----------
import number_reputation as _numrep


class NumberMark(BaseModel):
    number: str = Field(..., max_length=32)
    action: str = Field(default="report", pattern="^(report|trust)$")  # report=举报诈骗 / trust=标为可信


@app.post("/report_number")
def report_number(m: NumberMark):
    """用户/家人举报某号码为诈骗(拉黑),或标为可信(加白)。即时生效,喂养号码信誉库。"""
    if m.action == "report":
        _numrep.report_fraud_number(m.number)
    else:
        _numrep.trust_number(m.number)
    return {"ok": True, "action": m.action, "stats": _numrep.stats()}


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

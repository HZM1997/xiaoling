"""
小灵 · 云端大脑入口
链路:先规则层(快)→ 再大模型(准),规则命中即 0 延迟返回。
运行: cd server && uvicorn main:app --host 0.0.0.0 --port 8000
文档: http://localhost:8000/docs
"""
from __future__ import annotations
import hashlib
import hmac
import os
import base64
import json

from fastapi import FastAPI, Request, File, Form, UploadFile, WebSocket
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from models import Utterance, Reply
import skills          # 导入即注册所有技能
import firewall        # 后端防火墙(限流/体积限制/安全头)
import agent_registry  # 受控的签名 Skill / Agent 能力目录
import account_store   # 账号、实名状态与永久权益持久化
import asr_gateway
import fraud
import realtime_gateway
import quality_store
from agent_runtime import runtime
import llm_gateway

app = FastAPI(
    title="小灵 · AI手机精灵大脑",
    version="0.4.0",
    docs_url=None if firewall.production() else "/docs",
    redoc_url=None if firewall.production() else "/redoc",
    openapi_url=None if firewall.production() else "/openapi.json",
)
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
    runtime_status = runtime.status()
    realtime_status = realtime_gateway.status()
    cloud_asr = asr_gateway.available()
    return {"ok": True, "api_version": "0.4.0", "llm": runtime_status["models"]["available"],
            "asr": cloud_asr or realtime_status["available"],
            "asr_fallback": cloud_asr,
            "realtime": realtime_status,
            "anti_fraud": fraud.status(),
            "skills": [name for name, _, _ in skills._REGISTRY],
            "agent_registry": agent_registry.status(), "runtime": runtime_status,
            "quality": quality_store.store.stats(), "security": firewall.security_status()}


@app.websocket("/realtime")
async def realtime_voice(websocket: WebSocket):
    """全双工语音代理。所有模型密钥仅保存在服务端,不下发到 APK。"""
    await realtime_gateway.handle(websocket)


@app.get("/runtime/status")
def runtime_status():
    """长驻运行时状态;不返回记忆正文或模型密钥。"""
    return {"ok": True, **runtime.status(), "quality": quality_store.store.stats()}


class QualityEvent(BaseModel):
    event: str = Field(..., min_length=3, max_length=40)
    latency_ms: int = Field(default=0, ge=0, le=60_000)
    success: bool = True


class VisionRequest(BaseModel):
    image_base64: str = Field(..., min_length=32, max_length=2_800_000)
    prompt: str = Field(default="识别画面中的物品，并用简洁中文说明它是什么、用途和安全注意事项。", max_length=1200)
    lens: str = Field(default="back", pattern="^(front|back)$")
    previous_observation: str = Field(default="", max_length=1200)


class VisionStyleRequest(BaseModel):
    image_base64: str = Field(..., min_length=32, max_length=2_800_000)
    prompt: str = Field(default="分析参考图片的摄影风格并转换为实时相机参数。", max_length=500)


@app.post("/vision/analyze")
def vision_analyze(req: VisionRequest):
    """Analyze one user-requested camera frame. Frames are decoded in memory and never persisted."""
    try:
        raw = base64.b64decode(req.image_base64, validate=True)
    except Exception:
        return {"ok": False, "speech": "我没能读取这张画面，请再试一次。", "caption": "无法读取画面"}
    if len(raw) == 0 or len(raw) > 2_000_000:
        return {"ok": False, "speech": "这张画面太大了，请再试一次。", "caption": "画面大小不合适"}
    provider = os.getenv("XL_VISION_PROVIDER", "").strip().lower() or None
    model = os.getenv("XL_VISION_MODEL", "").strip() or None
    if provider is None and os.getenv("DASHSCOPE_API_KEY", "").strip():
        provider = "qwen"
    if model is None and provider == "qwen":
        model = "qwen-vl-plus"
    if not llm_gateway.available():
        return {"ok": False, "speech": "视觉识别服务还没有配置好，暂时不能看清物品。", "caption": "视觉服务未配置"}
    data_url = "data:image/jpeg;base64," + req.image_base64
    previous = req.previous_observation.strip()
    messages = [{"role": "system", "content": (
                    "你是小灵的视觉识别助手，服务对象包含老年用户。只依据这一帧画面回答用户的具体问题。"
                    "优先识别用户手持、指向或画面中央占比最大的目标，并理解颜色、数量、位置、动作和物体间关系；"
                    "先区分物品本身、包装和背景，避免把背景类别当作用户询问的主体；"
                    "读取与问题有关的清晰文字、数字、日期和包装信息，再说明用途；"
                    "发现药品误服、火电刀具、陌生二维码、付款或疑似诈骗画面时给一句明确安全提醒。"
                    "用户说‘这个、那个、刚才、现在’时，要结合上一帧摘要判断目标或变化，但当前画面优先。"
                    "先直接说结论，再用最多三句口语化中文说明。不能确认品牌、药名、真伪或人物身份时必须明确说不能确认，"
                    "不得根据模糊外观猜测，也不要声称已经执行现实世界动作。"
                )},
                {"role": "user", "content": [
                    {"type": "text", "text": (
                        (f"上一帧摘要：{previous}\n" if previous else "") +
                        f"用户当前问题：{req.prompt}"
                    )},
                    {"type": "image_url", "image_url": {"url": data_url}},
                ]}]
    message = llm_gateway.chat(messages, temperature=0.2, max_tokens=500, timeout=15.0,
                               model_override=model, provider_override=provider)
    content = message.get("content") if isinstance(message, dict) else ""
    if isinstance(content, list):
        content = "".join(str(item.get("text", "")) for item in content if isinstance(item, dict))
    answer = str(content or "").strip()[:1200]
    if not answer:
        return {"ok": False, "speech": "我暂时没看清楚，请把物品放近一点再试试。", "caption": "暂时看不清"}
    return {"ok": True, "speech": answer, "caption": answer, "provider": message.get("_provider", "") if isinstance(message, dict) else ""}


@app.post("/vision/style")
def vision_style(req: VisionStyleRequest):
    """Convert a reference image into bounded camera grading parameters; the image is never persisted."""
    try:
        raw = base64.b64decode(req.image_base64, validate=True)
    except Exception:
        return {"ok": False, "speech": "我没能读取这张参考图片。"}
    if len(raw) == 0 or len(raw) > 2_000_000 or not llm_gateway.available():
        return {"ok": False, "speech": "这张参考图片暂时无法分析。"}
    provider = os.getenv("XL_VISION_PROVIDER", "").strip().lower() or None
    model = os.getenv("XL_VISION_MODEL", "").strip() or None
    if provider is None and os.getenv("DASHSCOPE_API_KEY", "").strip():
        provider = "qwen"
    if model is None and provider == "qwen":
        model = "qwen-vl-plus"
    data_url = "data:image/jpeg;base64," + req.image_base64
    filters = ["natural", "warm", "cream", "mist", "cool", "vivid", "sunset", "forest",
               "teal_orange", "vintage", "film", "hong_kong", "mono", "noir"]
    messages = [{"role": "system", "content": (
        "你是摄影调色师。分析参考图的冷暖、明暗、饱和度、年代感、电影感和人像美化程度。"
        "只输出一个JSON对象，不要Markdown。filter只能从给定枚举选择；所有数值必须在指定范围。"
        "不要识别人脸身份，也不要描述人物隐私。JSON字段：filter，filter_strength(0.25到1)，"
        "exposure(-0.35到0.35)，saturation(0.35到1.65)，whitening(0到1)，smoothing(0到1)，"
        "style_description(不超过16个中文字符)。可选filter：" + ",".join(filters)
    )}, {"role": "user", "content": [
        {"type": "text", "text": req.prompt},
        {"type": "image_url", "image_url": {"url": data_url}},
    ]}]
    message = llm_gateway.chat(messages, temperature=0.1, max_tokens=220, timeout=15.0,
                               model_override=model, provider_override=provider)
    content = message.get("content") if isinstance(message, dict) else ""
    if isinstance(content, list):
        content = "".join(str(item.get("text", "")) for item in content if isinstance(item, dict))
    text = str(content or "").strip().removeprefix("```json").removeprefix("```").removesuffix("```").strip()
    try:
        style = json.loads(text)
    except Exception:
        return {"ok": False, "speech": "我看懂了参考图，但这次没能生成调色参数。"}
    if not isinstance(style, dict):
        return {"ok": False, "speech": "这张参考图没有生成可用的调色参数。"}

    def bounded(name: str, low: float, high: float, default: float) -> float:
        try:
            return max(low, min(high, float(style.get(name, default))))
        except (TypeError, ValueError):
            return default

    chosen = str(style.get("filter") or "natural").strip().lower()
    chosen = chosen if chosen in filters else "natural"
    description = str(style.get("style_description") or "参考图风格").strip()[:16]
    return {
        "ok": True,
        "filter": chosen,
        "filter_strength": bounded("filter_strength", 0.25, 1.0, 0.75),
        "exposure": bounded("exposure", -0.35, 0.35, 0.0),
        "saturation": bounded("saturation", 0.35, 1.65, 1.0),
        "whitening": bounded("whitening", 0.0, 1.0, 0.0),
        "smoothing": bounded("smoothing", 0.0, 1.0, 0.0),
        "style_description": description,
        "speech": f"好的，已经参考这张图片调成{description}。",
        "provider": message.get("_provider", "") if isinstance(message, dict) else "",
    }


@app.post("/quality/event")
def quality_event(item: QualityEvent):
    """Aggregate allowlisted quality signals; conversation text is never accepted or stored."""
    accepted = quality_store.store.record(item.event, item.latency_ms, item.success)
    return {"ok": accepted}


@app.get("/agent/catalog")
def agent_catalog():
    """查看当前已验证能力。端点地址被隐藏,避免泄露内部拓扑。"""
    agent_registry.refresh(force=False)
    return {"ok": True, **agent_registry.status()}


@app.post("/agent/admin/refresh")
def agent_refresh(request: Request):
    """管理端立即刷新签名能力目录。"""
    expected = os.getenv("AGENT_ADMIN_TOKEN", "").strip()
    supplied = request.headers.get("authorization", "").removeprefix("Bearer ").strip()
    if not expected or not hmac.compare_digest(expected, supplied):
        return {"ok": False, "msg": "unauthorized"}
    return {"ok": True, **agent_registry.refresh(force=True)}


@app.get("/alerts")
def alerts(lat: float | None = None, lon: float | None = None):
    """聚合已配置的官方台风/暴雨/沙尘暴预警源;未配置时返回空列表。"""
    from alerts import collect_alerts
    return {"alerts": collect_alerts(lat, lon)}


@app.post("/asr")
async def speech_to_text(audio: UploadFile = File(...)):
    """系统 ASR 不可用时的云端中文转写兜底;单轮音频上限 1 MiB。"""
    data = await audio.read(1024 * 1024 + 1)
    if not data or len(data) > 1024 * 1024:
        return {"ok": False, "text": "", "error": "invalid_audio"}
    text = await run_in_threadpool(
        asr_gateway.transcribe,
        data,
        audio.filename or "speech.wav",
        audio.content_type or "audio/wav",
    )
    return {"ok": bool(text), "text": text or ""}


@app.post("/dialogue", response_model=Reply)
def dialogue(u: Utterance) -> Reply:
    """
    分层链路(快+省+智能):
      1) 规则层:防诈/呼救/打电话/导航/翻译等 高频+安全指令,0 延迟,离线可用;
      2) 智能大脑:规则拿不准时,大模型带 [对话上下文+用户画像+场景] 理解行为,
         调技能 / 生成多选 / 陪伴闲聊;
      3) 兜底:未配大模型 KEY 时退回原简易 llm/离线兜底,保证永远有温暖回应。
    """
    return runtime.process(u)


# 供 CLI / 测试 直接进程内调用,免起服务
def handle(text: str, context: dict | None = None, user_id: str = "guest") -> Reply:
    return dialogue(Utterance(text=text, context=context, user_id=user_id))


# ---------- 支付(演示用下单;真实微信/支付宝需接官方 SDK + 商户号 + 验签) ----------
import time


class Order(BaseModel):
    plan: str = Field(default="basic", pattern="^(basic|premium)$")
    method: str = Field(default="", max_length=16)
    phone: str = Field(default="", max_length=32)


def _stable_number(value: str, digits: int = 1000000) -> int:
    return int(hashlib.sha256(value.encode("utf-8")).hexdigest()[:12], 16) % digits


class SendCode(BaseModel):
    phone: str = Field(..., min_length=6, max_length=20, pattern=r"^\+?\d{6,20}$")


class LoginReq(BaseModel):
    phone: str = Field(..., min_length=6, max_length=20, pattern=r"^\+?\d{6,20}$")
    code: str = Field(..., min_length=4, max_length=8, pattern=r"^\d{4,8}$")


@app.post("/auth/send_code")
def auth_send_code(s: SendCode):
    if os.getenv("XL_ALLOW_INSECURE_DEMO", "").lower() != "true":
        return {"ok": False, "msg": "sms provider is not configured"}
    """发送验证码。真实场景接短信服务(阿里云/腾讯云)。demo:固定验证码 1234。"""
    return {"ok": True, "hint": "演示验证码为 1234(真实场景通过短信下发)"}


@app.post("/auth/login")
def auth_login(r: LoginReq):
    if os.getenv("XL_ALLOW_INSECURE_DEMO", "").lower() != "true":
        return {"ok": False, "msg": "sms provider is not configured"}
    """手机号 + 验证码登录/注册。demo:验证码 1234 即通过。会员/家庭组跟账号走。"""
    if r.code != "1234":
        return {"ok": False, "msg": "验证码错误(演示请输入 1234)"}
    u = account_store.get(r.phone) or {}
    u.setdefault("uid", "u" + str(_stable_number(r.phone)))
    u.setdefault("family_id", "fam-" + str(_stable_number(r.phone, 100000)))
    u.setdefault("membership", "")
    account_store.save(r.phone, u)
    return {"ok": True, "token": "demo-" + u["uid"], "uid": u["uid"],
            "family_id": u["family_id"], "membership": u["membership"],
            "real_name_verified": u.get("real_name_verified", False),
            "display_name": u.get("display_name", ""),
            "chat_entitlement": u.get("chat_entitlement", "")}


class WxLogin(BaseModel):
    code: str = Field(default="", max_length=128)


@app.post("/auth/wx_login")
def auth_wx_login(w: WxLogin):
    if os.getenv("XL_ALLOW_INSECURE_DEMO", "").lower() != "true":
        return {"ok": False, "msg": "wechat login is not configured"}
    """微信一键登录。真实场景:后端用 code 调微信 code2session 换 openid,再建会话。demo:返回一个微信演示账号。"""
    phone = "wx-" + (w.code[-6:] if w.code else "demo")
    u = account_store.get(phone) or {}
    u.setdefault("uid", "wx" + str(_stable_number(phone)))
    u.setdefault("family_id", "fam-" + str(_stable_number(phone, 100000)))
    u.setdefault("membership", "")
    account_store.save(phone, u)
    return {"ok": True, "token": "demo-" + u["uid"], "uid": u["uid"], "phone": phone,
            "family_id": u["family_id"], "membership": u["membership"],
            "real_name_verified": u.get("real_name_verified", False),
            "display_name": u.get("display_name", ""),
            "chat_entitlement": u.get("chat_entitlement", "")}


class RealNameReq(BaseModel):
    phone: str = Field(..., min_length=6, max_length=32)
    token: str = Field(..., min_length=6, max_length=256)
    name: str = Field(..., min_length=2, max_length=30)
    id_no: str = Field(..., min_length=18, max_length=18)


@app.post("/auth/real-name/verify")
def real_name_verify(req: RealNameReq):
    """调用合规实名服务;成功即永久授予无限畅聊陪伴权益。"""
    from identity import verify

    account = account_store.get(req.phone)
    if not account or req.token != "demo-" + account.get("uid", ""):
        return {"ok": False, "verified": False, "msg": "登录状态已失效,请重新登录"}
    verified, message = verify(req.name, req.id_no)
    if not verified:
        return {"ok": False, "verified": False, "msg": message}
    salt = os.getenv("IDENTITY_HASH_SALT", "xiaoling-change-in-production")
    account["identity_hash"] = hashlib.sha256((salt + req.id_no.upper()).encode("utf-8")).hexdigest()
    account["real_name_verified"] = True
    account["display_name"] = req.name
    account["chat_entitlement"] = "lifetime_unlimited"
    account_store.save(req.phone, account)
    return {"ok": True, "verified": True, "display_name": req.name,
            "chat_entitlement": "lifetime_unlimited",
            "msg": "实名认证完成,已赠送永久无限畅聊陪伴"}


@app.post("/pay/create")
def pay_create(o: Order):
    if os.getenv("XL_ALLOW_INSECURE_DEMO", "").lower() != "true":
        return {"ok": False, "msg": "payment provider is not configured"}
    """下单:真实场景这里调微信统一下单/支付宝下单,返回 prepay_id/orderInfo 给客户端拉起收银台。"""
    price = "29.9" if o.plan == "basic" else "299"
    if o.phone:                       # 已登录 → 会员跟账号走(服务器端记录)
        account = account_store.get(o.phone) or {}
        account["membership"] = o.plan
        account_store.save(o.phone, account)
    return {"ok": True, "orderId": f"XL{int(time.time())}", "plan": o.plan,
            "method": o.method, "amount": price}


@app.post("/pay/notify")
def pay_notify(body: dict):
    """支付回调(真实场景由支付平台异步回调并验签,验签通过后给用户发放会员)。"""
    return {"ok": False, "paid": False, "msg": "unsigned payment callback rejected"}


# ---------- 跨设备实时推送(家人看护) ----------
# 事件总线 + SSE 订阅:老人机上报事件 → 家人设备实时收到。
# 真实生产建议叠加厂商推送(极光/个推/华为/小米/APNs)以送达「关着的 App」;此处 SSE 覆盖「App 在线」实时场景。
import asyncio
import json as _json
from collections import defaultdict
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

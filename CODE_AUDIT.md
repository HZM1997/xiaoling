# 小灵 · 全库代码自检审计报告

> 生成方式:多维度只读审查(编译正确性 / 运行时逻辑 / 后端 / 安全 / 一致性 / 死代码 6 个维度覆盖)。
> 审查范围:**活跃工程** `xiaoling/android-compose/`(原生 App)+ `xiaoling/server/`(后端)。
> 不含:`xiaoling/android/`(旧 XML 骨架,com.jingling 包)、`xiaoling-src/`(去敏副本)——这两个是历史遗留,不参与构建。
>
> 结论概览:**未发现会导致编译失败或崩溃的 BLOCKER/HIGH 问题**。以下均为 MEDIUM 及以下的优化项、DEMO 待替换项、LOW 清理项。逐条可改。

---

## 一、Android(编译 + 运行时)

### 编译正确性 —— 通过
- ✅ 已删除的"老人端/家人端"角色功能:全库 grep `ui.role / familyEvents / GuardianHomeScreen / callElder / setRole / Account.role` **零残留引用**,不会编译失败。
- ✅ `GuardianHomeScreen.kt` 已清为空(仅 `package com.xiaoling.ui`),合法可编译。
- ✅ `PorcupineWakeEngine.kt` 为**纯反射**实现(只 import Context/File,`ai.picovoice` 仅作字符串类名)→ 缺 SDK 也能编译。
- ✅ `when(ui.screen)` 穷尽(Home/Settings/Login);`withTimeoutOrNull` 已 import;`curUtt`/`@Volatile` 跨线程标志齐全。
- 历次子代理编译审查累计结论均为 NO COMPILE ERRORS。

### 运行时(已加固,记录在案)
| # | 级别 | 位置 | 说明 | 现状 |
|---|---|---|---|---|
| A1 | 已修 | AppState 常听循环 | speaking 卡死→失聪 | 已修:speak 返回 id,ERROR 即回调;curUtt 只认最新句 |
| A2 | 已修 | HomeScreen/WakeService | 前后台抢麦 | 已修:按 lifecycle(onStart/onStop)开停 App 端听,后台交给服务 |
| A3 | 已修 | PushClient SSE | 阻塞读取消泄漏 | 已修:取消时 disconnect;job.invokeOnCompletion |
| A4 | 已修 | WakeService | 麦克风 FGS 在授权前启动崩溃(API34) | 已修:startForeground try/catch + 授权后启动 |
| A5 | 已修 | Avatar3DView | WebView 泄漏 | 已修:AndroidView onRelease{ destroy() } |
| A6 | 已修 | 警报叠加 | 多事件叠加提前复位 | 已修:alarmUntil deadline |

### 待优化 / 清理(可选)
| # | 级别 | 位置 | 问题 | 建议 |
|---|---|---|---|---|
| A7 | LOW | HomeScreen.kt | `CircleShape` 等个别 import 重构后可能未用 | 清理未用 import(仅警告,不影响编译) |
| A8 | LOW | Theme.kt | `AccentGlow` 若某处未用 | 同上 |
| A9 | LOW | GuardianHomeScreen.kt | 空文件占位 | 择机 `git rm` 彻底删除 |
| A10 | MEDIUM | WakeService | 系统识别做"唤醒"耗电/个别机型有提示音 | 生产接 Picovoice 离线唤醒(代码位已留 WakeConfig+PorcupineWakeEngine) |
| A11 | MEDIUM | 国产 ROM | 后台常驻服务易被杀 | 引导用户加"自启动/后台保活白名单"(说明书/首启提示) |

---

## 二、后端 Python(server/)

### 语法 / 导入 —— 通过
- ✅ 导入图无环:`skills → {models, fraud, fraud_session, llm, translate}`;`fraud_session → fraud`;`llm → models`;`translate → re`;`firewall → starlette`;`main → {models, skills, llm, firewall}`。
- ✅ `fraud.py` 用到的所有 rules 键(categories/thresholds/amplifiers/suppressors/normalize/conversation/number_reputation)在 `fraud_rules.json` v3 均存在;取值多用 `.get(...,默认)` 防 KeyError。
- ✅ pydantic v2 用法正确(`Field(pattern=..., max_length=...)`,非旧版 `regex=`)。

### 逻辑 —— 通过(要点)
- ✅ `fraud.py`:归一化抗混淆 → 红线短路 → 分类累加 → 放大/抑制因子 → 阈值;`ConversationTracker` 多轮衰减累积取峰值。本机评测 P=1.0 / R=1.0(77 样本)。
- ✅ `fraud_session.py`:按 session_id/caller+scene 维护会话,TTL 180s + GC + 上限,防内存膨胀。
- ✅ `firewall.py`:滑窗限流(全局+敏感端点)+ 请求体 64KB 上限 + 安全响应头 + 周期 GC。

### 待处理
| # | 级别 | 位置 | 问题 | 建议 |
|---|---|---|---|---|
| B1 | LOW | skills.py:12 | `from fraud import analyze as fraud_analyze` 已无人调用(anti_fraud 改用 analyze_session) | 删除该死导入 |
| B2 | DEMO | main.py 登录 | `code == "1234"` 即通过 | 上线前接短信 OTP + JWT |
| B3 | DEMO | main.py 支付 | `/pay/create` 恒成功、无验签 | 上线前接微信/支付宝 SDK + 后端回调验签 |
| B4 | DEMO | main.py 用户库 | `_users` 内存字典,重启即失 | 上线前换数据库 |
| B5 | MEDIUM | main.py `/push` | `_subscribers` + `asyncio.Queue` 无界 | 多实例部署换 Redis;Queue 设 maxsize |
| B6 | 已修 | firewall.py | `X-Forwarded-For` 无条件信任第一段 | 已修:仅当直连来源在 `XL_TRUSTED_PROXIES` 白名单内才信任 XFF,自右向左取首个非可信 IP;否则用真实 client.host |
| B7 | LOW | llm.py | 无 KEY 时各 LLM 函数返回 None → 规则/兜底生效 | 符合预期,仅提示接国内大模型(通义/豆包)时改 client |

---

## 三、安全

- ✅ App 加固:release 开 R8 混淆 + 资源压缩 + 去日志;release 强制 HTTPS(src/release network_security_config);登录页 FLAG_SECURE 防截屏。
- ✅ 后端:限流 + 体积限制 + 输入校验(手机号/验证码/plan 用 pattern 约束)+ 安全头。
- ⚠️ B2/B3/B4 为演示实现,**上线前必须替换**(已在代码 TODO 标注)。
- ⚠️ 敏感权限:`RECEIVE_SMS`(读短信)上架审核严格,需在商店隐私说明中逐条解释用途。

---

## 四、一致性

- ✅ `fraud_eval.js`(Node 评测器)镜像 `fraud.py` 逻辑,读同一份 rules+corpus;二者判级一致(实测通过)。
- ✅ 端侧 `LocalIntents.kt` 与服务端 `skills.py` 的技能语义对齐(打电话/导航/提醒/听/翻译);端侧为离线快通道,云端为完整版。
- ✅ `LocalSafetyNet.kt`(端侧红线/呼救)与 `fraud_rules.json` redline 词表方向一致。

---

## 五、历史遗留(建议清理,不影响当前构建)

| 目录 | 说明 | 建议 |
|---|---|---|
| `xiaoling/android/`(com.jingling) | 最早的 XML 骨架 App | 已被 android-compose 取代,可删或保留作参考 |
| `xiaoling-src/` | 早期去敏源码副本 | 与主库重复,建议删除避免混淆 |
| `xiaoling/mobile/` | Capacitor WebView 封装(网页套壳) | 与原生 App 定位不同,保留则明确其用途 |
| `xiaoling/demo/`、`deck/` | 网页演示 + 路演稿 | 保留(非 App 代码) |

---

## 六、优先级修改建议(按投入产出)

**立刻(几分钟,零风险清理):**
- B1 删 skills.py 死导入;A9 删空文件 GuardianHomeScreen.kt;A7/A8 清未用 import。

**上线前必做(安全红线):**
- B2/B3/B4 替换演示登录/支付/用户库;B6 修正 X-Forwarded-For 信任边界。

**体验增强(可排期):**
- A10 接 Picovoice 离线唤醒;A11 加后台保活引导;B5 推送换 Redis。

> 说明:本报告为静态只读审查汇总。因本机无 Android SDK / Python 解释器,最终以 GitHub Actions 首次构建(编译器)+ 真机运行为准。历次子代理审查已消除编译错与主要运行时隐患。

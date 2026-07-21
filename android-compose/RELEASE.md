# 出可上架的签名 Release APK

Release 包使用正式签名、生产 AI 服务和离线“小灵”唤醒资源;debug 包只用于编译自测。

## 一、创建签名密钥库(keystore)—— 只做一次,妥善保管
> ⚠️ keystore 和密码是**机密**,别提交到仓库、别发给任何人。丢了就无法给已上架 App 出更新。

在电脑上(需装 JDK 的 keytool;Android Studio 自带):
```bash
keytool -genkeypair -v -keystore xiaoling.keystore \
  -alias xiaoling -keyalg RSA -keysize 2048 -validity 10000
# 按提示设「密钥库口令」和「密钥口令」(可设成一样),记牢
```
得到 `xiaoling.keystore`。

## 二、把密钥配到 GitHub(云端出包用 Secrets,不进代码)
1. 把 keystore 转 base64:
   ```bash
   # Git Bash / Linux / macOS:
   base64 -w0 xiaoling.keystore > ks.b64      # 内容就是一长串
   # (macOS 用 base64 -i xiaoling.keystore -o ks.b64)
   ```
2. GitHub 仓库 → **Settings → Secrets and variables → Actions → New repository secret**,配置:
   - `KEYSTORE_BASE64` = `ks.b64` 里的整串内容
   - `KS_PASS` = 密钥库口令
   - `KEY_ALIAS` = `xiaoling`(上面 -alias 的值)
    - `KEY_PASS` = 密钥口令
    - `BRAIN_URL` = 已部署且 `/health` 返回 `llm:true, asr:true` 的公网 HTTPS 地址
    - `PORCUPINE_ACCESS_KEY` = Picovoice AccessKey
    - `PORCUPINE_KEYWORD_BASE64` = 训练好的“小灵”中文 `.ppn` 的 base64
    - `PORCUPINE_MODEL_URL` = 中文参数模型的固定 HTTPS 下载地址
    - `PORCUPINE_MODEL_SHA256` = 上述参数模型的 SHA-256
3. Actions → **Build Release APK** → Run workflow → 跑完在 Artifacts 下载 **`xiaoling-release-apk`**(即 `app-release.apk`)。这就是签名版,可安装/上架。

> 没配 Secrets 时:该工作流的解码步骤会产出空 keystore,`assembleRelease` 会出**未签名**包(能验证编译,但不能安装)。所以务必配好 4 个 Secret 再出正式包。

## 三、本地出签名包(可选,需 Android Studio/SDK)
```bash
cd android-compose
export XL_KEYSTORE=/绝对路径/xiaoling.keystore
export XL_KS_PASS=你的密钥库口令
export XL_KEY_ALIAS=xiaoling
export XL_KEY_PASS=你的密钥口令
./gradlew assembleRelease
# 产物:app/build/outputs/apk/release/app-release.apk
```
(Windows PowerShell 用 `$env:XL_KEYSTORE="..."` 逐个设置。)

## 四、Release 版做了哪些加固 + 瘦身
- **R8 当前关闭**:此前真机发生 Release 启动闪退,在完整真机冒烟前不重新开启激进裁剪。
- **去日志**:剥离所有 `Log.*` / `println`,防运行时信息泄漏。
- **中文资源限定**:`resConfigs "zh"` 只保留中文字符串;资源压缩随 R8 暂时关闭。
- **只打 ARM 架构**:`abiFilters armeabi-v7a/arm64-v8a`,去掉模拟器用的 x86 native 库。
- **离线唤醒**:正式包打入 Porcupine ARM native 库、中文参数模型和“小灵”关键词模型;CI 会逐项校验。
- **生产 HTTPS 硬校验**:Release CI 只接受可公开访问且真实提供 LLM/ASR 的 HTTPS 服务。
- **登录页防截屏/录屏**:`FLAG_SECURE`,防手机号/验证码被截取。

> 想进一步减小"单个用户下载的体积":上 Google Play 用 `bundleRelease` 出 AAB,Play 会按用户机型只下发对应架构;国内商店发 APK 时,上面这些优化已把体积压到较小。

## 五、上架前还需
- 换正式的服务器地址(公网 HTTPS,见 `server/DEPLOY.md`),别用 `192.168.x.x`。
- 商店合规:隐私政策页、权限使用说明(录音/电话/短信/定位需逐条说明用途,尤其**读短信**权限上架审核严格)。
- 建议出 **AAB**(`bundleRelease`)上 Google Play;国内商店用 APK。

# 出可上架的签名 Release APK

Release 包 = **R8 混淆 + 资源压缩 + 去日志 + 强制 HTTPS**,可上架应用商店(debug 包只能自测)。

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
2. GitHub 仓库 → **Settings → Secrets and variables → Actions → New repository secret**,建 4 个:
   - `KEYSTORE_BASE64` = `ks.b64` 里的整串内容
   - `KS_PASS` = 密钥库口令
   - `KEY_ALIAS` = `xiaoling`(上面 -alias 的值)
   - `KEY_PASS` = 密钥口令
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

## 四、Release 版做了哪些加固
- **R8 混淆 + 优化**:类/方法名打乱,加大逆向难度(`proguard-rules.pro`)。
- **去日志**:剥离所有 `Log.*` / `println`,防运行时信息泄漏。
- **资源压缩**:`shrinkResources` 去无用资源,减小体积。
- **强制 HTTPS**:release 的 `network_security_config` 禁明文,只走 HTTPS(debug 仍允许明文,方便连电脑联调)。
- **登录页防截屏/录屏**:`FLAG_SECURE`,防手机号/验证码被截取。

## 五、上架前还需
- 换正式的服务器地址(公网 HTTPS,见 `server/DEPLOY.md`),别用 `192.168.x.x`。
- 商店合规:隐私政策页、权限使用说明(录音/电话/短信/定位需逐条说明用途,尤其**读短信**权限上架审核严格)。
- 建议出 **AAB**(`bundleRelease`)上 Google Play;国内商店用 APK。

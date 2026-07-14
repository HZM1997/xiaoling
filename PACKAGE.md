# 打包成手机可安装的测试包

> 说明:APK 是**安卓专属**格式,iPhone **不能装 APK**。所以"一个包同时装两端"在技术上不存在。
> 下面给出**两端都能装**的现实方案。

## 方案一 · PWA(最快,两端都能装,免签名/免商店)⭐
把 `demo/` 目录放到任意 HTTPS 静态托管(GitHub Pages / Netlify / Vercel / 你的服务器),
然后手机浏览器打开 `…/demo/player.html`:
- **安卓 Chrome**:菜单 → “安装应用/添加到主屏幕” → 变成独立 App(离线可用)。
- **iPhone Safari**:分享 → “添加到主屏幕” → 变成独立 App。

> 一分钟上线到 GitHub Pages:把仓库推到 GitHub → Settings → Pages → 选分支 →
> 访问 `https://<用户名>.github.io/<仓库>/demo/player.html`。
> (PWA 的离线/安装能力需要 HTTPS;`file://` 直接双击只能看,不能"安装"。)

## 方案二 · 真·安卓 APK(GitHub 云端构建,本机不用装 SDK)⭐
仓库已带 `mobile/`(Capacitor 封装)和 `.github/workflows/build-apk.yml`:
1. 把整个 `xiaoling/` 推到 GitHub。
2. 打开仓库 **Actions** 页 → 选 **Build Android APK** → **Run workflow**。
3. 跑完在该次运行的 **Artifacts** 里下载 `xiaoling-debug-apk`(即 `app-debug.apk`)。
4. 传到安卓手机,允许"未知来源"安装即可。

> 这是**调试版(debug)** APK,用于测试分发;上架应用商店需要正式签名(release keystore)。

## 方案二(本机构建,需 Android Studio / SDK)
```bash
cd mobile
npm install
npm run prep            # demo/ → www/(player.html 作为 index.html)
npx cap add android
npx cap sync android
cd android && ./gradlew assembleDebug
# 产物:mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

## iPhone 原生 App(IPA)
iOS 无法像 APK 那样直接侧载,需 **Mac + Xcode + Apple 开发者账号**:
```bash
cd mobile && npm install && npm run prep
npx cap add ios
npx cap open ios        # 在 Mac 上用 Xcode 打开
# Xcode 里选签名 Team → 连真机运行,或 Archive 走 TestFlight 分发给测试用户
```
> 没有 Mac 时,iPhone 端建议直接用**方案一 PWA**(Safari 添加到主屏幕),体验接近原生且零门槛。

## 常见问题
- **图标**:PWA/安卓用 `demo/icon.svg`;iOS 的 apple-touch-icon 对 SVG 支持一般,
  想更精致可把 `icon.svg` 转成 `icon-180.png / 192 / 512` 再在 `manifest` / `<link rel=apple-touch-icon>` 引用。
- **语音**:App/PWA 内的中文 TTS 依赖系统语音,老人机可能需在系统里装中文语音包;没有则自动退回字幕。
- **Live2D**:`?live2d=1` 需联网加载模型;打包进 App 想离线用需把模型与运行时一并放进 `www/`。

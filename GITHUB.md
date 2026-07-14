# 从零把「小灵」推上 GitHub → 出 APK + 装 PWA(详细教程)

> 你的环境已就绪:Git 2.52 已装、身份已设(wuyongchang)。**不需要装 gh CLI**。
> 全程在 **Git Bash** 里执行,项目在 `~/xiaoling`。命令里的 `<你的用户名>` 换成你的 GitHub 用户名。

---

## 第 0 步 · 准备
1. 有一个 **GitHub 账号**(没有就去 github.com 注册)。
2. 确认 Git 身份(已设好,想改可执行):
   ```bash
   git config --global user.name  "wuyongchang"
   git config --global user.email "wuyongchang@raisegame.cn"
   ```

---

## 第 1 步 · 在 GitHub 网页上建一个空仓库
1. 浏览器打开 https://github.com/new
2. **Repository name** 填 `xiaoling`
3. 选 **Public**(重要:GitHub Pages 免费需要公开仓库;私有仓库的 Pages 要付费账户)
4. 下面的 “Add a README / .gitignore / license” **都不要勾**(我们本地已有)
5. 点 **Create repository**
6. 建好后页面上有个 HTTPS 地址,形如 `https://github.com/<你的用户名>/xiaoling.git`,记下来

---

## 第 2 步 · 本地初始化并推送
在 Git Bash 里**逐行**执行:
```bash
cd ~/xiaoling
git init
git branch -M main
git add .
git commit -m "小灵 AI手机精灵:演示 + 后端 + 打包脚手架"
git remote add origin https://github.com/<你的用户名>/xiaoling.git
git push -u origin main
```
> **首次 push 会弹出浏览器让你登录 GitHub**(Git for Windows 自带的“凭据管理器”),
> 点 **Sign in with your browser** 授权一次即可,以后不再问。
> 如果没弹窗、提示要密码:见文末「push 登录不了」。

推成功后,刷新 GitHub 仓库页,能看到所有文件即为成功。

---

## 第 3 步 · 云端构建安卓 APK(本机不用装 Android SDK)
1. 打开你的仓库页 → 顶部 **Actions** 标签。
   - 若提示 “Workflows aren’t being run…”,点绿色按钮 **I understand my workflows, go ahead and enable them**。
2. 左侧点 **Build Android APK** → 右侧 **Run workflow** ▾ → 分支选 `main` → 绿色 **Run workflow**。
3. 等 5~10 分钟(第一次较慢)。点进这次运行,全部变绿 ✓ 后,页面底部 **Artifacts** 里有 `xiaoling-debug-apk`。
4. 点它下载(是个 zip)→ 解压得到 **`app-debug.apk`**。
5. 把 apk 传到安卓手机(微信/USB/网盘都行)→ 点击安装 →
   若提示风险,进 **设置 → 允许安装未知来源应用**,允许后即可装上。
> 这是**调试版**,用于测试分发没问题;正式上架商店需要 release 签名(另说)。

---

## 第 4 步 · 开 GitHub Pages,让手机装 PWA(安卓 + iPhone 都行)
1. 仓库页 → **Settings** → 左侧 **Pages**。
2. **Source** 选 **Deploy from a branch**;**Branch** 选 `main`,文件夹选 **/ (root)** → **Save**。
3. 等 1~2 分钟,Pages 页面顶部会出现网址:`https://<你的用户名>.github.io/xiaoling/`
4. **手机浏览器**打开演示页:
   ```
   https://<你的用户名>.github.io/xiaoling/demo/player.html
   ```
5. 把它“装”成 App:
   - **安卓 Chrome**:右上 ⋮ → **安装应用 / 添加到主屏幕**。
   - **iPhone Safari**:底部 **分享** → **添加到主屏幕**。
   装好后主屏会出现「小灵」图标,点开就是全屏 App,可离线打开。

---

## 第 5 步 · 以后怎么更新
改完代码后:
```bash
cd ~/xiaoling
git add .
git commit -m "说明这次改了啥"
git push
```
- **PWA**:Pages 自动重新发布,手机上重开(或下拉刷新)即是新版本。
- **APK**:回到 **Actions → Run workflow** 再跑一次,下载新的 apk。

---

## 常见问题排查
- **push 登录不了 / 一直要密码**:GitHub 不支持账号密码推送,要用**浏览器授权**或**令牌(PAT)**。
  令牌方式:github.com → 右上头像 → Settings → Developer settings → Personal access tokens → **Tokens (classic)** → Generate new token,勾 `repo`,生成后复制。
  push 时用户名填 GitHub 用户名,密码处**粘贴这个令牌**。
- **Actions 跑失败**:点开红色 ✗ 的步骤看日志。多数是网络波动,**重跑一次**(Re-run jobs)即可。
- **Pages 打开 404**:刚开可能没生效,等 1~2 分钟;或换手机浏览器强制刷新。确认网址带了 `/xiaoling/demo/player.html`。
- **APK 装不上**:确认已允许“未知来源/未知应用”安装;个别厂商手机需在“应用管理”里给浏览器/文件管理器单独放行。
- **iPhone 图标不好看**:iOS 对 SVG 图标支持一般。想精致可把 `demo/icon.svg` 转成 `icon-180.png` 再在 `player.html` 的 `apple-touch-icon` 引用。
- **仓库必须公开吗**:Pages 免费要公开。若要私有又想要 Pages,需要 GitHub Pro;或改用 Netlify/Vercel 托管 `demo/`(同样能装 PWA)。

---

## 一页速查(照抄)
```bash
# 建仓库:github.com/new → 名字 xiaoling → Public → Create
cd ~/xiaoling
git init && git branch -M main
git add . && git commit -m "小灵 初版"
git remote add origin https://github.com/<你的用户名>/xiaoling.git
git push -u origin main
# 之后:Actions→Run workflow 出 APK;Settings→Pages→main /root 开 PWA
# 手机装:https://<你的用户名>.github.io/xiaoling/demo/player.html → 添加到主屏幕
```

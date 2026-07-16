# 出正式签名 APK · 照抄命令清单(仓库 HZM1997/xiaoling)

> 目的:出一个 **release 签名包**,发给任何人、任何安卓手机都能装(debug 包会被很多手机拒装)。
> 全程在电脑 **Git Bash** 里操作。方括号处按提示替换成你自己的值。

---

## 第 1 步:建密钥库 keystore(一辈子做一次,务必保管好)

在 Git Bash 里(需要 JDK 的 keytool;装了 Android Studio 就有):
```bash
cd ~
keytool -genkeypair -v -keystore xiaoling.keystore -alias xiaoling \
  -keyalg RSA -keysize 2048 -validity 10000
```
按提示输入:
- **密钥库口令**:自己设一个,记牢 → 记作 `[KS_PASS]`
- 名字/组织等:随便填(可回车跳过)
- **密钥口令**:可以和上面一样(直接回车表示相同)→ 记作 `[KEY_PASS]`

生成 `~/xiaoling.keystore`。

> ⚠️ 这个文件 + 两个密码是机密:**别提交到 Git、别发别人、别丢**。丢了以后就无法给已发布的 App 出更新。建议备份到你自己的私密网盘。

---

## 第 2 步:把 keystore 转成 base64(供 GitHub Secret 用)

```bash
cd ~
base64 -w0 xiaoling.keystore > ks.b64
cat ks.b64
```
把打印出来的**一长串内容**整段复制(等下粘到 Secret)。

---

## 第 3 步:在 GitHub 配 4 个 Secret

浏览器打开:**https://github.com/HZM1997/xiaoling/settings/secrets/actions**
点 **New repository secret**,依次建 4 个(名字要一字不差):

| Secret 名称 | 值 |
|---|---|
| `KEYSTORE_BASE64` | 第 2 步复制的那一长串 |
| `KS_PASS` | 你的 `[KS_PASS]`(密钥库口令) |
| `KEY_ALIAS` | `xiaoling` |
| `KEY_PASS` | 你的 `[KEY_PASS]`(密钥口令) |

---

## 第 4 步:跑 CI 出签名包

1. 打开 **https://github.com/HZM1997/xiaoling/actions**
2. 左侧选 **Build Release APK** → 右侧 **Run workflow** ▾ → 分支 `main` → 绿色 **Run workflow**
3. 等 5~10 分钟变绿 → 点进这次运行 → 底部 **Artifacts** 下载 **`xiaoling-release-apk`**
4. 解压得到 **`app-release.apk`** —— 这就是能发给所有人的正式签名包。

---

## 第 5 步:安全地发给朋友(避免又被加后缀)

**别直接用微信/QQ 发 apk**(会被压成 zip 或加 `.1` 后缀)。用下面任一:
- 传到**百度网盘/阿里云盘**,发链接让朋友下载;
- 或用**「快牙/茄子快传」**面对面传;
- 或先把 apk 改名成 `xiaoling.apk`(避开系统重命名),再发。

朋友安装时:若拦截 → 设置里允许"安装未知应用" → 国产机弹"纯净模式"就选**继续安装**。

---

## 常见问题
- **构建红叉**:多半是 4 个 Secret 名字/值填错,或 keystore base64 没复制全 → 核对第 3 步重跑。
- **还是 `.apk.1` 打不开**:那是接收端重命名导致,把结尾 `.1` 删掉、确保以 `.apk` 结尾即可(见第 5 步换传输方式)。
- **debug vs release**:以后**发人一律用 release**;debug 只适合自己插数据线在真机测。
- **上架应用商店**:release 包可用;Google Play 建议改出 AAB(`bundleRelease`),国内商店用这个 APK 即可。

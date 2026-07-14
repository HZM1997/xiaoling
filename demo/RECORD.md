# 把演示录成 MP4

`player.html` 支持 `?record=1` 录制模式:隐藏页面外壳、自动播放、只留手机居中,
时长确定(静音走定时,不依赖语音),适合录屏。

## ⭐ 最省事(零安装、还能连语音一起录)
你这台电脑已装 Edge,直接:
1. 用 Edge 打开 `player.html?record=1`(自动播放、只剩手机居中)。
2. 按 **Win + G** 打开 Xbox Game Bar(或用 OBS)→ 点录制。
3. 录完在 `视频/捕获` 里就是带**小灵语音**的 MP4。

## 自动录(无头浏览器,产出无声 MP4)
```bash
cd demo
npm install                              # 装 playwright
# 二选一:
npx playwright install chromium          # 下载自带 chromium,或
set PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1    # 跳过下载,用系统 Edge(record.js 会自动回退 channel:msedge)
node record.js
```
产物:`demo/out/小灵演示.webm`(有 ffmpeg 时再自动转 `小灵演示.mp4`)。
> 装 ffmpeg(可选):`winget install Gyan.FFmpeg`,之后 webm 会自动转 mp4。

## 参数
- 画布默认 460×1000、2 倍高清(改 `record.js` 顶部 `W/H`、`deviceScaleFactor`)。
- 录制等演示自然结束(`window.__ended`)后停止,最长 3 分钟。

## 关于声音
无头浏览器录屏是**无声视频**——画面里的**字幕+动作卡**已完整表达剧情。要带语音就用上面"最省事"的 Win+G 方案,
或另配旁白后合成:`ffmpeg -i 小灵演示.mp4 -i voice.mp3 -c:v copy -c:a aac -shortest 成片.mp4`。

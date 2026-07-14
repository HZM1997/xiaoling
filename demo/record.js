#!/usr/bin/env node
/*
 * 小灵 · 演示录制器(无头 Chromium → MP4)
 * 打开 player.html?record=1(自动播放、隐藏外壳、只留手机),录制到视频,
 * 等演示自然结束(window.__ended)后停止,若装了 ffmpeg 再转成 mp4。
 *
 * 用法:
 *   cd demo && npm install && npx playwright install chromium
 *   node record.js
 * 产物:demo/out/小灵演示.webm  (+ 小灵演示.mp4 若系统有 ffmpeg)
 *
 * 说明:无头浏览器录屏是“无声视频”(不含 TTS 语音),画面里的字幕/动作卡已能完整表达。
 * 需要配音时:用系统录屏软件录“真实浏览器”窗口,或另配旁白后用 ffmpeg 合成音轨。
 */
const path = require('path');
const fs = require('fs');
const { spawnSync } = require('child_process');

const W = 460, H = 1000;                          // 竖屏画布(9:19.2 手机居中)
const OUT = path.resolve(__dirname, 'out');
const URL = 'file://' + path.resolve(__dirname, 'player.html').replace(/\\/g, '/') + '?record=1';

(async () => {
  let chromium;
  try { ({ chromium } = require('playwright')); }
  catch { console.error('缺少 playwright,请先:npm install && npx playwright install chromium'); process.exit(1); }

  fs.mkdirSync(OUT, { recursive: true });
  // 优先用自带 chromium;没有就用系统已装的 Edge(免下载浏览器)
  let browser;
  try { browser = await chromium.launch(); }
  catch { console.log('ℹ 未装 chromium,改用系统 Edge…'); browser = await chromium.launch({ channel: 'msedge' }); }
  const context = await browser.newContext({
    viewport: { width: W, height: H },
    deviceScaleFactor: 2,                          // 高清
    recordVideo: { dir: OUT, size: { width: W, height: H } },
  });
  const page = await context.newPage();
  console.log('▶ 打开', URL);
  await page.goto(URL);

  // 等演示自然结束(最多 3 分钟),再多录 1 秒收尾
  await page.waitForFunction('window.__ended===true', { timeout: 180000 })
            .catch(() => console.warn('⚠ 未检测到结束标志,按超时停止'));
  await page.waitForTimeout(1000);

  const video = page.video();
  await context.close();                           // 关闭后视频才落盘
  await browser.close();

  const raw = video ? await video.path() : null;
  if (!raw) { console.error('未生成视频'); process.exit(1); }
  const webm = path.join(OUT, '小灵演示.webm');
  fs.renameSync(raw, webm);
  console.log('✅ 已生成', webm);

  // 有 ffmpeg 就转 mp4(H.264,微信/朋友圈/PPT 通吃)
  const ff = spawnSync('ffmpeg', ['-y', '-i', webm, '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
    '-movflags', '+faststart', path.join(OUT, '小灵演示.mp4')], { stdio: 'inherit' });
  if (ff.status === 0) console.log('🎬 已转 MP4:', path.join(OUT, '小灵演示.mp4'));
  else console.log('ℹ 未找到 ffmpeg,保留 webm。装 ffmpeg 后可转 mp4。');
})();

#!/usr/bin/env node
/*
 * 软件说明书 PDF 生成(零依赖,纯 Node)。
 * 读 COPYRIGHT_MANUAL.md 的文字 + screenshots/ 里的截图,拼成 PDF。
 * 用法:
 *   1) 把真机截图放到  screenshots/  下,命名任意(png/jpg)
 *   2) 在说明书里把【截图占位…】那行换成  图片文件名(见下方"图片写法")
 *   3) 运行:  node tools/manual_pdf.js
 * 产物: copyright_out/manual.pdf
 *
 * 图片写法(在 COPYRIGHT_MANUAL.md 里,单独一行):
 *   @img home.png            // 引用 screenshots/home.png
 *   @img fraud.png 60        // 60 = 显示宽度百分比(可选,默认 70)
 *
 * 说明:PDF 用等宽英文字体渲染中文会显示为 ?(纯 Node 无内嵌中文字体)。
 *   → 文字页建议仍用 Word/VSCode 导中文 PDF;本脚本主要价值是"把一堆截图快速拼进 PDF 凑页数"。
 *   → 想要中文正常显示的完整说明书,用 Word 方案(见 RELEASE 检查清单)。
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const MD = path.join(ROOT, 'COPYRIGHT_MANUAL.md');
const SHOTS = path.join(ROOT, 'screenshots');
const TITLE = '小灵智能语音助手软件 V1.0 - 说明书';

// ---- 读 PNG/JPEG 尺寸(只为按比例缩放) ----
function imgInfo(buf) {
  // PNG
  if (buf.length > 24 && buf[0] === 0x89 && buf[1] === 0x50) {
    return { type: 'png', w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
  }
  // JPEG
  if (buf[0] === 0xff && buf[1] === 0xd8) {
    let p = 2;
    while (p < buf.length) {
      if (buf[p] !== 0xff) { p++; continue; }
      const marker = buf[p + 1];
      if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
        return { type: 'jpg', h: buf.readUInt16BE(p + 5), w: buf.readUInt16BE(p + 7) };
      }
      p += 2 + buf.readUInt16BE(p + 2);
    }
  }
  return null;
}

function parse() {
  if (!fs.existsSync(MD)) { console.error('找不到 COPYRIGHT_MANUAL.md'); process.exit(1); }
  const lines = fs.readFileSync(MD, 'utf8').replace(/\r\n/g, '\n').split('\n');
  const items = []; // {type:'text'|'img', ...}
  for (const ln of lines) {
    const m = ln.match(/^@img\s+(\S+)(?:\s+(\d+))?/);
    if (m) items.push({ type: 'img', file: m[1], pct: m[2] ? +m[2] : 70 });
    else items.push({ type: 'text', text: ln });
  }
  return items;
}

// ---- 极简 PDF(文本 + 嵌入图片) ----
function esc(s) { return s.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)'); }

function build(items) {
  const chunks = []; let off = 0;
  const enc = s => Buffer.isBuffer(s) ? s : Buffer.from(s, 'latin1');
  const add = s => { const b = enc(s); chunks.push(b); const a = off; off += b.length; return a; };
  const offs = {};
  const obj = (id, body, raw) => { offs[id] = off; add(id + ' 0 obj\n'); add(body); if (raw) add(raw); add('\nendobj\n'); };

  add('%PDF-1.4\n');

  // 规划对象号:1 Catalog, 2 Pages, 3 Font,然后每页(content + page)+每图 XObject
  let on = 4;
  const pages = [];       // {contentId, pageId, xobjs:{name:objId}}
  const imgObjs = [];     // {id, buf, w, h, isJpg}

  // 分页:文字每页 ~46 行;图片各自单独一页
  const PPL = 46;
  let cur = { lines: [], imgs: [] };
  const flushText = () => { if (cur.lines.length) { pages.push({ kind: 'text', lines: cur.lines }); cur = { lines: [], imgs: [] }; } };

  for (const it of items) {
    if (it.type === 'text') {
      cur.lines.push(it.text);
      if (cur.lines.length >= PPL) flushText();
    } else {
      flushText();
      const p = path.join(SHOTS, it.file);
      if (!fs.existsSync(p)) { pages.push({ kind: 'text', lines: ['[缺图: screenshots/' + it.file + ']'] }); continue; }
      const buf = fs.readFileSync(p);
      const info = imgInfo(buf);
      if (!info) { pages.push({ kind: 'text', lines: ['[无法解析图片: ' + it.file + ']'] }); continue; }
      pages.push({ kind: 'img', buf, info, pct: it.pct });
    }
  }
  flushText();

  // 分配对象号
  const fontId = 3;
  for (const pg of pages) {
    pg.contentId = on++; pg.pageId = on++;
    if (pg.kind === 'img') {
      pg.imgId = on++;
      imgObjs.push(pg);
    }
  }

  // Catalog / Pages
  obj(1, '<< /Type /Catalog /Pages 2 0 R >>');
  obj(2, '<< /Type /Pages /Kids [' + pages.map(p => p.pageId + ' 0 R').join(' ') + '] /Count ' + pages.length + ' >>');
  obj(fontId, '<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>');

  const PW = 595, PH = 842;
  for (const pg of pages) {
    let stream, res;
    if (pg.kind === 'text') {
      stream = 'BT /F1 10 Tf 40 800 Td 13 TL\n';
      for (const ln of pg.lines) {
        const safe = esc(ln.replace(/[^\x20-\x7e]/g, '?').slice(0, 100));
        stream += '(' + safe + ') Tj T*\n';
      }
      stream += 'ET';
      res = '/Font << /F1 ' + fontId + ' 0 R >>';
    } else {
      // 居中缩放图片
      const maxW = PW * (pg.pct / 100);
      const scale = maxW / pg.info.w;
      const w = pg.info.w * scale, h = pg.info.h * scale;
      const x = (PW - w) / 2, y = (PH - h) / 2;
      stream = 'q ' + w.toFixed(1) + ' 0 0 ' + h.toFixed(1) + ' ' + x.toFixed(1) + ' ' + y.toFixed(1) + ' cm /Im1 Do Q';
      res = '/XObject << /Im1 ' + pg.imgId + ' 0 R >>';
    }
    obj(pg.contentId, '<< /Length ' + stream.length + ' >>\nstream\n' + stream + '\nendstream');
    obj(pg.pageId, '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ' + PW + ' ' + PH + '] /Resources << ' + res + ' >> /Contents ' + pg.contentId + ' 0 R >>');
  }

  // 图片 XObject
  for (const pg of imgObjs) {
    const info = pg.info;
    if (info.type === 'jpg') {
      const hdr = '<< /Type /XObject /Subtype /Image /Width ' + info.w + ' /Height ' + info.h +
        ' /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ' + pg.buf.length + ' >>\nstream\n';
      offs[pg.imgId] = off; add(pg.imgId + ' 0 obj\n'); add(hdr); add(pg.buf); add('\nendstream\nendobj\n');
    } else {
      // PNG:嵌入需解码,较复杂;提示改用 JPG
      const note = '[请把 ' + '截图另存为 JPG 再引用(PNG 暂不支持嵌入)]';
      offs[pg.imgId] = off;
      // 用一个占位说明(极少见,提示用户转 jpg)
      const s = 'BT /F1 10 Tf 40 780 Td (' + esc(note.replace(/[^\x20-\x7e]/g, '?')) + ') Tj ET';
      add(pg.imgId + ' 0 obj\n<< /Type /XObject /Subtype /Form /BBox [0 0 595 842] /Length ' + s.length + ' >>\nstream\n' + s + '\nendstream\nendobj\n');
    }
  }

  // xref
  const maxId = on - 1;
  const xs = off;
  add('xref\n0 ' + (maxId + 1) + '\n0000000000 65535 f \n');
  for (let id = 1; id <= maxId; id++) add(String(offs[id] || 0).padStart(10, '0') + ' 00000 n \n');
  add('trailer\n<< /Size ' + (maxId + 1) + ' /Root 1 0 R >>\nstartxref\n' + xs + '\n%%EOF');
  return Buffer.concat(chunks);
}

const items = parse();
const buf = build(items);
const outDir = path.join(ROOT, 'copyright_out');
if (!fs.existsSync(outDir)) fs.mkdirSync(outDir);
const out = path.join(outDir, 'manual.pdf');
fs.writeFileSync(out, buf);
console.log('OK 已生成说明书 PDF:', path.relative(ROOT, out));
console.log('提示:中文文字会显示为 ?(纯Node无中文字体)。');
console.log('   → 想要中文正常的完整说明书,用 Word/VSCode 方案(见 提交检查清单)。');
console.log('   → 截图请用 JPG 格式(PNG 暂不支持嵌入),放 screenshots/,在说明书里用  @img 文件名  引用。');

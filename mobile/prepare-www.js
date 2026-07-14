/*
 * 把 demo/ 的网页资源拷进 mobile/www/,并把 player.html 作为入口 index.html。
 * Capacitor 默认加载 www/index.html。
 */
const fs = require('fs');
const path = require('path');

const SRC = path.resolve(__dirname, '..', 'demo');
const WWW = path.resolve(__dirname, 'www');

fs.mkdirSync(WWW, { recursive: true });

// 入口:player.html → index.html
fs.copyFileSync(path.join(SRC, 'player.html'), path.join(WWW, 'index.html'));

// 一并拷贝其余静态资源(存在才拷)
['live2d.js', 'manifest.webmanifest', 'sw.js', 'icon.svg'].forEach((f) => {
  const s = path.join(SRC, f);
  if (fs.existsSync(s)) fs.copyFileSync(s, path.join(WWW, f));
});

console.log('✓ www 就绪:', fs.readdirSync(WWW).join(', '));

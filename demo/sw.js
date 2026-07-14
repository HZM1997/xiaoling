/* 小灵 · Service Worker —— 让 PWA 可离线打开(核心页面缓存;Live2D 的 CDN 依赖不缓存,离线时自动退回 SVG) */
const CACHE = 'xiaoling-v1';
const ASSETS = ['./player.html', './manifest.webmanifest', './icon.svg', './live2d.js'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)).catch(() => {}));
  self.skipWaiting();
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))));
  self.clients.claim();
});
self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  e.respondWith(
    caches.match(e.request).then((hit) =>
      hit || fetch(e.request).then((resp) => {
        const copy = resp.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy)).catch(() => {});
        return resp;
      }).catch(() => caches.match('./player.html'))
    )
  );
});

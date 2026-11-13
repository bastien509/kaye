// Kaye service worker: keeps the app shell available with no signal.
// Firestore keeps the notebook data offline on its own; this only caches Kaye's own files.
const CACHE = 'kaye-shell-v4';
const SHELL = ['app.html', 'assets/app.css?v=4', 'assets/kaye.js?v=4', 'assets/logo.svg', 'manifest.webmanifest'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return; // never touch Firebase or Google traffic
  if (e.request.mode === 'navigate') {
    // Network first for the page, cached copy when offline.
    e.respondWith(fetch(e.request).then((r) => {
      const copy = r.clone();
      caches.open(CACHE).then((c) => c.put('app.html', copy));
      return r;
    }).catch(() => caches.match('app.html')));
    return;
  }
  e.respondWith(caches.match(e.request).then((hit) => hit || fetch(e.request).then((r) => {
    if (r.ok) { const copy = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); }
    return r;
  })));
});

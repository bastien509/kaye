// Kaye service worker: keeps the app shell available with no signal, and receives photos
// shared to the installed app (manifest share_target).
// Firestore keeps the notebook data offline on its own; this only caches Kaye's own files.
const CACHE = 'kaye-shell-v8';
const SHARE = 'kaye-share'; // holds one shared photo until the app picks it up
const SHELL = ['app.html', 'assets/app.css?v=8', 'assets/kaye.js?v=8', 'assets/logo.svg', 'assets/icons/icon-192.png', 'manifest.webmanifest'];

self.addEventListener('install', (e) => {
  // cache: 'reload' skips the browser's HTTP cache, so a new version never stores an old file.
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL.map((u) => new Request(u, { cache: 'reload' })))).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k !== CACHE && k !== SHARE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (url.origin !== location.origin) return; // never touch Firebase or Google traffic

  // Android share sheet → "Kaye": keep the photo, then open Snap.
  if (e.request.method === 'POST' && url.pathname.endsWith('/share-target')) {
    e.respondWith((async () => {
      try {
        const form = await e.request.formData();
        const file = form.get('photo');
        if (file && file.size) {
          const c = await caches.open(SHARE);
          await c.put('shared-photo', new Response(file, { headers: { 'content-type': file.type } }));
        }
      } catch { /* fall through to the app */ }
      return Response.redirect('app.html?shared=1#snap', 303);
    })());
    return;
  }
  if (e.request.method !== 'GET') return;

  if (e.request.mode === 'navigate') {
    // Network first for pages; the offline copy of the app is only ever app.html itself.
    const isApp = url.pathname.endsWith('/app.html');
    e.respondWith(fetch(e.request).then((r) => {
      if (isApp && r.ok) { const copy = r.clone(); caches.open(CACHE).then((c) => c.put('app.html', copy)); }
      return r;
    }).catch(async () => (await caches.match(e.request, { ignoreSearch: true })) || caches.match('app.html')));
    return;
  }
  e.respondWith(caches.match(e.request).then((hit) => hit || fetch(e.request).then((r) => {
    if (r.ok && url.pathname.includes('/assets/')) { const copy = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); }
    return r;
  })));
});

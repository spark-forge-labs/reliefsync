// Offline shell cache; API calls always go to network (queue handled in app.js via IndexedDB).
const C = "reliefsync-v1";
const SHELL = ["/", "/static/styles.css", "/static/app.js", "/static/icon.svg", "/static/vendor/leaflet.js", "/static/vendor/leaflet.css", "/manifest.webmanifest"];
self.addEventListener("install", (e) => e.waitUntil(caches.open(C).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())));
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));
self.addEventListener("fetch", (e) => {
  const u = new URL(e.request.url);
  if (e.request.method !== "GET" || u.pathname.startsWith("/api") || u.pathname === "/ws") return;
  e.respondWith(fetch(e.request).then((r) => { const cp = r.clone(); caches.open(C).then((c) => c.put(e.request, cp)); return r; }).catch(() => caches.match(e.request)));
});

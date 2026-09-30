
const CACHE='osm-pro-v4-push-20260930';
const ASSETS=['./','./index.html','./styles.css','./app.js','./manifest.webmanifest','./icon.svg','./push.html','./push.js'];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE)
      .then(cache => cache.addAll(ASSETS))
      .catch(() => null)
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;
  event.respondWith(
    fetch(event.request).then(response => {
      const copy = response.clone();
      caches.open(CACHE).then(cache => cache.put(event.request, copy)).catch(() => {});
      return response;
    }).catch(() => caches.match(event.request).then(r => r || caches.match('./index.html')))
  );
});

// FCM/Web Push: funciona sem SDK do Firebase dentro do worker.
// O Firebase entrega um evento Push padrão e o worker mostra a notificação.
self.addEventListener('push', event => {
  let payload = {};
  try { payload = event.data ? event.data.json() : {}; } catch (_) {
    payload = { data: { body: event.data ? event.data.text() : '' } };
  }

  const n = payload.notification || {};
  const d = payload.data || {};
  const title = n.title || d.title || 'OSM AI Coach';
  const options = {
    body: n.body || d.body || 'Você tem uma atualização.',
    icon: n.icon || d.icon || '/icon.svg',
    badge: n.badge || d.badge || '/icon.svg',
    tag: d.tag || 'osm-ai-coach',
    renotify: true,
    data: {
      url: d.url || '/',
      slot: d.slot || null,
      ...d
    }
  };

  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const url = event.notification?.data?.url || '/';
  event.waitUntil(
    clients.matchAll({ type:'window', includeUncontrolled:true }).then(list => {
      for (const client of list) {
        if ('focus' in client) {
          if ('navigate' in client) client.navigate(url).catch(() => {});
          return client.focus();
        }
      }
      return clients.openWindow ? clients.openWindow(url) : null;
    })
  );
});


self.addEventListener('message', event => {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});

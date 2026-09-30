
const CACHE='osm-pro-v4-webpush-20260930-v3';
const ASSETS=['./','./index.html','./styles.css','./app.js','./manifest.webmanifest','./icon.svg','./push.html','./push.js'];

self.addEventListener('install',event=>{
  event.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS)).catch(()=>null).then(()=>self.skipWaiting()));
});
self.addEventListener('activate',event=>{
  event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim()));
});
self.addEventListener('fetch',event=>{
  if(event.request.method!=='GET')return;
  event.respondWith(fetch(event.request).then(r=>{
    const copy=r.clone(); caches.open(CACHE).then(c=>c.put(event.request,copy)).catch(()=>{}); return r;
  }).catch(()=>caches.match(event.request).then(r=>r||caches.match('./index.html'))));
});
self.addEventListener('push',event=>{
  let data={};
  try{data=event.data?event.data.json():{}}catch(_){data={body:event.data?event.data.text():''}}
  const title=data.title||'OSM AI Coach';
  const options={
    body:data.body||'Você tem uma atualização.',
    icon:'/icon.svg',
    badge:'/icon.svg',
    tag:data.tag||'osm-ai-coach',
    renotify:true,
    data:{url:data.url||'/',...data}
  };
  event.waitUntil(self.registration.showNotification(title,options));
});
self.addEventListener('notificationclick',event=>{
  event.notification.close();
  const url=event.notification?.data?.url||'/';
  event.waitUntil(clients.matchAll({type:'window',includeUncontrolled:true}).then(list=>{
    for(const c of list){ if('focus'in c){ if('navigate'in c)c.navigate(url).catch(()=>{}); return c.focus(); } }
    return clients.openWindow?clients.openWindow(url):null;
  }));
});

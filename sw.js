const CACHE='osm-coach-v16';
const CORE=["/","/index.html","/styles.css","/app.js","/manifest.webmanifest","/assets/favicon.svg","/assets/icon-192.png","/assets/icon-512.png","/src/ai-router.js","/src/frame-dedup.js","/src/frame-extractor.js","/src/frame-selection.js","/src/image-preprocess.js","/src/learning-engine.js","/src/market-engine.js","/src/notifications.js","/src/ocr.js","/src/ocr-image.js","/src/parser-calendar.js","/src/parser-match.js","/src/parser-squad.js","/src/slots.js","/src/state.js","/src/storage.js","/src/tactics-engine.js","/src/ui.js","/src/review-application.js","/src/match-grounding.js","/src/squad-roster.js","/src/squad-attributes.js","/src/utils.js","/src/validator.js","/src/video.js","/src/domain.js","/src/review-ui.js","/assets/stadium.svg","/src/extraction.js","/src/ocr-layout.js","/src/calendar-ocr.js","/src/calendar-icons.js","/src/squad-icons.js","/src/match-icons.js","/src/visual-evidence.js","/src/webm-duration.js","/src/twelvelabs.js","/src/osm-digits.js","/src/reading-policy.js","/src/local-reader.js","/src/report-controls.js"];
self.addEventListener('install',e=>e.waitUntil(caches.open(CACHE).then(c=>c.addAll(CORE)).then(()=>self.skipWaiting())));
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('osm-coach')&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',e=>{
 const url=new URL(e.request.url);
 if(e.request.method!=='GET'||url.origin!==self.location.origin||url.pathname.startsWith('/api/'))return;
 e.respondWith(fetch(e.request).then(r=>{if(r.ok)caches.open(CACHE).then(c=>c.put(e.request,r.clone())).catch(()=>{});return r;}).catch(async()=>{
 const cached=await caches.match(e.request);if(cached)return cached;
 if(e.request.mode==='navigate')return (await caches.match('/index.html'))||new Response('Abra o app online uma vez.',{status:503});
 return new Response('',{status:504});
 }));
});
self.addEventListener('notificationclick',e=>{
 e.notification.close();e.waitUntil(clients.matchAll({type:'window',includeUncontrolled:true}).then(list=>list[0]?list[0].focus():clients.openWindow('/')));
});

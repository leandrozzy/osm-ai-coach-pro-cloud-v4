const RELEASE_ID='__OSM_RELEASE_ID__',CACHE_PREFIX='osm-coach-release-';
let installedRelease=null;
const cacheName=id=>CACHE_PREFIX+id;
function validRelease(release){
 const prefix='/releases/'+release?.id;
 if(release?.version!==1||!/^[a-f0-9]{64}$/.test(release.id)||release.entry!==prefix+'/src/ui.js'||release.style!==prefix+'/styles.css'||release.shell!=='/index.html'||!Array.isArray(release.assets)||release.assets.length>150)throw Error('Versão inválida.');
 const seen=new Set();
 for(const asset of release.assets){
  const path=asset?.url,relative=typeof path==='string'?path.slice(prefix.length):'';
  const allowed=path===release.style||path?.startsWith(prefix+'/src/')&&/^\/src\/[a-z0-9-]+\.js$/.test(relative)||['/index.html','/app.js','/manifest.webmanifest'].includes(path)||/^\/assets\/[a-z0-9.-]+$/.test(path||'');
  if(!allowed||seen.has(path)||!/^[a-f0-9]{64}$/.test(asset.sha256)||!Number.isInteger(asset.bytes)||asset.bytes<0)throw Error('Arquivos da versão inválidos.');seen.add(path);
 }
 for(const path of [release.entry,release.style,release.shell,'/app.js'])if(!seen.has(path))throw Error('Versão incompleta.');
 return release;
}
async function publishedRelease(){const response=await fetch('/release.json',{cache:'no-store'});if(!response.ok)throw Error('Versão indisponível.');return validRelease(await response.json());}
async function verify(response,asset){
 if(!response.ok)throw Error('Arquivo indisponível: '+asset.url);
 const bytes=await response.clone().arrayBuffer();
 const hash=[...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(byte=>byte.toString(16).padStart(2,'0')).join('');
 if(bytes.byteLength!==asset.bytes||hash!==asset.sha256)throw Error('Arquivo incompatível: '+asset.url);
 return response;
}
async function installRelease(){
 const release=await publishedRelease();
 if(/^[a-f0-9]{64}$/.test(RELEASE_ID)&&release.id!==RELEASE_ID)throw Error('A publicação mudou durante a atualização.');
 // Do not alter an already installed version until every response is valid.
 const responses=[];for(const asset of release.assets)responses.push([asset.url,await verify(await fetch(asset.url,{cache:'no-store'}),asset)]);
 const cache=await caches.open(cacheName(release.id));
 for(const [url,response]of responses)await cache.put(url,response);
 await cache.put('/release.json',new Response(JSON.stringify(release),{headers:{'content-type':'application/json'}}));
 installedRelease=release;await self.skipWaiting();
}
async function currentRelease(){
 if(installedRelease)return installedRelease;
 const names=await caches.keys(),preferred=cacheName(RELEASE_ID);
 for(const name of [preferred,...names.filter(name=>name.startsWith(CACHE_PREFIX)&&name!==preferred).reverse()]){
  const response=await (await caches.open(name)).match('/release.json');if(!response)continue;
  try{return installedRelease=validRelease(await response.json());}catch{}
 }
 return null;
}
async function releaseAsset(request,id){
 const cache=await caches.open(cacheName(id)),cached=await cache.match(request);if(cached)return cached;
 try{
  const marker=await cache.match('/release.json');const release=marker?validRelease(await marker.json()):await publishedRelease();
  if(release.id!==id)throw Error('Outra versão.');
  const asset=release.assets.find(asset=>new URL(asset.url,self.location.origin).href===request.url);if(!asset)throw Error('Arquivo fora da versão.');
  const response=await verify(await fetch(request,{cache:'no-store'}),asset);await cache.put(request,response.clone());return response;
 }catch{return new Response('Arquivo desta versão indisponível. Atualize o app online.',{status:504});}
}
self.addEventListener('install',event=>event.waitUntil(installRelease()));
// Older immutable releases remain available to tabs already using them.
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()));
self.addEventListener('fetch',e=>{
 const url=new URL(e.request.url);
 if(e.request.method!=='GET'||url.origin!==self.location.origin||url.pathname.startsWith('/api/'))return;
 if(url.pathname==='/release.json'&&url.searchParams.has('osm-repair')){e.respondWith(fetch(e.request,{cache:'no-store'}));return;}
 const version=url.pathname.match(/^\/releases\/([a-f0-9]{64})\//);
 if(version){e.respondWith(releaseAsset(e.request,version[1]));return;}
 e.respondWith(fetch(e.request,{cache:'no-store'}).catch(async()=>{
  const release=await currentRelease();if(!release)return new Response('Abra o app online uma vez.',{status:503});
  const cache=await caches.open(cacheName(release.id));
  const cached=await cache.match(e.request.mode==='navigate'?release.shell:url.pathname);if(cached)return cached;
  return new Response('Arquivo não disponível offline.',{status:504});
 }));
});
self.addEventListener('notificationclick',e=>{
 e.notification.close();e.waitUntil(clients.matchAll({type:'window',includeUncontrolled:true}).then(list=>list[0]?list[0].focus():clients.openWindow('/')));
});

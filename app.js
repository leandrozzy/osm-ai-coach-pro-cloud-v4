const DATA_KEYS=['osm-ai-coach-pro:v1','osm-ai-coach-pro:readings:v1','osm-ai-coach-pro:results:v1'];

export async function repairApp(){
 const response=await fetch('/release.json?osm-repair='+Date.now(),{cache:'no-store'});
 if(!response.ok)throw Error('A atualização ainda não está disponível. Seus arquivos e dados foram preservados.');
 const release=await response.json();
 if(release.version!==1||!/^[a-f0-9]{64}$/.test(release.id)||release.entry!=='/releases/'+release.id+'/src/ui.js')throw Error('A atualização está incompleta. Seus arquivos e dados foram preservados.');
 if('caches' in globalThis)for(const key of await caches.keys())if(key.startsWith('osm-coach'))await caches.delete(key);
 if(navigator.serviceWorker)for(const registration of await navigator.serviceWorker.getRegistrations()){
  const worker=registration.active||registration.waiting||registration.installing;
  if(worker&&new URL(worker.scriptURL,location.href).pathname==='/sw.js')await registration.unregister();
 }
 const url=new URL(location.href);url.searchParams.set('osm-update',String(Date.now()));location.replace(url.href);
}

export function showBootError(error){
 const root=document.querySelector('#app');root.replaceChildren();
 const main=document.createElement('main'),heading=document.createElement('h1'),message=document.createElement('p'),repair=document.createElement('button'),backup=document.createElement('button');
 heading.textContent='Não foi possível abrir o app';message.textContent=String(error?.message||error);
 repair.textContent='Atualizar app sem apagar dados';repair.onclick=async()=>{repair.disabled=true;try{await repairApp();}catch(error){message.textContent='Conecte à internet para atualizar. '+error.message;repair.disabled=false;}};
 backup.textContent='Exportar dados para recuperação';backup.onclick=()=>{
  const storage=Object.fromEntries(DATA_KEYS.map(key=>[key,localStorage.getItem(key)]).filter(([,value])=>value!==null));
  const blob=new Blob([JSON.stringify({version:1,exportedAt:new Date().toISOString(),storage},null,2)],{type:'application/json'}),url=URL.createObjectURL(blob),a=document.createElement('a');
  a.href=url;a.download='osm-recuperacao.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
 };
 main.append(heading,message,repair,backup);root.append(main);
}

export async function startApp({fetcher=globalThis.fetch,loadModule=url=>import(url),root=document.querySelector('#app')}={}){
 const response=await fetcher('/release.json',{cache:'no-store'});
 if(!response.ok)throw Error('A atualização do app está incompleta. Conecte à internet e toque em Atualizar app sem apagar dados.');
 const release=await response.json();
 if(release.version!==1||!/^[a-f0-9]{64}$/.test(release.id)||release.entry!=='/releases/'+release.id+'/src/ui.js'||release.style!=='/releases/'+release.id+'/styles.css')throw Error('Arquivos da atualização incompatíveis. Atualize o app sem apagar os dados.');
 let style=document.querySelector('link[rel=stylesheet]');
 if(!style){style=document.createElement('link');style.rel='stylesheet';document.head.append(style);}style.href=release.style;
 const {initApp}=await loadModule(release.entry);if(typeof initApp!=='function')throw Error('A atualização não contém a abertura do app.');
 initApp(root);
}

if(typeof document!=='undefined'){
 startApp().catch(showBootError);
 if('serviceWorker' in navigator)navigator.serviceWorker.register('/sw.js',{updateViaCache:'none'}).then(registration=>registration.update()).catch(()=>{});
}

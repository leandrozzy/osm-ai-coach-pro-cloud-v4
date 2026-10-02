const KEY='osm-ai-coach-pro:api-keys:v1';
const names=['google','groq','ocrspace','twelvelabs'];
const clean=value=>Object.fromEntries(Object.entries(value||{}).filter(([k,v])=>names.includes(k)&&typeof v==='string'&&v.trim()).map(([k,v])=>[k,v.trim()]));
let keys={};try{keys=clean(JSON.parse(globalThis.localStorage?.getItem(KEY)||'{}'));}catch{}
export const setSessionKey=value=>{
 const next=value&&typeof value==='object'?{...keys,...clean(value)}:{};
 if(globalThis.localStorage){try{localStorage.setItem(KEY,JSON.stringify(next));}catch{throw Error('Não foi possível salvar as chaves neste dispositivo. Confira o armazenamento do navegador.');}}
 keys=next;
};
export const hasSessionKey=()=>Object.values(keys).some(Boolean);
export const sessionProviders=()=>Object.keys(keys).filter(k=>keys[k]);
export const providerKey=name=>keys[name]||'';
export async function apiRequest(path,body={},signal,timeout=25000){
 const c=new AbortController(),stop=()=>c.abort();if(signal?.aborted)stop();signal?.addEventListener('abort',stop,{once:true});const timer=setTimeout(stop,timeout);
 try{const r=await fetch('/api/'+path,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({...body,keys}),signal:c.signal});const data=await r.json().catch(()=>({}));if(!r.ok)throw Error(data.error||('HTTP '+r.status));return data;}catch(e){if(e.name==='AbortError')throw Error('Tempo limite ou análise cancelada.');throw e;}finally{clearTimeout(timer);signal?.removeEventListener('abort',stop);}
}
export const askAI=(task,payload,model)=>apiRequest('ai',{task,payload,model},null,27000);
export async function apiStatus(){const c=new AbortController(),t=setTimeout(()=>c.abort(),5000);try{const r=await fetch('/api/status',{signal:c.signal,cache:'no-store'});return r.ok?await r.json():null;}catch{return null;}finally{clearTimeout(t);}}

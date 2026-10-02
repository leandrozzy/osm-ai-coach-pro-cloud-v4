let keys={};
export const setSessionKey=value=>{keys=typeof value==='object'?Object.fromEntries(Object.entries(value).filter(([k,v])=>['groq','ocrspace','twelvelabs'].includes(k)&&typeof v==='string').map(([k,v])=>[k,v.trim()])):{};};
export const hasSessionKey=()=>Object.values(keys).some(Boolean);
export const sessionProviders=()=>Object.keys(keys).filter(k=>keys[k]);
export async function apiRequest(path,body={},signal,timeout=25000){
 const c=new AbortController(),stop=()=>c.abort();if(signal?.aborted)stop();signal?.addEventListener('abort',stop,{once:true});const timer=setTimeout(stop,timeout);
 try{const r=await fetch('/api/'+path,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({...body,keys}),signal:c.signal});const data=await r.json().catch(()=>({}));if(!r.ok)throw Error(data.error||('HTTP '+r.status));return data;}catch(e){if(e.name==='AbortError')throw Error('Tempo limite ou análise cancelada.');throw e;}finally{clearTimeout(timer);signal?.removeEventListener('abort',stop);}
}
export const askAI=(task,payload,model)=>apiRequest('ai',{task,payload,model},null,15000);
export async function apiStatus(){const c=new AbortController(),t=setTimeout(()=>c.abort(),5000);try{const r=await fetch('/api/status',{signal:c.signal,cache:'no-store'});return r.ok?await r.json():null;}catch{return null;}finally{clearTimeout(t);}}

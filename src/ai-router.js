let sessionKey='';
export const setSessionKey=key=>{sessionKey=key.trim();};
export const hasSessionKey=()=>!!sessionKey;
export async function askAI(task,payload,model){
 const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),42000);
 try{const r=await fetch('/api/ai',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({task,payload,sessionKey,model}),signal:controller.signal});
 const data=await r.json().catch(()=>({}));if(!r.ok)throw Error(data.error||('IA HTTP '+r.status));return data;
 }catch(e){if(e.name==='AbortError')throw Error('IA excedeu 42 segundos. Tente novamente ou use modo local.');throw e;}finally{clearTimeout(timer);}
}
export function askVision(task,images,context={},model){return askAI(task,{images,context},model);}
export async function apiStatus(){
 const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),5000);
 try{const r=await fetch('/api/status',{signal:controller.signal,cache:'no-store'});return r.ok?await r.json():null;}catch{return null;}finally{clearTimeout(timer);}
}


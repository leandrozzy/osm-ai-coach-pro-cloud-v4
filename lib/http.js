export function requestBody(req){try{return typeof req.body==='string'?JSON.parse(req.body):req.body||{};}catch{throw Error('JSON inválido.');}}
export function allowed(req,res){
 res.setHeader('Cache-Control','no-store');
 if(req.method!=='POST'){res.status(405).json({error:'Use POST.'});return false;}
 if(req.headers?.origin&&req.headers.origin!=='https://'+req.headers.host&&req.headers.origin!=='http://'+req.headers.host){res.status(403).json({error:'Origem não permitida.'});return false;}return true;
}
export function keyFor(name,body={}){
 const env={google:'GEMINI_API_KEY',groq:'GROQ_API_KEY',ocrspace:'OCR_SPACE_API_KEY',twelvelabs:'TWELVELABS_API_KEY'};
 const key=body.keys?.[name];if(key!=null&&(typeof key!=='string'||key.length>300))throw Error('Chave inválida.');
 return key?.trim()||process.env[env[name]]||(name==='twelvelabs'?process.env.TWELVE_LABS_API_KEY:'')||'';
}
export async function boundedFetch(url,options={},ms=10000){
 const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),ms);
 try{const response=await fetch(url,{...options,signal:controller.signal});const body=await response.json().catch(()=>({}));if(!response.ok){const secretValues=[options.headers?.['x-goog-api-key'],options.headers?.['x-api-key'],options.headers?.authorization?.replace(/^Bearer /,'')].filter(Boolean);let detail=typeof body.error?.message==='string'?body.error.message:typeof body.error==='string'?body.error:typeof body.message==='string'?body.message:'';for(const value of secretValues)detail=detail.split(value).join('[chave oculta]');detail=detail.replace(/[\r\n]+/g,' ').slice(0,240);const e=Error('HTTP '+response.status+(detail?': '+detail:''));e.status=response.status;e.providerCode=body.error?.code||body.error?.type||body.code||null;const generation=body.error?.failed_generation;if(typeof generation==='string'&&generation.length<=120000){let safe=generation;for(const value of secretValues)safe=safe.split(value).join('[chave oculta]');Object.defineProperty(e,'failedGeneration',{value:safe,enumerable:false});}const after=response.headers?.get?.('retry-after');const seconds=Number(after);const headerDelay=after?(Number.isFinite(seconds)?seconds*1000:Date.parse(after)-Date.now()):0;const retryDetail=body.error?.details?.find?.(d=>typeof d.retryDelay==='string')?.retryDelay;const detailDelay=retryDetail?parseFloat(retryDetail)*1000:0;e.retryAfterMs=Math.max(0,headerDelay||detailDelay||0);throw e;}return body;}
 catch(e){if(e.name==='AbortError')throw Object.assign(Error('Tempo limite de '+Math.round(ms/1000)+'s'),{code:'PROVIDER_TIMEOUT'});throw e;}finally{clearTimeout(timer);}
}
export function parseJson(raw){if(typeof raw!=='string')throw Error('Resposta inválida.');return JSON.parse(raw.trim().replace(/^[\u0060]{3}(?:json)?\s*/i,'').replace(/[\u0060]{3}$/,'').trim());}
export function errorInfo(provider,e){return {provider,status:e.status||null,error:e.name==='SyntaxError'?'JSON inválido':String(e.message||'Falha na consulta').slice(0,260),code:e.code||e.providerCode||null,...(Number.isFinite(e.retryAfterMs)&&e.retryAfterMs>0?{retryAfterMs:e.retryAfterMs}:{}),...(e.stopAnalysis?{stopAnalysis:true}:{})};}

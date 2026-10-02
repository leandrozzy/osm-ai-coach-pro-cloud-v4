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
 try{const response=await fetch(url,{...options,signal:controller.signal});const body=await response.json().catch(()=>({}));if(!response.ok){const e=Error('HTTP '+response.status);e.status=response.status;throw e;}return body;}
 catch(e){if(e.name==='AbortError')throw Error('Tempo limite de '+Math.round(ms/1000)+'s');throw e;}finally{clearTimeout(timer);}
}
export function parseJson(raw){if(typeof raw!=='string')throw Error('Resposta inválida.');return JSON.parse(raw.trim().replace(/^[\u0060]{3}(?:json)?\s*/i,'').replace(/[\u0060]{3}$/,'').trim());}
export function errorInfo(provider,e){return {provider,status:e.status||null,error:e.status?'HTTP '+e.status:e.name==='SyntaxError'?'JSON inválido':String(e.message).slice(0,120)};}


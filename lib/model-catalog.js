import {createHash} from 'node:crypto';
import {boundedFetch} from './http.js';

// Provider models change independently of the installed app. Cache only a hash
// of the key and selected IDs; a retired default is never retried on every frame.
const entries=new Map();
const TTL=15*60*1000;
const MAX_ENTRIES=64;
function entryFor(provider,key){
 const id=provider+':'+createHash('sha256').update(key).digest('hex');
 let entry=entries.get(id);
 if(!entry||entry.expires<Date.now()){
  if(entries.size>=MAX_ENTRIES)entries.delete(entries.keys().next().value);
  entry={expires:Date.now()+TTL,models:null,loading:null,failed:{vision:new Set(),text:new Set()},selected:{}};
  entries.set(id,entry);
 }
 return entry;
}
function cleanId(model){
 const id=String(model||'').replace(/^models\//,'');
 if(!/^[a-zA-Z0-9._/-]{1,160}$/.test(id))throw Error('Nome de modelo inválido.');
 return id;
}
function supports(model,provider,visual){
 const id=String(provider==='google'?model.name:model.id).replace(/^models\//,'');
 if(provider==='google')return model.supportedGenerationMethods?.includes('generateContent')&&/^gemini-/i.test(id)&&/flash/i.test(id)&&!/(?:tts|audio|live|native-audio|image-generation|image-preview|robotics)/i.test(id);
 if(model.active===false)return false;
 if(visual)return /(?:llama-4-(?:scout|maverick)|llama-3\.2-.*vision)/i.test(id);
 return /(?:llama-3\.[13]-.*(?:versatile|instant)|llama-4-scout)/i.test(id);
}
function priority(id,provider,visual){
 if(provider==='google'){
  // Keep automatic recovery within the Flash family; never escalate to Pro.
  const version=id.match(/gemini-(\d+(?:\.\d+)?)/)?.[1];
  return (Number(version)||0)*100-(id.includes('lite')?40:0)-(/preview|exp/i.test(id)?20:0);
 }
 if(visual)return /scout/i.test(id)?100:/maverick/i.test(id)?80:50;
 return /3\.3.*versatile/i.test(id)?100:/3\.1.*instant/i.test(id)?80:60;
}
export function chooseModel(provider,models,{visual=false,preferred='',excluded=[]}={}){
 const omit=new Set(excluded),preferredId=String(preferred).replace(/^models\//,'');
 const active=models.filter(model=>model.active!==false);
 const available=model=>String(provider==='google'?model.name:model.id).replace(/^models\//,'');
 const exact=active.find(model=>available(model)===preferredId&&!omit.has(preferredId)&&(provider==='google'?model.supportedGenerationMethods?.includes('generateContent')&&/^gemini-.*(?:flash|pro)/i.test(preferredId)&&!/(?:tts|audio|live|image-generation|image-preview)/i.test(preferredId):(!visual||supports(model,provider,true))));
 if(exact)return preferredId;
 const candidates=active.filter(model=>supports(model,provider,visual)&&!omit.has(available(model))).map(available);
 candidates.sort((a,b)=>priority(b,provider,visual)-priority(a,provider,visual)||a.localeCompare(b));
 if(!candidates.length){const error=Error((provider==='google'?'Google':'Groq')+': nenhum modelo '+(visual?'visual':'de texto')+' compatível disponível nesta chave.');error.code='NO_SUPPORTED_MODEL';throw error;}
 return cleanId(candidates[0]);
}
async function discover(provider,key,entry,budget){
 if(entry.loading)return entry.loading;
 entry.loading=(async()=>{
  const started=Date.now(),models=[];
  if(provider==='groq'){
   const result=await boundedFetch('https://api.groq.com/openai/v1/models',{headers:{authorization:'Bearer '+key}},Math.min(3000,budget));
   if(!Array.isArray(result.data))throw Error('Groq: catálogo de modelos inválido.');
   models.push(...result.data);
  }else{
   let token='';
   for(let page=0;page<3;page++){
    const remaining=budget-(Date.now()-started);
    if(remaining<100)throw Error('Google: tempo limite ao consultar modelos disponíveis.');
    const params=new URLSearchParams({pageSize:'1000'});if(token)params.set('pageToken',token);
    const result=await boundedFetch('https://generativelanguage.googleapis.com/v1beta/models?'+params,{headers:{'x-goog-api-key':key}},Math.min(3000,remaining));
    if(!Array.isArray(result.models))throw Error('Google: catálogo de modelos inválido.');
    models.push(...result.models);token=result.nextPageToken||'';if(!token)break;
   }
  }
  entry.models=models;
  return models;
 })();
 try{return await entry.loading;}finally{entry.loading=null;}
}
function missingModel(error){
 if(error.status===404)return true;
 return [400,410].includes(error.status)&&/model.*(?:not found|does not exist|decommission|not supported|unsupported|no longer|retired)|(?:model_not_found|unsupported_model)/i.test(String(error.details||error.message||'')+' '+(error.providerCode||error.code||''));
}
export async function withModelRecovery({provider,key,preferred,visual=false,timeout=18000},request){
 const started=Date.now(),entry=entryFor(provider,key),selection=(visual?'vision':'text')+':'+preferred;
 const failed=entry.failed[visual?'vision':'text'];
 let model=cleanId(entry.selected[selection]||preferred);
 if(failed.has(model)&&entry.models){
  try{model=chooseModel(provider,entry.models,{visual,preferred,excluded:[...failed]});entry.selected[selection]=model;}
  catch(error){error.status=404;throw error;}
 }
 try{return await request(model,timeout);}catch(error){
  if(!missingModel(error))throw error;
  failed.add(model);
  const remaining=timeout-(Date.now()-started);
  if(remaining<250){error.message=(provider==='google'?'Google':'Groq')+': modelo '+model+' indisponível; tempo insuficiente para consultar alternativas.';throw error;}
  let alternatives;
  try{
   const cached=entry.selected[selection];
   alternatives=entry.models&&(!cached||cached!==model)?entry.models:await discover(provider,key,entry,Math.min(3000,remaining));
   model=chooseModel(provider,alternatives,{visual,preferred,excluded:[...failed]});
  }catch(catalogError){
   catalogError.status=catalogError.status||error.status;
   catalogError.code=catalogError.code||'MODEL_DISCOVERY_FAILED';
   catalogError.message=(provider==='google'?'Google':'Groq')+': modelo '+model+' indisponível. '+catalogError.message;
   throw catalogError;
  }
  const retryBudget=timeout-(Date.now()-started);
  if(retryBudget<250)throw Error('Tempo limite ao procurar um modelo disponível.');
  // Set before retry so concurrent requests also leave the retired default.
  entry.selected[selection]=model;
  try{return await request(model,retryBudget);}catch(retryError){
   if(missingModel(retryError)){failed.add(model);delete entry.selected[selection];retryError.message=(provider==='google'?'Google':'Groq')+': modelo '+model+' também indisponível nesta chave.';}
   throw retryError;
  }
 }
}
export async function probeModels(provider,key,{visionPreferred,textPreferred}={}){
 if(!['google','groq'].includes(provider))throw Error('Provedor de modelos inválido.');
 if(!key)throw Error('Chave não configurada.');
 const googleDefault=process.env.GEMINI_MODEL||'gemini-2.5-flash';
 visionPreferred=(visionPreferred&&visionPreferred!=='auto'?visionPreferred:null)||(provider==='google'?googleDefault:process.env.GROQ_VISION_MODEL||'meta-llama/llama-4-scout-17b-16e-instruct');
 textPreferred=(textPreferred&&textPreferred!=='auto'?textPreferred:null)||(provider==='google'?googleDefault:process.env.GROQ_TEXT_MODEL||'llama-3.3-70b-versatile');
 const entry=entryFor(provider,key),models=await discover(provider,key,entry,3000),warnings=[];
 const select=(preferred,visual)=>{
  try{const model=chooseModel(provider,models,{visual,preferred,excluded:[...entry.failed[visual?'vision':'text']]});entry.selected[(visual?'vision':'text')+':'+preferred]=model;return model;}
  catch(error){warnings.push(error.message);return null;}
 };
 return {visionModel:select(visionPreferred,true),textModel:select(textPreferred,false),warnings};
}

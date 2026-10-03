import {boundedFetch,parseJson,errorInfo} from './http.js';
import {extractionPrompt,groqExtractionPrompt,tacticPrompt} from './prompts.js';
import {normalizeExtraction,fuseExtraction,blankExtraction} from '../src/extraction.js';
import {withModelRecovery,withProviderBudget} from './model-catalog.js';
import {prepareGroqRequests,requestGroqJson,GROQ_INPUT_TOKENS} from './groq-request.js';
const GROQ_MAX_IMAGES=3;
export async function groqRead({key,type,images=[],text='',context={},model,task='extract',budgetMs=18000}){
 if(!key)throw Error('Chave Groq não configurada.');
 // Full frames arrive before focus crops. Groq accepts at most three images;
 // retain both frames and one crop rather than reject the complete batch.
 const providerImages=images.slice(0,GROQ_MAX_IMAGES);
 const visual=providerImages.length>0;let prompts=prepareGroqRequests({type,text,context,task});const inputBudget={used:0,limit:6500};
 const preferred=(model&&model!=='auto'?model:null)||(visual?(process.env.GROQ_VISION_MODEL||'meta-llama/llama-4-scout-17b-16e-instruct'):(process.env.GROQ_TEXT_MODEL||'llama-3.3-70b-versatile'));
 try{return await withProviderBudget('groq',key,()=>withModelRecovery({provider:'groq',key,preferred,visual,timeout:Math.min(18000,budgetMs)},async(active,budget)=>{
  const started=Date.now();let read=blankExtraction(),completed=0,resized=false;
  for(let index=0;index<Math.min(prompts.length,2);index++){
   try{
    const remaining=budget-(Date.now()-started);if(remaining<200){const error=Error('Tempo limite Groq; leitura parcial preservada.');error.code='PROVIDER_TIMEOUT';throw error;}
    const raw=await requestGroqJson({key,model:active,prompt:prompts[index],images:providerImages,type,task,budgetMs:remaining,inputBudget});
    if(task==='tactic')return raw;
    read=fuseExtraction(read,normalizeExtraction(raw,type,'Groq'+(visual?' visual':' OCR')));completed++;
   }catch(error){
    // An explicit smaller account allowance can still reject an estimated
    // payload. Repack all OCR facts once; do not cut the end of the input.
    if(error.status===413&&!resized&&text&&completed===0){prompts=prepareGroqRequests({type,text,context,task,maxInputTokens:Math.floor(GROQ_INPUT_TOKENS*.68)});resized=true;index=-1;continue;}
    if(!completed)throw error;
    read.warnings.push('Groq: '+String(error.message||'falha no lote seguinte').slice(0,200)+'. Os fatos já lidos foram preservados.');
    read.warnings.push('Groq interpretou '+completed+' de '+prompts.length+' lotes OCR dentro do limite. A leitura literal das outras telas foi preservada; revise os campos NI.');
    Object.defineProperty(error,'partialRead',{value:read,enumerable:false});throw error;
   }
  }
  if(completed<prompts.length)read.warnings.push('Groq interpretou '+completed+' de '+prompts.length+' lotes OCR dentro do limite. A leitura literal das outras telas foi preservada; revise os campos NI.');
  return read;
 }));}catch(error){if(error.partialRead){error.partialRead.providerFailures=[errorInfo('groq',error)];return error.partialRead;}throw error;}
}
export async function ocrRead({key,image,budgetMs=10000}){
 if(!key)throw Error('Chave OCR.space não configurada.');
 const form=new URLSearchParams({apikey:key,language:'por',isOverlayRequired:'true',OCREngine:'2',scale:'true',isTable:'true',base64Image:image});
 const d=await boundedFetch('https://api.ocr.space/parse/image',{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:form},Math.min(10000,budgetMs));
 const results=d.ParsedResults||[];
 const lines=results.flatMap(r=>r.TextOverlay?.Lines||[]);
 const text=results.map(r=>r.ParsedText||r.TextOverlay?.Lines?.map(line=>line.LineText||line.Words?.map(w=>w.WordText).join(' ')||'').join('\n')||'').join('\n');
 const detail=[...(Array.isArray(d.ErrorMessage)?d.ErrorMessage:[d.ErrorMessage]),d.ErrorDetails,...results.map(r=>r.ErrorMessage)].filter(Boolean).join(' ').split(key).join('[chave]').replace(/https?:\/\/\S+/g,'[endereço]').slice(0,200);
 if(!text.trim()){
  const error=Error('OCR.space: '+(detail||'nenhum texto reconhecido nesta tela.'));
  error.code='OCR_EMPTY';error.ocrExitCode=d.OCRExitCode||null;throw error;
 }
 return {text,lines,...(detail?{warnings:[detail]}:{})};
}
export async function twelveRead({key,type,video,context={},images=[],budgetMs=45000}){
 if(!key)throw Error('Chave TwelveLabs não configurada.');
 const body={model_name:'pegasus1.5',video,temperature:0,max_tokens:type==='match'?2400:7000,stream:false};
 // The compact schema uses the same output keys/rules without sending the
 // complete editor descriptions. Match data needs far fewer than 7000 tokens.
 let prompt=groqExtractionPrompt(type,context)+'\nO vídeo contém telas do OSM em sequência. Leia texto legível nas telas pausadas; transições não comprovam dados. Retorne apenas os campos comprovados; não acrescente chaves NI para preencher o esquema.';
 if(Array.isArray(context.targetFields)){
  const fields=context.targetFields.filter(field=>typeof field==='string'&&/^[A-Za-z][A-Za-z0-9.]{0,50}$/.test(field)).slice(0,40);
  if(fields.length)prompt+='\nPriorize os campos ainda pendentes: '+fields.join(', ')+'. Não preencha por suposição.';
 }
 if(images.length)body.prompt_v2={input_text:prompt+'\nLeia estas telas em detalhe: '+images.map((_,i)=>'<@frame'+i+'>').join(', '),media_sources:images.slice(0,4).map((image,i)=>({name:'frame'+i,media_type:'image',base64_string:image.split(',')[1]}))};else body.prompt=prompt;
 const timeout=Number.isFinite(budgetMs)?Math.min(45000,Math.max(1000,Math.floor(budgetMs))):45000;
 const data=await boundedFetch('https://api.twelvelabs.io/v1.3/analyze',{method:'POST',headers:{'x-api-key':key,'content-type':'application/json'},body:JSON.stringify(body)},timeout);
 if(data.finish_reason==='length')throw Error('TwelveLabs retornou saída cortada.');
 return normalizeExtraction(parseJson(data.data),type,'TwelveLabs vídeo');
}

export async function googleRead({key,type,images=[],context={},task='extract',model,budgetMs=24000}){
 if(!key)throw Error('Chave Google não configurada.');
 const started=Date.now(),deadline=Math.min(24000,budgetMs);
 const prompt=task==='tactic'?tacticPrompt(context):extractionPrompt(type,context);
 const parts=[{text:prompt},...images.map(image=>({inlineData:{mimeType:image.slice(5,image.indexOf(';')),data:image.split(',')[1]}}))];
 const preferred=(model&&model!=='auto'?model:null)||process.env.GEMINI_MODEL||'gemini-2.5-flash';
 if(!/^[a-zA-Z0-9._-]+$/.test(preferred))throw Error('Modelo Google inválido.');
 const response=await withProviderBudget('google',key,()=>{
  const remaining=deadline-(Date.now()-started);
  if(remaining<250)throw Object.assign(Error('Tempo limite Google.'),{code:'PROVIDER_TIMEOUT'});
  return withModelRecovery({provider:'google',key,preferred,visual:images.length>0,timeout:remaining,retryTransient:true},(active,budget,attempt)=>{
   // Reserve time for a confirmed alternative inside the same deadline; a
   // timeout never adds another full 20-second wait to this image batch.
   const reserve=attempt===0&&budget>=8000?Math.min(6500,Math.floor(budget*.3)):0;
   const requestBudget=Math.min(attempt===0?16000:20000,budget-reserve);
   return boundedFetch('https://generativelanguage.googleapis.com/v1beta/models/'+active+':generateContent',{method:'POST',headers:{'x-goog-api-key':key,'content-type':'application/json'},body:JSON.stringify({contents:[{role:'user',parts}],generationConfig:{temperature:0,responseMimeType:'application/json',maxOutputTokens:task==='tactic'?3000:type==='calendar'?6000:4500,...(/^gemini-2\.5-flash(?:$|-lite)/.test(active)?{thinkingConfig:{thinkingBudget:0}}:{})}})},requestBudget);
  });
 });
 const candidate=response.candidates?.[0];if(candidate?.finishReason!=='STOP')throw Error('Google não retornou leitura completa.');
 const raw=parseJson(candidate.content?.parts?.filter(p=>!p.thought).map(p=>p.text||'').join(''));
 return task==='tactic'?raw:normalizeExtraction(raw,type,'Google Gemini');
}

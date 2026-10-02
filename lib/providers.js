import {boundedFetch,parseJson} from './http.js';
import {extractionPrompt,tacticPrompt} from './prompts.js';
import {normalizeExtraction} from '../src/extraction.js';
import {withModelRecovery,withProviderBudget} from './model-catalog.js';
const GROQ_MAX_IMAGES=3;
export async function groqRead({key,type,images=[],text='',context={},model,task='extract',budgetMs=18000}){
 if(!key)throw Error('Chave Groq não configurada.');
 // Full frames arrive before focus crops. Groq accepts at most three images;
 // retain both frames and one crop rather than reject the complete batch.
 const providerImages=images.slice(0,GROQ_MAX_IMAGES);
 const visual=providerImages.length>0;const prompt=task==='tactic'?tacticPrompt(context):extractionPrompt(type,context)+(text?'\nOCR com posições/linhas fornecidas (só fatos do texto, cores/ícones sem imagem permanecem NI):\n'+text.slice(0,22000):'');
 const content=visual?[{type:'text',text:prompt},...providerImages.map(url=>({type:'image_url',image_url:{url}}))]:prompt;
 const preferred=(model&&model!=='auto'?model:null)||(visual?(process.env.GROQ_VISION_MODEL||'meta-llama/llama-4-scout-17b-16e-instruct'):(process.env.GROQ_TEXT_MODEL||'llama-3.3-70b-versatile'));
 const data=await withProviderBudget('groq',key,()=>withModelRecovery({provider:'groq',key,preferred,visual,timeout:Math.min(18000,budgetMs)},(active,budget)=>boundedFetch('https://api.groq.com/openai/v1/chat/completions',{method:'POST',headers:{authorization:'Bearer '+key,'content-type':'application/json'},body:JSON.stringify({model:active,temperature:0,max_completion_tokens:type==='calendar'?6000:4000,response_format:{type:'json_object'},messages:[{role:'user',content}]})},Math.min(12000,budget))));
 if(data.choices?.[0]?.finish_reason==='length')throw Error('Resposta cortada: reduza o lote.');
 const raw=parseJson(data.choices?.[0]?.message?.content);
 return task==='tactic'?raw:normalizeExtraction(raw,type,'Groq'+(visual?' visual':' OCR'));
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
export async function twelveRead({key,type,video,context={},images=[]}){
 if(!key)throw Error('Chave TwelveLabs não configurada.');
 const body={model_name:'pegasus1.5',video,temperature:0,max_tokens:7000,stream:false};
 const prompt=extractionPrompt(type,context);
 if(images.length)body.prompt_v2={input_text:prompt+'\nLeia estas telas em detalhe: '+images.map((_,i)=>'<@frame'+i+'>').join(', '),media_sources:images.slice(0,4).map((image,i)=>({name:'frame'+i,media_type:'image',base64_string:image.split(',')[1]}))};else body.prompt=prompt;
 const data=await boundedFetch('https://api.twelvelabs.io/v1.3/analyze',{method:'POST',headers:{'x-api-key':key,'content-type':'application/json'},body:JSON.stringify(body)},20000);
 if(data.finish_reason==='length')throw Error('TwelveLabs retornou saída cortada.');
 return normalizeExtraction(parseJson(data.data),type,'TwelveLabs vídeo');
}

export async function googleRead({key,type,images=[],context={},task='extract',model}){
 if(!key)throw Error('Chave Google não configurada.');
 const prompt=task==='tactic'?tacticPrompt(context):extractionPrompt(type,context);
 const parts=[{text:prompt},...images.map(image=>({inlineData:{mimeType:image.slice(5,image.indexOf(';')),data:image.split(',')[1]}}))];
 const preferred=(model&&model!=='auto'?model:null)||process.env.GEMINI_MODEL||'gemini-2.5-flash';
 if(!/^[a-zA-Z0-9._-]+$/.test(preferred))throw Error('Modelo Google inválido.');
 const response=await withProviderBudget('google',key,()=>withModelRecovery({provider:'google',key,preferred,visual:images.length>0,timeout:24000},(active,budget)=>boundedFetch('https://generativelanguage.googleapis.com/v1beta/models/'+active+':generateContent',{method:'POST',headers:{'x-goog-api-key':key,'content-type':'application/json'},body:JSON.stringify({contents:[{role:'user',parts}],generationConfig:{temperature:0,responseMimeType:'application/json',maxOutputTokens:task==='tactic'?3000:type==='calendar'?6000:4500,...(/^gemini-2\.5-flash(?:$|-lite)/.test(active)?{thinkingConfig:{thinkingBudget:0}}:{})}})},Math.min(20000,budget))));
 const candidate=response.candidates?.[0];if(candidate?.finishReason!=='STOP')throw Error('Google não retornou leitura completa.');
 const raw=parseJson(candidate.content?.parts?.filter(p=>!p.thought).map(p=>p.text||'').join(''));
 return task==='tactic'?raw:normalizeExtraction(raw,type,'Google Gemini');
}

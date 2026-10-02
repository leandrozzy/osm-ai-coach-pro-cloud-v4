import {boundedFetch,parseJson} from './http.js';
import {extractionPrompt,tacticPrompt} from './prompts.js';
import {normalizeExtraction} from '../src/extraction.js';
export async function groqRead({key,type,images=[],text='',context={},model,task='extract'}){
 if(!key)throw Error('Chave Groq não configurada.');
 const visual=images.length>0;const prompt=task==='tactic'?tacticPrompt(context):extractionPrompt(type,context)+(text?'\nOCR com posições/linhas fornecidas (só fatos do texto, cores/ícones sem imagem permanecem NI):\n'+text.slice(0,22000):'');
 const content=visual?[{type:'text',text:prompt},...images.map(url=>({type:'image_url',image_url:{url}}))]:prompt;
 const data=await boundedFetch('https://api.groq.com/openai/v1/chat/completions',{method:'POST',headers:{authorization:'Bearer '+key,'content-type':'application/json'},body:JSON.stringify({model:model||(visual?(process.env.GROQ_VISION_MODEL||'meta-llama/llama-4-scout-17b-16e-instruct'):(process.env.GROQ_TEXT_MODEL||'llama-3.3-70b-versatile')),temperature:0,max_completion_tokens:type==='calendar'?6000:4000,response_format:{type:'json_object'},messages:[{role:'user',content}]})},10000);
 if(data.choices?.[0]?.finish_reason==='length')throw Error('Resposta cortada: reduza o lote.');
 const raw=parseJson(data.choices?.[0]?.message?.content);
 return task==='tactic'?raw:normalizeExtraction(raw,type,'Groq'+(visual?' visual':' OCR'));
}
export async function ocrRead({key,image}){
 if(!key)throw Error('Chave OCR.space não configurada.');
 const form=new URLSearchParams({apikey:key,language:'por',isOverlayRequired:'true',OCREngine:'2',scale:'true',isTable:'true',base64Image:image});
 const d=await boundedFetch('https://api.ocr.space/parse/image',{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:form},10000);
 if(d.IsErroredOnProcessing)throw Error('OCR.space não conseguiu ler a imagem.');
 const results=d.ParsedResults||[];const text=results.map(r=>r.ParsedText||'').join('\n');
 if(!text.trim())throw Error('OCR.space retornou texto vazio.');
 return {text,lines:results.flatMap(r=>r.TextOverlay?.Lines||[])};
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

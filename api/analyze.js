import {allowed,requestBody,keyFor,errorInfo} from '../lib/http.js';
import {groqRead,ocrRead,googleRead} from '../lib/providers.js';
import {blankExtraction,normalizeExtraction,fuseExtraction,coverage} from '../src/extraction.js';
import {overlayExtraction,structuredOcr} from '../src/ocr-layout.js';
import {mergeMatchTexts} from '../src/parser-match.js';
import {normalize} from '../src/utils.js';
export default async function handler(req,res){
 if(!allowed(req,res))return;
 try{
 const body=requestBody(req),type=body.type;
 if(!['match','squad','calendar'].includes(type))return res.status(400).json({error:'Tipo inválido.'});
 const images=body.images;
 if(!Array.isArray(images)||images.length<1||images.length>2||images.some(i=>!i||typeof i.url!=='string'||i.url.length>1400000||!/^data:image\/(?:jpeg|png);base64,/.test(i.url)||!Number.isFinite(i.width)||!Number.isFinite(i.height)))return res.status(400).json({error:'Envie 1 ou 2 telas JPEG/PNG, até 1 MB por tela.'});
 const google=body.disabled?.includes('google')?'':keyFor('google',body);
 const focus=Array.isArray(body.focusImages)?body.focusImages:[];
 if(focus.length>2||focus.some(i=>typeof i!=='string'||i.length>500000||!/^data:image\/(?:jpeg|png);base64,/.test(i)))return res.status(400).json({error:'Recortes de leitura inválidos.'});
 const visualImages=[...images.map(i=>i.url),...focus];
 const groq=body.disabled?.includes('groq')?'':keyFor('groq',body),ocr=body.disabled?.includes('ocrspace')?'':keyFor('ocrspace',body);
 const context=body.context||{},failures=[],attempts=[];const started=Date.now();
 let output=blankExtraction();
 // Start visual reading and text reading together; one slow service doesn't hold up the other indefinitely.
 const jobs=[];
 if(groq)jobs.push((async()=>{attempts.push('groq-visual');try{return await groqRead({key:groq,type,images:visualImages,context,model:body.models?.groqVision});}catch(e){failures.push(errorInfo('groq',e));return null;}})());
 if(google)jobs.push((async()=>{attempts.push('google-visual');try{return await googleRead({key:google,type,images:visualImages,context});}catch(e){failures.push(errorInfo('google',e));return null;}})());
 let ocrResults=[];
 if(ocr)jobs.push((async()=>{
 const results=await Promise.allSettled(images.map(i=>ocrRead({key:ocr,image:i.url})));let read=blankExtraction();attempts.push('ocrspace');
 results.forEach((r,index)=>{if(r.status==='fulfilled'){ocrResults.push({index,...r.value});read=fuseExtraction(read,overlayExtraction(type,r.value,images[index].width,images[index].height));}else failures.push(errorInfo('ocrspace',r.reason));});
 if(type==='match'&&ocrResults.length)read=fuseExtraction(read,normalizeExtraction(mergeMatchTexts(ocrResults.map(r=>r.text)),'match','OCR.space explícito'));
 return read;
 })());
 const results=await Promise.allSettled(jobs);
 // Visual data has priority; OCR fills holes and reports disagreements.
 for(const r of results)if(r.status==='fulfilled'&&r.value)output=fuseExtraction(output,r.value);
 if(groq&&ocrResults.length&&coverage(type,output).percent<35&&!failures.some(f=>f.provider==='groq'&&[401,403,404,429].includes(f.status))){
 try{attempts.push('groq-ocr');const text=ocrResults.map(r=>structuredOcr(type,r,images[r.index].width,images[r.index].height)).join('\n');output=fuseExtraction(output,await groqRead({key:groq,type,text,context,model:body.models?.groqText}));}catch(e){failures.push(errorInfo('groq',e));}
 }
 // Do not let a rival squad or another slot's calendar contaminate the selected team.
 if(type!=='match'&&context.myTeam&&output.meta.team&&output.meta.team!=='NI'&&normalize(context.myTeam)!==normalize(output.meta.team)){
 output.warnings.push('Time lido '+output.meta.team+' é diferente do time selecionado '+context.myTeam+'. Dados não aplicados.');output.players=[];output.calendar=[];
 }
 const quality=coverage(type,output);
 return res.status(200).json({data:output,coverage:quality,attempts,failures,elapsedMs:Date.now()-started,needsVideo:!quality.complete});
 }catch(e){return res.status(400).json({error:'Solicitação inválida: '+e.message});}
}


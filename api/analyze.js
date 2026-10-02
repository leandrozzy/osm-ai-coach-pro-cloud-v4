import {allowed,requestBody,keyFor,errorInfo} from '../lib/http.js';
import {groqRead,ocrRead,googleRead} from '../lib/providers.js';
import {blankExtraction,normalizeExtraction,fuseExtraction,coverage} from '../src/extraction.js';
import {overlayExtraction,structuredOcr,parseCalendarOverlay,parseSquadOverlay} from '../src/ocr-layout.js';
import {mergeMatchTexts} from '../src/parser-match.js';
import {known} from '../src/domain.js';
import {cachedCapability,probeModels} from '../lib/model-catalog.js';
function usefulRead(type,data){
 if(type==='match')return Object.values(data.match||{}).filter(known).length>=2;
 if(type==='squad')return data.players?.some(row=>known(row.name));
 return data.calendar?.some(row=>['opponent','date','time','score','home','cup'].some(field=>known(row[field])));
}
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
 const ocrImages=Array.isArray(body.ocrImages)&&body.ocrImages.length?body.ocrImages:images.map(i=>({...i,region:'full'}));
 if(ocrImages.length>4||ocrImages.some(i=>!i||typeof i.url!=='string'||i.url.length>1400000||!/^data:image\/(?:jpeg|png);base64,/.test(i.url)||!Number.isFinite(i.width)||!Number.isFinite(i.height)||!['full','report'].includes(i.region)))return res.status(400).json({error:'Telas de OCR inválidas.'});
 const visualImages=[...images.map(i=>i.url),...focus];
 const groq=body.disabled?.includes('groq')?'':keyFor('groq',body),ocr=body.disabled?.includes('ocrspace')?'':keyFor('ocrspace',body);
 const context=body.context||{},failures=[],attempts=[];const started=Date.now();let preferredProvider=null;
 let output=blankExtraction();
 // One visual provider reads selected batches; OCR handles the remaining screens.
 // The client determines visual sampling across the whole clip, not per frame.
 const readers={};
 if(google)readers.google=()=>googleRead({key:google,type,images:visualImages,context,model:body.models?.google});
 if(groq&&!body.disabled?.includes('groq-visual'))readers.groq=()=>groqRead({key:groq,type,images:visualImages,context,model:body.models?.groqVision});
 const order=body.preferredProvider==='groq'?['groq','google']:['google','groq'];
 let ocrResults=[];const calendarEvidence=[],squadEvidence=[],matchEvidence=[],teamEvidence=[];
 const visual=async()=>{
  if(body.useVisual===false)return;
  const provider=order.find(name=>readers[name]);if(!provider)return;
  if(provider==='groq'&&cachedCapability('groq',groq,{visual:true,preferred:body.models?.groqVision})===false){failures.push({provider:'groq',stage:'vision',status:404,code:'NO_SUPPORTED_MODEL',error:'Groq: nenhum modelo visual compatível disponível nesta chave.'});return;}
  attempts.push(provider+'-visual');
  try{const data=await readers[provider]();if(usefulRead(type,data))preferredProvider=provider;return data;}
  catch(e){failures.push({...errorInfo(provider,e),stage:'vision'});}
 };
 const readOCR=async()=>{
 const remaining=30000-(Date.now()-started);if(!ocr||remaining<500)return;
 // Scout report crops have faint text and must not be dropped behind full screens.
 const selected=ocrImages.map((image,index)=>({...image,index})).slice(0,type==='match'?4:2);
 const results=await Promise.allSettled(selected.map(i=>ocrRead({key:ocr,image:i.url,budgetMs:remaining})));let read=blankExtraction();attempts.push('ocrspace');
 results.forEach((r,selectedIndex)=>{const image=selected[selectedIndex],index=image.index;if(r.status==='fulfilled'){ocrResults.push({index,...r.value});read.warnings.push(...r.value.warnings||[]);if(image.region==='full'){
 const literal=overlayExtraction(type,r.value,image.width,image.height);
 if(literal.meta?.teamVerified===true&&known(literal.meta.team))teamEvidence.push({team:literal.meta.team,frameIndex:Number.isInteger(image.frameIndex)?image.frameIndex:index,verified:true});
 read=fuseExtraction(read,normalizeExtraction(literal,type,type==='squad'?'OCR.space posição da linha':'OCR.space card',{sourceKind:'ocr-layout',fields:type==='squad'?['position','strength','age','value']:['round','date','time','displayedScore']}));
 const frameIndex=Number.isInteger(image.frameIndex)?image.frameIndex:index;
 if(type==='calendar')for(const row of parseCalendarOverlay(r.value.lines,image.width,image.height)){const literalRow=normalizeExtraction({calendar:[row]},'calendar','OCR.space card',{sourceKind:'ocr-layout',fields:['round','date','time','displayedScore']});const normalized=literalRow.calendar[0]||literalRow.calendarFragments[0];if(normalized)calendarEvidence.push({frameIndex,row:{...normalized,_card:row._card}});}
 if(type==='squad')squadEvidence.push({frameIndex,players:parseSquadOverlay(r.value.lines,image.width,image.height),width:image.width,height:image.height});
 if(type==='match')matchEvidence.push({frameIndex,ocr:{text:r.value.text,lines:r.value.lines},width:image.width,height:image.height,region:'full',context});
 }}else failures.push(errorInfo('ocrspace',r.reason));});
 if(type==='match'&&ocrResults.length)read=fuseExtraction(read,normalizeExtraction(mergeMatchTexts(ocrResults.map(r=>r.text),{myTeam:context.myTeam,rivalName:context.rivalName}),'match','OCR.space explícito',{sourceKind:'ocr-explicit',fields:['rivalFormation','rivalPlan','rivalMarking','rivalOffside','rivalTackling','stadium','trainingCamp','myTrainingCamp']}));
 return read;
 };
 if(body.forceOCR===true){const results=await Promise.allSettled([visual(),readOCR()]);for(const result of results)if(result.status==='fulfilled'&&result.value)output=fuseExtraction(output,result.value);}
 else{const data=await visual();if(data)output=fuseExtraction(output,data);if(!usefulRead(type,output)){const read=await readOCR();if(read)output=fuseExtraction(output,read);}}
 const usefulOcr=ocrResults.filter(r=>r.text?.trim().length>=40);
 if(groq&&usefulOcr.length&&coverage(type,output).percent<35&&Date.now()-started<30000&&!body.disabled?.includes('groq-text')&&!failures.some(f=>f.provider==='groq'&&[401,403,429,503].includes(f.status))){
 try{
  // A failed visual model does not imply there is no compatible text model.
  let supported=cachedCapability('groq',groq,{visual:false,preferred:body.models?.groqText});
  if(supported===null)supported=(await probeModels('groq',groq,{textPreferred:body.models?.groqText})).textModel;
  if(supported&&Date.now()-started<29500){attempts.push('groq-ocr');const text=usefulOcr.map(r=>ocrImages[r.index].region==='report'?r.text:structuredOcr(type,r,ocrImages[r.index].width,ocrImages[r.index].height)).join('\n');output=fuseExtraction(output,await groqRead({key:groq,type,text,context,model:supported,budgetMs:30000-(Date.now()-started)}));}
  else failures.push({provider:'groq',stage:'text',status:404,code:'NO_SUPPORTED_MODEL',error:'Groq: nenhum modelo de texto compatível disponível nesta chave.'});
 }catch(e){failures.push({...errorInfo('groq',e),stage:'text'});}
 }
 // Keep the reading reviewable. The final save validates the destination club
 // after combining all image batches, local evidence and video fallback.
 const quality=coverage(type,output);
 return res.status(200).json({data:output,coverage:quality,attempts,failures,preferredProvider,elapsedMs:Date.now()-started,teamEvidence,needsVideo:!quality.complete,...(type==='calendar'?{calendarEvidence}:type==='squad'?{squadEvidence}:{matchEvidence})});
 }catch(e){return res.status(400).json({error:'Solicitação inválida: '+e.message});}
}

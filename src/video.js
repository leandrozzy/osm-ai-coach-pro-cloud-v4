import {extractFrames,imageToCanvas} from './frame-extractor.js';
import {localOCR} from './ocr.js';
import {apiRequest,apiStatus,sessionProviders} from './ai-router.js';
import {analyzeVideo} from './twelvelabs.js';
import {blankExtraction,normalizeExtraction,fuseExtraction,coverage} from './extraction.js';
import {mergeMatchTexts} from './parser-match.js';
import {parseSquadText} from './parser-squad.js';
import {parseCalendarText} from './parser-calendar.js';
function focusImage(canvas,type,index){
 const c=document.createElement('canvas'),ctx=c.getContext('2d');
 if(type==='squad'&&canvas.width>canvas.height){
  // Bring names and the age/position/strength/value columns together at native scale.
  const left=Math.round(canvas.width*.34),right=Math.round(canvas.width*.47),x=Math.round(canvas.width*.53);
  c.width=left+right;c.height=canvas.height;ctx.drawImage(canvas,0,0,left,canvas.height,0,0,left,canvas.height);ctx.drawImage(canvas,x,0,right,canvas.height,left,0,right,canvas.height);
 }else{const x=index%2?Math.round(canvas.width*(type==='match'?.42:.5)):0,w=type==='match'?Math.round(canvas.width*(index%2?.58:.42)):Math.round(canvas.width*.5);c.width=w;c.height=canvas.height;ctx.drawImage(canvas,x,0,w,canvas.height,0,0,w,canvas.height);}
 let quality=.85,url=c.toDataURL('image/jpeg',quality);while(url.length>480000&&quality>.15){quality-=.1;url=c.toDataURL('image/jpeg',quality);}return url.length<=500000?url:null;
}
export async function analyzeMedia(files,type,options={},onUpdate=()=>{}){
 if(files.length>24||files.some(f=>f.size>150*1024*1024))throw Error('Máximo 24 arquivos, 150 MB por arquivo.');
 const started=Date.now(),limit=options.profile==='complete'?180000:90000,frames=[],errors=[],attempts=[],disabled=new Set();let data=blankExtraction();
 const expired=()=>options.signal?.aborted||Date.now()-started>=limit;
 const context={username:options.username,myTeam:options.myTeam,rivalName:options.rivalName,competitionType:options.competitionType};
 const status=options.vision?await apiStatus():null;
 const providers=new Set([...sessionProviders(),...Object.entries(status?.providers||{}).filter(([,v])=>v===true||v?.configured).map(([k])=>k)]);
 if(options.vision&&!providers.size)throw Error('Nenhuma chave detectada. Abra Configurações, salve uma chave Google/Groq/OCR.space/TwelveLabs e tente novamente. A análise automática não usa OCR local.');
 onUpdate(options.vision?'APIs detectadas: '+[...providers].join(', '):'OCR local selecionado manualmente');
 for(const file of files){
  if(expired())break;onUpdate('Preparando '+file.name);
  try{if(file.type.startsWith('video/'))frames.push(...(await extractFrames(file,{maxFrames:24,signal:options.signal})).map(f=>({...f,name:file.name})));else if(file.type.startsWith('image/'))frames.push({canvas:await imageToCanvas(file),name:file.name,time:0});else errors.push('Formato não suportado: '+file.name);}catch(e){errors.push(e.message);}
 }
 if(!frames.length)throw Error('Nenhuma tela extraída.');
 const previews=frames.map(f=>({url:f.canvas.toDataURL('image/jpeg',.25),name:f.name,time:f.time}));
 let next=0,done=0,videoJob=null;
 const startVideo=()=>{if(videoJob||!options.vision||!providers.has('twelvelabs'))return;videoJob=(async()=>{for(const file of files.filter(f=>f.type.startsWith('video/'))){if(expired())break;try{const r=await analyzeVideo(file,type,context,options.signal,onUpdate);data=fuseExtraction(data,r.data);attempts.push('twelvelabs');}catch(e){errors.push(e.message);}}})();};
 if(providers.has('twelvelabs')&&!providers.has('groq')&&!providers.has('ocrspace')&&!providers.has('google'))startVideo();
 const worker=async()=>{while(next<frames.length&&!expired()){
  const index=next;next+=2;const batch=frames.slice(index,index+2);
  onUpdate('Lendo '+done+'/'+frames.length+' telas · provedores disponíveis');
  try{
   if(options.vision&&[...providers].every(p=>disabled.has(p))){errors.push('Provedores indisponíveis: confira as chaves/cotas em Configurações.');next=frames.length;break;}
   if(options.vision&&providers.size){
    const images=batch.map(f=>{let quality=.82,url=f.canvas.toDataURL('image/jpeg',quality);while(url.length>1350000&&quality>.25){quality-=.1;url=f.canvas.toDataURL('image/jpeg',quality);}return {url,width:f.canvas.width,height:f.canvas.height};});
    const r=await apiRequest('analyze',{type,images,focusImages:batch.map((f,j)=>focusImage(f.canvas,type,index+j)).filter(Boolean),context,disabled:[...disabled],models:{groqVision:options.visionModel,groqText:options.model}},options.signal,23000);
    data=fuseExtraction(data,r.data);attempts.push(...r.attempts||[]);if(!r.coverage?.complete)startVideo();
    for(const f of r.failures||[]){errors.push(f.provider+': '+(f.message||f.error||'indisponível'));if([401,403,404,429].includes(f.status))disabled.add(f.provider);}
   }else if(!options.vision){
    for(const frame of batch){if(expired())break;onUpdate('OCR local: '+frame.name);const r=await localOCR(frame.canvas,{signal:options.signal});const raw=type==='match'?mergeMatchTexts([r.text]):type==='squad'?{players:parseSquadText(r.text)}:{calendar:parseCalendarText(r.text)};data=fuseExtraction(data,normalizeExtraction(raw,type,'OCR local'));attempts.push('OCR local');}
   }else errors.push('Nenhuma chave configurada.');
  }catch(e){errors.push(e.message);next=frames.length;}done+=batch.length;
 }};
 // Two batches in flight; every service request has its own deadline.
 await Promise.all(options.vision&&providers.size?[worker(),worker()]:[worker()]);
 if(!coverage(type,data).complete)startVideo();
 if(videoJob)await videoJob;
 if(expired())errors.push('Limite atingido ou cancelado: revise os dados parciais.');
 const quality=coverage(type,data);
 if(!quality.complete)errors.push('Leitura parcial: '+quality.missing.slice(0,12).join('; '));
 if(data.conflicts.length)errors.push(data.conflicts.length+' divergências entre leituras: confira os valores na mídia.');
 return {...data,files:[...files],type,previews,errors:[...new Set([...errors,...data.warnings])],mode:[...new Set(attempts)].join(' + ')||'Sem resposta',frames:frames.length,coverage:quality,elapsedMs:Date.now()-started,createdAt:new Date().toISOString()};
}

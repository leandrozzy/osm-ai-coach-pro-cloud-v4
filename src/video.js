import {extractFrames,imageToCanvas} from './frame-extractor.js';
import {distinctFrames,visualBatches} from './frame-selection.js';
import {prepareOcrImages} from './ocr-image.js';
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
 let quality=.85,url=c.toDataURL('image/jpeg',quality);while(url.length>240000&&quality>.15){quality-=.1;url=c.toDataURL('image/jpeg',quality);}return url.length<=250000?url:null;
}
function visualImage(source){
 let canvas=source,quality=.82,url=canvas.toDataURL('image/jpeg',quality);
 while(url.length>650000&&quality>.25){quality-=.1;url=canvas.toDataURL('image/jpeg',quality);}
 while(url.length>650000&&Math.max(canvas.width,canvas.height)>480){const smaller=document.createElement('canvas');smaller.width=Math.max(1,Math.round(canvas.width*.85));smaller.height=Math.max(1,Math.round(canvas.height*.85));smaller.getContext('2d').drawImage(canvas,0,0,smaller.width,smaller.height);canvas=smaller;url=canvas.toDataURL('image/jpeg',.65);}
 if(url.length>650000)throw Error('Tela muito grande para enviar; use um trecho ou imagem menor.');
 return {url,width:canvas.width,height:canvas.height};
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
  try{if(file.type.startsWith('video/'))frames.push(...distinctFrames(await extractFrames(file,{maxFrames:24,signal:options.signal}),{limit:type==='match'?10:8}).map(f=>({...f,name:file.name})));else if(file.type.startsWith('image/'))frames.push({canvas:await imageToCanvas(file),name:file.name,time:0});else errors.push('Formato não suportado: '+file.name);}catch(e){errors.push(e.message);}
 }
 if(!frames.length)throw Error('Nenhuma tela extraída.');
 const previews=frames.map(f=>({url:f.canvas.toDataURL('image/jpeg',.25),name:f.name,time:f.time}));
 let next=0,done=0,videoJob=null;const visualSlots=visualBatches(frames.length,3);
 const startVideo=()=>{if(videoJob||!options.vision||!providers.has('twelvelabs'))return;videoJob=(async()=>{for(const file of files.filter(f=>f.type.startsWith('video/'))){if(expired())break;try{const r=await analyzeVideo(file,type,context,options.signal,onUpdate,{frames:frames.filter(f=>f.name===file.name)});data=fuseExtraction(data,r.data);attempts.push('twelvelabs');if(r.transport==='sampled-video')errors.push('TwelveLabs leu um vídeo das '+r.sampledFrames+' telas selecionadas, mantendo a resolução. Confira as telas e os campos pendentes.');}catch(e){errors.push('TwelveLabs: '+e.message);}}})();};
 if(providers.has('twelvelabs')&&!providers.has('groq')&&!providers.has('ocrspace')&&!providers.has('google'))startVideo();
 const worker=async()=>{while(next<frames.length&&!expired()){
  const index=next;next+=2;const batch=frames.slice(index,index+2);
  onUpdate('Lendo '+done+'/'+frames.length+' telas · provedores disponíveis');
  try{
   if(options.vision&&[...providers].filter(p=>p!=='twelvelabs').every(p=>disabled.has(p))){startVideo();errors.push('Leitura por imagem indisponível: confira os avisos das APIs.');next=frames.length;break;}
   if(options.vision&&providers.size){
    const images=batch.map(f=>visualImage(f.canvas));
    const r=await apiRequest('analyze',{type,images,useVisual:visualSlots.has(Math.floor(index/2)),forceOCR:true,preferredProvider:disabled.has('google')?'groq':'google',ocrImages:batch.flatMap(f=>prepareOcrImages(f.canvas,type)).sort((a,b)=>(a.region==='full'?0:1)-(b.region==='full'?0:1)),focusImages:batch.map((f,j)=>focusImage(f.canvas,type,index+j)).filter(Boolean),context,disabled:[...disabled],models:{groqVision:options.visionModel,groqText:options.model}},options.signal,35000);
    data=fuseExtraction(data,r.data);attempts.push(...r.attempts||[]);if(!r.coverage?.complete)startVideo();
    for(const f of r.failures||[]){errors.push(f.provider+': '+(f.status===429?'Limite de cota atingido. Novas chamadas foram pausadas; aguarde a renovação do limite do provedor.':f.status===503?'Serviço sobrecarregado. A leitura continua com os outros serviços.':f.message||f.error||'indisponível'));if(f.provider==='groq'&&(f.code==='NO_SUPPORTED_MODEL'||f.status===404))disabled.add(f.stage==='text'?'groq-text':'groq-visual');else if([401,403,404,429,503].includes(f.status)||f.code==='PROVIDER_BUSY'||f.code==='PROVIDER_COOLDOWN')disabled.add(f.provider);}
   }else if(!options.vision){
    for(const frame of batch){if(expired())break;onUpdate('OCR local: '+frame.name);const r=await localOCR(frame.canvas,{signal:options.signal});const raw=type==='match'?mergeMatchTexts([r.text]):type==='squad'?{players:parseSquadText(r.text)}:{calendar:parseCalendarText(r.text)};data=fuseExtraction(data,normalizeExtraction(raw,type,'OCR local'));attempts.push('OCR local');}
   }else errors.push('Nenhuma chave configurada.');
  }catch(e){errors.push(e.message);next=frames.length;}done+=batch.length;
 }};
 // One batch at a time and at most three visual calls per analysis protect free API quotas.
 await worker();
 if(!coverage(type,data).complete)startVideo();
 if(videoJob)await videoJob;
 if(expired())errors.push('Limite atingido ou cancelado: revise os dados parciais.');
 const quality=coverage(type,data);
 if(!quality.complete)errors.push('Leitura parcial: '+quality.missing.slice(0,12).join('; '));
 if(data.conflicts.length)errors.push(data.conflicts.length+' divergências entre leituras: confira os valores na mídia.');
 return {...data,files:[...files],type,previews,errors:[...new Set([...errors,...data.warnings])],mode:[...new Set(attempts)].join(' + ')||'Sem resposta',frames:frames.length,coverage:quality,elapsedMs:Date.now()-started,createdAt:new Date().toISOString()};
}

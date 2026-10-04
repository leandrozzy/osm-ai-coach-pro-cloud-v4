import {extractFrames,extractAdditionalFrames,imageToCanvas} from './frame-extractor.js';
import {visualBatches} from './frame-selection.js';
import {recoverOriginalFrames} from './frame-recovery.js';
import {videoRequired,disableFailedProvider} from './reading-policy.js';
import {matchFields} from './domain.js';
import {applyScreenEvidence} from './visual-evidence.js';
import {refineSquadRoster} from './squad-roster.js';
import {sanitizeProviderMatch} from './match-grounding.js';
import {prepareOcrImages} from './ocr-image.js';
import {readLocalScreen} from './local-reader.js';
import {apiRequest,apiStatus,sessionProviders} from './ai-router.js';
import {analyzeVideo} from './twelvelabs.js';
import {blankExtraction,fuseExtraction,coverage} from './extraction.js';
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
export function batchOcrImages(batch,type){
 const prepared=batch.flatMap((frame,frameIndex)=>prepareOcrImages(frame.canvas,type).map(image=>({...image,frameIndex,kind:frame.layout?.kind||'unknown'})));
 const full=prepared.filter(image=>image.region==='full');
 const report=prepared.filter(image=>image.region==='report'&&!['roster-header','roster-list','comparison'].includes(image.kind));
 const priority=image=>image.kind==='report-details'||image.kind==='report-field'?2:image.kind==='report-cover'?1:0;
 report.sort((a,b)=>priority(b)-priority(a));
 if(type==='match'&&report.some(image=>priority(image)>0)){
  const first=report[0],paired=report.filter(image=>image.frameIndex===first.frameIndex).slice(0,2);
  // Preserve a complete reference for the report and one club header, and
  // read both report panels. Three full frames must not crowd out the tactics.
  const references=[...full.filter(image=>image.frameIndex===first.frameIndex),...full.filter(image=>image.frameIndex!==first.frameIndex)].slice(0,4-paired.length);
  return [...references,...paired].map(({kind,...image})=>image);
 }
 return [...full,...report].slice(0,type==='match'?4:2).map(({kind,...image})=>image);
}
export async function analyzeMedia(files,type,options={},onUpdate=()=>{}){
 if(files.length>24||files.some(f=>f.size>150*1024*1024))throw Error('Máximo 24 arquivos, 150 MB por arquivo.');
 const started=Date.now(),limit=options.profile==='complete'?180000:90000,frames=[],reserves=[],errors=[],attempts=[],rosterEvidence=[],readingTeams=[],disabled=new Set();let data=blankExtraction();
 const localEnabled=options.localRecovery===true||!options.vision,localReadFrames=new Set();let localAvailable=localEnabled;
 const userSignal=options.signal,deadlineController=new AbortController(),cancel=()=>deadlineController.abort();
 let videoController=null;
 const stopVideo=()=>videoController?.abort();
 if(userSignal?.aborted)cancel();else userSignal?.addEventListener('abort',cancel,{once:true});
 const deadlineTimer=setTimeout(cancel,limit);options={...options,signal:deadlineController.signal};
 try{
 const expired=()=>options.signal?.aborted||Date.now()-started>=limit;
 const context={username:options.username,myTeam:options.myTeam||'NI',rivalName:options.rivalName,competitionType:options.competitionType};
 const statusRequest=options.vision?apiStatus(options.signal):Promise.resolve(null),providers=new Set(sessionProviders());
 const configured=async()=>{const status=await statusRequest;for(const [provider,value] of Object.entries(status?.providers||{}))if(value===true||value?.configured)providers.add(provider);};
 if(!localEnabled)await configured();
 if(options.vision&&!providers.size&&!localEnabled)throw Error('Nenhuma chave detectada. Abra Configurações, salve uma chave Google/Groq/OCR.space/TwelveLabs e tente novamente.');
 onUpdate(localEnabled?'Preparando leitura local das telas…':'APIs detectadas: '+[...providers].join(', '));
 for(const file of files){
  if(expired())break;onUpdate('Preparando '+file.name);
  try{if(file.type.startsWith('video/')){const selected=await extractFrames(file,{type,profile:options.profile,signal:options.signal});const fileIndex=files.indexOf(file);frames.push(...selected.map(f=>({...f,name:file.name,fileIndex})));if(selected.omittedFrames?.length)reserves.push({file,fileIndex,probes:selected.omittedFrames});}else if(file.type.startsWith('image/'))frames.push({canvas:await imageToCanvas(file),name:file.name,fileIndex:files.indexOf(file),time:0});else errors.push('Formato não suportado: '+file.name);}catch(e){errors.push(e.message);}
 }
 if(!frames.length)throw Error(options.signal.aborted?(userSignal?.aborted?'Leitura cancelada.':'Tempo limite ao preparar as telas. Tente um trecho menor; os dados salvos foram preservados.'):'Nenhuma tela extraída.');
 const previews=frames.map(f=>({url:f.canvas.toDataURL('image/jpeg',.25),name:f.name,time:f.time}));
 const captureEvidence=(response,batch)=>{
  for(const proof of response.teamEvidence||[])if(proof.verified===true&&typeof proof.team==='string'&&proof.team.trim()&&proof.team!=='NI')readingTeams.push(proof.team.trim());
  if(type==='squad')for(const evidence of response.squadEvidence||[]){const frame=batch[evidence.frameIndex];if(frame)rosterEvidence.push(...(evidence.players||[]).map(row=>({...row,width:evidence.width,height:evidence.height,frameId:frame.name+':'+frame.time})));}
 };
 const localScreen=async frame=>{
  if(!localAvailable||expired()||localReadFrames.has(frame))return;
  localReadFrames.add(frame);
  onUpdate('Leitura local '+localReadFrames.size+'/'+frames.length+' · '+frame.name);
  try{
   const remaining=limit-(Date.now()-started),budgetMs=Math.min(localReadFrames.size===1?35000:15000,remaining);
   const read=await readLocalScreen(frame.canvas,type,{signal:options.signal,context,layout:frame.layout,budgetMs,onUpdate});
   data=fuseExtraction(data,read.data);captureEvidence(read.response||{},[frame]);
   const checked=applyScreenEvidence(data,read.response||{},[frame],type);data=checked.data;
   attempts.push('OCR local estruturado',...checked.used);errors.push(...read.warnings||[]);
  }catch(e){
   errors.push('OCR local: '+e.message);
   if(/carregar|download|inicializ|indispon[ií]vel|motor|bootstrap/i.test(e.message)||['OCR_INIT','OCR_UNAVAILABLE'].includes(e.code))localAvailable=false;
  }
 };
 // Initialize and read the first original screen before using the providers.
 // The remaining local screens can run while cloud requests are pending.
 if(localEnabled)await localScreen(frames[0]);
 if(localEnabled)await configured();
 const batchSize=type==='match'?3:2;
 let next=0,done=0,videoJob=null;const visualSlots=visualBatches(frames.length,3,{batchSize});
 const visualAvailable=()=>providers.has('google')&&!disabled.has('google')||providers.has('groq')&&!disabled.has('groq')&&!disabled.has('groq-visual');
 const startVideo=()=>{
  const remaining=limit-(Date.now()-started);
  if(videoJob||!options.vision||!providers.has('twelvelabs')||!videoRequired(type,data)||remaining<5000||expired())return;
  videoController=new AbortController();options.signal?.addEventListener('abort',stopVideo,{once:true});
  videoJob=(async()=>{
   for(const file of files.filter(f=>f.type.startsWith('video/'))){
    if(expired()||videoController.signal.aborted||!videoRequired(type,data))break;
    try{
     const budgetMs=Math.min(50000,limit-(Date.now()-started));
     const missing=coverage(type,data).missing;
     const targetFields=type==='match'?matchFields.filter(([key,label])=>missing.some(field=>field===label||field.startsWith(label+' ('))||(data.conflicts||[]).some(conflict=>conflict.field==='match.'+key&&!conflict.resolved)).map(([key])=>key):[...new Set(missing.map(field=>field.includes(': ')?field.split(': ').at(-1):field==='Início da lista não confirmado'?'meta.sawTop':field==='Fim da lista não confirmado'?'meta.sawBottom':'').filter(field=>/^[A-Za-z][A-Za-z0-9.]{0,50}$/.test(field)))];
     const r=await analyzeVideo(file,type,{...context,targetFields},videoController.signal,onUpdate,{frames:frames.filter(f=>f.fileIndex===files.indexOf(file)&&f.canvas.width>0),budgetMs});
     data=fuseExtraction(data,type==='match'?sanitizeProviderMatch(r.data,context):r.data);attempts.push('twelvelabs');
    }catch(e){if(!videoController.signal.aborted)errors.push('TwelveLabs: '+e.message);break;}
   }
  })();
 };
 if(providers.has('twelvelabs')&&!providers.has('groq')&&!providers.has('ocrspace')&&!providers.has('google'))startVideo();
 const worker=async(additional=false)=>{while(next<frames.length&&!expired()){
  if(coverage(type,data).complete)break;
  const index=next;next+=batchSize;const batch=frames.slice(index,index+batchSize);
  onUpdate('Lendo '+done+'/'+frames.length+' telas · provedores disponíveis');
  try{
   if(!options.vision||!providers.size){done+=batch.length;continue;}
   if([...providers].filter(p=>p!=='twelvelabs').every(p=>disabled.has(p))){startVideo();if(!localEnabled)errors.push('Leitura por imagem indisponível: confira os avisos das APIs.');next=frames.length;break;}
   if(options.vision&&providers.size){
    const images=batch.map(f=>visualImage(f.canvas));
    const r=await apiRequest('analyze',{type,images,useVisual:!additional&&visualSlots.has(Math.floor(index/batchSize)),useText:!additional,forceOCR:true,preferredProvider:disabled.has('google')?'groq':'google',ocrImages:batchOcrImages(batch,type),focusImages:additional?[]:batch.slice(0,2).map((f,j)=>focusImage(f.canvas,type,index+j)).filter(Boolean),context,disabled:[...disabled],models:{groqVision:options.visionModel,groqText:options.model}},options.signal,Math.max(1000,Math.min(35000,limit-(Date.now()-started))));
    data=fuseExtraction(data,type==='match'?sanitizeProviderMatch(r.data,context):r.data);attempts.push(...r.attempts||[]);
    captureEvidence(r,batch);

    const local=applyScreenEvidence(data,r,batch,type);data=local.data;attempts.push(...local.used);
    for(const f of r.failures||[]){errors.push(f.provider+': '+(f.status===429?'Limite de cota atingido. Novas chamadas foram pausadas; aguarde a renovação do limite do provedor.':f.status===503?'Serviço sobrecarregado. A leitura continua com os outros serviços.':f.message||f.error||'indisponível'));disableFailedProvider(disabled,f);}
    if(videoJob&&!videoRequired(type,data))stopVideo();else if(!additional&&!visualAvailable())startVideo();
   }
  }catch(e){errors.push(e.message);next=frames.length;}done+=batch.length;
  if(additional&&(!options.vision||!providers.has('twelvelabs')))for(const frame of batch){frame.canvas.width=0;frame.canvas.height=0;}
 }};
 // At most three visual batches; a failed provider can use one bounded fallback
 // on the same screens so the first club headers are not lost.
 const localJob=async()=>{if(localEnabled)for(const frame of frames){if(expired())break;await localScreen(frame);}};
 const cloudJob=async()=>{if(options.vision&&providers.size&&!coverage(type,data).complete)await worker();};
 const readings=await Promise.allSettled([localJob(),cloudJob()]);
 for(const reading of readings)if(reading.status==='rejected')errors.push(reading.reason?.message||'Falha ao completar a leitura.');
 // Revisit retained original pauses in small batches, refreshing the missing
 // fields after each read. Local and cloud readers share the same canvases.
 const recovery=await recoverOriginalFrames({
  reserves,type,signal:options.signal,getMissing:()=>{
   const missing=coverage(type,data).missing;
   if(type==='match')for(const [field,label] of matchFields)if((data.conflicts||[]).some(conflict=>conflict.field==='match.'+field&&!conflict.resolved)&&!missing.includes(label))missing.push(label+' (divergência)');
   return missing;
  },
  excludeTimes:fileIndex=>frames.filter(frame=>frame.fileIndex===fileIndex).map(frame=>frame.time),
  remainingMs:()=>localAvailable||options.vision&&providers.has('ocrspace')&&!disabled.has('ocrspace')?limit-(Date.now()-started):0,
  decode:(reserve,probes)=>{
   onUpdate('Completando campos pendentes nas telas originais…');
   return extractAdditionalFrames(reserve.file,{probes,signal:options.signal,maxFrames:probes.length});
  },
  onFrames:extras=>{
   next=frames.length;frames.push(...extras);
   previews.push(...extras.map(frame=>({url:frame.canvas.toDataURL('image/jpeg',.25),name:frame.name,time:frame.time})));
  },
  readBatch:async extras=>{
   try{
    for(const frame of extras)await localScreen(frame);
    if(options.vision&&providers.has('ocrspace')&&!disabled.has('ocrspace'))await worker(true);
   }finally{
    // A later video fallback also needs these newly recovered original panels.
    // Keep at most eight extras until the global finally when it is configured.
    if(!options.vision||!providers.has('twelvelabs'))for(const frame of extras){frame.canvas.width=0;frame.canvas.height=0;}
   }
  },
  onError:error=>errors.push(error.message)
 });
 if(videoRequired(type,data))startVideo();else stopVideo();
 if(videoJob)await videoJob;
 if(expired())errors.push('Limite atingido ou cancelado: revise os dados parciais.');
 if(type==='squad'){const checked=refineSquadRoster(data,rosterEvidence);data=checked.data;data.warnings.push(...checked.warnings);}
 const quality=coverage(type,data);
 if(!quality.complete&&recovery.remainingRelevant)errors.push('Há pausas adicionais no vídeo. Use Completar leitura para os campos ainda pendentes.');
 if(!quality.complete)errors.push('Leitura parcial: '+quality.missing.slice(0,12).join('; '));
 const unresolved=data.conflicts.filter(c=>!c.resolved);if(unresolved.length)errors.push(unresolved.length+' divergências entre leituras: confira os valores na mídia.');
 return {...data,rosterEvidence,readingTeams:[...new Set(readingTeams)],files:[...files],type,previews,errors:[...new Set([...errors,...data.warnings])],mode:[...new Set(attempts)].join(' + ')||'Sem resposta',frames:frames.length,coverage:quality,elapsedMs:Date.now()-started,createdAt:new Date().toISOString()};
 }finally{
  clearTimeout(deadlineTimer);userSignal?.removeEventListener('abort',cancel);
  options.signal?.removeEventListener('abort',stopVideo);stopVideo();
  for(const frame of frames){frame.canvas.width=0;frame.canvas.height=0;}
 }
}

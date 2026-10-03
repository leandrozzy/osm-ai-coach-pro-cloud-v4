import {apiRequest} from './ai-router.js';
import {repairWebmDuration} from './webm-duration.js';

// JSON/base64 adds about one third; leave room below the Vercel 4.5 MB body limit.
const MAX_VIDEO_BYTES=3000000;
const abortError=()=>Object.assign(Error('Envio TwelveLabs cancelado.'),{code:'ABORTED'});
const checkAbort=signal=>{if(signal?.aborted)throw abortError();};
const deadlineError=()=>Object.assign(Error('TwelveLabs não concluiu dentro do prazo desta leitura; os dados já reconhecidos foram preservados.'),{code:'TWELVE_DEADLINE'});
function lifecycle(signal,budgetMs){
 const budget=Number.isFinite(budgetMs)?Math.min(90000,Math.max(1000,budgetMs)):50000,until=Date.now()+budget;
 return {
  remaining(){checkAbort(signal);const left=until-Date.now();if(left<=0)throw deadlineError();return left;},
  request(body,maximum=12000){const left=this.remaining();return apiRequest('twelvelabs',body,signal,Math.max(1,Math.min(maximum,left)));}
 };
}
function pause(ms,signal){
 return new Promise((resolve,reject)=>{
  if(signal?.aborted){reject(abortError());return;}
  const done=()=>{clearTimeout(timer);signal?.removeEventListener('abort',cancel);resolve();};
  const cancel=()=>{clearTimeout(timer);signal?.removeEventListener('abort',cancel);reject(abortError());};
  const timer=setTimeout(done,ms);signal?.addEventListener('abort',cancel,{once:true});
 });
}
function base64(file,signal){
 return new Promise((resolve,reject)=>{
  checkAbort(signal);const reader=new FileReader();
  const cleanup=()=>signal?.removeEventListener('abort',cancel);
  const cancel=()=>{reader.abort();cleanup();reject(abortError());};
  reader.onload=()=>{cleanup();resolve(String(reader.result).split(',')[1]);};
  reader.onerror=()=>{cleanup();reject(Error('Não foi possível preparar o vídeo.'));};
  reader.onabort=()=>{cleanup();reject(abortError());};
  signal?.addEventListener('abort',cancel,{once:true});reader.readAsDataURL(file);
 });
}

export async function compactFrames(frames,signal,onUpdate=()=>{}){
 checkAbort(signal);
 const selected=frames.filter(f=>f?.canvas?.width>0&&f.canvas.height>0);
 if(!selected.length)throw Error('TwelveLabs precisa das telas extraídas para preparar um vídeo menor.');
 if(typeof MediaRecorder==='undefined')throw Error('Este navegador não consegue preparar o vídeo para TwelveLabs. Use Google/OCR.space ou um vídeo de até 3 MB.');
 const canvas=document.createElement('canvas');canvas.width=selected[0].canvas.width;canvas.height=selected[0].canvas.height;
 if(typeof canvas.captureStream!=='function')throw Error('Captura de vídeo não disponível. Use Google/OCR.space ou um vídeo de até 3 MB.');
 const mimeType=['video/webm;codecs=vp9','video/webm;codecs=vp8','video/mp4','video/webm'].find(m=>MediaRecorder.isTypeSupported(m));
 if(!mimeType)throw Error('Formato de vídeo não suportado neste navegador. Use Google/OCR.space ou um vídeo de até 3 MB.');
 const ctx=canvas.getContext('2d');ctx.drawImage(selected[0].canvas,0,0,canvas.width,canvas.height);
 const stream=canvas.captureStream(2),tracks=stream.getTracks(),parts=[];
 // Keep native text dimensions. These are the existing sampled screens in sequence.
 const count=Math.max(8,selected.length),duration=count*.5;
 const bitsPerSecond=Math.min(2000000,Math.floor(2600000*8/duration));
 let recorder,recordingError,recordingBytes=0,recordingTimer;
 try{
  recorder=new MediaRecorder(stream,{mimeType,videoBitsPerSecond:bitsPerSecond});
  let finish;
  const finished=new Promise(resolve=>{finish=resolve;});
  recorder.ondataavailable=e=>{if(e.data?.size){parts.push(e.data);recordingBytes+=e.data.size;if(recordingBytes>MAX_VIDEO_BYTES){recordingError=Error('Vídeo preparado acima de 3 MB; a leitura pelas outras APIs foi preservada.');if(recorder.state!=='inactive')recorder.stop();}}};
  recorder.onerror=()=>{recordingError=Error('Não foi possível preparar o vídeo para TwelveLabs.');finish();};
  recorder.onstop=()=>finish();
  recordingTimer=setTimeout(()=>{recordingError=Error('Tempo limite ao preparar vídeo para TwelveLabs.');if(recorder.state!=='inactive')recorder.stop();finish();},duration*1000+6000);
  const cancel=()=>{recordingError=abortError();if(recorder.state!=='inactive')recorder.stop();};
  signal?.addEventListener('abort',cancel,{once:true});
  try{
   recorder.start(250);
   for(let i=0;i<count;i++){
    checkAbort(signal);if(recordingError)throw recordingError;
    const frame=selected[Math.min(i,selected.length-1)].canvas;
    ctx.clearRect(0,0,canvas.width,canvas.height);ctx.drawImage(frame,0,0,canvas.width,canvas.height);
    tracks[0]?.requestFrame?.();
    onUpdate('TwelveLabs: preparando telas '+Math.min(i+1,selected.length)+'/'+selected.length);
    await pause(500,signal);
   }
   if(recorder.state!=='inactive')recorder.stop();
   await finished;checkAbort(signal);if(recordingError)throw recordingError;
   let clip=new Blob(parts,{type:mimeType.split(';')[0]});
   if(!clip.size||clip.size>MAX_VIDEO_BYTES)throw Error('Não foi possível preparar vídeo de até 3 MB; a leitura das outras APIs foi preservada.');
   if(clip.type==='video/webm'){
    onUpdate('TwelveLabs: conferindo duração do vídeo');
    clip=await repairWebmDuration(clip,duration,signal);checkAbort(signal);
    if(clip.size>MAX_VIDEO_BYTES)throw Error('Vídeo preparado acima de 3 MB; a leitura pelas outras APIs foi preservada.');
   }
   return clip;
  }finally{signal?.removeEventListener('abort',cancel);}
 }finally{
  clearTimeout(recordingTimer);
  if(recorder?.state&&recorder.state!=='inactive')recorder.stop();
  for(const track of tracks)track.stop();
 }
}

async function analyzeAsset(video,type,context,job,onUpdate){
 // The provider's analysis is synchronous. Give it the time remaining in this
 // lifecycle, with transport headroom, rather than restarting a 20 s clock at
 // every upload, preparation and inference stage.
 const left=job.remaining();if(left<2000)throw deadlineError();
 const budgetMs=Math.min(45000,Math.floor(left-1500));
 onUpdate('TwelveLabs: analisando vídeo · leitura por imagem continua');
 return job.request({action:'analyze',type,context,...video,budgetMs},budgetMs+1500);
}

async function analyzeClip(clip,type,context,signal,onUpdate,job){
 checkAbort(signal);onUpdate('TwelveLabs: enviando vídeo de '+(clip.size/1000000).toFixed(1)+' MB');
 const videoBase64=await base64(clip,signal);job.remaining();
 return analyzeAsset({videoBase64},type,context,job,onUpdate);
}

export async function analyzeVideo(file,type,context,signal,onUpdate=()=>{},{frames=[],budgetMs=50000,assetId}={}){
 checkAbort(signal);const job=lifecycle(signal,budgetMs);
 if(assetId){if(!/^[A-Za-z0-9_-]{8,100}$/.test(assetId))throw Error('Asset TwelveLabs inválido.');return analyzeAsset({assetId},type,context,job,onUpdate);}
 if(file.size<=MAX_VIDEO_BYTES)return analyzeClip(file,type,context,signal,onUpdate,job);
 // Large S3 parts cannot pass through Vercel. Selected screens fit in a compact
 // video and use the same-origin API, avoiding browser S3 CORS and hidden ETags.
 if(frames.length){
  if(job.remaining()<Math.max(8,frames.length)*500+2500)throw deadlineError();
  const clip=await compactFrames(frames,signal,onUpdate);
  job.remaining();
  return {...await analyzeClip(clip,type,context,signal,onUpdate,job),transport:'sampled-video',sampledFrames:frames.length};
 }
 onUpdate('TwelveLabs: preparando upload original');
 const u=await job.request({action:'create',filename:file.name,size:file.size});
 if(!u.upload_id||!u.asset_id||!u.chunk_size||!u.total_chunks)throw Error('Upload TwelveLabs incompleto.');
 const chunks=[];
 for(let index=1;index<=u.total_chunks;index++){
  checkAbort(signal);const blob=file.slice((index-1)*u.chunk_size,index*u.chunk_size);
  if(blob.size>MAX_VIDEO_BYTES)throw Error('TwelveLabs exige blocos maiores que o limite do servidor. Reanalise a mídia para preparar um vídeo das telas, ou use Google/OCR.space.');
  onUpdate('TwelveLabs: enviando bloco '+index+'/'+u.total_chunks);
  // The server obtains this exact part URL from TwelveLabs using this key. The
  // browser never PUTs to S3, and no caller-supplied URL is fetched by the server.
  const chunkBase64=await base64(blob,signal);
  const result=await job.request({action:'chunk',uploadId:u.upload_id,index,chunkBase64},25000);
  if(!result.chunk)throw Error('Confirmação do bloco TwelveLabs ausente.');chunks.push(result.chunk);
 }
 await job.request({action:'report',uploadId:u.upload_id,chunks});
 const until=Date.now()+Math.min(20000,Math.max(0,job.remaining()-2500));
 while(Date.now()<until){
  const asset=await job.request({action:'status',assetId:u.asset_id},9000);
  if(asset.status==='ready')return analyzeAsset({assetId:u.asset_id},type,context,job,onUpdate);
  if(asset.status==='failed')throw Error('Vídeo não processado pelo TwelveLabs.');
  onUpdate('TwelveLabs: aguardando preparação do vídeo');await pause(Math.min(1000,job.remaining()),signal);
 }
 const pending=Object.assign(Error('TwelveLabs ainda está preparando o vídeo; dados parciais preservados.'),{code:'TWELVE_PROCESSING',resume:{assetId:u.asset_id}});
 throw pending;
}

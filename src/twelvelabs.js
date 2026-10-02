import {apiRequest} from './ai-router.js';
import {repairWebmDuration} from './webm-duration.js';

// JSON/base64 adds about one third; leave room below the Vercel 4.5 MB body limit.
const MAX_VIDEO_BYTES=3000000;
const abortError=()=>Object.assign(Error('Envio TwelveLabs cancelado.'),{code:'ABORTED'});
const checkAbort=signal=>{if(signal?.aborted)throw abortError();};
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

async function analyzeClip(clip,type,context,signal,onUpdate){
 checkAbort(signal);onUpdate('TwelveLabs: analisando vídeo de '+(clip.size/1000000).toFixed(1)+' MB');
 return apiRequest('twelvelabs',{action:'analyze',type,context,videoBase64:await base64(clip,signal)},signal,23000);
}

export async function analyzeVideo(file,type,context,signal,onUpdate=()=>{},{frames=[]}={}){
 checkAbort(signal);
 if(file.size<=MAX_VIDEO_BYTES)return analyzeClip(file,type,context,signal,onUpdate);
 // Large S3 parts cannot pass through Vercel. Selected screens fit in a compact
 // video and use the same-origin API, avoiding browser S3 CORS and hidden ETags.
 if(frames.length){
  const clip=await compactFrames(frames,signal,onUpdate);
  return {...await analyzeClip(clip,type,context,signal,onUpdate),transport:'sampled-video',sampledFrames:frames.length};
 }
 onUpdate('TwelveLabs: preparando upload original');
 const u=await apiRequest('twelvelabs',{action:'create',filename:file.name,size:file.size},signal,12000);
 if(!u.upload_id||!u.asset_id||!u.chunk_size||!u.total_chunks)throw Error('Upload TwelveLabs incompleto.');
 const chunks=[];
 for(let index=1;index<=u.total_chunks;index++){
  checkAbort(signal);const blob=file.slice((index-1)*u.chunk_size,index*u.chunk_size);
  if(blob.size>MAX_VIDEO_BYTES)throw Error('TwelveLabs exige blocos maiores que o limite do servidor. Reanalise a mídia para preparar um vídeo das telas, ou use Google/OCR.space.');
  onUpdate('TwelveLabs: enviando bloco '+index+'/'+u.total_chunks);
  // The server obtains this exact part URL from TwelveLabs using this key. The
  // browser never PUTs to S3, and no caller-supplied URL is fetched by the server.
  const result=await apiRequest('twelvelabs',{action:'chunk',uploadId:u.upload_id,index,chunkBase64:await base64(blob,signal)},signal,25000);
  if(!result.chunk)throw Error('Confirmação do bloco TwelveLabs ausente.');chunks.push(result.chunk);
 }
 await apiRequest('twelvelabs',{action:'report',uploadId:u.upload_id,chunks},signal,12000);
 const until=Date.now()+20000;
 while(Date.now()<until){
  checkAbort(signal);const asset=await apiRequest('twelvelabs',{action:'status',assetId:u.asset_id},signal,9000);
  if(asset.status==='ready')return apiRequest('twelvelabs',{action:'analyze',type,context,assetId:u.asset_id},signal,23000);
  if(asset.status==='failed')throw Error('Vídeo não processado pelo TwelveLabs.');
  onUpdate('TwelveLabs: aguardando preparação do vídeo');await pause(1000,signal);
 }
 throw Error('TwelveLabs ainda processando; dados parciais preservados.');
}

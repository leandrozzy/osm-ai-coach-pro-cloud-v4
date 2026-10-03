import {dhash} from './frame-dedup.js';
import {frameFeatures,selectAnalysisFrames} from './frame-selection.js';

function aborted(){const e=new Error('Leitura cancelada.');e.name='AbortError';return e;}
function waitEvent(target,event,{timeoutMs=8000,signal,action}={}){
 return new Promise((resolve,reject)=>{
  let timer;
  const cleanup=()=>{clearTimeout(timer);target.removeEventListener(event,ok);target.removeEventListener('error',bad);signal?.removeEventListener('abort',cancel);};
  const ok=()=>{cleanup();resolve();},bad=()=>{cleanup();reject(target.error||new Error('Falha em '+event));},cancel=()=>{cleanup();reject(aborted());};
  if(signal?.aborted){reject(aborted());return;}
  target.addEventListener(event,ok,{once:true});target.addEventListener('error',bad,{once:true});signal?.addEventListener('abort',cancel,{once:true});
  timer=setTimeout(()=>{cleanup();reject(new Error('Tempo limite aguardando '+event));},timeoutMs);
  try{action?.();}catch(error){cleanup();reject(error);}
 });
}
async function seek(video,time,signal){
 if(signal?.aborted)throw aborted();
 if(Math.abs(video.currentTime-time)<.002&&video.readyState>=2)return;
 await waitEvent(video,'seeked',{timeoutMs:6000,signal,action:()=>{video.currentTime=time;}});
}
function canvasFor(video,maxWidth){
 const c=document.createElement('canvas'),width=video.videoWidth||720,height=video.videoHeight||1280,scale=Math.min(1,maxWidth/Math.max(width,height));
 c.width=Math.max(1,Math.round(width*scale));c.height=Math.max(1,Math.round(height*scale));
 c.getContext('2d',{willReadFrequently:true}).drawImage(video,0,0,c.width,c.height);return c;
}
export async function extractFrames(file,{maxFrames,profile='fast',type='match',candidates=60,signal,onProbe}={}){
 const video=document.createElement('video');video.muted=true;video.playsInline=true;video.preload='metadata';
 const objectUrl=URL.createObjectURL(file),frames=[];
 try{
  await waitEvent(video,'loadedmetadata',{timeoutMs:10000,signal,action:()=>{video.src=objectUrl;}});
  const duration=Number.isFinite(video.duration)&&video.duration>0?video.duration:1;
  const targetCount=Math.min(candidates,Math.max(8,Math.ceil(duration/.5))),probes=[];
  // First pass retains only tiny fingerprints, never sixty full resolution canvases.
  for(let i=0;i<targetCount;i++){
   if(signal?.aborted)throw aborted();
   const time=Math.max(.01,Math.min(Math.max(.01,duration-.03),(duration-.03)*i/Math.max(1,targetCount-1)));
   await seek(video,time,signal);
   const thumbnail=canvasFor(video,384);const features=frameFeatures(thumbnail,{type});thumbnail.width=0;thumbnail.height=0;
   probes.push({time,...features});onProbe?.({done:i+1,total:targetCount});
  }
  const selected=selectAnalysisFrames(probes,{type,profile,limit:maxFrames});
  // Second pass decodes only selected scenes at their original readable resolution.
  for(const probe of selected){
   if(signal?.aborted)throw aborted();
   await seek(video,probe.time,signal);
   const canvas=canvasFor(video,2448);frames.push({...probe,canvas,hash:dhash(canvas)});
  }
  Object.defineProperty(frames,'omittedFrames',{value:selected.omittedFrames,enumerable:false});
  Object.defineProperty(frames,'selection',{value:selected.selection,enumerable:false});return frames;
 }catch(error){
  for(const frame of frames){frame.canvas.width=0;frame.canvas.height=0;}
  throw error;
 }finally{
  URL.revokeObjectURL(objectUrl);video.removeAttribute('src');video.load?.();
 }
}
export async function extractAdditionalFrames(file,{probes=[],signal,maxFrames=4}={}){
 const requested=probes.filter((probe,index)=>Number.isFinite(Number(probe.time))&&!probes.slice(0,index).some(other=>Math.abs(Number(other.time)-Number(probe.time))<.04)).slice(0,Math.max(1,Math.min(4,Math.floor(Number(maxFrames)||4))));
 if(!requested.length)return [];
 const video=document.createElement('video');video.muted=true;video.playsInline=true;video.preload='metadata';
 const objectUrl=URL.createObjectURL(file),frames=[];
 try{
  await waitEvent(video,'loadedmetadata',{timeoutMs:10000,signal,action:()=>{video.src=objectUrl;}});
  const duration=Number.isFinite(video.duration)&&video.duration>0?video.duration:1;
  for(const probe of requested){
   if(signal?.aborted)throw aborted();
   const time=Math.max(.01,Math.min(Math.max(.01,duration-.03),Number(probe.time)));
   await seek(video,time,signal);
   const canvas=canvasFor(video,2448);frames.push({...probe,time,canvas,hash:dhash(canvas)});
  }
  return frames;
 }catch(error){
  for(const frame of frames){frame.canvas.width=0;frame.canvas.height=0;}
  throw error;
 }finally{
  URL.revokeObjectURL(objectUrl);video.removeAttribute('src');video.load?.();
 }
}
export async function imageToCanvas(file){
 const img=await createImageBitmap(file);
 try{
  const c=document.createElement('canvas'),scale=Math.min(1,2448/Math.max(img.width,img.height));
  c.width=Math.max(1,Math.round(img.width*scale));c.height=Math.max(1,Math.round(img.height*scale));
  c.getContext('2d',{willReadFrequently:true}).drawImage(img,0,0,c.width,c.height);return c;
 }finally{img.close();}
}

import {dhash} from './frame-dedup.js';

function waitEvent(target,event,timeoutMs=8000){
  return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{cleanup();reject(new Error(`Timeout aguardando ${event}`));},timeoutMs);
    const ok=()=>{cleanup();resolve();};
    const bad=()=>{cleanup();reject(target.error||new Error(`Falha em ${event}`));};
    const cleanup=()=>{clearTimeout(timer);target.removeEventListener(event,ok);target.removeEventListener('error',bad);};
    target.addEventListener(event,ok,{once:true});
    target.addEventListener('error',bad,{once:true});
  });
}

function noveltySelect(frames,maxFrames=24){
 if(frames.length<=maxFrames)return frames;
 return Array.from({length:maxFrames},(_,i)=>frames[Math.round(i*(frames.length-1)/(maxFrames-1))]);
}

export async function extractFrames(file,{maxFrames=24,candidates=60,signal}={}){
  const video=document.createElement('video');
  video.muted=true;video.playsInline=true;video.preload='metadata';
  const objectUrl=URL.createObjectURL(file);video.src=objectUrl;
  try{
    await waitEvent(video,'loadedmetadata',10000);
    const duration=Number.isFinite(video.duration)&&video.duration>0?video.duration:1;
    const targetCount=Math.min(candidates,Math.max(8,Math.ceil(duration/.5)));
    const frames=[];
    for(let i=0;i<targetCount;i++){
      if(signal?.aborted)break;
      const time=targetCount===1?0.01:Math.max(0.01,Math.min(duration-.03,(duration-.03)*i/(targetCount-1)));
      if(Math.abs(video.currentTime-time)>.001){
        video.currentTime=time;
        await waitEvent(video,'seeked',6000);
      }
      const c=document.createElement('canvas');
      const width=video.videoWidth||720,height=video.videoHeight||1280;
      const scale=Math.min(1,2448/Math.max(width,height));
      c.width=Math.max(1,Math.round(width*scale));
      c.height=Math.max(1,Math.round(height*scale));
      c.getContext('2d',{willReadFrequently:true}).drawImage(video,0,0,c.width,c.height);
      const hash=dhash(c);
      frames.push({time,canvas:c,hash});
    }
    return noveltySelect(frames,maxFrames);
  } finally {
    URL.revokeObjectURL(objectUrl);
    video.removeAttribute('src');
  }
}

export async function imageToCanvas(file){
  const img=await createImageBitmap(file);
  try{
    const c=document.createElement('canvas');
    const scale=Math.min(1,2448/Math.max(img.width,img.height));
    c.width=Math.max(1,Math.round(img.width*scale));
    c.height=Math.max(1,Math.round(img.height*scale));
    c.getContext('2d',{willReadFrequently:true}).drawImage(img,0,0,c.width,c.height);
    return c;
  } finally { img.close(); }
}

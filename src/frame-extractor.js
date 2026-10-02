import {dhash,uniqueFrames} from './frame-dedup.js';

function waitEvent(target, event, timeoutMs=8000){
  return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{cleanup();reject(new Error(`Timeout aguardando ${event}`));},timeoutMs);
    const ok=()=>{cleanup();resolve();};
    const bad=()=>{cleanup();reject(target.error||new Error(`Falha em ${event}`));};
    const cleanup=()=>{clearTimeout(timer);target.removeEventListener(event,ok);target.removeEventListener('error',bad);};
    target.addEventListener(event,ok,{once:true});target.addEventListener('error',bad,{once:true});
  });
}

export async function extractFrames(file,{maxFrames=54,minInterval=.5}={}){
  const video=document.createElement('video');
  video.muted=true;video.playsInline=true;video.preload='metadata';
  const objectUrl=URL.createObjectURL(file);video.src=objectUrl;
  try{
    await waitEvent(video,'loadedmetadata',10000);
    const duration=Number.isFinite(video.duration)&&video.duration>0?video.duration:1;
    const targetCount=Math.min(maxFrames,Math.max(6,Math.ceil(duration/minInterval)));
    const frames=[];
    for(let i=0;i<targetCount;i++){
      const time=targetCount===1?0:Math.max(0,Math.min(duration-.03,(duration-.03)*i/(targetCount-1)));
      if(Math.abs(video.currentTime-time)>.02){video.currentTime=time;await waitEvent(video,'seeked',6000);}
      const c=document.createElement('canvas');
      const width=video.videoWidth||720,height=video.videoHeight||1280;
      const scale=Math.min(1,1440/width);
      c.width=Math.max(1,Math.round(width*scale));c.height=Math.max(1,Math.round(height*scale));
      c.getContext('2d',{willReadFrequently:true}).drawImage(video,0,0,c.width,c.height);
      frames.push({time,canvas:c,hash:dhash(c)});
    }
    // Mantém todas as telas visualmente distintas, inclusive durante rolagens rápidas.
    return uniqueFrames(frames,5);
  } finally { URL.revokeObjectURL(objectUrl); video.removeAttribute('src'); }
}

export async function imageToCanvas(file){
  const img=await createImageBitmap(file);try{
    const c=document.createElement('canvas');const scale=Math.min(1,1800/img.width);
    c.width=Math.max(1,Math.round(img.width*scale));c.height=Math.max(1,Math.round(img.height*scale));
    c.getContext('2d',{willReadFrequently:true}).drawImage(img,0,0,c.width,c.height);return c;
  } finally {img.close();}
}

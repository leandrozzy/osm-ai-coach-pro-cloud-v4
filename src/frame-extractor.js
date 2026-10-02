import {dhash,uniqueFrames} from './frame-dedup.js';
export async function extractFrames(file,{maxFrames=18}={}){
 const video=document.createElement('video');video.muted=true;video.playsInline=true;video.src=URL.createObjectURL(file);await new Promise((res,rej)=>{video.onloadedmetadata=res;video.onerror=rej;});
 const duration=video.duration||1;const count=Math.min(maxFrames,Math.max(4,Math.ceil(duration/1.4)));const frames=[];
 for(let i=0;i<count;i++){const time=count===1?0:(duration-0.05)*i/(count-1);video.currentTime=Math.max(0,time);await new Promise(r=>video.onseeked=r);const c=document.createElement('canvas');const scale=Math.min(1,1280/video.videoWidth);c.width=Math.round(video.videoWidth*scale);c.height=Math.round(video.videoHeight*scale);c.getContext('2d').drawImage(video,0,0,c.width,c.height);frames.push({time,canvas:c,hash:dhash(c)});}
 URL.revokeObjectURL(video.src);return uniqueFrames(frames);
}
export async function imageToCanvas(file){const img=await createImageBitmap(file);const c=document.createElement('canvas');const scale=Math.min(1,1600/img.width);c.width=Math.round(img.width*scale);c.height=Math.round(img.height*scale);c.getContext('2d').drawImage(img,0,0,c.width,c.height);img.close();return c;}

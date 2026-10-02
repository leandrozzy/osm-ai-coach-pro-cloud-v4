export function signature(canvas){
 const c=document.createElement('canvas');c.width=96;c.height=48;const ctx=c.getContext('2d',{willReadFrequently:true});
 const top=Math.round(canvas.height*.08);ctx.drawImage(canvas,0,top,canvas.width,canvas.height-top,0,0,96,48);
 const rgba=ctx.getImageData(0,0,96,48).data,out=new Uint8Array(rgba.length/4);
 for(let i=0,j=0;i<rgba.length;i+=4,j++)out[j]=Math.round(.299*rgba[i]+.587*rgba[i+1]+.114*rgba[i+2]);return out;
}
export function signatureDistance(a,b){if(!a||!b||a.length!==b.length)return 1;let sum=0;for(let i=0;i<a.length;i++)sum+=Math.abs(a[i]-b[i]);return sum/(a.length*255);}
export function distinctFrames(frames,{limit=10,threshold=.012}={}){
 if(frames.length<2)return frames;
 const scenes=[];for(const frame of frames){const sig=frame.signature||signature(frame.canvas);const previous=scenes.at(-1);if(previous&&signatureDistance(previous.signature,sig)<threshold){previous.frames.push({...frame,signature:sig});}else scenes.push({signature:sig,frames:[{...frame,signature:sig}]});}
 // The centre of a stable screen is less likely to be a scrolling transition.
 const stable=scenes.map(scene=>scene.frames[Math.floor((scene.frames.length-1)/2)]);
 if(stable.length<=limit)return stable;
 return Array.from({length:limit},(_,i)=>stable[Math.round(i*(stable.length-1)/(limit-1))]);
}
export function visualBatches(total,maximum=3){
 const count=Math.ceil(total/2);if(!count)return new Set();if(count<=maximum)return new Set(Array.from({length:count},(_,i)=>i));
 return new Set(Array.from({length:maximum},(_,i)=>Math.round(i*(count-1)/(maximum-1))));
}

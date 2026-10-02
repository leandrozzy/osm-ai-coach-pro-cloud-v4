const SAMPLE_WIDTH=192,SAMPLE_HEIGHT=96;
export function signature(canvas,{type='match'}={}){
 const c=document.createElement('canvas');c.width=SAMPLE_WIDTH;c.height=SAMPLE_HEIGHT;
 const ctx=c.getContext('2d',{willReadFrequently:true}),top=Math.round(canvas.height*.08),height=canvas.height-top;
 if(type==='squad'&&canvas.width/canvas.height>1.7){
  // Compare names and attributes rather than averaging the roster's empty centre.
  ctx.drawImage(canvas,0,top,canvas.width*.24,height,0,0,72,SAMPLE_HEIGHT);
  ctx.drawImage(canvas,canvas.width*.53,top,canvas.width*.47,height,72,0,120,SAMPLE_HEIGHT);
 }else ctx.drawImage(canvas,0,top,canvas.width,height,0,0,SAMPLE_WIDTH,SAMPLE_HEIGHT);
 const rgba=ctx.getImageData(0,0,SAMPLE_WIDTH,SAMPLE_HEIGHT).data,out=new Uint8Array(rgba.length/4);
 for(let i=0,j=0;i<rgba.length;i+=4,j++)out[j]=Math.round(.299*rgba[i]+.587*rgba[i+1]+.114*rgba[i+2]);
 c.width=0;c.height=0;return out;
}
export function signatureDistance(a,b){if(!a||!b||a.length!==b.length)return 1;let sum=0;for(let i=0;i<a.length;i++)sum+=Math.abs(a[i]-b[i]);return sum/(a.length*255);}
export function regionalDistance(a,b){
 if(!a||!b||a.length!==b.length)return 1;
 const bands=Math.min(12,a.length),distances=[];
 for(let band=0;band<bands;band++){const start=Math.floor(band*a.length/bands),end=Math.floor((band+1)*a.length/bands);let sum=0;for(let i=start;i<end;i++)sum+=Math.abs(a[i]-b[i]);distances.push(sum/((end-start)*255));}
 distances.sort((x,y)=>y-x);const count=Math.min(3,distances.length);
 return distances.slice(0,count).reduce((a,b)=>a+b,0)/count;
}
export function signatureMetrics(values,{width=SAMPLE_WIDTH,height=SAMPLE_HEIGHT}={}){
 if(!values?.length)return {sharpness:0,variation:0};
 let sum=0,squared=0,edges=0,count=0;
 for(const v of values){sum+=v;squared+=v*v;}
 if(width*height===values.length){
  for(let y=1;y<height-1;y++)for(let x=1;x<width-1;x++){const i=y*width+x;edges+=Math.abs(4*values[i]-values[i-1]-values[i+1]-values[i-width]-values[i+width]);count++;}
 }else{for(let i=1;i<values.length;i++){edges+=Math.abs(values[i]-values[i-1]);count++;}}
 return {sharpness:count?edges/count:0,variation:Math.sqrt(Math.max(0,squared/values.length-(sum/values.length)**2))};
}
export function frameFeatures(canvas,{type='match'}={}){const sig=signature(canvas,{type});return {signature:sig,...signatureMetrics(sig)};}
function prepare(frame,type){
 const features=frame.signature?{signature:frame.signature,...signatureMetrics(frame.signature)}:frameFeatures(frame.canvas,{type});
 return {...frame,...features,...(Number.isFinite(frame.sharpness)?{sharpness:frame.sharpness}:{}),...(Number.isFinite(frame.variation)?{variation:frame.variation}:{})};
}
function bestFrame(scene){
 const middle=(scene.frames.length-1)/2,max=Math.max(...scene.frames.map(f=>f.sharpness));
 // Equivalent sharp frames use the centre; the edges of a pause often still scroll.
 return scene.frames.reduce((best,frame,index)=>{
  const quality=max?frame.sharpness/max:1,centre=1-Math.abs(index-middle)/Math.max(1,middle+1),score=quality*.8+centre*.2;
  return !best||score>best.score?{frame,score}:best;
 },null).frame;
}
function scenesFor(frames,{type='match',threshold=.012,regionalThreshold=.035}={}){
 const prepared=frames.map(frame=>prepare(frame,type)),usable=prepared.filter(frame=>frame.variation>2||frame.sharpness>1);
 // Discard only nearly uniform images. Sharpness alone cannot prove redundancy.
 const candidates=usable.length?usable:prepared,scenes=[];
 for(const frame of candidates){
  const previous=scenes.at(-1),same=previous&&signatureDistance(previous.reference,frame.signature)<threshold&&regionalDistance(previous.reference,frame.signature)<regionalThreshold;
  if(same)previous.frames.push(frame);else scenes.push({reference:frame.signature,frames:[frame]});
 }
 return {prepared,scenes:scenes.map(scene=>({frames:scene.frames,frame:bestFrame(scene)})),blankSkipped:prepared.length-candidates.length};
}
function chooseScenes(scenes,limit){
 if(scenes.length<=limit)return scenes;
 if(limit===1)return [scenes.reduce((a,b)=>a.frames.length>=b.frames.length?a:b)];
 const chosen=[scenes[0],scenes.at(-1)],remaining=scenes.slice(1,-1);
 const start=scenes[0].frame.time??0,end=scenes.at(-1).frame.time??scenes.length,span=Math.max(1,end-start);
 while(chosen.length<limit&&remaining.length){
  let bestIndex=0,bestScore=-Infinity;
  for(const [index,scene] of remaining.entries()){
   const novelty=Math.min(...chosen.map(other=>regionalDistance(scene.frame.signature,other.frame.signature)));
   const separation=Math.min(...chosen.map(other=>Math.abs((scene.frame.time??0)-(other.frame.time??0))))/span;
   const stable=Math.min(1,(scene.frames.length-1)/3),score=novelty*.65+separation*.25+stable*.1;
   if(score>bestScore){bestScore=score;bestIndex=index;}
  }
  chosen.push(...remaining.splice(bestIndex,1));
 }
 return chosen.sort((a,b)=>(a.frame.time??0)-(b.frame.time??0));
}
export function selectAnalysisFrames(frames,{type='match',profile='fast',limit,threshold,regionalThreshold}={}){
 const maximum=Math.max(1,Math.min(24,Number(limit)||(profile==='complete'?12:8)));
 const grouped=scenesFor(frames,{type,threshold,regionalThreshold});
 const chosen=chooseScenes(grouped.scenes,maximum),result=chosen.map(scene=>scene.frame);
 Object.defineProperty(result,'selection',{value:{candidates:frames.length,scenes:grouped.scenes.length,selected:result.length,omittedScenes:Math.max(0,grouped.scenes.length-result.length),blankSkipped:grouped.blankSkipped,limited:grouped.scenes.length>result.length},enumerable:false});
 return result;
}
export function distinctFrames(frames,options={}){return selectAnalysisFrames(frames,{...options,limit:options.limit??10});}
export function visualBatches(total,maximum=3){
 const count=Math.ceil(total/2);if(!count||maximum<1)return new Set();if(count<=maximum)return new Set(Array.from({length:count},(_,i)=>i));
 if(maximum===1)return new Set([Math.floor((count-1)/2)]);
 return new Set(Array.from({length:maximum},(_,i)=>Math.round(i*(count-1)/(maximum-1))));
}

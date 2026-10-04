const SAMPLE_WIDTH=192,SAMPLE_HEIGHT=96;
// These broad shapes help select screens. They do not establish teams or match facts.
export function matchFrameLayout({data,width,height}){
 if(!data||data.length!==width*height*4)return {kind:'unknown',usable:true};
 const region=(left,top,right,bottom)=>{
  const counts={white:0,gray:0,blue:0,yellow:0,green:0,dark:0,cyan:0};let total=0;
  for(let y=Math.floor(top*height);y<Math.ceil(bottom*height);y++)for(let x=Math.floor(left*width);x<Math.ceil(right*width);x++){
   const i=(y*width+x)*4,r=data[i],g=data[i+1],b=data[i+2],min=Math.min(r,g,b),max=Math.max(r,g,b);total++;
   if(min>205&&max-min<40)counts.white++;
   if(min>85&&min<190&&max-min<35)counts.gray++;
   if(b>r*1.45&&b>g*1.1&&b>90)counts.blue++;
   if(r>160&&g>125&&b<125&&r>b*1.5)counts.yellow++;
   if(g>r*1.2&&g>b*1.15&&g>80)counts.green++;
   if(g>r*1.2&&b>r*1.2&&Math.min(g,b)>85)counts.cyan++;
   if(max<130)counts.dark++;
  }
  return Object.fromEntries(Object.entries(counts).map(([key,count])=>[key,count/Math.max(1,total)]));
 };
 const body=region(0,0,1,1),left=region(0,0,.45,1),right=region(.48,0,1,1),side=region(.9,0,1,1),top=region(0,0,1,.32),bottom=region(0,.5,1,1);
 if(body.blue>.9&&left.blue>.95&&body.white<.006&&body.green<.02&&body.yellow<.005)return {kind:'loading',usable:false};
 if(side.blue>.82&&left.gray>.3&&left.white<.01&&left.yellow<.025)return {kind:'menu',usable:false};
 if(left.white>.55&&right.blue+right.green>.55)return {kind:right.green>.3?'report-field':'report-details',usable:true};
 if(left.yellow>.3&&left.white<.12)return {kind:'report-cover',usable:true};
 if(bottom.white>.6&&top.dark>.5&&body.blue>.08)return {kind:'roster-header',usable:true};
 if(body.white>.64&&top.white>.55)return {kind:'roster-list',usable:true};
 const firstCircle=region(.30,.12,.38,.28),secondCircle=region(.62,.12,.70,.28);
 if(body.dark>.58&&body.white<.02&&left.yellow<.1&&body.green>.025&&firstCircle.cyan>.12&&secondCircle.cyan>.10)return {kind:'comparison',usable:true};
 return {kind:'unknown',usable:true};
}
function sample(canvas,{type='match'}={}){
 const c=document.createElement('canvas');c.width=SAMPLE_WIDTH;c.height=SAMPLE_HEIGHT;
 const ctx=c.getContext('2d',{willReadFrequently:true}),top=Math.round(canvas.height*.08),height=canvas.height-top;
 if(type==='squad'&&canvas.width/canvas.height>1.7){
  // Compare names and attributes rather than averaging the roster's empty centre.
  ctx.drawImage(canvas,0,top,canvas.width*.24,height,0,0,72,SAMPLE_HEIGHT);
  ctx.drawImage(canvas,canvas.width*.53,top,canvas.width*.47,height,72,0,120,SAMPLE_HEIGHT);
 }else ctx.drawImage(canvas,0,top,canvas.width,height,0,0,SAMPLE_WIDTH,SAMPLE_HEIGHT);
 const rgba=ctx.getImageData(0,0,SAMPLE_WIDTH,SAMPLE_HEIGHT).data,out=new Uint8Array(rgba.length/4);
 for(let i=0,j=0;i<rgba.length;i+=4,j++)out[j]=Math.round(.299*rgba[i]+.587*rgba[i+1]+.114*rgba[i+2]);
 const layout=type==='match'?matchFrameLayout({data:rgba,width:SAMPLE_WIDTH,height:SAMPLE_HEIGHT}):undefined;
 c.width=0;c.height=0;return {signature:out,...(layout?{layout}:{})};
}
export function signature(canvas,options={}){return sample(canvas,options).signature;}
export function signatureDistance(a,b){if(!a||!b||a.length!==b.length)return 1;let sum=0;for(let i=0;i<a.length;i++)sum+=Math.abs(a[i]-b[i]);return sum/(a.length*255);}
export function regionalDistance(a,b){
 if(!a||!b||a.length!==b.length)return 1;
 const bands=Math.min(12,a.length),distances=[];
 for(let band=0;band<bands;band++){const start=Math.floor(band*a.length/bands),end=Math.floor((band+1)*a.length/bands);let sum=0;for(let i=start;i<end;i++)sum+=Math.abs(a[i]-b[i]);distances.push(sum/((end-start)*255));}
 distances.sort((x,y)=>y-x);const count=Math.min(3,distances.length);
 return distances.slice(0,count).reduce((a,b)=>a+b,0)/count;
}
export function comparisonDetailDistance(a,b){
 if(!a||!b||a.length!==b.length)return 1;
 const width=Math.sqrt(a.length*2),height=width/2;
 if(!Number.isInteger(width)||!Number.isInteger(height))return 0;
 let sum=0,count=0;
 // The two comparison circles alternate strength and the visible match bonus.
 for(const [left,top,right,bottom] of [[.30,.12,.38,.28],[.62,.12,.70,.28]]){
  for(let y=Math.floor(top*height);y<Math.ceil(bottom*height);y++)for(let x=Math.floor(left*width);x<Math.ceil(right*width);x++){const i=y*width+x;sum+=Math.abs(a[i]-b[i]);count++;}
 }
 return sum/(Math.max(1,count)*255);
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
export function frameFeatures(canvas,{type='match'}={}){const sampled=sample(canvas,{type});return {...sampled,...signatureMetrics(sampled.signature)};}
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
 const prepared=frames.map(frame=>prepare(frame,type)),visible=prepared.filter(frame=>type!=='match'||frame.layout?.usable!==false),usable=visible.filter(frame=>frame.variation>2||frame.sharpness>1);
 // Discard only nearly uniform images. Sharpness alone cannot prove redundancy.
 const candidates=usable.length?usable:visible,scenes=[];
 for(const frame of candidates){
  const previous=scenes.at(-1),comparisonChanged=type==='match'&&previous?.frames[0].layout?.kind==='comparison'&&frame.layout?.kind==='comparison'&&comparisonDetailDistance(previous.reference,frame.signature)>.009;
  const same=previous&&!comparisonChanged&&signatureDistance(previous.reference,frame.signature)<threshold&&regionalDistance(previous.reference,frame.signature)<regionalThreshold;
  if(same)previous.frames.push(frame);else scenes.push({reference:frame.signature,frames:[frame]});
 }
 return {prepared,scenes:scenes.map(scene=>({frames:scene.frames,frame:bestFrame(scene)})),blankSkipped:visible.length-candidates.length,obscuredSkipped:prepared.length-visible.length};
}
function chooseScenes(scenes,limit,type){
 if(scenes.length<=limit)return scenes;
 if(limit===1)return [scenes.reduce((a,b)=>a.frames.length>=b.frames.length?a:b)];
 const chosen=[scenes[0],scenes.at(-1)],remaining=scenes.slice(1,-1);
 const start=scenes[0].frame.time??0,end=scenes.at(-1).frame.time??scenes.length,span=Math.max(1,end-start);
 while(chosen.length<limit&&remaining.length){
  let bestIndex=0,bestScore=-Infinity;
  for(const [index,scene] of remaining.entries()){
   const novelty=Math.min(...chosen.map(other=>regionalDistance(scene.frame.signature,other.frame.signature)));
   const separation=Math.min(...chosen.map(other=>Math.abs((scene.frame.time??0)-(other.frame.time??0))))/span;
   const stable=Math.min(1,(scene.frames.length-1)/3),kind=scene.frame.layout?.kind;
   const diverse=type==='match'&&kind&&kind!=='unknown'&&!chosen.some(other=>other.frame.layout?.kind===kind);
   const header=type==='match'&&kind==='roster-header',repeatedList=type==='match'&&kind==='roster-list'&&!diverse;
   const comparisons=chosen.filter(other=>other.frame.layout?.kind==='comparison');
   const detail=type==='match'&&kind==='comparison'&&comparisons.length&&comparisons.every(other=>comparisonDetailDistance(scene.frame.signature,other.frame.signature)>.009);
   const unfamiliar=type==='match'&&kind==='unknown';
   const score=novelty*.65+separation*.25+stable*.1+(diverse?.4:0)+(header?.12:0)+(detail?.18:0)-(repeatedList?.08:0)-(unfamiliar?.08:0);
   if(score>bestScore){bestScore=score;bestIndex=index;}
  }
  chosen.push(...remaining.splice(bestIndex,1));
 }
 return chosen.sort((a,b)=>(a.frame.time??0)-(b.frame.time??0));
}
function panelDistance(a,b,kind){
 if(!a||!b||a.length!==b.length)return 1;
 if(kind==='comparison')return comparisonDetailDistance(a,b);
 if(kind!=='roster-header')return regionalDistance(a,b);
 const width=Math.sqrt(a.length*2),height=width/2;
 if(!Number.isInteger(width)||!Number.isInteger(height))return regionalDistance(a,b);
 let sum=0,count=0;
 // Scrolling player rows below an unchanged club header does not add match facts.
 for(let y=0;y<Math.ceil(height*.38);y++)for(let x=0;x<width;x++){const i=y*width+x;sum+=Math.abs(a[i]-b[i]);count++;}
 return sum/(Math.max(1,count)*255);
}
function essentialExtra(scene,chosen){
 const kind=scene.frame.layout?.kind;
 if(scene.frames.length<2||!['comparison','roster-header','report-cover','report-field','report-details'].includes(kind))return false;
 const existing=chosen.filter(other=>other.frame.layout?.kind===kind);
 const minimum=kind==='comparison'?.009:kind==='roster-header'?.009:.025;
 return !existing.length||existing.every(other=>panelDistance(scene.frame.signature,other.frame.signature,kind)>minimum);
}
export function selectAnalysisFrames(frames,{type='match',profile='fast',limit,threshold,regionalThreshold}={}){
 const maximum=Math.max(1,Math.min(24,Number(limit)||(profile==='complete'?12:8)));
 const grouped=scenesFor(frames,{type,threshold,regionalThreshold});
 const chosen=chooseScenes(grouped.scenes,maximum,type);
 // A fast reading must not silently drop a different, stationary report panel
 // just because eight other images have already been retained. Expansion is
 // limited to four useful pauses; transitions stay available as tiny probes.
 if(type==='match'&&profile==='fast'&&!Number(limit)){
  const extras=grouped.scenes.filter(scene=>!chosen.includes(scene)&&essentialExtra(scene,chosen)).sort((a,b)=>b.frames.length-a.frames.length||b.frame.sharpness-a.frame.sharpness);
  for(const scene of extras){if(chosen.length>=12)break;if(essentialExtra(scene,chosen))chosen.push(scene);}
  chosen.sort((a,b)=>(a.frame.time??0)-(b.frame.time??0));
 }
 const result=chosen.map(scene=>scene.frame),omitted=grouped.scenes.filter(scene=>!chosen.includes(scene)).map(scene=>{const {canvas,hash,...probe}=scene.frame;return {...probe,stableFrames:scene.frames.length};});
 Object.defineProperty(result,'omittedFrames',{value:omitted,enumerable:false});
 Object.defineProperty(result,'selection',{value:{candidates:frames.length,scenes:grouped.scenes.length,selected:result.length,omittedScenes:omitted.length,blankSkipped:grouped.blankSkipped,obscuredSkipped:grouped.obscuredSkipped,expanded:result.length>maximum,limited:omitted.length>0},enumerable:false});
 return result;
}
export function selectCompletionFrames(probes=[],{type='match',missing=[],limit=4,excludeTimes=[]}={}){
 const words=(Array.isArray(missing)?missing.join(' '):String(missing||'')).normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase();
 const wanted=new Set();
 if(/bonus|arbitro|referee|casa|fora|\bhome\b/.test(words))wanted.add('comparison');
 if(/nickname|humano|rivalhuman|forca|strength|valor|value|jogador|player|gol|def|mei|ata|secreto|secret/.test(words))wanted.add('roster-header');
 if(/estadio|stadium|campo|trainingcamp|secreto|secret/.test(words))wanted.add('report-cover');
 if(/formacao|formation/.test(words))wanted.add('report-field');
 if(/marcacao|marking|impedimento|offside|plano|plan|desarme|tackl/.test(words))wanted.add('report-details');
 const excluded=excludeTimes.map(Number).filter(Number.isFinite),available=probes.filter(probe=>probe.layout?.usable!==false&&!excluded.some(time=>Math.abs(time-Number(probe.time))<.04));
 const maximum=Math.max(1,Math.min(4,Math.floor(Number(limit)||4))),selected=[];
 const score=probe=>{
  const kind=probe.layout?.kind,known=kind&&kind!=='unknown';
  const desired=type==='match'&&wanted.size?wanted.has(kind):known;
  const stable=Math.min(1,(probe.stableFrames||1)/4);
  const spread=selected.length?Math.min(...selected.map(other=>Math.abs(Number(probe.time)-Number(other.time)))):0;
  const marking=type==='match'&&/marcacao|marking/.test(words)&&kind==='report-details';
  return (marking?2:0)+(desired?4:0)+(known?1:0)+stable*.5+Math.min(1,spread/8)*.2+Math.min(1,(probe.sharpness||0)/50)*.1;
 };
 while(selected.length<maximum&&available.length){
  available.sort((a,b)=>score(b)-score(a));const next=available.shift();
  // Use a different physical panel before another copy of the same pause.
  if(selected.some(other=>other.layout?.kind===next.layout?.kind&&signatureDistance(other.signature,next.signature)<.012&&regionalDistance(other.signature,next.signature)<.035))continue;
  selected.push(next);
 }
 return selected.sort((a,b)=>Number(a.time)-Number(b.time));
}
export function distinctFrames(frames,options={}){return selectAnalysisFrames(frames,{...options,limit:options.limit??10});}
export function visualBatches(total,maximum=3,{batchSize=2}={}){
 // A Partida batch can contain three full frames, the Groq visual limit. This
 // lets all eight selected panels reach visual analysis within three calls.
 const size=Math.max(1,Math.min(3,Math.floor(Number(batchSize)||2))),count=Math.ceil(total/size);if(!count||maximum<1)return new Set();if(count<=maximum)return new Set(Array.from({length:count},(_,i)=>i));
 if(maximum===1)return new Set([Math.floor((count-1)/2)]);
 return new Set(Array.from({length:maximum},(_,i)=>Math.round(i*(count-1)/(maximum-1))));
}

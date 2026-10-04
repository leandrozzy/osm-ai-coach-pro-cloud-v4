import {selectCompletionFrames} from './frame-selection.js';

const sameTime=(a,b)=>Number.isFinite(Number(a))&&Number.isFinite(Number(b))&&Math.abs(Number(a)-Number(b))<.04;
const eligible=probe=>Number.isFinite(Number(probe?.time))&&probe.layout?.usable!==false&&
 (probe.layout?.kind&&probe.layout.kind!=='unknown'||Number(probe.stableFrames)>=2);

/** Read retained original pauses without resampling the video. The caller
 * registers the decoded canvases and owns their disposal after both readers. */
export async function recoverOriginalFrames({reserves=[],type='match',getMissing,excludeTimes=()=>[],remainingMs=()=>0,signal,decode,readBatch,onFrames=()=>{},onError=()=>{},maxFrames=8}={}){
 if(typeof getMissing!=='function'||typeof decode!=='function'||typeof readBatch!=='function')throw new TypeError('Callbacks de recuperação ausentes.');
 const maximum=Math.max(0,Math.min(8,Math.floor(Number(maxFrames)||0))),consumed=new Map();
 let attempted=0,decodedCount=0,readCount=0;
 const missing=()=>{const value=getMissing();return Array.isArray(value)?value:[];};
 const available=reserve=>{
  const used=consumed.get(reserve.fileIndex)||[],excluded=excludeTimes(reserve.fileIndex)||[];
  const probes=(reserve.probes||[]).filter(probe=>eligible(probe)&&!used.some(time=>sameTime(time,probe.time))&&!excluded.some(time=>sameTime(time,probe.time)));
  return probes.filter((probe,index)=>!probes.slice(0,index).some(other=>sameTime(other.time,probe.time)));
 };
 while(attempted<maximum&&!signal?.aborted&&Number(remainingMs())>6000){
  const pending=missing();if(!pending.length)break;
  let reserve,probes;
  for(const candidate of reserves){
   const selected=selectCompletionFrames(available(candidate),{type,missing:pending,limit:Math.min(2,maximum-attempted)});
   if(selected.length){reserve=candidate;probes=selected;break;}
  }
  if(!reserve)break;
  // An unreadable pause must not be decoded repeatedly until the deadline.
  const used=consumed.get(reserve.fileIndex)||[];used.push(...probes.map(probe=>Number(probe.time)));consumed.set(reserve.fileIndex,used);
  reserve.probes=(reserve.probes||[]).filter(probe=>!probes.some(selected=>sameTime(selected.time,probe.time)));
  attempted+=probes.length;
  let frames;
  try{
   frames=await decode(reserve,probes);
  }catch(error){
   if(signal?.aborted||error?.name==='AbortError')break;
   onError(error);continue;
  }
  frames=(Array.isArray(frames)?frames:[]).filter(Boolean).map(frame=>({...frame,name:reserve.file?.name||frame.name,fileIndex:reserve.fileIndex}));
  if(!frames.length)continue;
  decodedCount+=frames.length;
  // Register even when decoding used the remaining budget, so final cleanup
  // can release every original canvas that was allocated.
  await onFrames(frames);
  if(signal?.aborted||Number(remainingMs())<=6000||!missing().length)break;
  try{await readBatch(frames);readCount+=frames.length;}
  catch(error){if(signal?.aborted||error?.name==='AbortError')break;onError(error);}
 }
 const remainingRelevant=missing().length?reserves.reduce((count,reserve)=>count+available(reserve).length,0):0;
 return {readCount,decodedCount,remainingRelevant};
}

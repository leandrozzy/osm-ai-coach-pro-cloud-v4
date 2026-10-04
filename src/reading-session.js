import {blankExtraction,coverage,relevantConflicts} from './extraction.js';
import {autoMatchFields} from './domain.js';

const sameTime=(first,second)=>Math.abs(first-second)<.04;
const fileKey=file=>({name:String(file?.name||''),type:String(file?.type||''),size:Number(file?.size)||0,lastModified:Number(file?.lastModified)||0});
const contextKey=context=>JSON.stringify(['myTeam','username','competitionType'].map(key=>String(context?.[key]||'').trim().replace(/\s+/g,' ')));
const complete=(done,frame)=>done?.has?.(frame)===true;

function descriptor(probe){
 if(!probe||probe.time==null||probe.time===''||!Number.isFinite(Number(probe.time))||Number(probe.time)<0)return null;
 const out={time:Number(probe.time),localDone:probe.localDone===true,cloudDone:probe.cloudDone===true};
 if(probe.wasSelected===true)out.wasSelected=true;
 if(probe.signature&&(Array.isArray(probe.signature)||ArrayBuffer.isView(probe.signature))&&probe.signature.length>0&&probe.signature.length<=192*96)out.signature=Uint8Array.from(probe.signature);
 if(probe.layout&&typeof probe.layout==='object')out.layout={kind:typeof probe.layout.kind==='string'?probe.layout.kind.slice(0,40):'unknown',usable:probe.layout.usable!==false};
 for(const key of ['sharpness','variation'])if(Number.isFinite(probe[key]))out[key]=probe[key];
 if(Number.isFinite(Number(probe.stableFrames))&&Number(probe.stableFrames)>=1)out.stableFrames=Math.floor(Number(probe.stableFrames));
 return out;
}

/** Only fingerprints and stage completion flags survive between attempts.
 * Full canvases, object URLs and File references remain owned by the caller. */
export function createReadingSession({files=[],type,context={},reserves=[],frames=[],localEnabled=true,vision=true,localDone=new Set(),cloudDone=new Set()}={}){
 const grouped=new Map();
 const add=(fileIndex,probe)=>{
  if(!Number.isInteger(fileIndex)||fileIndex<0||fileIndex>=files.length)return;
  const next=descriptor(probe);if(!next)return;
  const probes=grouped.get(fileIndex)||[],existing=probes.find(item=>sameTime(item.time,next.time));
  if(existing){
   // A decoded frame may finish a stage of an otherwise identical reserve.
   const merged={...existing,...next,localDone:existing.localDone||next.localDone,cloudDone:existing.cloudDone||next.cloudDone};
   Object.assign(existing,merged);
  }else probes.push(next);
  grouped.set(fileIndex,probes);
 };
 for(const reserve of reserves)for(const probe of reserve.probes||[])add(reserve.fileIndex,probe);
 for(const frame of frames)add(frame.fileIndex,{...frame,localDone:frame.localDone===true||complete(localDone,frame),cloudDone:frame.cloudDone===true||complete(cloudDone,frame)});
 const pending=[...grouped].map(([fileIndex,probes])=>({fileIndex,probes:probes.filter(probe=>localEnabled&&!probe.localDone||vision&&!probe.cloudDone).sort((a,b)=>a.time-b.time)})).filter(entry=>entry.probes.length).sort((a,b)=>a.fileIndex-b.fileIndex);
 return {version:1,type,fileKeys:files.map(fileKey),contextKey:contextKey(context),pending};
}

/** An exhausted compatible session returns an empty queue. It must not cause
 * the caller to restart the sixty-probe sampling pass. */
export function restoreReadingSession(previous,files=[],type,context={}){
 const session=previous?.readingSession;
 if(!session||session.version!==1||session.type!==type||session.contextKey!==contextKey(context)||!Array.isArray(session.fileKeys)||!Array.isArray(session.pending)||JSON.stringify(session.fileKeys)!==JSON.stringify(files.map(fileKey)))return null;
 const pending=[];
 for(const entry of session.pending){
  if(!Number.isInteger(entry?.fileIndex)||entry.fileIndex<0||entry.fileIndex>=files.length||!Array.isArray(entry.probes))return null;
  const probes=[];
  for(const raw of entry.probes){
   const probe=descriptor(raw);if(!probe)return null;
   if(!probes.some(other=>sameTime(other.time,probe.time)))probes.push(probe);
  }
  if(probes.length)pending.push({file:files[entry.fileIndex],fileIndex:entry.fileIndex,probes});
 }
 return pending;
}

export function pendingReadingFields(type,data){
 const source=data||blankExtraction(),missing=[...coverage(type,source).missing];
 if(type==='match')for(const conflict of relevantConflicts(type,source.conflicts||[])){
  if(conflict.resolved)continue;
  const label=autoMatchFields.find(([field])=>conflict.field==='match.'+field)?.[1];
  if(label&&!missing.some(item=>item===label||item.startsWith(label+' (')))missing.push(label+' (divergência)');
 }
 return missing.sort((first,second)=>Number(!first.startsWith('Marcação rival'))-Number(!second.startsWith('Marcação rival')));
}

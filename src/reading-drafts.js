import {blankExtraction,coverage} from './extraction.js';
import {known} from './domain.js';

const KEY='osm-ai-coach-pro:readings:v1';
const TYPES=new Set(['match','squad','calendar']);
const LABELS={match:'Partida',squad:'Elenco',calendar:'Calendário'};
// localStorage stores UTF-16 strings. Count those bytes, including captions.
const PREVIEW_BUDGET=1024*1024;
const blockedKey=/^(?:__proto__|prototype|constructor|files?|canvas|imageData|blob|credentials?|providerCredentials?|api[_-]?keys?|keys?|access[_-]?token|refresh[_-]?token|tokens?|password|authorization|headers|options|settings|api|client|controller|signal)$/i;
const credentialKey=key=>blockedKey.test(key)||/api[_-]?key|credential|password/i.test(key)||/(?:token|secret|keys?)$/i.test(key);
const object=value=>value&&typeof value==='object'&&!Array.isArray(value)&&[Object.prototype,null].includes(Object.getPrototypeOf(value));
const error=(message,code,cause)=>Object.assign(new Error(message,{cause}),{code});

function validate(slot,type){
 if(!Number.isInteger(slot)||slot<1||slot>4||!TYPES.has(type))throw error('Escolha um slot de 1 a 4 e um tipo de leitura válido.','READING_DRAFT_INVALID');
}
function store(){
 try{if(!globalThis.localStorage)throw Error('localStorage indisponível');return globalThis.localStorage;}
 catch(cause){throw error('As leituras não puderam ser acessadas neste dispositivo. Os dados confirmados foram preservados.','READING_DRAFT_STORAGE',cause);}
}
function text(value,max=16000){
 if(typeof value!=='string'||/^(?:blob:|data:)/i.test(value))return undefined;
 return value.slice(0,max).replace(/\bBearer\s+[^\s,;]+/gi,'[credencial omitida]').replace(/\b(?:sk|gsk)[-_][A-Za-z0-9_-]{16,}\b/g,'[credencial omitida]');
}
function clone(value,stack=new Set(),depth=0){
 if(value===null||typeof value==='boolean')return value;
 if(typeof value==='number')return Number.isFinite(value)?value:undefined;
 if(typeof value==='string')return text(value);
 if(depth>16||stack.has(value)||!Array.isArray(value)&&!object(value))return undefined;
 stack.add(value);
 let out;
 if(Array.isArray(value))out=value.slice(0,1500).map(item=>clone(item,stack,depth+1)).filter(item=>item!==undefined);
 else{
  out={};for(const [key,item] of Object.entries(value)){
   if(credentialKey(key))continue;
   const cleaned=clone(item,stack,depth+1);if(cleaned!==undefined)out[key]=cleaned;
  }
 }
 stack.delete(value);return out;
}
function session(raw,type){
 if(!object(raw)||raw.version!==1||raw.type!==type||!Array.isArray(raw.fileKeys)||!Array.isArray(raw.pending)||typeof raw.contextKey!=='string')return undefined;
 const fileKeys=raw.fileKeys.slice(0,32).map(file=>({name:text(file?.name,300)||'',type:text(file?.type,100)||'',size:Number.isFinite(file?.size)?Math.max(0,file.size):0,lastModified:Number.isFinite(file?.lastModified)?Math.max(0,file.lastModified):0}));
 const pending=[];
 for(const entry of raw.pending){
  if(!Number.isInteger(entry?.fileIndex)||entry.fileIndex<0||entry.fileIndex>=fileKeys.length||!Array.isArray(entry.probes))continue;
  const probes=[];
  for(const probe of entry.probes.slice(0,240)){
   if(!Number.isFinite(probe?.time)||probe.time<0)continue;
   const next={time:probe.time,localDone:probe.localDone===true,cloudDone:probe.cloudDone===true};
   if(probe.wasSelected===true)next.wasSelected=true;
   if(object(probe.layout))next.layout={kind:text(probe.layout.kind,40)||'unknown',usable:probe.layout.usable!==false};
   for(const key of ['sharpness','variation','stableFrames'])if(Number.isFinite(probe[key]))next[key]=probe[key];
   const signature=probe.signature;
   if((Array.isArray(signature)||ArrayBuffer.isView(signature))&&signature.length>0&&signature.length<=192*96&&Array.from(signature).every(byte=>Number.isInteger(byte)&&byte>=0&&byte<=255))next.signature=Array.from(signature);
   probes.push(next);
  }
  if(probes.length)pending.push({fileIndex:entry.fileIndex,probes});
 }
 return {version:1,type,fileKeys,contextKey:text(raw.contextKey,2000)||'',pending};
}
function previews(rows){
 const out=[];
 for(const row of Array.isArray(rows)?rows:[]){
  if(!object(row)||typeof row.url!=='string'||row.url.length*2>PREVIEW_BUDGET||!/^data:image\/(?:jpeg|png|webp);base64,[A-Za-z0-9+/]+={0,2}$/.test(row.url)||!Number.isFinite(row.time)||row.time<0)continue;
  const next={url:row.url,name:text(row.name,300)||'Tela original',time:row.time};
  if(Number.isInteger(row.fileIndex)&&row.fileIndex>=0)next.fileIndex=row.fileIndex;
  if(typeof row.frameId==='string')next.frameId=text(row.frameId,400);
  out.push(next);if(out.length>=100)break;
 }
 return out;
}
function prepare(raw,{saving=false}={}){
 validate(raw?.slot,raw?.type);
 const out={...blankExtraction(),playerCandidates:[],rosterEvidence:[],readingTeams:[],errors:[],previews:[],files:[],slot:raw.slot,type:raw.type,reader:['local','vision','hybrid'].includes(raw.reader)?raw.reader:'hybrid',mode:text(raw.mode,1000)||'Leitura salva para revisão',frames:Number.isInteger(raw.frames)&&raw.frames>=0?raw.frames:0,elapsedMs:Number.isFinite(raw.elapsedMs)&&raw.elapsedMs>=0?raw.elapsedMs:0};
 if(Number.isInteger(raw.destinationSlot)&&raw.destinationSlot>=1&&raw.destinationSlot<=4)out.destinationSlot=raw.destinationSlot;
 for(const key of ['match','meta','_matchFieldSources'])if(object(raw[key]))out[key]=clone(raw[key]);
 for(const key of ['players','playerCandidates','calendar','calendarFragments','rosterEvidence','readingTeams','errors','sources','conflicts','warnings'])if(Array.isArray(raw[key]))out[key]=clone(raw[key]);
 out.previews=previews(raw.previews);
 const cursor=session(raw.readingSession,raw.type);if(cursor)out.readingSession=cursor;
 if(raw.resumed===true)out.resumed=true;
 if(typeof raw.createdAt==='string')out.createdAt=text(raw.createdAt,100);
 out.draftSavedAt=saving?new Date().toISOString():text(raw.draftSavedAt,100)||out.createdAt||'';
 if(raw.draftPreviewsOmitted===true)out.draftPreviewsOmitted=true;
 if(raw.draftSessionReduced===true)out.draftSessionReduced=true;
 out.coverage=coverage(out.type,out);
 return out;
}
function read(storage=store()){
 let raw;
 try{raw=storage.getItem(KEY);}catch(cause){throw error('As leituras não puderam ser acessadas neste dispositivo. Os dados confirmados foram preservados.','READING_DRAFT_STORAGE',cause);}
 if(raw===null)return {version:1,drafts:{}};
 try{
  const parsed=JSON.parse(raw);
  if(!object(parsed)||parsed.version!==1||!object(parsed.drafts))throw Error('Formato de leituras inválido');
  const drafts={};
  for(const [key,value] of Object.entries(parsed.drafts)){
   if(!object(value)||key!==value.slot+':'+value.type)throw Error('Identidade de leitura inválida');
   drafts[key]=prepare(value);
  }
  return {version:1,drafts};
 }catch(cause){throw error('As leituras salvas não puderam ser lidas. Seus dados não foram apagados.','READING_DRAFT_CORRUPT',cause);}
}
function limitPreviews(data){
 let used=0;
 const newest=Object.values(data.drafts).sort((a,b)=>b.draftSavedAt.localeCompare(a.draftSavedAt));
 for(const draft of newest){
  const kept=[];
  for(const preview of draft.previews){const size=JSON.stringify(preview).length*2;if(used+size<=PREVIEW_BUDGET){kept.push(preview);used+=size;}else draft.draftPreviewsOmitted=true;}
  draft.previews=kept;
 }
 return data;
}
function write(storage,data){
 limitPreviews(data);
 try{storage.setItem(KEY,JSON.stringify(data));return;}
 catch(first){
  // Retain the recognized facts even when pictures fill the device's quota.
  for(const draft of Object.values(data.drafts)){if(draft.previews.length)draft.draftPreviewsOmitted=true;draft.previews=[];}
  try{storage.setItem(KEY,JSON.stringify(data));return;}
  catch(second){
   // Fingerprint pixels can be recomputed when the original video is selected.
   // Keep file identity, timestamps and completed stages so resuming still works.
   for(const draft of Object.values(data.drafts))for(const entry of draft.readingSession?.pending||[])for(const probe of entry.probes)if(probe.signature){delete probe.signature;draft.draftSessionReduced=true;}
   try{storage.setItem(KEY,JSON.stringify(data));return;}
   catch(cause){throw error('Não foi possível salvar esta leitura no dispositivo. Ela continua aberta; libere espaço ou confirme os dados antes de fechar o app.','READING_DRAFT_STORAGE',cause);}
  }
 }
}

/** An independent review draft; it never confirms or applies data to a slot. */
export function saveReadingDraft(review){
 const next=prepare(review,{saving:true}),storage=store(),data=read(storage),key=next.slot+':'+next.type;
 data.drafts[key]=next;write(storage,data);return prepare(data.drafts[key]);
}
export function getReadingDraft(slot,type){
 validate(slot,type);const draft=read().drafts[slot+':'+type];return draft?prepare(draft):null;
}
export function listReadingDrafts(){
 return Object.values(read().drafts).sort((a,b)=>b.draftSavedAt.localeCompare(a.draftSavedAt)||a.slot-b.slot||a.type.localeCompare(b.type)).map(draft=>({slot:draft.slot,type:draft.type,label:LABELS[draft.type],at:draft.draftSavedAt,team:text(known(draft.meta.team)?draft.meta.team:draft.match.myName,120)||'NI',requiresReview:true,count:draft.coverage.count,pendingCount:draft.coverage.pendingCount}));
}
export function removeReadingDraft(slot,type){
 validate(slot,type);const storage=store(),data=read(storage),key=slot+':'+type;
 if(!Object.hasOwn(data.drafts,key))return false;
 delete data.drafts[key];write(storage,data);return true;
}
export function clearReadingDrafts(slot){
 if(slot!==undefined&&(!Number.isInteger(slot)||slot<1||slot>4))throw error('Escolha um slot de 1 a 4.','READING_DRAFT_INVALID');
 const storage=store(),data=read(storage),keys=Object.keys(data.drafts).filter(key=>slot===undefined||data.drafts[key].slot===slot);
 if(!keys.length)return 0;for(const key of keys)delete data.drafts[key];write(storage,data);return keys.length;
}

import {known,rowTime} from './domain.js';
import {normalize,sameClub} from './utils.js';

const time=value=>{const n=Date.parse(value||'');return Number.isFinite(n)?n:null;};

// Separate observations made at different times from disagreements between
// providers reading the same media. A later observed tactic is an update.
export function updateSavedRecord(saved={},incoming={},options={}){
 const out={...saved,_fieldSources:{...saved._fieldSources}},at=options.at||new Date().toISOString();
 const freshAt=time(options.readingAt),fields=options.fields||Object.keys(incoming);
 for(const field of fields){
  if(field.startsWith('_')||field==='id')continue;
  if(!known(incoming[field])){if(!Object.hasOwn(out,field)&&Object.hasOwn(incoming,field))out[field]=incoming[field];continue;}
  const previous=out._fieldSources[field],proof=incoming._fieldSources?.[field],oldRank=previous?.rank||0,newRank=proof?.rank||0;
  const manual=proof?.kind==='manual'&&newRank>=4;
  const oldAt=time(previous?.observedAt||(oldRank>=4?saved._manualUpdatedAt:null)||saved._lastReadAt);
  if(!manual&&oldRank>=4&&!known(out[field])&&(freshAt===null||oldAt!==null&&freshAt<oldAt))continue;
  if(known(out[field])&&!manual){
   if(oldRank>=2&&newRank<2)continue;
   if(oldAt!==null&&freshAt!==null&&freshAt<oldAt)continue;
   if(oldRank>=4&&newRank<4&&freshAt===null)continue;
   // A weaker numeric OCR must not undo a circle verified in native pixels.
   if(['myStrength','rivalStrength','strength','shirtNumber'].includes(field)&&oldRank>=3&&oldRank<4&&newRank<oldRank)continue;
  }
  if(['sawTop','sawBottom','teamVerified'].includes(field)&&out[field]===true&&incoming[field]===false)continue;
  out[field]=incoming[field];
  if(proof)out._fieldSources[field]={...proof,observedAt:manual?(proof.observedAt||at):(options.readingAt||at)};
  else delete out._fieldSources[field];
 }
 return out;
}

const playerKey=row=>normalize(row.name).replace(/[\s.\-’']/g,'');
export function updateSavedPlayers(saved=[],incoming=[],options={}){
 const map=new Map(saved.map(row=>[playerKey(row),row])),seen=new Set(),rows=[];
 for(const row of incoming){
  const key=playerKey(row);if(!key||seen.has(key))continue;seen.add(key);
  const previous=map.get(key),merged=updateSavedRecord(previous||{},row,options);
  rows.push({...merged,id:previous?.id||row.id});
 }
 if(!options.complete)for(const row of saved)if(!seen.has(playerKey(row)))rows.push(structuredClone(row));
 return rows;
}

function sameFixture(a,b){
 if(known(a.cup)&&known(b.cup)&&a.cup!==b.cup)return false;
 if(known(a.round)&&known(b.round))return String(a.round)===String(b.round);
 if(!known(a.date)||a.date!==b.date)return false;
 if(sameClub(a.opponent,b.opponent))return true;
 return !known(a.opponent)&&!known(b.opponent)&&known(a.stage)&&normalize(a.stage)===normalize(b.stage);
}
export function updateSavedCalendar(saved=[],incoming=[],options={}){
 const used=new Set(),rows=[];
 for(const row of incoming){
  const candidates=saved.map((old,index)=>!used.has(index)&&sameFixture(old,row)?index:-1).filter(index=>index>=0);
  const index=candidates.length===1?candidates[0]:-1,previous=index>=0?saved[index]:null;
  if(previous)used.add(index);
  rows.push({...updateSavedRecord(previous||{},row,options),id:previous?.id||row.id});
 }
 if(!options.complete)for(const [index,row]of saved.entries())if(!used.has(index))rows.push(structuredClone(row));
 return rows.sort((a,b)=>known(a.round)&&known(b.round)?Number(a.round)-Number(b.round):(rowTime(a)??Infinity)-(rowTime(b)??Infinity));
}

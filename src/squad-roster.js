import {known} from './domain.js';
import {normalize} from './utils.js';

const nameKey=value=>normalize(value).replace(/[\s.\-’']/g,'');
const manualName=row=>row?._rosterPending!==true&&row?._fieldSources?.name?.kind==='manual'&&row._fieldSources.name.rank>=4;
const rowIsLiteral=row=>known(row?.name)&&Number.isFinite(row._rowY)&&row._rowY>0&&row._rowY<1&&['position','strength','age','value'].some(key=>known(row[key]));
const sameAttribute=(key,a,b)=>known(a?.[key])&&known(b?.[key])&&(key==='value'?normalize(a[key]).replace(',','.')===normalize(b[key]).replace(',','.'):String(a[key])===String(b[key]));

// OCR can miss a single letter. A nearby spelling alone never proves a player;
// require two independent attributes from the same physical row as well.
function oneLetterApart(a,b){
 if(a.length<4||b.length<4||Math.abs(a.length-b.length)>1)return false;
 let i=0,j=0,edits=0;
 while(i<a.length&&j<b.length){
  if(a[i]===b[j]){i++;j++;continue;}
  if(++edits>1)return false;
  if(a.length>=b.length)i++;
  if(b.length>=a.length)j++;
 }
 return edits+(a.length-i)+(b.length-j)===1;
}
function supportsNearbyName(player,row){
 return oneLetterApart(nameKey(player.name),nameKey(row.name))&&['position','age','value','strength'].filter(key=>sameAttribute(key,player,row)).length>=2;
}

/**
 * evidenceRows must be literal OCR table rows, retaining their _rowY anchor.
 * Suggested visual names are kept for review, never silently discarded or
 * replaced with a famous player's name. Partial OCR cannot prove their absence.
 */
export function refineSquadRoster(initial,evidenceRows=[]){
 const data=initial||{},literalRows=(Array.isArray(evidenceRows)?evidenceRows:[]).filter(rowIsLiteral);
 const candidates=Array.isArray(data.playerCandidates)?data.playerCandidates:[];
 const players=Array.isArray(data.players)?data.players:[];
 const records=[...players,...candidates].filter(row=>known(row?.name));
 const unique=new Map();
 for(const row of records){const key=nameKey(row.name),prior=unique.get(key);if(!prior||manualName(row)&&!manualName(prior))unique.set(key,row);}
 // Without a usable OCR row, visual/video reading remains reviewable normally.
 // A missing or failed OCR response must never empty a valid visual result.
 if(!literalRows.length)return {data:{...data,players:[...players],playerCandidates:[...candidates]},playerCandidates:[...candidates],verified:false,warnings:[]};

 const byName=new Map();
 for(const row of literalRows){const key=nameKey(row.name);if(!byName.has(key))byName.set(key,[]);byName.get(key).push(row);}
 const accepted=[],pending=[],claimed=new Set(),nearby=[];
 const accept=row=>{const clean={...row};delete clean._rosterPending;delete clean._rosterReason;accepted.push(clean);};
 for(const row of unique.values()){
  const key=nameKey(row.name);
  if(manualName(row)||byName.has(key)){accept(row);if(byName.has(key))claimed.add(key);}
  else nearby.push(row);
 }
 for(const row of nearby){
  const matches=[...byName].filter(([key,observed])=>!claimed.has(key)&&observed.some(other=>supportsNearbyName(row,other)));
  if(matches.length===1){accept(row);claimed.add(matches[0][0]);}
  else pending.push({...row,_rosterPending:true,_rosterReason:'Nome sugerido pela IA sem confirmação em uma linha legível. Confira a tela e marque para incluir.'});
 }
 const warnings=pending.length?[pending.length+' nome'+(pending.length===1?'':'s')+' sugerido'+(pending.length===1?'':'s')+' pela IA sem linha OCR confirmada. '+(pending.length===1?'O jogador foi preservado':'Os jogadores foram preservados')+' para revisão e só '+(pending.length===1?'entra':'entram')+' no elenco se você marcar para incluir. O OCR pode ter deixado nomes válidos ilegíveis.']:[];
 const next={...data,players:accepted,playerCandidates:pending};
 return {data:next,playerCandidates:pending,verified:true,warnings};
}

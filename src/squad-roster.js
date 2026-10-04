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
function sameObservedRow(low,high,nearbyName=false){
 if(low.frameId==null||high.frameId==null||low.frameId===high.frameId||!low._nameBox||!high._nameBox)return false;
 // Never equate rows from different input files just because their prices
 // happen to match. Timestamped frames of one file retain that identity.
 const file=id=>typeof id==='string'&&id.includes(':')?id.slice(0,id.lastIndexOf(':')):null;
 if(file(low.frameId)&&file(high.frameId)&&file(low.frameId)!==file(high.frameId))return false;
 if(nearbyName){
  if(!supportsNearbyName(low,high))return false;
  for(const field of ['strength','value','age','position'])if(known(low[field])&&known(high[field])&&!sameAttribute(field,low,high))return false;
 }else if(!sameAttribute('strength',low,high)||!sameAttribute('value',low,high))return false;
 for(const field of ['age','position'])if(known(low[field])&&known(high[field])&&!sameAttribute(field,low,high))return false;
 const width=Number(low.width||high.width),tolerance=Number.isFinite(width)?Math.max(4,width*.004):4;
 const a=low._nameBox,b=high._nameBox,ah=a.bottom-a.top,bh=b.bottom-b.top;
 if(![a.right,b.right,ah,bh].every(Number.isFinite)||ah<5||bh<5||Math.abs(a.right-b.right)>tolerance||ah/bh<.7||ah/bh>1.4)return false;
 // Two matching neighbouring names and matching relative row distances
 // prove table order across a scroll. Statistics alone do not prove it.
 for(const direction of ['before','after']){
  const one=low._rowNeighbours?.[direction],two=high._rowNeighbours?.[direction];
  if(!known(one?.name)||!known(two?.name)||nameKey(one.name)!==nameKey(two.name))return false;
  if(!Number.isFinite(one.rowY)||!Number.isFinite(two.rowY)||Math.abs((one.rowY-low._rowY)-(two.rowY-high._rowY))>.015)return false;
 }
 return true;
}
function provedAliases(byName,records){
 const aliases=new Map();
 for(const [key,lowRows] of byName){
  if(!lowRows.length||!lowRows.every(row=>Number.isFinite(row._rowNameConfidence)))continue;
  const zeroConfidence=lowRows.every(row=>row._rowNameConfidence<=0);
  const matches=[...byName].filter(([other,highRows])=>{
   if(other===key||!records.has(other))return false;
   if(zeroConfidence&&lowRows.every(low=>highRows.some(high=>high._rowNameConfidence>=70&&sameObservedRow(low,high))))return true;
   // A readable OCR typo (Ounah/Ounahi in the original Tobol video) can
   // otherwise create a nineteenth player. Require one spelling edit, two
   // matching attributes, both neighbouring rows and the same scroll geometry.
   // The preferred spelling must be better read on two independent frames.
   if(!oneLetterApart(key,other))return false;
   return lowRows.every(low=>{
    const proved=highRows.filter(high=>Number.isFinite(high._rowNameConfidence)&&high._rowNameConfidence>=Math.max(70,low._rowNameConfidence+5)&&sameObservedRow(low,high,true));
    return new Set(proved.map(row=>row.frameId)).size>=2;
   });
  });
  if(matches.length===1)aliases.set(key,records.get(matches[0][0]).name);
 }
 return aliases;
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
 const aliases=provedAliases(byName,unique),accepted=[],pending=[],claimed=new Set(),nearby=[];
 const accept=row=>{const clean={...row};delete clean._rosterPending;delete clean._rosterReason;accepted.push(clean);};
 for(const row of unique.values()){
  const key=nameKey(row.name);
  if(!manualName(row)&&aliases.has(key))pending.push({...row,_rosterPending:true,_rosterAliasOf:aliases.get(key),_rosterReason:'Variação OCR do nome na mesma linha de '+aliases.get(key)+', comprovada pelos atributos e pelas duas linhas vizinhas. Confira a leitura preservada antes de incluir outro jogador.'});
  else if(manualName(row)||byName.has(key)){accept(row);if(byName.has(key))claimed.add(key);}
  else nearby.push(row);
 }
 for(const row of nearby){
  const matches=[...byName].filter(([key,observed])=>!claimed.has(key)&&observed.some(other=>supportsNearbyName(row,other)));
  if(matches.length===1){accept(row);claimed.add(matches[0][0]);}
  else pending.push({...row,_rosterPending:true,_rosterReason:'Nome sugerido pela IA sem confirmação em uma linha legível. Confira a tela e marque para incluir.'});
 }
 const duplicates=pending.filter(row=>row._rosterAliasOf).length,suggestions=pending.length-duplicates,warnings=[];
 if(duplicates)warnings.push(duplicates+' variação'+(duplicates===1?'':'s')+' OCR de nome repete'+(duplicates===1?'':'m')+' uma linha confirmada. Preservadas para revisão, sem duplicar a contagem do elenco.');
 if(suggestions)warnings.push(suggestions+' nome'+(suggestions===1?'':'s')+' sugerido'+(suggestions===1?'':'s')+' pela IA sem linha OCR confirmada. '+(suggestions===1?'O jogador foi preservado':'Os jogadores foram preservados')+' para revisão e só '+(suggestions===1?'entra':'entram')+' no elenco se você marcar para incluir. O OCR pode ter deixado nomes válidos ilegíveis.');
 const next={...data,players:accepted,playerCandidates:pending};
 return {data:next,playerCandidates:pending,verified:true,warnings};
}

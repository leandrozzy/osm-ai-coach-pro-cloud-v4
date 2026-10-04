import {cleanMatch,cleanPlayers,matchFields,known,num,scoreOutcome} from './domain.js';
import {normalize} from './utils.js';
const safeText=(v,max=100)=>known(v)&&['string','number'].includes(typeof v)?String(v).trim().slice(0,max):'NI';
export const blankExtraction=()=>({match:{},_matchFieldSources:{},players:[],calendar:[],calendarFragments:[],meta:{},sources:[],conflicts:[],warnings:[]});
function cleanPendingMatchFacts(rows){
 return (Array.isArray(rows)?rows:[]).filter(row=>row&&matchFields.some(([field])=>field===row.field)&&known(row.value)&&['string','number','boolean'].includes(typeof row.value)).slice(0,80).map(row=>({field:row.field,value:typeof row.value==='string'?row.value.slice(0,120):row.value,reason:safeText(row.reason,240),source:safeText(row.source,160)}));
}
const evidenceKinds={visual:1,'ocr-layout':2,'ocr-explicit':2,pixels:3,'ocr-consensus':3,manual:4};
const textIdentityFields=new Set(['name','opponent','nickname','stage','myName','rivalName','rivalNickname']);
const monetaryFields=new Set(['value','cash','squadValue','mySquadValue','rivalSquadValue']);
const metaFactFields=['team','cash','squadValue','strength','GK','DEF','MID','ATT','expectedPlayers','expectedRounds'];
const matchHeaderFields=['myName','rivalName','rivalNickname','human','myStrength','rivalStrength','myGK','rivalGK','myDEF','rivalDEF','myMID','rivalMID','myATT','rivalATT','mySquadValue','rivalSquadValue','myPlayers','rivalPlayers','myBonus','rivalBonus','location'];
const reportTacticFields=['rivalFormation','rivalPlan','rivalMarking','rivalOffside','rivalTackling'];
function monetaryAmount(value){
 const text=String(value).trim().replace(/^(?:R\$|US\$|EUR|USD|[€$£])\s*/i,'').replace(/\s+/g,'');
 const match=text.match(/^(\d+(?:[.,]\d+)*)([KMB])?$/i);if(!match)return null;
 let digits=match[1];
 if(digits.includes(',')&&digits.includes('.')){const decimal=digits.lastIndexOf(',')>digits.lastIndexOf('.')?',':'.';digits=digits.replace(new RegExp('\\'+(decimal===','?'.':','),'g'),'').replace(decimal,'.');}
 else if(!match[2]&&/^\d{1,3}(?:[.,]\d{3})+$/.test(digits))digits=digits.replace(/[.,]/g,'');
 else digits=digits.replace(',','.');
 const amount=Number(digits)*({K:1e3,M:1e6,B:1e9}[match[2]?.toUpperCase()]||1);
 return Number.isFinite(amount)?amount:null;
}
function equivalentField(key,a,b){
 if(textIdentityFields.has(key))return normalize(a).replace(/\s+/g,' ')===normalize(b).replace(/\s+/g,' ');
 if(monetaryFields.has(key)){const first=monetaryAmount(a),second=monetaryAmount(b);if(first!==null&&second!==null)return first===second;}
 return String(a)===String(b);
}
function cleanFieldSources(raw={}){
 const out={};if(!raw||typeof raw!=='object')return out;
 for(const [key,value]of Object.entries(raw)){if(!value||typeof value!=='object'||key.startsWith('_'))continue;
 const rank=value.kind==='derived'?Math.max(0,Math.min(4,Number(value.rank)||0)):evidenceKinds[value.kind];
 if(rank===undefined)continue;out[key]={kind:value.kind,rank,source:safeText(value.source,160)};
 }return out;
}
function sourceKind(source,options={}){
 if(Object.hasOwn(evidenceKinds,options.sourceKind))return options.sourceKind;
 if(/^(?:icones nas telas|pixels)/.test(normalize(source)))return 'pixels';
 if(/^ocr\.space (?:card|posicao)/.test(normalize(source)))return 'ocr-layout';
 if(/^ocr\.space explicito/.test(normalize(source)))return 'ocr-explicit';
 return source?'visual':null;
}
function evidenceFor(record,type,source,options={},prior={}){
 const kind=sourceKind(source,options);
 const preserve=options.preserveEvidence===true||!source||['pixels','ocr-layout','ocr-explicit','ocr-consensus','manual'].includes(kind);
 const sources=preserve?cleanFieldSources(prior):{};
 const allowed=kind==='ocr-consensus'?(type==='match'?['myStrength','rivalStrength']:[]):kind==='pixels'?(type==='calendar'?['home','cup','result']:type==='squad'?['position','strength','training','forSale']:['secretTraining','referee','human','myStrength','rivalStrength','myBonus','rivalBonus']):kind==='ocr-layout'?(type==='calendar'?['round','date','time','displayedScore']:type==='squad'?['position','strength','age','value']:matchHeaderFields):kind==='ocr-explicit'?matchFields.map(([key])=>key).filter(key=>!textIdentityFields.has(key)):Object.keys(record);
 const selected=Array.isArray(options.fields)?options.fields:type==='match'&&kind==='ocr-layout'?[]:type==='match'&&kind==='pixels'?allowed.filter(key=>!['myStrength','rivalStrength','myBonus','rivalBonus'].includes(key)):allowed;
 for(const key of Object.keys(record)){
 if(key.startsWith('_')||key==='id'||!known(record[key]))continue;
 const trusted=kind&&allowed.includes(key)&&selected.includes(key);
 if(trusted&&!(options.preserveEvidence===true&&(sources[key]?.rank||0)>evidenceKinds[kind]))sources[key]={kind,rank:evidenceKinds[kind],source:safeText(source||kind,160)};
 else if(!sources[key]&&kind)sources[key]={kind:'visual',rank:1,source:safeText(source,160)};
 }
 return Object.fromEntries(Object.entries(sources).filter(([key])=>known(record[key])));
}
function metaEvidenceFor(record,source,options={},prior={}){
 const kind=sourceKind(source,options),preserve=!source||options.preserveEvidence===true||['pixels','ocr-layout','ocr-explicit','ocr-consensus','manual'].includes(kind);
 const sources=preserve?cleanFieldSources(prior):{};
 const allowed=kind==='ocr-consensus'?['strength']:kind==='manual'?[...metaFactFields,'sawTop','sawBottom']:kind==='ocr-layout'?[...metaFactFields,'sawTop','sawBottom']:kind==='ocr-explicit'?metaFactFields.filter(field=>field!=='team'):kind==='pixels'?['strength','GK','DEF','MID','ATT','sawTop','sawBottom']:metaFactFields;
 const selected=Array.isArray(options.metaFields)?options.metaFields:kind==='pixels'?[]:allowed;
 for(const key of [...metaFactFields,'sawTop','sawBottom']){
  if(!known(record[key]))continue;
  const rank=kind&&allowed.includes(key)&&selected.includes(key)?evidenceKinds[kind]:kind?1:0;
  if(rank&&(!sources[key]||rank>sources[key].rank||options.preserveEvidence!==true))sources[key]={kind:rank===1?'visual':kind,rank,source:safeText(source||kind,160)};
 }
 return Object.fromEntries(Object.entries(sources).filter(([key])=>known(record[key])));
}
const fieldRank=(row,key)=>Number(row?._fieldSources?.[key]?.rank)||0;
const derivedSource=(rank,source)=>({kind:'derived',rank,source});
function confirmedReportLock(data){return data.meta?.rivalReportLocked===true&&data.match?.secretTraining==='Sim'&&data._matchFieldSources?.secretTraining?.kind==='pixels'&&data._matchFieldSources.secretTraining.rank===3;}
function enforceReportPrivacy(data){
 if(!confirmedReportLock(data))return data;
 const hidden=reportTacticFields.filter(key=>(data._matchFieldSources?.[key]?.rank||0)<4);
 for(const key of hidden){delete data.match[key];delete data._matchFieldSources[key];}
 data.meta.hiddenFields=[...new Set([...(data.meta.hiddenFields||[]).filter(key=>!reportTacticFields.includes(key)),...hidden])];
 if(data.meta.pendingMatchFacts)data.meta.pendingMatchFacts=data.meta.pendingMatchFacts.filter(fact=>!hidden.includes(fact.field));
 // No tactic can be confirmed from a currently locked report. Conflicting old
 // readings of those hidden fields stay unavailable instead of selecting one.
 data.conflicts=(data.conflicts||[]).filter(conflict=>!hidden.some(key=>conflict.field==='match.'+key));
 if(hidden.length)data.warnings=[...new Set([...(data.warnings||[]),'Treino secreto comprovado: os dados táticos do relatório rival estão ocultos e permanecem NI.'])];
 return data;
}
function enforceMatchConsistency(data){
 const cpu=data._matchFieldSources?.human, nickname=data._matchFieldSources?.rivalNickname;
 // An empty, visible manager band rules out weak OCR letters from flags or
 // graphics. Keep actual competing text evidence and the user's choices.
 const disputedHuman=(data.conflicts||[]).some(c=>c.field==='match.human'&&!c.resolved&&((c.first===true&&(c.firstRank||0)>=3)||(c.second===true&&(c.secondRank||0)>=3)));
 if(data.match?.human!==false||(cpu?.rank||0)<3||(nickname?.rank||0)>=3||disputedHuman)return data;
 delete data.match.rivalNickname;delete data._matchFieldSources.rivalNickname;
 data.conflicts=(data.conflicts||[]).filter(c=>c.field!=='match.rivalNickname'||Math.max(c.firstRank||0,c.secondRank||0)>=3);
 if(data.meta?.pendingMatchFacts)data.meta.pendingMatchFacts=data.meta.pendingMatchFacts.filter(fact=>fact.field!=='rivalNickname');
 return data;
}
export function relevantConflicts(type,conflicts=[]){
 return type==='match'?conflicts.filter(c=>c.field?.startsWith('match.')&&matchFields.some(([key])=>c.field==='match.'+key)):conflicts;
}
export function normalizeDate(v){
 const m=String(v||'').match(/^(\d{1,2})[/-](\d{1,2})[/-](\d{2}|\d{4})$/);
 if(!m)return 'NI';
 const year=m[3].length===2?'20'+m[3]:m[3];
 const d=new Date(+year,+m[2]-1,+m[1]);if(d.getDate()!==+m[1]||d.getMonth()!==+m[2]-1)return 'NI';
 return m[1].padStart(2,'0')+'/'+m[2].padStart(2,'0')+'/'+year;
}
export function cleanCalendar(rows=[],conflicts=null){
 return rows.filter(r=>r&&typeof r==='object'&&(known(r.round)||known(r.opponent)||(known(r.stage)&&(known(normalizeDate(r.date))||/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(r.time))))).slice(0,150).map(r=>{
 const fieldSources=cleanFieldSources(r._fieldSources);
 let result=['V','E','D'].includes(r.result)?r.result:'NI';
 const home=typeof r.home==='boolean'?r.home:null;
 let score=known(r.score)&&scoreOutcome(r.score)?String(r.score).replace(/[×:-]/,'x').replace(/\s/g,''):'NI';
 // displayedScore is a home/away scoreboard. score is always our team first.
 if(known(r.displayedScore)&&scoreOutcome(r.displayedScore)&&home!==null){const [a,b]=String(r.displayedScore).split(/[x×:-]/).map(Number);score=home?a+'x'+b:b+'x'+a;fieldSources.score=derivedSource(Math.min(fieldSources.displayedScore?.rank||0,fieldSources.home?.rank||0),'Placar exibido e local confirmados');}
 if(known(score)&&known(result)&&scoreOutcome(score)!==result){
 const derived=scoreOutcome(score),scoreRank=fieldSources.score?.rank||0,resultRank=fieldSources.result?.rank||0;
 if(scoreRank>=2&&scoreRank>resultRank){if(conflicts)conflicts.push({field:(known(r.round)?'round:'+r.round:'date:'+r.date+'|'+normalize(r.opponent))+'.result',first:result,second:derived,resolved:true,preferred:derived,firstSource:fieldSources.result?.source||'Leitura anterior',secondSource:fieldSources.score.source,firstRank:resultRank,secondRank:scoreRank});result=derived;fieldSources.result=derivedSource(scoreRank,fieldSources.score.source);}
 else score='NI';
 }
 if(known(score)&&!known(result)){result=scoreOutcome(score)||'NI';fieldSources.result=derivedSource(fieldSources.score?.rank||0,fieldSources.score?.source||'Placar do nosso time');}
 const row={id:typeof r.id==='string'?r.id:crypto.randomUUID(),round:num(r.round),date:normalizeDate(r.date),time:/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(r.time)?r.time:'NI',opponent:safeText(r.opponent),nickname:safeText(r.nickname),home,cup:typeof r.cup==='boolean'?r.cup:null,score,displayedScore:known(r.displayedScore)&&scoreOutcome(r.displayedScore)?String(r.displayedScore).replace(/[×:-]/,'x').replace(/\s/g,''):'NI',result,stage:safeText(r.stage),_source:safeText(r._source,160)};
 row._fieldSources=Object.fromEntries(Object.entries(fieldSources).filter(([key])=>known(row[key])));return row;
 });
}
export function normalizeExtraction(raw={},type,source='',options={}){
 const out=blankExtraction();if(!raw||typeof raw!=='object')return out;
 const payload=raw.data&&typeof raw.data==='object'?raw.data:raw;
 if(type==='match')out.match=cleanMatch(payload.match||payload);
 if(payload!==raw)raw=payload;
 if(type==='squad')out.players=cleanPlayers(Array.isArray(raw.players)?raw.players:[]).map(p=>{const original=raw.players.find(row=>normalize(row?.name)===normalize(p.name))||{};return {...p,_source:source,_fieldSources:evidenceFor(p,type,source,options,original._fieldSources)};});
 if(type==='calendar'){
 const originalRows=[...(Array.isArray(raw.calendar)?raw.calendar:[]),...(Array.isArray(raw.calendarFragments)?raw.calendarFragments:[])];
 const rows=cleanCalendar(originalRows.map(row=>({...row,_fieldSources:evidenceFor(row,type,source,options,row._fieldSources)})),out.conflicts).map(r=>({...r,_source:source||r._source}));
 out.calendar=rows.filter(calendarHasSchedule);out.calendarFragments=rows.filter(r=>!calendarHasSchedule(r));
 }
 if(raw.meta&&typeof raw.meta==='object'){
 const m=raw.meta;out.meta={rivalReportLocked:m.rivalReportLocked===true,team:safeText(m.team),teamVerified:m.teamVerified===true&&(sourceKind(source,options)==='ocr-layout'||!source||options.preserveEvidence===true),cash:safeText(m.cash),squadValue:safeText(m.squadValue),...Object.fromEntries(['strength','GK','DEF','MID','ATT'].map(k=>[k,num(m[k])!==null&&num(m[k])>0&&num(m[k])<=400?num(m[k]):null])),expectedPlayers:num(m.expectedPlayers),expectedRounds:num(m.expectedRounds),sawTop:m.sawTop===true,sawBottom:m.sawBottom===true,visibleNames:Array.isArray(m.visibleNames)?m.visibleNames.filter(n=>typeof n==='string').slice(0,80):[],hiddenFields:Array.isArray(m.hiddenFields)?m.hiddenFields.filter(k=>matchFields.some(([f])=>f===k)):[]};
 out.meta._fieldSources=metaEvidenceFor(out.meta,source,options,m._fieldSources);
 // A model's claim to have seen a list boundary cannot certify list completeness.
 // Literal OCR layout, an actual pixel check or the user's review can certify it.
 for(const field of ['sawTop','sawBottom'])if(source&&out.meta[field]&&(out.meta._fieldSources[field]?.rank||0)<2)out.meta[field]=false;
 if(type==='match'&&(!source||options.preserveEvidence===true))out.meta.pendingMatchFacts=cleanPendingMatchFacts(m.pendingMatchFacts);
 // Squad accounting fields have no editable counterpart in match review.
 // A number from an unrelated screen must not become a match disagreement.
 if(type==='match'){for(const key of [...metaFactFields,'sawTop','sawBottom','teamVerified','visibleNames'])delete out.meta[key];out.meta._fieldSources={};}
 }
 if(type==='match'&&out.meta.rivalReportLocked)out.match.secretTraining='Sim';
 if(type==='match')out._matchFieldSources=evidenceFor(out.match,type,source,options,raw._matchFieldSources||raw.match?._fieldSources);
 if(source)out.sources=[source];return type==='match'?enforceMatchConsistency(enforceReportPrivacy(out)):out;
}
const rowKey=(r,type)=>type==='players'?normalize(r.name).replace(/[\s.]/g,''):known(r.round)?'round:'+r.round:'date:'+r.date+'|'+normalize(r.opponent);
function mergeFields(a,b,conflicts,path){
 const out={...a,_fieldSources:{...cleanFieldSources(a._fieldSources)}};const incomingSources=cleanFieldSources(b._fieldSources);
 for(const [k,v] of Object.entries(b)){if(k.startsWith('_')||k==='id'||!known(v))continue;
 if(!known(out[k])){out[k]=v;if(incomingSources[k])out._fieldSources[k]=incomingSources[k];}
 else if(!equivalentField(k,out[k],v)&&k!=='raw'){
 const firstRank=fieldRank(out,k),secondRank=incomingSources[k]?.rank||0;
 const strengthField=['myStrength','rivalStrength','strength'].includes(k),firstKind=out._fieldSources[k]?.kind,secondKind=incomingSources[k]?.kind;
 const consensusWins=strengthField&&firstRank===3&&secondRank===3&&((firstKind==='ocr-consensus'&&secondKind==='pixels')||(secondKind==='ocr-consensus'&&firstKind==='pixels'));
 const resolved=Math.max(firstRank,secondRank)>=2&&firstRank!==secondRank||consensusWins;
 const first=out[k],firstSource=out._fieldSources[k]?.source||a._source||'Leitura anterior',secondSource=incomingSources[k]?.source||b._source||'Nova leitura';
 if(resolved&&(secondRank>firstRank||consensusWins&&secondKind==='ocr-consensus')){out[k]=v;out._fieldSources[k]=incomingSources[k];}
 conflicts.push({field:path+'.'+k,first,second:v,resolved,preferred:out[k],firstSource,secondSource,firstRank,secondRank,firstKind,secondKind});
 }else if((incomingSources[k]?.rank||0)>fieldRank(out,k)||['myStrength','rivalStrength','strength'].includes(k)&&incomingSources[k]?.kind==='ocr-consensus'&&out._fieldSources[k]?.kind==='pixels')out._fieldSources[k]=incomingSources[k];
 }return out;
}
const calendarHasSchedule=row=>['round','date','time'].some(key=>known(row[key]));
const calendarValue=(key,value)=>['opponent','nickname','stage'].includes(key)?normalize(value).replace(/\s+/g,' '):String(value);
const cupStage=value=>{const text=normalize(value).replace(/[- ]+/g,' ').trim();if(/^(?:meias? finais?|semi ?finais?)$/.test(text))return 'semifinal';if(/^(?:quartos?(?: de)? final)$/.test(text))return 'quarterfinal';if(/^(?:oitavos?(?: de)? final)$/.test(text))return 'last16';return /^(?:final|preliminar(?:es)?(?: (?:da |de )?copa)?|qualificacao)$/.test(text)?text:null;};
function compatibleFixtureField(a,b,key){
 if(!known(a[key])||!known(b[key])||calendarValue(key,a[key])===calendarValue(key,b[key]))return true;
 if(known(a.round)&&known(b.round)&&a.round!==b.round)return false;
 const firstRank=fieldRank(a,key),secondRank=fieldRank(b,key);
 return Math.max(firstRank,secondRank)>=2&&firstRank!==secondRank;
}
function datedCalendarIdentity(a,b){
 if(!known(a.date)||a.date!==b.date||!['time','home','cup'].every(key=>compatibleFixtureField(a,b,key)))return false;
 if(known(a.round)&&known(b.round)&&a.round!==b.round&&Math.min(fieldRank(a,'round'),fieldRank(b,'round'))>=2)return false;
 if(known(a.opponent)&&known(b.opponent)&&calendarValue('opponent',a.opponent)===calendarValue('opponent',b.opponent))return true;
 // An undrawn cup fixture still has an observable date and phase. Its NI rival
 // is expected; keep it once instead of requiring a fabricated opponent.
 const phaseA=cupStage(a.stage),phaseB=cupStage(b.stage);
 if(a.cup===true&&b.cup===true&&phaseA&&phaseA===phaseB&&(!known(a.opponent)||!known(b.opponent)))return true;
 // OCR can read a round or club name incompletely. Match a uniquely dated card
 // to its numbered counterpart only with a literal date and another matching
 // venue/competition/time marker. Different explicit rounds remain distinct.
 if(known(a.round)===known(b.round)||Math.max(fieldRank(a,'date'),fieldRank(b,'date'))<2)return false;
 if(known(a.stage)&&known(b.stage)&&calendarValue('stage',a.stage)!==calendarValue('stage',b.stage))return false;
 return ['home','cup','time'].some(key=>known(a[key])&&known(b[key])&&calendarValue(key,a[key])===calendarValue(key,b[key])&&Math.max(fieldRank(a,key),fieldRank(b,key))>=2);
}
const calendarCompatible=(a,b)=>['date','time','opponent','nickname','home','cup','score','displayedScore','result','stage'].every(key=>!known(a[key])||!known(b[key])||calendarValue(key,a[key])===calendarValue(key,b[key]));
function calendarPartialIdentity(a,b){
 // An opponent can appear in both legs. Its name alone never identifies a game.
 if(!calendarHasSchedule(a)||!calendarHasSchedule(b)||known(a.round)&&known(b.round)||!known(a.opponent)||!known(b.opponent)||calendarValue('opponent',a.opponent)!==calendarValue('opponent',b.opponent)||!calendarCompatible(a,b))return false;
 const same=key=>known(a[key])&&known(b[key])&&calendarValue(key,a[key])===calendarValue(key,b[key]);
 return ['home','time','stage'].some(same)||(same('cup')&&['score','displayedScore'].some(same));
}
function fuseCalendar(rows,conflicts){
 const calendar=[],fragments=[];
 for(const row of cleanCalendar(rows,conflicts)){
 if(!calendarHasSchedule(row)){
 const key=JSON.stringify(Object.fromEntries(['opponent','nickname','home','cup','score','displayedScore','result','stage'].map(k=>[k,calendarValue(k,row[k])])));
 if(!fragments.some(fragment=>fragment.key===key))fragments.push({key,row});
 continue;
 }
 const identity=known(row.date)?'date:'+row.date+'|'+(known(row.opponent)?normalize(row.opponent):'stage:'+cupStage(row.stage)):null;
 const exactMatches=calendar.map((old,index)=>datedCalendarIdentity(old,row)?index:-1).filter(index=>index>=0);
 const exact=exactMatches.length===1?exactMatches[0]:-1;
 const byRound=calendar.findIndex(old=>known(row.round)&&known(old.round)&&old.round===row.round);
 const partial=calendar.map((old,index)=>calendarPartialIdentity(old,row)?index:-1).filter(index=>index>=0);
 const index=exact>=0?exact:byRound>=0?byRound:partial.length===1?partial[0]:-1;
 if(index<0){calendar.push(row);continue;}
 const old=calendar[index],key=identity||rowKey(old,'calendar');
 const oldRank=fieldRank(old,'round'),newRank=fieldRank(row,'round'),roundDiffers=known(old.round)&&known(row.round)&&old.round!==row.round;
 const priorRoundConflicts=identity?conflicts.filter(conflict=>conflict.field===identity+'.round'&&!conflict.resolved):[];
 for(const prior of priorRoundConflicts)if(known(row.round)&&newRank>=2&&newRank>Math.max(prior.firstRank||0,prior.secondRank||0)){prior.resolved=true;prior.preferred=row.round;prior.resolutionSource=row._fieldSources?.round?.source;}
 const authoritativeRound=roundDiffers&&Math.max(oldRank,newRank)>=2&&oldRank!==newRank;
 const roundConflict=identity&&((roundDiffers&&!authoritativeRound)||priorRoundConflicts.some(conflict=>!conflict.resolved));
 if(roundConflict&&roundDiffers)conflicts.push({field:identity+'.round',first:old.round,second:row.round,firstRank:oldRank,secondRank:newRank,resolved:false});
 const merged=mergeFields(old,roundConflict?{...row,round:null}:row,conflicts,key);
 if(roundConflict){merged.round=null;delete merged._fieldSources.round;}
 merged._source=[...new Set([old._source,row._source].filter(known))].join(' + ');
 calendar[index]=merged;
 }
 const ordered=cleanCalendar(calendar,conflicts).sort((a,b)=>{
 if(known(a.round)&&known(b.round))return a.round-b.round;
 if(known(a.round)!==known(b.round))return known(a.round)?-1:1;
 if(known(a.date)&&known(b.date))return a.date.split('/').reverse().join('').localeCompare(b.date.split('/').reverse().join(''));
 return 0;
 });
 return {calendar:ordered,calendarFragments:fragments.map(fragment=>fragment.row)};
}
function oneCharacterApart(a,b){
 if(Math.abs(a.length-b.length)>1||Math.min(a.length,b.length)<4)return false;
 let i=0,j=0,difference=0;
 while(i<a.length&&j<b.length){if(a[i]===b[j]){i++;j++;continue;}if(++difference>1)return false;if(a.length>=b.length)i++;if(b.length>=a.length)j++;}
 return difference+(i<a.length||j<b.length?1:0)===1;
}
function playerOcrIdentity(a,b){
 const name=value=>normalize(value).replace(/[^\p{L}\p{N}]/gu,'');
 if(!known(a._source)||!known(b._source)||a._source===b._source)return false;
 if(!oneCharacterApart(name(a.name),name(b.name)))return false;
 const equal=(key,x,y)=>key==='value'?String(x).toLowerCase().replace(/\s/g,'').replace(',','.')===String(y).toLowerCase().replace(/\s/g,'').replace(',','.'):String(x)===String(y);
 const keys=['age','value','strength','position'];
 if(keys.some(key=>known(a[key])&&known(b[key])&&!equal(key,a[key],b[key])))return false;
 const anchors=keys.filter(key=>key!=='position'&&known(a[key])&&known(b[key])&&equal(key,a[key],b[key]));
 // A near-spelled name is not sufficient. Require two observed attributes and
 // literal OCR for at least one, from separate readings. The differing name
 // remains a review conflict; two names in the same source remain separate.
 return anchors.length>=2&&anchors.some(key=>Math.max(fieldRank(a,key),fieldRank(b,key))>=2);
}
function uniqueConflicts(conflicts){
 const records=new Map();
 for(const conflict of conflicts){
 const field=String(conflict.field||''),fieldKey=field.split('.').at(-1);if(equivalentField(fieldKey,conflict.first,conflict.second))continue;
 const key=field+'|'+[conflict.first,conflict.second].map(value=>JSON.stringify(textIdentityFields.has(fieldKey)?normalize(value).replace(/\s+/g,' '):value)).sort().join('|');
 const prior=records.get(key),ranks=entry=>[Math.max(entry.firstRank||0,entry.secondRank||0),Math.min(entry.firstRank||0,entry.secondRank||0)];
 const currentRanks=ranks(conflict),priorRanks=prior?ranks(prior):[-1,-1];
 // A newly conflicting pair of literal readings must reopen an earlier weak
 // disagreement even when both pairs happen to contain the same two values.
 if(!prior||currentRanks[0]>priorRanks[0]||currentRanks[0]===priorRanks[0]&&currentRanks[1]>priorRanks[1]||currentRanks[0]===priorRanks[0]&&currentRanks[1]===priorRanks[1]&&!prior.resolved&&conflict.resolved)records.set(key,conflict);
 }return [...records.values()];
}
function outranksConflict(source,conflict){
 const maximum=Math.max(conflict.firstRank||0,conflict.secondRank||0);
 if(source.rank>maximum)return true;
 // A previously agreeing crop still strengthens the provenance. Its two
 // literal passes can also settle older template-vs-template disagreements,
 // while a different two-pass observation remains a real disagreement.
 return source.kind==='ocr-consensus'&&source.rank===3&&maximum===3&&['first','second'].every(side=>(conflict[side+'Rank']||0)<3||conflict[side+'Kind']==='pixels');
}
function settleMatchConflicts(data){
 for(const conflict of data.conflicts||[]){
 if(!conflict.field?.startsWith('match.'))continue;
 const key=conflict.field.slice(6),value=data.match[key],source=data._matchFieldSources[key];
 if(!known(value)||!source||source.rank<2||!outranksConflict(source,conflict))continue;
 if(!equivalentField(key,value,conflict.first)&&!equivalentField(key,value,conflict.second)&&!['ocr-consensus','manual'].includes(source.kind))continue;
 conflict.resolved=true;conflict.preferred=value;conflict.resolutionSource=source.source;
 }return data;
}
function settleMetaConflicts(data){
 for(const conflict of data.conflicts||[]){
  if(!conflict.field?.startsWith('meta.'))continue;
  const key=conflict.field.slice(5),value=data.meta[key],source=data.meta._fieldSources?.[key];
  if(!known(value)||!source||source.rank<2||!outranksConflict(source,conflict))continue;
  if(!equivalentField(key,value,conflict.first)&&!equivalentField(key,value,conflict.second)&&!['ocr-consensus','manual'].includes(source.kind))continue;
  conflict.resolved=true;conflict.preferred=value;conflict.resolutionSource=source.source;
 }return data;
}
export function fuseExtraction(first,second){
 const a=first||blankExtraction(),b=second||blankExtraction(),conflicts=[...(a.conflicts||[]),...(b.conflicts||[])].map(conflict=>({...conflict}));
 const match=mergeFields({...a.match,_fieldSources:a._matchFieldSources||a.match?._fieldSources},{...b.match,_fieldSources:b._matchFieldSources||b.match?._fieldSources},conflicts,'match');
 const matchFieldSources=match._fieldSources;delete match._fieldSources;
 const out={...a,match,_matchFieldSources:matchFieldSources,players:[...(a.players||[])],calendar:[...(a.calendar||[])],calendarFragments:[...(a.calendarFragments||[])],sources:[...new Set([...(a.sources||[]),...(b.sources||[])])],warnings:[...new Set([...(a.warnings||[]),...(b.warnings||[])])],conflicts,meta:{...a.meta}};
 for(const type of ['players']){
 const map=new Map();
 for(const row of [...(a[type]||[]),...(b[type]||[])]){let key=rowKey(row,type);
 if(!map.has(key)){const candidates=[...map].filter(([,old])=>playerOcrIdentity(old,row));if(candidates.length===1)key=candidates[0][0];}
 const old=map.get(key);if(!old)map.set(key,row);else{
 const merged=mergeFields(old,row,conflicts,key);
 merged._source=[old._source,row._source].filter(Boolean).join(' + ');map.delete(key);map.set(rowKey(merged,type),merged);}}
 out[type]=[...map.values()];
 }
 Object.assign(out,fuseCalendar([...(a.calendar||[]),...(a.calendarFragments||[]),...(b.calendar||[]),...(b.calendarFragments||[])],conflicts));
 for(const fragment of out.calendarFragments)out.warnings.push('Informação parcial do calendário: '+fragment.opponent+'. Sem rodada, data ou horário comprovados; preservada para revisão e não contada como jogo.');
 out.warnings=[...new Set(out.warnings)];
 const am=a.meta||{},bm=b.meta||{};
 const metaRecord=m=>({...Object.fromEntries(metaFactFields.map(key=>[key,m[key]])),_fieldSources:cleanFieldSources(m._fieldSources)});
 const mergedMeta=mergeFields(metaRecord(am),metaRecord(bm),conflicts,'meta');
 Object.assign(out.meta,Object.fromEntries(metaFactFields.map(key=>[key,mergedMeta[key]])),{_fieldSources:mergedMeta._fieldSources});
 if(bm.teamVerified===true&&known(bm.team)&&am.teamVerified!==true){out.meta.team=bm.team;if(bm._fieldSources?.team)out.meta._fieldSources.team=bm._fieldSources.team;}
 out.meta.teamVerified=(am.teamVerified===true&&known(am.team))||(bm.teamVerified===true&&known(bm.team)&&normalize(out.meta.team)===normalize(bm.team));
 out.meta.rivalReportLocked=am.rivalReportLocked===true||bm.rivalReportLocked===true;
 if(out.meta.rivalReportLocked&&(!known(out.match.secretTraining)||(out._matchFieldSources.secretTraining?.rank||0)<2))out.match.secretTraining='Sim';
 out.meta.sawTop=am.sawTop===true||bm.sawTop===true;out.meta.sawBottom=am.sawBottom===true||bm.sawBottom===true;
 for(const field of ['sawTop','sawBottom']){const first=am[field]===true?am._fieldSources?.[field]:null,second=bm[field]===true?bm._fieldSources?.[field]:null;if(second&&(!first||second.rank>first.rank))out.meta._fieldSources[field]={...second};else if(first)out.meta._fieldSources[field]={...first};}
 out.meta.visibleNames=[...new Set([...(am.visibleNames||[]),...(bm.visibleNames||[])])];
 out.meta.hiddenFields=[...new Set([...(am.hiddenFields||[]),...(bm.hiddenFields||[])])];
 out.conflicts=uniqueConflicts(conflicts);
 settleMatchConflicts(out);
 settleMetaConflicts(out);
 const pending=cleanPendingMatchFacts([...(am.pendingMatchFacts||[]),...(bm.pendingMatchFacts||[])]);
 out.meta.pendingMatchFacts=[...new Map(pending.map(fact=>[fact.field+'|'+JSON.stringify(fact.value)+'|'+fact.reason,fact])).values()].filter(fact=>!known(out.match[fact.field])||(out._matchFieldSources[fact.field]?.rank||0)<2||out.conflicts.some(conflict=>conflict.field==='match.'+fact.field&&!conflict.resolved));
 return enforceMatchConsistency(enforceReportPrivacy(out));
}
export function coverage(type,data){
 const missing=[],hidden=[],notApplicable=[];let present=0,total=0,checksPassed=0,checksTotal=0;
 const check=(passed,label)=>{checksTotal++;if(passed)checksPassed++;else missing.push(label);};
 if(type==='match'){for(const [key,label] of matchFields){if(confirmedReportLock(data)&&reportTacticFields.includes(key)&&!known(data.match?.[key])){hidden.push(label);continue;}if(key==='rivalNickname'&&data.match?.human===false&&(data._matchFieldSources?.human?.rank||0)>=2&&!known(data.match?.[key])){notApplicable.push(label+' (CPU)');continue;}total++;const pending=(data.meta?.pendingMatchFacts||[]).some(fact=>fact.field===key)&&(data._matchFieldSources?.[key]?.rank||0)<2;if(known(data.match?.[key])&&!pending)present++;else missing.push(label+(pending?' (leitura sem comprovação)':''));}}
 else{
 const rows=type==='squad'?data.players:data.calendar;
 const fields=type==='squad'?['name','position','strength','age','value','forSale','training']:['round','date','time','opponent','home','cup','score','result'];
 // OSM can show a future date or just the next game's clock. These are observed
 // alternatives, never a license to manufacture the missing timestamp component.
 // Past scoreboards still require their date and the correctly oriented result.
 for(const [i,row] of (rows||[]).entries())for(const f of fields){
 const played=known(row.result)||known(row.score)||known(row.displayedScore);
 if(type==='calendar'&&((['score','result'].includes(f)&&!played)||(f==='time'&&(played||known(row.date)))||(f==='date'&&!played&&known(row.time))))continue;
 if(type==='calendar'&&f==='opponent'&&!played&&row.cup===true&&cupStage(row.stage)&&!known(row.opponent))continue;
 total++;if(known(row[f]))present++;else missing.push((type==='squad'?row.name:known(row.round)?'Rodada '+row.round:known(row.date)?'Jogo em '+row.date:'Jogo sem rodada '+(i+1))+': '+f);
 }
 check(!!rows?.length,type==='squad'?'Nenhum jogador reconhecido':'Nenhum jogo reconhecido');
 if(type==='calendar'&&data.calendarFragments?.length)check(false,data.calendarFragments.length+' informações parciais sem rodada, data ou horário');
 check(data.meta?.sawTop===true,'Início da lista não confirmado');
 check(data.meta?.sawBottom===true,'Fim da lista não confirmado');
 const expected=num(type==='squad'?data.meta?.expectedPlayers:data.meta?.expectedRounds);
 if(expected!==null)check(expected===(rows||[]).length,'Quantidade esperada '+expected+', capturada '+(rows||[]).length);
 }
 const conflicts=uniqueConflicts(relevantConflicts(type,data.conflicts||[])),unresolved=conflicts.filter(conflict=>!conflict.resolved).length,resolved=conflicts.length-unresolved;
 // Contradictions and unobserved list boundaries are requirements of a complete
 // reading too. Counting them prevents a filled but disputed form from reporting
 // 100%, and flooring prevents one absent date in 34 games from rounding to 100%.
 const required=total+checksTotal+unresolved,fulfilled=present+checksPassed;
 return {percent:required?Math.floor(fulfilled/required*100):0,filledFields:present,requiredFields:total,checksPassed,checksTotal,pendingCount:missing.length+unresolved,missing,hidden,notApplicable,conflicts:unresolved,resolvedConflicts:resolved,complete:missing.length===0&&!unresolved,fragmentCount:type==='calendar'?data.calendarFragments?.length||0:0,detectedCount:type==='calendar'?new Set((data.calendar||[]).map(row=>row.round).filter(known)).size:type==='squad'?(data.players||[]).length:present,count:type==='squad'?(data.players||[]).length:type==='calendar'?(data.calendar||[]).filter(r=>calendarHasSchedule(r)&&['opponent','stage','date','time','result','displayedScore','score'].some(k=>known(r[k]))).length:present};
}

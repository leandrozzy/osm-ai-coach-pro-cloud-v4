import {cleanMatch,cleanPlayers,matchFields,known,num,scoreOutcome} from './domain.js';
import {normalize} from './utils.js';
const safeText=(v,max=100)=>known(v)&&['string','number'].includes(typeof v)?String(v).trim().slice(0,max):'NI';
export const blankExtraction=()=>({match:{},_matchFieldSources:{},players:[],calendar:[],calendarFragments:[],meta:{},sources:[],conflicts:[],warnings:[]});
const evidenceKinds={visual:1,'ocr-layout':2,'ocr-explicit':2,pixels:3,manual:4};
const textIdentityFields=new Set(['name','opponent','nickname','stage','myName','rivalName','rivalNickname']);
function equivalentField(key,a,b){return textIdentityFields.has(key)?normalize(a).replace(/\s+/g,' ')===normalize(b).replace(/\s+/g,' '):String(a)===String(b);}
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
 const preserve=options.preserveEvidence===true||!source||['pixels','ocr-layout','ocr-explicit','manual'].includes(kind);
 const sources=preserve?cleanFieldSources(prior):{};
 const allowed=kind==='pixels'?(type==='calendar'?['home','cup','result']:type==='squad'?['training','forSale']:['secretTraining','referee']):kind==='ocr-layout'?(type==='calendar'?['round','date','time','displayedScore']:type==='squad'?['position','strength','age','value']:[]):kind==='ocr-explicit'?matchFields.map(([key])=>key).filter(key=>!textIdentityFields.has(key)):Object.keys(record);
 const selected=Array.isArray(options.fields)?options.fields:allowed;
 for(const key of Object.keys(record)){
 if(key.startsWith('_')||key==='id'||!known(record[key]))continue;
 const trusted=kind&&allowed.includes(key)&&selected.includes(key);
 if(trusted&&!(options.preserveEvidence===true&&(sources[key]?.rank||0)>evidenceKinds[kind]))sources[key]={kind,rank:evidenceKinds[kind],source:safeText(source||kind,160)};
 else if(!sources[key]&&kind)sources[key]={kind:'visual',rank:1,source:safeText(source,160)};
 }
 return Object.fromEntries(Object.entries(sources).filter(([key])=>known(record[key])));
}
const fieldRank=(row,key)=>Number(row?._fieldSources?.[key]?.rank)||0;
const derivedSource=(rank,source)=>({kind:'derived',rank,source});
export function normalizeDate(v){
 const m=String(v||'').match(/^(\d{1,2})[/-](\d{1,2})[/-](\d{2}|\d{4})$/);
 if(!m)return 'NI';
 const year=m[3].length===2?'20'+m[3]:m[3];
 const d=new Date(+year,+m[2]-1,+m[1]);if(d.getDate()!==+m[1]||d.getMonth()!==+m[2]-1)return 'NI';
 return m[1].padStart(2,'0')+'/'+m[2].padStart(2,'0')+'/'+year;
}
export function cleanCalendar(rows=[],conflicts=null){
 return rows.filter(r=>r&&typeof r==='object'&&(known(r.round)||known(r.opponent))).slice(0,150).map(r=>{
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
 const m=raw.meta;out.meta={rivalReportLocked:m.rivalReportLocked===true,team:safeText(m.team),cash:safeText(m.cash),squadValue:safeText(m.squadValue),...Object.fromEntries(['strength','GK','DEF','MID','ATT'].map(k=>[k,num(m[k])!==null&&num(m[k])>0&&num(m[k])<=400?num(m[k]):null])),expectedPlayers:num(m.expectedPlayers),expectedRounds:num(m.expectedRounds),sawTop:m.sawTop===true,sawBottom:m.sawBottom===true,visibleNames:Array.isArray(m.visibleNames)?m.visibleNames.filter(n=>typeof n==='string').slice(0,80):[],hiddenFields:Array.isArray(m.hiddenFields)?m.hiddenFields.filter(k=>matchFields.some(([f])=>f===k)):[]};
 }
 if(type==='match'&&out.meta.rivalReportLocked)out.match.secretTraining='Sim';
 if(type==='match')out._matchFieldSources=evidenceFor(out.match,type,source,options,raw._matchFieldSources||raw.match?._fieldSources);
 if(source)out.sources=[source];return out;
}
const rowKey=(r,type)=>type==='players'?normalize(r.name).replace(/[\s.]/g,''):known(r.round)?'round:'+r.round:'date:'+r.date+'|'+normalize(r.opponent);
function mergeFields(a,b,conflicts,path){
 const out={...a,_fieldSources:{...cleanFieldSources(a._fieldSources)}};const incomingSources=cleanFieldSources(b._fieldSources);
 for(const [k,v] of Object.entries(b)){if(k.startsWith('_')||k==='id'||!known(v))continue;
 if(!known(out[k])){out[k]=v;if(incomingSources[k])out._fieldSources[k]=incomingSources[k];}
 else if(!equivalentField(k,out[k],v)&&k!=='raw'){
 const firstRank=fieldRank(out,k),secondRank=incomingSources[k]?.rank||0,resolved=Math.max(firstRank,secondRank)>=2&&firstRank!==secondRank;
 const first=out[k],firstSource=out._fieldSources[k]?.source||a._source||'Leitura anterior',secondSource=incomingSources[k]?.source||b._source||'Nova leitura';
 if(resolved&&secondRank>firstRank){out[k]=v;out._fieldSources[k]=incomingSources[k];}
 conflicts.push({field:path+'.'+k,first,second:v,resolved,preferred:out[k],firstSource,secondSource,firstRank,secondRank});
 }else if((incomingSources[k]?.rank||0)>fieldRank(out,k))out._fieldSources[k]=incomingSources[k];
 }return out;
}
const calendarHasSchedule=row=>['round','date','time'].some(key=>known(row[key]));
const calendarValue=(key,value)=>['opponent','nickname','stage'].includes(key)?normalize(value).replace(/\s+/g,' '):String(value);
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
 const identity=known(row.date)&&known(row.opponent)?'date:'+row.date+'|'+normalize(row.opponent):null;
 const exactMatches=calendar.map((old,index)=>identity&&old.date===row.date&&calendarValue('opponent',old.opponent)===calendarValue('opponent',row.opponent)&&['time','home','cup'].every(key=>{
 if(!known(old[key])||!known(row[key])||calendarValue(key,old[key])===calendarValue(key,row[key]))return true;
 // Correct an uncertain field within the same fixture, while preserving two
 // explicitly different rounds whose venue/competition identifies separate games.
 if(known(old.round)&&known(row.round)&&old.round!==row.round)return false;
 const oldRank=fieldRank(old,key),newRank=fieldRank(row,key);
 return Math.max(oldRank,newRank)>=2&&oldRank!==newRank;
 })?index:-1).filter(index=>index>=0);
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
 return {calendar:cleanCalendar(calendar,conflicts),calendarFragments:fragments.map(fragment=>fragment.row)};
}
export function fuseExtraction(first,second){
 const a=first||blankExtraction(),b=second||blankExtraction(),conflicts=[...(a.conflicts||[]),...(b.conflicts||[])].map(conflict=>({...conflict}));
 const match=mergeFields({...a.match,_fieldSources:a._matchFieldSources||a.match?._fieldSources},{...b.match,_fieldSources:b._matchFieldSources||b.match?._fieldSources},conflicts,'match');
 const matchFieldSources=match._fieldSources;delete match._fieldSources;
 const out={...a,match,_matchFieldSources:matchFieldSources,players:[...(a.players||[])],calendar:[...(a.calendar||[])],calendarFragments:[...(a.calendarFragments||[])],sources:[...new Set([...(a.sources||[]),...(b.sources||[])])],warnings:[...new Set([...(a.warnings||[]),...(b.warnings||[])])],conflicts,meta:{...a.meta}};
 for(const type of ['players']){
 const map=new Map(out[type].map(r=>[rowKey(r,type),r]));
 for(const row of b[type]||[]){let key=rowKey(row,type);
 const old=map.get(key);if(!old)map.set(key,row);else{
 const merged=mergeFields(old,row,conflicts,key);
 merged._source=[old._source,row._source].filter(Boolean).join(' + ');map.delete(key);map.set(rowKey(merged,type),merged);}}
 out[type]=[...map.values()];
 }
 Object.assign(out,fuseCalendar([...(a.calendar||[]),...(a.calendarFragments||[]),...(b.calendar||[]),...(b.calendarFragments||[])],conflicts));
 for(const fragment of out.calendarFragments)out.warnings.push('Informação parcial do calendário: '+fragment.opponent+'. Sem rodada, data ou horário comprovados; preservada para revisão e não contada como jogo.');
 out.warnings=[...new Set(out.warnings)];
 const am=a.meta||{},bm=b.meta||{};
 for(const k of ['team','cash','squadValue','strength','GK','DEF','MID','ATT','expectedPlayers','expectedRounds'])if(!known(out.meta[k])&&known(bm[k]))out.meta[k]=bm[k];
 out.meta.rivalReportLocked=am.rivalReportLocked===true||bm.rivalReportLocked===true;
 if(out.meta.rivalReportLocked&&!(out._matchFieldSources.secretTraining?.rank>=3&&out.match.secretTraining==='Não'))out.match.secretTraining='Sim';
 out.meta.sawTop=am.sawTop===true||bm.sawTop===true;out.meta.sawBottom=am.sawBottom===true||bm.sawBottom===true;
 out.meta.visibleNames=[...new Set([...(am.visibleNames||[]),...(bm.visibleNames||[])])];
 out.meta.hiddenFields=[...new Set([...(am.hiddenFields||[]),...(bm.hiddenFields||[])])];
 out.conflicts=[...new Map(conflicts.map(c=>[JSON.stringify(c),c])).values()];
 return out;
}
export function coverage(type,data){
 const missing=[];let present=0,total=0;
 if(type==='match'){for(const [key,label] of matchFields){total++;if(known(data.match?.[key]))present++;else missing.push(label);}}
 else{
 const rows=type==='squad'?data.players:data.calendar;
 const fields=type==='squad'?['name','position','strength','age','value','forSale','training']:['round','date','time','opponent','home','cup','score','result'];
 // Future cards show a date without an hour. Past scoreboards require a result,
 // even if home/away is still missing and the displayed score cannot be inverted.
 for(const [i,row] of (rows||[]).entries())for(const f of fields){
 const played=known(row.result)||known(row.score)||known(row.displayedScore);
 if(type==='calendar'&&((['score','result'].includes(f)&&!played)||(f==='time'&&(played||known(row.date)))))continue;
 total++;if(known(row[f]))present++;else missing.push((type==='squad'?row.name:'Rodada '+(row.round??i+1))+': '+f);
 }
 if(!rows?.length)missing.push(type==='squad'?'Nenhum jogador reconhecido':'Nenhum jogo reconhecido');
 if(type==='calendar'&&data.calendarFragments?.length)missing.push(data.calendarFragments.length+' informações parciais sem rodada, data ou horário');
 if(!data.meta?.sawTop)missing.push('Início da lista não confirmado');
 if(!data.meta?.sawBottom)missing.push('Fim da lista não confirmado');
 const expected=num(type==='squad'?data.meta?.expectedPlayers:data.meta?.expectedRounds);
 if(expected!==null&&expected!==(rows||[]).length)missing.push('Quantidade esperada '+expected+', capturada '+(rows||[]).length);
 }
 const unresolved=(data.conflicts||[]).filter(conflict=>!conflict.resolved).length,resolved=(data.conflicts||[]).length-unresolved;
 return {percent:total?Math.round(present/total*100):0,missing,conflicts:unresolved,resolvedConflicts:resolved,complete:missing.length===0&&!unresolved,fragmentCount:type==='calendar'?data.calendarFragments?.length||0:0,detectedCount:type==='calendar'?data.calendar.filter(calendarHasSchedule).length:type==='squad'?data.players.length:present,count:type==='squad'?data.players.length:type==='calendar'?data.calendar.filter(r=>calendarHasSchedule(r)&&['opponent','stage','date','time','result','displayedScore','score'].some(k=>known(r[k]))).length:present};
}

import {cleanMatch,cleanPlayers,matchFields,known,num,scoreOutcome,positions} from './domain.js';
import {normalize} from './utils.js';
const safeText=(v,max=100)=>known(v)&&['string','number'].includes(typeof v)?String(v).trim().slice(0,max):'NI';
export const blankExtraction=()=>({match:{},players:[],calendar:[],meta:{},sources:[],conflicts:[],warnings:[]});
export function normalizeDate(v){
 const m=String(v||'').match(/^(\d{1,2})[/-](\d{1,2})[/-](\d{2}|\d{4})$/);
 if(!m)return 'NI';
 const year=m[3].length===2?'20'+m[3]:m[3];
 const d=new Date(+year,+m[2]-1,+m[1]);if(d.getDate()!==+m[1]||d.getMonth()!==+m[2]-1)return 'NI';
 return m[1].padStart(2,'0')+'/'+m[2].padStart(2,'0')+'/'+year;
}
export function cleanCalendar(rows=[]){
 return rows.filter(r=>r&&typeof r==='object'&&(known(r.round)||known(r.opponent))).slice(0,150).map(r=>{
 const result=['V','E','D'].includes(r.result)?r.result:'NI';
 const home=typeof r.home==='boolean'?r.home:null;
 let score=known(r.score)&&scoreOutcome(r.score)?String(r.score).replace(/[×:-]/,'x').replace(/\s/g,''):'NI';
 // displayedScore is a home/away scoreboard. score is always our team first.
 if(known(r.displayedScore)&&scoreOutcome(r.displayedScore)&&home!==null){const [a,b]=String(r.displayedScore).split(/[x×:-]/).map(Number);score=home?a+'x'+b:b+'x'+a;}
 if(known(score)&&known(result)&&scoreOutcome(score)!==result)score='NI';
 return {id:typeof r.id==='string'?r.id:crypto.randomUUID(),round:num(r.round),date:normalizeDate(r.date),time:/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(r.time)?r.time:'NI',opponent:safeText(r.opponent),nickname:safeText(r.nickname),home,cup:typeof r.cup==='boolean'?r.cup:null,score,displayedScore:known(r.displayedScore)&&scoreOutcome(r.displayedScore)?String(r.displayedScore).replace(/[×:-]/,'x').replace(/\s/g,''):'NI',result,stage:safeText(r.stage),_source:safeText(r._source,160)};
 });
}
export function normalizeExtraction(raw={},type,source=''){
 const out=blankExtraction();if(!raw||typeof raw!=='object')return out;
 const payload=raw.data&&typeof raw.data==='object'?raw.data:raw;
 if(type==='match')out.match=cleanMatch(payload.match||payload);
 if(payload!==raw)raw=payload;
 if(type==='squad')out.players=cleanPlayers(Array.isArray(raw.players)?raw.players:[]).map(p=>({...p,_source:source}));
 if(type==='calendar')out.calendar=cleanCalendar(Array.isArray(raw.calendar)?raw.calendar:[]).map(r=>({...r,_source:source}));
 if(raw.meta&&typeof raw.meta==='object'){
 const m=raw.meta;out.meta={rivalReportLocked:m.rivalReportLocked===true,team:safeText(m.team),cash:safeText(m.cash),squadValue:safeText(m.squadValue),...Object.fromEntries(['strength','GK','DEF','MID','ATT'].map(k=>[k,num(m[k])!==null&&num(m[k])>0&&num(m[k])<=400?num(m[k]):null])),expectedPlayers:num(m.expectedPlayers),expectedRounds:num(m.expectedRounds),sawTop:m.sawTop===true,sawBottom:m.sawBottom===true,visibleNames:Array.isArray(m.visibleNames)?m.visibleNames.filter(n=>typeof n==='string').slice(0,80):[],hiddenFields:Array.isArray(m.hiddenFields)?m.hiddenFields.filter(k=>matchFields.some(([f])=>f===k)):[]};
 }
 if(type==='match'&&out.meta.rivalReportLocked)out.match.secretTraining='Sim';
 if(source)out.sources=[source];return out;
}
const rowKey=(r,type)=>type==='players'?normalize(r.name).replace(/[\s.]/g,''):known(r.round)?'round:'+r.round:'date:'+r.date+'|'+normalize(r.opponent);
function mergeFields(a,b,conflicts,path){
 const out={...a};for(const [k,v] of Object.entries(b)){if(k.startsWith('_')||k==='id'||!known(v))continue;
 if(!known(out[k]))out[k]=v;
 else if(String(out[k])!==String(v)&&k!=='raw'){conflicts.push({field:path+'.'+k,first:out[k],second:v});}
 }return out;
}
export function fuseExtraction(first,second){
 const a=first||blankExtraction(),b=second||blankExtraction(),conflicts=[...(a.conflicts||[]),...(b.conflicts||[])];
 const out={...a,match:mergeFields(a.match||{},b.match||{},conflicts,'match'),players:[...(a.players||[])],calendar:[...(a.calendar||[])],sources:[...new Set([...(a.sources||[]),...(b.sources||[])])],warnings:[...new Set([...(a.warnings||[]),...(b.warnings||[])])],conflicts,meta:{...a.meta}};
 for(const type of ['players','calendar']){
 const map=new Map(out[type].map(r=>[rowKey(r,type),r]));
 for(const row of b[type]||[]){let key=rowKey(row,type);
 const identity=type==='calendar'&&known(row.date)&&known(row.opponent)?'date:'+row.date+'|'+normalize(row.opponent):null;
 if(identity){const match=[...map].find(([,r])=>r.date===row.date&&normalize(r.opponent)===normalize(row.opponent));if(match)key=match[0];}
 const old=map.get(key);if(!old)map.set(key,row);else{
 const roundConflict=identity&&((known(old.round)&&known(row.round)&&old.round!==row.round)||conflicts.some(c=>c.field===identity+'.round'));
 if(roundConflict&&known(old.round)&&known(row.round)&&old.round!==row.round)conflicts.push({field:identity+'.round',first:old.round,second:row.round});
 const merged=mergeFields(old,roundConflict?{...row,round:null}:row,conflicts,key);if(roundConflict)merged.round=null;
 merged._source=[old._source,row._source].filter(Boolean).join(' + ');map.delete(key);map.set(rowKey(merged,type),merged);}}
 out[type]=[...map.values()];
 }
 out.calendar=cleanCalendar(out.calendar);
 const am=a.meta||{},bm=b.meta||{};
 for(const k of ['team','cash','squadValue','strength','GK','DEF','MID','ATT','expectedPlayers','expectedRounds'])if(!known(out.meta[k])&&known(bm[k]))out.meta[k]=bm[k];
 out.meta.rivalReportLocked=am.rivalReportLocked===true||bm.rivalReportLocked===true;
 if(out.meta.rivalReportLocked)out.match.secretTraining='Sim';
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
 // Future games do not need a result and past games do not need a time.
 for(const [i,row] of (rows||[]).entries())for(const f of fields){if(type==='calendar'&&((['score','result'].includes(f)&&!known(row.result)&&!known(row.score))||(f==='time'&&(known(row.result)||known(row.score)))))continue;total++;if(known(row[f]))present++;else missing.push((type==='squad'?row.name:'Rodada '+(row.round??i+1))+': '+f);}
 if(!rows?.length)missing.push(type==='squad'?'Nenhum jogador reconhecido':'Nenhum jogo reconhecido');
 if(!data.meta?.sawTop)missing.push('Início da lista não confirmado');
 if(!data.meta?.sawBottom)missing.push('Fim da lista não confirmado');
 const expected=num(type==='squad'?data.meta?.expectedPlayers:data.meta?.expectedRounds);
 if(expected!==null&&expected!==(rows||[]).length)missing.push('Quantidade esperada '+expected+', capturada '+(rows||[]).length);
 }
 return {percent:total?Math.round(present/total*100):0,missing,conflicts:data.conflicts?.length||0,complete:missing.length===0&&!data.conflicts?.length,detectedCount:type==='calendar'?data.calendar.length:type==='squad'?data.players.length:present,count:type==='squad'?data.players.length:type==='calendar'?data.calendar.filter(r=>['opponent','stage','date','time','result','displayedScore','score'].some(k=>known(r[k]))).length:present};
}

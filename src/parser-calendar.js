import {normalize,uid} from './utils.js';
import {known,rowTime} from './domain.js';
export function parseCalendarText(text=''){
 const rows=[];for(const line of text.split(/\n+/)){const dm=line.match(/\b(\d{1,2})[\/-](\d{1,2})[\/-](\d{4})\b/);if(!dm)continue;
 const time=(line.match(/\b([01]?\d|2[0-3]):[0-5]\d\b/)||[])[0]||'NI';
 const opponent=(line.match(/(?:rival|advers[aá]rio)\s*:\s*([^|;]+)/i)||[])[1]?.trim()||'NI';
 rows.push({id:uid(),date:`${dm[1].padStart(2,'0')}/${dm[2].padStart(2,'0')}/${dm[3]}`,time,opponent,round:Number((line.match(/rodada\s*:?\s*(\d+)/i)||[])[1])||null,home:/\bcasa\b/i.test(line)?true:/\bfora\b/i.test(line)?false:null,cup:/\bcopa\b/i.test(line)?true:/\bliga\b/i.test(line)?false:null,result:(line.match(/resultado\s*:\s*([VED])\b/i)||[])[1]?.toUpperCase()||'NI',score:(line.match(/placar\s*:\s*(\d+\s*x\s*\d+)/i)||[])[1]||'NI',raw:line});}return rows;
}
export function mergeCalendar(oldRows=[],newRows=[]){
 const map=new Map(oldRows.map(r=>[known(r.round)?'round:'+r.round:`${r.date}|${normalize(r.opponent)}`,r]));
 for(const r of newRows){const key=known(r.round)?'round:'+r.round:`${r.date}|${normalize(r.opponent)}`;const old=map.get(key)||{};const merged={...old};for(const [k,v] of Object.entries(r)){if(known(v))merged[k]=v;}merged.id=old.id||r.id||uid();map.set(key,merged);}
 return [...map.values()].sort((a,b)=>(rowTime(a)??Infinity)-(rowTime(b)??Infinity));
}


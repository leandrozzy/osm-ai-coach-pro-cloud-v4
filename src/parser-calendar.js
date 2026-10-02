import {normalize, uid} from './utils.js';
export function parseCalendarText(text=''){
 const out=[]; const lines=text.split(/\n+/).map(s=>s.trim()).filter(Boolean);
 for(let i=0;i<lines.length;i++){
   const l=lines[i]; const dm=l.match(/\b(\d{1,2})[\/-](\d{1,2})(?:[\/-](\d{2,4}))?\b/); if(!dm)continue;
   const time=(l.match(/\b([01]?\d|2[0-3]):[0-5]\d\b/)||[])[0]||null;
   const result=(l.match(/\b(V|E|D)\b/i)||[])[1]?.toUpperCase()||null;
   const round=(l.match(/(?:rodada|r)\s*(\d+)/i)||[])[1]||null;
   const cup=/copa|taça|taca/i.test(l);
   const opponent=l.replace(dm[0],'').replace(time||'','').replace(/\b(V|E|D)\b/ig,'').replace(/(?:rodada|r)\s*\d+/i,'').replace(/copa|taça|taca/ig,'').trim()||null;
   out.push({id:uid(),date:`${dm[1].padStart(2,'0')}/${dm[2].padStart(2,'0')}/${dm[3]||new Date().getFullYear()}`,time,round:round?Number(round):null,opponent,result,cup,home:/casa|🏠/i.test(l),raw:l});
 }
 return out;
}
export function mergeCalendar(oldRows=[],newRows=[]){const map=new Map(oldRows.map(r=>[`${r.date}|${r.round??''}|${normalize(r.opponent)}`,r]));for(const r of newRows){const k=`${r.date}|${r.round??''}|${normalize(r.opponent)}`;map.set(k,{...(map.get(k)||{}),...r});}return [...map.values()].sort((a,b)=>a.date.localeCompare(b.date));}

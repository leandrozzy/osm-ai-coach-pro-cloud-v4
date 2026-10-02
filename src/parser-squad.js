import {normalize,uid} from './utils.js';
import {known} from './domain.js';
export function parseSquadText(text=''){
 const rows=[];for(const line of text.split(/\n+/)){const m=line.match(/^\s*(.+?)\s+(ATA|MEI|DEF|GOL)\b(.*)$/i);if(!m)continue;
 const tail=m[3];const pick=label=>{const r=tail.match(new RegExp('(?:'+label+')'+':?\\s*(\\d+)','i'));return r?+r[1]:null;};
 // Números sem rótulo são ambíguos: idade e força permanecem NI.
 rows.push({id:uid(),name:m[1].trim(),position:m[2].toUpperCase(),strength:pick('força|forca'),age:pick('idade'),value:(tail.match(/valor\s*:?\s*([\d.,]+\s*[KMB]?)/i)||[])[1]||'NI',forSale:/venda\s*:\s*sim/i.test(tail)?true:/venda\s*:\s*n[aã]o/i.test(tail)?false:null,training:/treino\s*:\s*sim/i.test(tail)?true:/treino\s*:\s*n[aã]o/i.test(tail)?false:null});}return dedupePlayers(rows);
}
export function dedupePlayers(players=[]){const map=new Map();for(const p of players){const key=normalize(p.name).replace(/[\s.]/g,'');const old=map.get(key);const merged={...(old||p)};for(const [k,v] of Object.entries(p)){if(known(v))merged[k]=v;}map.set(key,merged);}return [...map.values()];}
export function squadSummary(players=[]){const by={ATA:0,MEI:0,DEF:0,GOL:0};players.forEach(p=>{if(p.position in by)by[p.position]++;});return {total:players.length,by,training:players.filter(p=>p.training===true).length,forSale:players.filter(p=>p.forSale===true).length};}

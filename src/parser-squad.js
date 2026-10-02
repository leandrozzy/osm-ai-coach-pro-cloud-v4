import {normalize, toNumber, uid} from './utils.js';
const POS=['ATA','MEI','DEF','GOL'];
export function parseSquadText(text=''){
 const rows=[]; const seen=new Set();
 for(const line of text.split(/\n+/).map(s=>s.trim()).filter(Boolean)){
   const pos=POS.find(p=>new RegExp(`\\b${p}\\b`,'i').test(line)); if(!pos)continue;
   const nums=[...line.matchAll(/\b\d{1,3}\b/g)].map(m=>Number(m[0]));
   const strength=nums.find(n=>n>=20&&n<=200)??null; const age=nums.find(n=>n>=15&&n<=45)??null;
   const name=line.split(new RegExp(`\\b${pos}\\b`,'i'))[0].replace(/^\d+\s*/,'').trim();
   if(!name||name.length<2)continue; const key=normalize(name)+'|'+pos; if(seen.has(key))continue; seen.add(key);
   rows.push({id:uid(),name,position:pos,strength,age,value:null,forSale:/venda|seta|transfer/i.test(line),training:/trein|laranja/i.test(line)});
 }
 return rows;
}
export function dedupePlayers(players=[]){const map=new Map();for(const p of players){const k=normalize(p.name)+'|'+p.position;const old=map.get(k);if(!old||((p.strength||0)>(old.strength||0)))map.set(k,p);}return [...map.values()];}
export function squadSummary(players=[]){const by={ATA:0,MEI:0,DEF:0,GOL:0};players.forEach(p=>by[p.position]=(by[p.position]||0)+1);return {total:players.length,by,training:players.filter(p=>p.training).length,forSale:players.filter(p=>p.forSale).length};}

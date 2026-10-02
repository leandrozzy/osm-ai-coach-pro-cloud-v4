import {squadSummary} from './parser-squad.js';
import {num} from './domain.js';
export const TARGET={ATA:4,MEI:6,DEF:6,GOL:2};
export function buildMarketPlan(squad={players:[]},cash=null,teamStrength=null){
 const players=squad.players||[],summary=squadSummary(players),gaps={};
 for(const p of Object.keys(TARGET))gaps[p]=TARGET[p]-(summary.by[p]||0);
 const available=Math.max(0,4-summary.forSale);
 const excess={...summary.by};
 const sell=[...players].filter(p=>num(p.strength)!==null&&!p.forSale&&p.training!==true).sort((a,b)=>a.strength-b.strength).filter(p=>{if(excess[p.position]<=TARGET[p.position])return false;excess[p.position]--;return true;}).slice(0,available);
 return {createdAt:new Date().toISOString(),currentStrength:num(teamStrength),cash,summary,gaps,sell:sell.map(p=>({...p,reason:'Excedente na posição; menor força entre os disponíveis'})),buy:Object.entries(gaps).filter(([,count])=>count>0).map(([position,count])=>({position,count,profile:'Comparar força/preço e priorizar jovens; preço do mercado = NI'})),rules:['Meta 4 ATA / 6 MEI / 6 DEF / 2 GOL','Máximo de 4 simultaneamente à venda','Não vender abaixo da meta; revisar orçamento antes de comprar']};
}


import {uid,clubKey,sameClub} from './utils.js';
import {known,scoreOutcome} from './domain.js';
const statRanges={cards:30,rivalCards:30,yellowCards:30,rivalYellowCards:30,redCards:11,rivalRedCards:11,shots:100,rivalShots:100,possession:100,rivalPossession:100,corners:50,rivalCorners:50};
function resultStats(result){
 const out={...result};
 for(const [field,max] of Object.entries(statRanges)){
  const raw=result[field];if(!known(raw)){out[field]='NI';continue;}
  if(typeof raw!=='number'&&typeof raw!=='string'||typeof raw==='string'&&!/^\d{1,3}$/.test(raw.trim()))throw Error('Estatística inválida: '+field+'.');
  const value=Number(raw);if(!Number.isInteger(value)||value<0||value>max)throw Error('Estatística inválida: '+field+'.');
  out[field]=value;
 }
 return out;
}
function fixtureMatches(match,row,{snapshot=false,calendar=[]}={}){
 if(!match||!row||!sameClub(match.rivalName,row.opponent))return false;
 if(known(match._calendarId))return match._calendarId===row.id;
 if(snapshot)return false;
 // Old tactics lack snapshots. A repeated opponent needs the actual fixture id.
 return calendar.filter(game=>sameClub(game.opponent,row.opponent)).length===1;
}
function ownClubMatches(match,slot){
 const own=clubKey(slot.myTeam)?slot.myTeam:slot.match?.myName;
 return !clubKey(own)||sameClub(match?.myName,own);
}
function resultContext(slot,row){
 const current=slot.match||{},tactic=slot.tactics,snapshot=tactic?.matchSnapshot;
 const currentMatches=!row||fixtureMatches(current,row,{calendar:slot.calendar||[]});
 let context=currentMatches?structuredClone(current):{_schemaVersion:3,myName:slot.myTeam||current.myName||'NI'};
 let recordedTactic=null;
 if(tactic){
  const snapshotMatches=snapshot&&ownClubMatches(snapshot,slot)&&(row?fixtureMatches(snapshot,row,{snapshot:true}):sameClub(snapshot.rivalName,current.rivalName)&&(!known(snapshot._calendarId)||snapshot._calendarId===current._calendarId));
  if(snapshotMatches){context=structuredClone(snapshot);recordedTactic=structuredClone(tactic);}
  else if(!snapshot&&currentMatches&&ownClubMatches(current,slot))recordedTactic=structuredClone(tactic);
 }
 if(row){
  context.rivalName=known(row.opponent)?row.opponent:'NI';context.location=row.home===true?'Casa':row.home===false?'Fora':'NI';
  context._calendarId=row.id;context._calendarDate=known(row.date)?row.date:'NI';context._calendarTime=known(row.time)?row.time:'NI';
 }
 return {context,tactic:recordedTactic};
}
export function rebuildLearningWeights(slot){
 const weights={};
 for(const rec of slot.learning.matches){
  const key=rec.tactic?.formation;if(!key||!['V','E','D'].includes(rec.outcome))continue;
  const w=weights[key]||{games:0,wins:0,draws:0,losses:0};w.games++;w[rec.outcome==='V'?'wins':rec.outcome==='E'?'draws':'losses']++;
  w.weight=Number(((w.wins-w.losses)/(w.games+4)).toFixed(3));weights[key]=w;
 }
 slot.learning.weights=weights;return weights;
}
export function recordResult(slot,result){
 const outcome=scoreOutcome(result.score);if(!outcome)throw Error('Informe o placar do seu time primeiro, por exemplo 2x1.');
 const row=known(result.calendarId)?slot.calendar.find(game=>game.id===result.calendarId):null;
 if(known(result.calendarId)&&!row)throw Error('O jogo escolhido não está mais no calendário deste slot.');
 const values=resultStats(result),assigned=resultContext(slot,row),previous=row?slot.learning.matches.find(rec=>rec.calendarId===row.id):null;
 if(previous)for(const field of Object.keys(statRanges))if(!known(values[field])&&known(previous[field])&&result._fieldSources?.[field]?.kind!=='manual')values[field]=previous[field];
 // Correcting the same fixture updates its record rather than teaching it twice.
 if(previous&&sameClub(previous.context?.rivalName,row.opponent)&&!assigned.tactic&&previous.tactic){assigned.tactic=structuredClone(previous.tactic);assigned.context=structuredClone(previous.context);}
 const rec={...values,id:previous?.id||uid(),at:new Date().toISOString(),tactic:assigned.tactic,context:assigned.context,outcome};
 if(previous)slot.learning.matches=slot.learning.matches.filter(item=>item.id!==previous.id);
 slot.learning.matches.unshift(rec);slot.learning.matches=slot.learning.matches.slice(0,300);rebuildLearningWeights(slot);
 if(row){row.result=outcome;row.score=result.score;row._fieldSources={...row._fieldSources,result:{kind:'manual',rank:4,source:'Resultado revisado e registrado'},score:{kind:'manual',rank:4,source:'Placar revisado e registrado'}};}
 return rec;
}

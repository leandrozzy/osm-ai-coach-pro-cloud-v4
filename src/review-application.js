import {known,cleanMatch,cleanPlayers,matchFields} from './domain.js';
import {normalize} from './utils.js';
import {mergeBetter} from './validator.js';
import {dedupePlayers} from './parser-squad.js';
import {cleanCalendar,fuseExtraction} from './extraction.js';
import {rivalHuman} from './slots.js';

export const slotTeam=slot=>known(slot.myTeam)?slot.myTeam:known(slot.match?.myName)?slot.match.myName:'NI';
export const readingTeam=(type,parsed,meta={})=>type==='match'?parsed?.myName:parsed?.meta?.team||meta.team;
const sameTeam=(a,b)=>normalize(a).replace(/\s+/g,' ').trim()===normalize(b).replace(/\s+/g,' ').trim();

// The final save checks every provider's combined result, including video fallback.
export function applyReading(slot,type,parsed,{meta={},teams=[],conflicts=[],username='leandrozzy',mode='',at=new Date().toISOString()}={}){
 const distinctTeams=[...new Set(teams.filter(known).map(name=>normalize(name).replace(/\s+/g,' ').trim()))];
 if(distinctTeams.length>1)throw Error('A mídia mostra mais de um clube. Analise os arquivos de cada clube separadamente para salvar no slot correspondente.');
 const team=readingTeam(type,parsed,meta),current=slotTeam(slot);
 if(known(team)&&known(current)&&!sameTeam(team,current))throw Error('Esta leitura é de '+team+', mas S'+slot.id+' pertence a '+current+'. Escolha o slot desse clube em “Salvar no slot” ou corrija o nome lido antes de confirmar.');
 const next=structuredClone(slot);
 if(type==='match'){
  const unresolved=conflicts.filter(c=>!c.resolved&&String(c.field).startsWith('match.')).map(c=>c.field.slice(6)).filter(field=>known(parsed?.[field])&&(parsed?._fieldSources?.[field]?.rank||0)<4);
  if(unresolved.length)throw Error('Escolha o valor correto nos campos divergentes antes de confirmar, ou marque NI quando não for possível identificar.');
  const match=cleanMatch(parsed);if(Object.keys(match).length<=1)throw Error('Nenhum dado válido identificado. Preencha manualmente.');
  if(known(next.match.rivalName)&&known(match.rivalName)&&!sameTeam(next.match.rivalName,match.rivalName)){
   for(const field of ['rivalName','rivalNickname','human','rivalStrength','rivalSquadValue','rivalPlayers','rivalGK','rivalDEF','rivalMID','rivalATT','rivalBonus','stadium','location','referee','secretTraining','trainingCamp','myTrainingCamp','myBonus','rivalFormation','rivalPlan','rivalMarking','rivalOffside','rivalTackling'])delete next.match[field];
  }
  next.match=mergeBetter(next.match,match);
  if(meta.rivalReportLocked===true&&match.secretTraining==='Sim'&&(parsed._fieldSources?.secretTraining?.rank||0)>=3)for(const field of ['rivalFormation','rivalPlan','rivalMarking','rivalOffside','rivalTackling'])if((parsed._fieldSources?.[field]?.rank||0)<4)delete next.match[field];
  for(const [field]of matchFields)if(!known(parsed[field])&&parsed?._fieldSources?.[field]?.kind==='manual'&&parsed._fieldSources[field].rank>=4)delete next.match[field];
  const ownName=normalize(username).replace(/^@/,''),selfNickname=known(next.match.rivalNickname)&&normalize(next.match.rivalNickname).replace(/^@/,'')===ownName;
  const manualHuman=parsed?._fieldSources?.human?.kind==='manual'&&parsed._fieldSources.human.rank>=4;
  if(selfNickname){delete next.match.rivalNickname;if(!manualHuman)next.match.human=null;}
  const human=rivalHuman(next.match.rivalNickname,next.competitionType,username);
  if(normalize(next.competitionType).includes('batalha'))next.match.human=true;
  else if(!manualHuman&&!known(match.human)&&human!==null)next.match.human=human;
  next.match._lastReader=mode;next.match._lastReadAt=at;next.tacticStale=!!next.tactics;
 }else if(type==='squad'){
  const rows=cleanPlayers(Array.isArray(parsed)?parsed:parsed?.players||[]);if(!rows.length)throw Error('Nenhum jogador válido. Confira nomes e posições.');
  next.squad.players=dedupePlayers([...next.squad.players,...rows]);next.squad.updatedAt=at;
  const headerMeta=parsed?.meta||meta;if(known(headerMeta.cash))next.director.cash=String(headerMeta.cash).slice(0,80);
  next.match=mergeBetter(next.match,cleanMatch({myName:headerMeta.team,mySquadValue:headerMeta.squadValue,myStrength:headerMeta.strength,myGK:headerMeta.GK,myDEF:headerMeta.DEF,myMID:headerMeta.MID,myATT:headerMeta.ATT,myPlayers:headerMeta.expectedPlayers}));next.tacticStale=!!next.tactics;
 }else if(type==='calendar'){
  const rows=cleanCalendar(Array.isArray(parsed)?parsed:[]);if(!rows.length)throw Error('Nenhum jogo válido. Confira a rodada ou data.');
  next.calendar=fuseExtraction({calendar:next.calendar},{calendar:rows}).calendar;
 }else throw Error('Tipo de leitura inválido.');
 if(!known(current)&&known(team))next.myTeam=String(team).slice(0,120);
 return next;
}

import {known,cleanMatch,cleanPlayers,matchFields} from './domain.js';
import {normalize} from './utils.js';
import {dedupePlayers} from './parser-squad.js';
import {cleanCalendar,fuseExtraction,relevantConflicts} from './extraction.js';
import {rivalHuman} from './slots.js';
import {isRivalNickname} from './parser-match.js';
import {synchronizePreparation} from './preparation.js';

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
  const unresolved=relevantConflicts('match',conflicts).filter(c=>!c.resolved).map(c=>c.field.slice(6)).filter(field=>known(parsed?.[field])&&(parsed?._fieldSources?.[field]?.rank||0)<4);
  if(unresolved.length)throw Error('Escolha o valor correto nos campos divergentes antes de confirmar, ou marque NI quando não for possível identificar.');
  const match=cleanMatch(parsed);if(Object.keys(match).length<=1)throw Error('Nenhum dado válido identificado. Preencha manualmente.');
  const savedSources={...next.match._fieldSources};
  // A rejected value must not survive through the "preserve NI" merge when
  // the same ungrounded value was saved by an earlier version of the reader.
  for(const fact of meta.pendingMatchFacts||[]){
   if(!matchFields.some(([field])=>field===fact.field)||known(match[fact.field]))continue;
   const minimum=['myStrength','rivalStrength'].includes(fact.field)&&fact.source==='OCR do círculo da equipa'?3:2;
   if(known(next.match[fact.field])&&String(next.match[fact.field])===String(fact.value)&&(savedSources[fact.field]?.rank||0)<minimum){delete next.match[fact.field];delete savedSources[fact.field];}
  }
  if(known(next.match.rivalName)&&known(match.rivalName)&&!sameTeam(next.match.rivalName,match.rivalName)){
   for(const field of ['rivalName','rivalNickname','human','rivalStrength','rivalSquadValue','rivalPlayers','rivalGK','rivalDEF','rivalMID','rivalATT','rivalBonus','stadium','location','referee','secretTraining','trainingCamp','myTrainingCamp','myBonus','rivalFormation','rivalPlan','rivalMarking','rivalOffside','rivalTackling']){delete next.match[field];delete savedSources[field];}
  }
  // A fresh reading may update equally grounded facts, while a weaker model
  // cannot replace a value already checked in pixels or by the user.
  for(const [field] of matchFields){
   if(!known(match[field]))continue;
   const incoming=parsed._fieldSources?.[field];
   const visibleManager=field==='human'&&match.human===true&&known(match.rivalNickname)&&isRivalNickname(match.rivalNickname,{username})&&(parsed._fieldSources?.rivalNickname?.rank||0)>=2&&(savedSources.human?.rank||0)<4;
   if(known(next.match[field])&&(savedSources[field]?.rank||0)>(incoming?.rank||0)&&!visibleManager)continue;
   next.match[field]=match[field];
   if(incoming)savedSources[field]=incoming;else delete savedSources[field];
  }
  next.match._fieldSources=savedSources;
  if(meta.rivalReportLocked===true&&match.secretTraining==='Sim'&&(parsed._fieldSources?.secretTraining?.rank||0)>=3)for(const field of ['rivalFormation','rivalPlan','rivalMarking','rivalOffside','rivalTackling'])if((parsed._fieldSources?.[field]?.rank||0)<4)delete next.match[field];
  for(const [field]of matchFields)if(!known(parsed[field])&&parsed?._fieldSources?.[field]?.kind==='manual'&&parsed._fieldSources[field].rank>=4)delete next.match[field];
  const ownName=normalize(username).replace(/^@/,''),selfNickname=known(next.match.rivalNickname)&&normalize(next.match.rivalNickname).replace(/^@/,'')===ownName;
  const manualHuman=parsed?._fieldSources?.human?.kind==='manual'&&parsed._fieldSources.human.rank>=4;
  if(selfNickname||known(next.match.rivalNickname)&&!isRivalNickname(next.match.rivalNickname,{username})){delete next.match.rivalNickname;if(!manualHuman&&!known(match.human))next.match.human=null;}
  if(match.human===false&&(parsed._fieldSources?.human?.rank||0)>=2&&!known(match.rivalNickname)&&(next.match._fieldSources.rivalNickname?.rank||0)<3){delete next.match.rivalNickname;delete next.match._fieldSources.rivalNickname;}
  const nicknameSource=next.match._fieldSources.rivalNickname;
  const human=(nicknameSource?.rank||0)>=2?rivalHuman(next.match.rivalNickname,next.competitionType,username):null;
  const freshNickname=known(match.rivalNickname)&&(parsed._fieldSources?.rivalNickname?.rank||0)>=2;
  if(!manualHuman&&!known(match.human)&&human!==null&&(next.match._fieldSources.human?.rank||0)<4&&(freshNickname||!known(next.match.human))){next.match.human=human;next.match._fieldSources.human={...nicknameSource,source:'Nome do treinador nas telas'};}
  next.match._fieldSources=Object.fromEntries(Object.entries(next.match._fieldSources||{}).filter(([field])=>known(next.match[field])));
  next.match._lastReader=mode;next.match._lastReadAt=at;next.tacticStale=!!next.tactics;
 }else if(type==='squad'){
  const rows=cleanPlayers(Array.isArray(parsed)?parsed:parsed?.players||[]);if(!rows.length)throw Error('Nenhum jogador válido. Confira nomes e posições.');
  const headerMeta=parsed?.meta||meta;if(known(headerMeta.cash))next.director.cash=String(headerMeta.cash).slice(0,80);
  const complete=headerMeta.sawTop===true&&headerMeta.sawBottom===true&&['sawTop','sawBottom'].every(field=>(headerMeta._fieldSources?.[field]?.rank||0)>=2);
  const nameKey=value=>normalize(value).replace(/[\s.\-’']/g,'');
  const aliases=(parsed?.playerCandidates||[]).filter(candidate=>candidate._rosterPending===true&&known(candidate._rosterAliasOf)&&rows.some(row=>nameKey(row.name)===nameKey(candidate._rosterAliasOf)));
  const preserved=next.squad.players.filter(player=>(player._fieldSources?.name?.rank||0)>=4||!aliases.some(alias=>nameKey(alias.name)===nameKey(player.name)));
  next.squad.players=dedupePlayers(complete?rows:[...preserved,...rows]);next.squad.updatedAt=at;
  const groundedStrength=(headerMeta._fieldSources?.strength?.rank||0)>=2?headerMeta.strength:undefined;
  const fields={myName:'team',mySquadValue:'squadValue',myStrength:'strength',myGK:'GK',myDEF:'DEF',myMID:'MID',myATT:'ATT',myPlayers:'expectedPlayers'};
  const facts=cleanMatch({myName:headerMeta.team,mySquadValue:headerMeta.squadValue,myStrength:groundedStrength,myGK:headerMeta.GK,myDEF:headerMeta.DEF,myMID:headerMeta.MID,myATT:headerMeta.ATT,myPlayers:headerMeta.expectedPlayers});
  const sources=Object.fromEntries(Object.entries(fields).filter(([field,key])=>known(facts[field])&&headerMeta._fieldSources?.[key]).map(([field,key])=>[field,headerMeta._fieldSources[key]]));
  const merged=fuseExtraction({match:next.match,_matchFieldSources:next.match._fieldSources},{match:facts,_matchFieldSources:sources});
  next.match={...merged.match,_fieldSources:merged._matchFieldSources};next.squad.readMeta=headerMeta;next.tacticStale=!!next.tactics;
 }else if(type==='calendar'){
  const rows=cleanCalendar(Array.isArray(parsed)?parsed:[]);if(!rows.length)throw Error('Nenhum jogo válido. Confira a rodada ou data.');
  next.calendar=fuseExtraction({calendar:next.calendar},{calendar:rows}).calendar;
  const readingMeta=parsed?.meta||meta;
  next.calendarMeta={...next.calendarMeta,...readingMeta};
 }else throw Error('Tipo de leitura inválido.');
 if(!known(current)&&known(team))next.myTeam=String(team).slice(0,120);
 return synchronizePreparation(next);
}

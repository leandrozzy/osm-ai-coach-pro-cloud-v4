import {known,cleanMatch,cleanPlayers,matchFields} from './domain.js';
import {normalize,clubKey,sameClub} from './utils.js';
import {cleanCalendar,relevantConflicts} from './extraction.js';
import {rivalHuman} from './slots.js';
import {isRivalNickname} from './parser-match.js';
import {synchronizePreparation} from './preparation.js';
import {updateSavedRecord,updateSavedPlayers,updateSavedCalendar} from './reading-update.js';

export const slotTeam=slot=>known(slot.myTeam)?slot.myTeam:known(slot.match?.myName)?slot.match.myName:'NI';
export const readingTeam=(type,parsed,meta={})=>type==='match'?parsed?.myName:known(parsed?.meta?.team)?parsed.meta.team:meta.team;
const sameTeam=sameClub;

// This is a display view only. Retained fields must not enter the recognition
// result, its coverage, or the resume cursor for the current media.
export function reviewWithSavedMatch(review,slot){
 if(review.type!=='match'||!slot)return review;
 const team=known(review.match?.myName)?review.match.myName:(review.readingTeams||[]).find(known);
 if(!sameClub(team,slotTeam(slot)))return review;
 const saved=synchronizePreparation(slot).match,match={...review.match},sources={...review._matchFieldSources};
 const sameRival=sameClub(match.rivalName,saved.rivalName);
 const ownFields=new Set(['myName','myStrength','mySquadValue','myPlayers','myGK','myDEF','myMID','myATT']);
 const hidden=review.meta?.rivalReportLocked===true&&match.secretTraining==='Sim';
 for(const [field]of matchFields){
  if(known(match[field])||!known(saved[field])||!ownFields.has(field)&&!sameRival)continue;
  if(hidden&&['rivalFormation','rivalPlan','rivalMarking','rivalOffside','rivalTackling'].includes(field))continue;
  if(field==='rivalNickname'&&match.human===false)continue;
  if((review.meta?.pendingMatchFacts||[]).some(fact=>fact.field===field&&String(fact.value)===String(saved[field])&&(saved._fieldSources?.[field]?.rank||0)<(['myStrength','rivalStrength'].includes(field)?3:2)))continue;
  match[field]=saved[field];sources[field]={...saved._fieldSources?.[field],retained:true};
 }
 return {...review,match,_matchFieldSources:sources};
}

// The final save checks every provider's combined result, including video fallback.
export function applyReading(slot,type,parsed,{meta={},teams=[],conflicts=[],username='leandrozzy',mode='',readingAt,at=new Date().toISOString()}={}){
 const distinctTeams=[...new Set(teams.filter(known).map(clubKey).filter(Boolean))];
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
  next.match=updateSavedRecord({...next.match,_fieldSources:savedSources},{...match,_fieldSources:parsed._fieldSources},{fields:matchFields.map(([field])=>field),readingAt,at});
  if(meta.rivalReportLocked===true&&match.secretTraining==='Sim'&&(parsed._fieldSources?.secretTraining?.rank||0)>=3)for(const field of ['rivalFormation','rivalPlan','rivalMarking','rivalOffside','rivalTackling'])if((parsed._fieldSources?.[field]?.rank||0)<4)delete next.match[field];
  for(const [field]of matchFields)if(!known(parsed[field])&&parsed?._fieldSources?.[field]?.kind==='manual'&&parsed._fieldSources[field].rank>=4){delete next.match[field];next.match._fieldSources[field]={...parsed._fieldSources[field],observedAt:parsed._fieldSources[field].observedAt||at};}
  const ownName=normalize(username).replace(/^@/,''),selfNickname=known(next.match.rivalNickname)&&normalize(next.match.rivalNickname).replace(/^@/,'')===ownName;
  const manualHuman=parsed?._fieldSources?.human?.kind==='manual'&&parsed._fieldSources.human.rank>=4;
  if(selfNickname||known(next.match.rivalNickname)&&!isRivalNickname(next.match.rivalNickname,{username})){delete next.match.rivalNickname;if(!manualHuman&&!known(match.human))next.match.human=null;}
  if(match.human===false&&(parsed._fieldSources?.human?.rank||0)>=2&&!known(match.rivalNickname)&&(next.match._fieldSources.rivalNickname?.rank||0)<3){delete next.match.rivalNickname;delete next.match._fieldSources.rivalNickname;}
  const nicknameSource=next.match._fieldSources.rivalNickname;
  const human=(nicknameSource?.rank||0)>=2?rivalHuman(next.match.rivalNickname,next.competitionType,username):null;
  const freshNickname=known(match.rivalNickname)&&(parsed._fieldSources?.rivalNickname?.rank||0)>=2;
  if(!manualHuman&&!known(match.human)&&human!==null&&(next.match._fieldSources.human?.rank||0)<4&&(freshNickname||!known(next.match.human))){next.match.human=human;next.match._fieldSources.human={...nicknameSource,source:'Nome do treinador nas telas'};}
  next.match._fieldSources=Object.fromEntries(Object.entries(next.match._fieldSources||{}).filter(([field,proof])=>known(next.match[field])||proof.kind==='manual'&&proof.rank>=4));
  next.match._lastReader=mode;next.match._lastReadAt=at;next.tacticStale=!!next.tactics;
 }else if(type==='squad'){
  const rows=cleanPlayers(Array.isArray(parsed)?parsed:parsed?.players||[]);if(!rows.length)throw Error('Nenhum jogador válido. Confira nomes e posições.');
  const headerMeta=parsed?.meta||meta;if(known(headerMeta.cash))next.director.cash=String(headerMeta.cash).slice(0,80);
  const complete=headerMeta.sawTop===true&&headerMeta.sawBottom===true&&['sawTop','sawBottom'].every(field=>(headerMeta._fieldSources?.[field]?.rank||0)>=2);
  const nameKey=value=>normalize(value).replace(/[\s.\-’']/g,'');
  const aliases=(parsed?.playerCandidates||[]).filter(candidate=>candidate._rosterPending===true&&known(candidate._rosterAliasOf)&&rows.some(row=>nameKey(row.name)===nameKey(candidate._rosterAliasOf)));
  const preserved=next.squad.players.filter(player=>(player._fieldSources?.name?.rank||0)>=4||!aliases.some(alias=>nameKey(alias.name)===nameKey(player.name)));
  next.squad.players=updateSavedPlayers(preserved,rows,{complete,readingAt,at});next.squad.updatedAt=at;
  const groundedStrength=(headerMeta._fieldSources?.strength?.rank||0)>=2?headerMeta.strength:undefined;
  const fields={myName:'team',mySquadValue:'squadValue',myStrength:'strength',myGK:'GK',myDEF:'DEF',myMID:'MID',myATT:'ATT',myPlayers:'expectedPlayers'};
  const facts=cleanMatch({myName:headerMeta.team,mySquadValue:headerMeta.squadValue,myStrength:groundedStrength,myGK:headerMeta.GK,myDEF:headerMeta.DEF,myMID:headerMeta.MID,myATT:headerMeta.ATT,myPlayers:headerMeta.expectedPlayers});
  const sources=Object.fromEntries(Object.entries(fields).filter(([field,key])=>known(facts[field])&&headerMeta._fieldSources?.[key]).map(([field,key])=>[field,headerMeta._fieldSources[key]]));
  next.match=updateSavedRecord(next.match,{...facts,_fieldSources:sources},{fields:Object.keys(fields),readingAt,at});
  next.squad.readMeta=updateSavedRecord(next.squad.readMeta||{},headerMeta,{readingAt,at});next.tacticStale=!!next.tactics;
 }else if(type==='calendar'){
  const rows=cleanCalendar(Array.isArray(parsed)?parsed:[]);if(!rows.length)throw Error('Nenhum jogo válido. Confira a rodada ou data.');
  const readingMeta=parsed?.meta||meta;
  const complete=readingMeta.sawTop===true&&readingMeta.sawBottom===true&&['sawTop','sawBottom'].every(field=>(readingMeta._fieldSources?.[field]?.rank||0)>=2);
  next.calendar=updateSavedCalendar(next.calendar,rows,{readingAt,at,complete});
  next.calendarMeta=updateSavedRecord(next.calendarMeta||{},readingMeta,{readingAt,at});
 }else throw Error('Tipo de leitura inválido.');
 if(!known(current)&&known(team))next.myTeam=String(team).slice(0,120);
 return synchronizePreparation(next);
}

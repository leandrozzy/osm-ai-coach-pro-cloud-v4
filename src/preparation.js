import {known,nextMatch} from './domain.js';
import {normalize} from './utils.js';
import {emptySlot} from './storage.js';

const sameClub=(a,b)=>normalize(a).replace(/\s+/g,' ').trim()===normalize(b).replace(/\s+/g,' ').trim();

export function preparationCalendarConflict(slot,{now=Date.now()}={}){
 const game=nextMatch(slot,now);
 return game&&known(game.opponent)&&known(slot.match?.rivalName)&&!sameClub(slot.match.rivalName,game.opponent)?game:null;
}

// Calendar supplies the fixture. Strengths only come from an identified roster
// header; neither individual player averages nor unrelated opponents fill them.
export function synchronizePreparation(slot,{now=Date.now()}={}){
 const next=structuredClone(slot),match=next.match||{},sources={...match._fieldSources};
 const team=known(next.myTeam)?next.myTeam:match.myName,meta=next.squad?.readMeta||{};
 if(!known(match.myName)&&known(team)){match.myName=team;sources.myName={kind:'manual',rank:4,source:'Time configurado no slot'};}
 if(meta.teamVerified===true&&known(team)&&sameClub(meta.team,team)){
  for(const [field,key] of Object.entries({myStrength:'strength',mySquadValue:'squadValue',myGK:'GK',myDEF:'DEF',myMID:'MID',myATT:'ATT'})){
   if(known(match[field])||!known(meta[key])||(meta._fieldSources?.[key]?.rank||0)<2)continue;
   match[field]=meta[key];sources[field]={...meta._fieldSources[key]};
  }
 }
 const game=nextMatch(next,now);
 if(game&&known(game.opponent)&&(!known(match.rivalName)||sameClub(match.rivalName,game.opponent))){
  if(!known(match.rivalName)){match.rivalName=game.opponent;sources.rivalName={...(game._fieldSources?.opponent||{kind:'derived',rank:1}),source:'Adversário do calendário'};}
  if(!known(match.location)&&typeof game.home==='boolean'){match.location=game.home?'Casa':'Fora';sources.location={...(game._fieldSources?.home||{kind:'derived',rank:1}),source:'Local do calendário'};}
  match._calendarId=game.id;
 }
 next.match={...match,_fieldSources:sources};return next;
}

export function createNextPreparation(slot,{now=Date.now()}={}){
 const next=structuredClone(slot);
 next.match={_schemaVersion:3,myName:next.myTeam||next.match?.myName||'NI',human:null};
 next.tactics=null;next.tacticStale=false;
 return synchronizePreparation(next,{now});
}

export function configureLeague(slot,values,{fresh=false}={}){
 const myTeam=String(values.myTeam||'').trim().slice(0,120),competition=String(values.competition||'').trim().slice(0,120);
 if(!myTeam||!competition)throw Error('Informe seu time e o nome da liga.');
 if(!['Liga normal','Batalha','Copa'].includes(values.competitionType))throw Error('Escolha o tipo da competição.');
 const previous=known(slot.myTeam)?slot.myTeam:slot.match?.myName;
 if(!fresh&&known(previous)&&!sameClub(previous,myTeam))throw Error('Para mudar de clube, use Criar nova liga neste slot.');
 const next=fresh?emptySlot(slot.id):structuredClone(slot);
 if(fresh)next.learning=structuredClone(slot.learning);
 next.myTeam=myTeam;next.competition=competition;next.competitionType=values.competitionType;
 next.match.myName=myTeam;next.match._fieldSources={...next.match._fieldSources,myName:{kind:'manual',rank:4,source:'Time configurado no slot'}};
 return synchronizePreparation(next);
}

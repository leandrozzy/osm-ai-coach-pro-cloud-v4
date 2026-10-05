import {known} from './domain.js';
import {normalize,clubKey} from './utils.js';
import {isRivalNickname} from './parser-match.js';

const identity=value=>normalize(value).replace(/^@/,'').replace(/\s+/g,' ').trim();
const ownFields=['myName','myStrength','mySquadValue','myPlayers','myGK','myDEF','myMID','myATT','myBonus'];
const rivalFields=['rivalName','rivalNickname','human','rivalStrength','rivalSquadValue','rivalPlayers','rivalGK','rivalDEF','rivalMID','rivalATT','rivalBonus','stadium','secretTraining','trainingCamp','rivalFormation','rivalPlan','rivalMarking','rivalOffside','rivalTackling'];

// Provider guesses cannot identify the manager's own nickname as the opponent,
// or bind another club's header to the selected club. OCR handles those facts.
export function sanitizeProviderMatch(initial,context={}){
 if(!initial?.match)return initial;
 const out={...initial,match:{...initial.match},_matchFieldSources:{...initial._matchFieldSources},meta:{...initial.meta,pendingMatchFacts:[...(initial.meta?.pendingMatchFacts||[])]},warnings:[...(initial.warnings||[])]};
 const drop=field=>{if((out._matchFieldSources[field]?.rank||0)>=4)return;delete out.match[field];delete out._matchFieldSources[field];};
 drop('myTrainingCamp');
 const pending=(field,reason)=>{const value=out.match[field];if(!known(value)||(out._matchFieldSources[field]?.rank||0)>=4)return;out.meta.pendingMatchFacts.push({field,value,reason,source:out._matchFieldSources[field]?.source||'Leitura da API'});drop(field);};
 const own=clubKey(context.myTeam),username=identity(context.username||'leandrozzy');
 if(known(out.match.rivalNickname)&&!isRivalNickname(out.match.rivalNickname,context)){
  const ownNickname=identity(out.match.rivalNickname)===username;
  drop('rivalNickname');if((out._matchFieldSources.human?.rank||0)<2)drop('human');
  out.warnings.push(ownNickname?'O nickname do seu usuário foi ignorado no campo do rival.':'Nota, número ou rótulo ignorado no nickname do rival.');
 }
 if(own&&known(out.match.myName)&&clubKey(out.match.myName)!==own){
  const club=out.match.myName,clubRank=out._matchFieldSources.myName?.rank||0;
  for(const field of ownFields){
   // A guessed club/username must not erase independently grounded own header
   // values that were already merged into this result.
   const rank=out._matchFieldSources[field]?.rank||0;
   if(field!=='myName'&&clubRank<2&&rank>=2)continue;
   drop(field);
  }
  out.warnings.push('A API associou '+club+' ao seu lado. Esses campos aguardam identificação do clube na tela.');
 }
 if(own&&known(out.match.rivalName)&&clubKey(out.match.rivalName)===own){
  for(const field of rivalFields)drop(field);out.meta.rivalReportLocked=false;out.meta.hiddenFields=[];
  out.warnings.push('A API colocou seu clube no lado rival. Os dados desse lado aguardam confirmação nas telas.');
 }
 for(const [field,clubField] of [['myStrength','myName'],['rivalStrength','rivalName']]){
  if(!known(out.match[field])||(out._matchFieldSources[field]?.rank||0)>=2)continue;
  const club=out.match[clubField];
  if(!known(club)||identity(club)===username||!/[\p{L}]/u.test(String(club))){
   pending(field,'Clube e círculo de força aguardam identificação na tela.');
  }else{
   // Naming the club does not prove that a model read its strength badge.
   // Nearby ratings, counters and other screens have unrelated numbers.
   pending(field,'Valor sugerido pela IA sem leitura comprovada do círculo de força.');
  }
 }
 if(known(out.match.human)&&(out._matchFieldSources.human?.rank||0)<2){
  pending('human','Humano/CPU precisa de indicação explícita na tela; nickname ausente não comprova CPU.');
 }
 let countRemoved=false;
 for(const field of ['myPlayers','rivalPlayers'])if(known(out.match[field])&&(out._matchFieldSources[field]?.rank||0)<2){drop(field);countRemoved=true;}
 if(countRemoved)out.warnings.push('Quantidade de jogadores sem contagem comprovada: mantida NI.');
 const pendingByValue=new Map(out.meta.pendingMatchFacts.filter(fact=>fact&&known(fact.value)).map(fact=>[fact.field+'|'+String(fact.value),fact]));
 out.meta.pendingMatchFacts=[...pendingByValue.values()].slice(-20);
 out.warnings=[...new Set(out.warnings)];return out;
}

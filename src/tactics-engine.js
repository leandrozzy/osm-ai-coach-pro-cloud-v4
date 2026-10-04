import {clamp} from './utils.js';
import {num,known,confirmedMatchSecretTraining,confirmedMatchField,formations,styles,tackles} from './domain.js';
const templates={
 '4-3-3 A':['Jogar pelas alas',74,76,78,'Atacar apenas','Pressionar na frente','Defender atrás'],
 '4-3-3 B':['Jogo de passes',72,74,76,'Atacar apenas','Manter posição','Defender atrás'],
 '4-4-2 B':['Jogo de passes',62,61,68,'Atacar apenas','Manter posição','Defender atrás'],
 '4-5-1':['Remate à vista',46,39,63,'Ajudar meio-campo','Manter posição','Defender atrás'],
 '5-3-2':['Contra-ataque',38,32,67,'Atacar apenas','Ajudar a defesa','Defender atrás'],
 '5-4-1 A':['Contra-ataque',34,28,61,'Ajudar meio-campo','Ajudar a defesa','Defender atrás'],
 '4-2-3-1':['Remate à vista',51,46,69,'Atacar apenas','Manter posição','Defender atrás']
};
export function generateTactic(match={},settings={},learning={}){
 const a=num(match.myStrength),b=num(match.rivalStrength);const diff=a!==null&&b!==null?a-b:null;
 const advantage=diff===null?0:diff+(num(match.myBonus)??0)/4-(num(match.rivalBonus)??0)/4+(match.location==='Casa'?3:match.location==='Fora'?-3:0)+(match.myTrainingCamp==='Sim'?6:0)-(match.trainingCamp==='Sim'?6:0);
 let formation=advantage<=-18?'5-4-1 A':advantage<=-10?'5-3-2':advantage<=-4?'4-5-1':advantage>=8?'4-4-2 B':'4-2-3-1';
 const mid=num(match.myMID),rMid=num(match.rivalMID),att=num(match.myATT),rDef=num(match.rivalDEF);
 if(mid!==null&&rMid!==null&&mid-rMid<-10&&advantage<8)formation='4-5-1';
 if(match.rivalFormation?.startsWith('4-3-3')&&advantage>=-4&&advantage<8)formation='4-2-3-1';
 const weights=learning.weights||{};
 const allowed=advantage<-10?['5-4-1 A','5-3-2']:advantage<4?['4-5-1','4-2-3-1']:['4-4-2 B','4-2-3-1'];
 let learned=false;
 const score=f=>{const w=weights[f];return w?.games>=3?(w.wins-w.losses)/(w.games+4):0;};
 const candidate=allowed.reduce((best,f)=>score(f)>score(best)?f:best,formation);
 if(score(candidate)>score(formation)+.25){formation=candidate;learned=true;}
 const [style,p,m,r,attack,midfield,defense]=templates[formation];
 const secret=match.secretTraining==='Sim';const away=match.location==='Fora';
 const missing=['myStrength','rivalStrength','referee','location'].filter(k=>!known(match[k]));
 if(!confirmedMatchSecretTraining(match)&&!confirmedMatchField(match,'rivalMarking'))missing.push('rivalMarking');
 const missingLabels={myStrength:'minha força',rivalStrength:'força rival',referee:'árbitro',location:'local',rivalMarking:'marcação rival'};
 const sectorBoost=att!==null&&rDef!==null?Math.round(clamp((att-rDef)/5,-4,4)):0;
 return {formation,style,pressure:clamp(p+(away?-4:0)-(secret?4:0)),mentality:clamp(m+(away?-3:0)+sectorBoost),tempo:r,marking:'À zona',offside:'Não',tackling:settings.refereeMap?.[match.referee]||({Verde:'Agressivo',Azul:'Agressivo',Amarelo:'Normal',Laranja:'Cauteloso',Vermelho:'Cauteloso'}[match.referee])||'Cauteloso',attack,midfield,defense,strongAvailable:diff!==null&&diff>=13,source:'Motor local',createdAt:new Date().toISOString(),provisional:missing.length>0,reason:`Diferença de força: ${diff??'NI'}. Local: ${match.location||'NI'}. Rival: ${match.human===true?'humano':match.human===false?'CPU':'NI'}. Árbitro: ${match.referee||'NI'}. Marcação rival: ${confirmedMatchField(match,'rivalMarking')?match.rivalMarking:confirmedMatchSecretTraining(match)?'oculta pelo treino secreto':'NI'}. ${secret?'Treino secreto: maior cautela. ':''}${match.trainingCamp==='Sim'?'Campo rival considerado. ':''}${learned?'Histórico compartilhado favoreceu esta formação. ':''}${missing.length?'Provisória: faltam '+missing.map(key=>missingLabels[key]).join(', ')+'. ':''}Recomendação heurística; vitória não é garantida.`};
}
export function generateStrong433(match={},settings={}){
 const a=num(match.myStrength),b=num(match.rivalStrength);if(a===null||b===null||a-b<13)throw Error('A tática forte requer vantagem de força confirmada de pelo menos 13.');
 const base=generateTactic(match,settings);const formation=num(match.myATT)!==null&&num(match.myMID)!==null&&num(match.myATT)>num(match.myMID)?'4-3-3 A':'4-3-3 B';
 const [style,p,m,r,attack,midfield,defense]=templates[formation];
 return {...base,formation,style,pressure:p+(match.location==='Fora'?-4:0),mentality:m+(match.location==='Fora'?-3:0),tempo:r,attack,midfield,defense,reason:`Tática forte solicitada: vantagem real de ${a-b}. ${base.reason}`};
}
export function validateTactic(t){
 if(!t||!formations.includes(t.formation)||!styles.includes(t.style)||!['À zona','Individual'].includes(t.marking)||!['Sim','Não'].includes(t.offside)||!tackles.includes(t.tackling))return false;
 if(!['pressure','mentality','tempo'].every(k=>Number.isInteger(t[k])&&t[k]>=0&&t[k]<=100))return false;
 return ['Atacar apenas','Ajudar meio-campo','Ajudar a defesa'].includes(t.attack)&&['Pressionar na frente','Manter posição','Ajudar a defesa'].includes(t.midfield)&&['Defender atrás','Laterais ofensivos','Apoiar meio-campo'].includes(t.defense);
}

export function updateTacticSliders(tactic,values={}){
 if(!tactic||!['pressure','mentality','tempo'].every(key=>Number.isInteger(values[key])&&values[key]>=0&&values[key]<=100))throw Error('Os sliders precisam ser números inteiros de 0 a 100.');
 const changed=['pressure','mentality','tempo'].some(key=>tactic[key]!==values[key]);
 return changed?{...tactic,...Object.fromEntries(['pressure','mentality','tempo'].map(key=>[key,values[key]])),source:'Ajuste manual'}:{...tactic};
}

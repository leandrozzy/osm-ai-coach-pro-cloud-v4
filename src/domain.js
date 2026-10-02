export const NI='NI';
export const positions=['ATA','MEI','DEF','GOL'];
export const colors=['Verde','Azul','Amarelo','Laranja','Vermelho'];
export const tackles=['Cauteloso','Normal','Agressivo','Imprudente'];
export const formations=['4-3-3 A','4-3-3 B','4-5-1','5-3-2','5-4-1 A','4-2-3-1','4-4-2 A','4-4-2 B'];
export const styles=['Jogar pelas alas','Jogo de passes','Contra-ataque','Remate à vista','Bola longa'];
export const matchFields=[
 ['myName','Meu time'],['rivalName','Time rival'],['rivalNickname','Nickname rival'],['human','Humano/CPU','human'],
 ['myStrength','Minha força','number'],['rivalStrength','Força rival','number'],['mySquadValue','Valor do meu elenco'],['rivalSquadValue','Valor do elenco rival'],['myPlayers','Meus jogadores','number'],['rivalPlayers','Jogadores rival','number'],
 ...[['GK','GOL'],['DEF','DEF'],['MID','MEI'],['ATT','ATA']].flatMap(([key,label])=>[[`my${key}`,`Meu ${label}`,'number'],[`rival${key}`,`Rival ${label}`,'number']]),
 ['stadium','Nível do estádio','number'],['myBonus','Meu bônus (%)','number'],['rivalBonus','Bônus rival (%)','number'],['location','Casa/fora',['Casa','Fora']],['referee','Árbitro',colors],['secretTraining','Treino secreto rival',['Sim','Não']],['trainingCamp','Campo de treinamento rival',['Sim','Não']],['myTrainingCamp','Meu campo de treinamento',['Sim','Não']],['rivalFormation','Formação rival',formations],['rivalPlan','Plano rival',styles],['rivalMarking','Marcação rival',['À zona','Individual']],['rivalOffside','Impedimento rival',['Sim','Não']],['rivalTackling','Desarme rival',tackles]
];
export const known=v=>v!==NI&&v!=null&&v!=='';
export const num=v=>known(v)&&Number.isFinite(Number(v))?Number(v):null;
export function cleanMatch(raw={}){
 const out={_schemaVersion:3};
 for(const [key,,type] of matchFields){let v=raw[key];if(!known(v))continue;
  if(type==='human'){if(v===true||v===false)out[key]=v;else if(['Humano','CPU'].includes(v))out[key]=v==='Humano';continue;}
  if(type==='number'){v=num(v);if(v===null||v<0||v>100000)continue; if(/Strength|GK|DEF|MID|ATT/.test(key)&&(v<1||v>400))continue;if(/Players/.test(key)&&v>100)continue;if(/Bonus/.test(key)&&v>100)continue;}
  if(Array.isArray(type)&&!type.includes(v))continue;
  out[key]=typeof v==='string'?v.trim().slice(0,120):v;
 }
 return out;
}
export function cleanPlayers(rows=[]){return rows.filter(r=>r&&known(r.name)&&positions.includes(r.position)).map(r=>({id:r.id||crypto.randomUUID(),name:String(r.name).trim().slice(0,80),position:r.position,strength:num(r.strength),age:num(r.age),value:known(r.value)?String(r.value).slice(0,80):NI,forSale:typeof r.forSale==='boolean'?r.forSale:null,training:typeof r.training==='boolean'?r.training:null})).filter(r=>(r.strength===null||(r.strength>0&&r.strength<=400))&&(r.age===null||(r.age>=15&&r.age<=60)));}
export function rowTime(r){if(!r||!known(r.date)||!known(r.time))return null;const m=String(r.date).match(/^(\d{2})\/(\d{2})\/(\d{4})$/);if(!m||!/^\d{2}:\d{2}$/.test(r.time))return null;const [h,min]=r.time.split(':').map(Number);const d=new Date(+m[3],+m[2]-1,+m[1],h,min);return h<24&&min<60&&d.getDate()===+m[1]&&d.getMonth()===+m[2]-1?d.getTime():null;}
export function nextMatch(slot,now=Date.now()){return [...slot.calendar].filter(r=>!known(r.result)&&!known(r.score)).sort((a,b)=>(rowTime(a)??Infinity)-(rowTime(b)??Infinity)).find(r=>rowTime(r)===null||rowTime(r)>=now-3*3600000)||null;}
export function scoreOutcome(score){const m=String(score).match(/^(\d{1,2})\s*[x×:-]\s*(\d{1,2})$/);return m?(+m[1]>+m[2]?'V':+m[1]===+m[2]?'E':'D'):null;}
export function pending(slot){const m=slot.match;const out=[];if(!known(m.myStrength)||!known(m.rivalStrength)||!known(m.referee)||!known(m.location))out.push({label:'Completar dados da partida',tab:'pregame',priority:2});if(!slot.tactics||slot.tacticStale)out.push({label:'Preparar tática',tab:'pregame',priority:1});if(slot.calendar.some(r=>rowTime(r)!==null&&rowTime(r)<Date.now()-3*3600000&&!known(r.result)&&!known(r.score)))out.push({label:'Registrar resultado pendente',tab:'learning',priority:3});if(!slot.squad.players.length)out.push({label:'Cadastrar ou ler elenco',tab:'info',priority:4});if(!slot.director.plan&&slot.squad.players.length)out.push({label:'Atualizar plano do Diretor',tab:'director',priority:5});return out.sort((a,b)=>a.priority-b.priority);}
export function sharedLearning(state){const records=state.slots.flatMap(s=>s.learning.matches.map(r=>({...r,slot:s.id})));const weights={};for(const r of records){const key=r.tactic?.formation;if(!key)continue;const w=weights[key]||{games:0,wins:0,draws:0,losses:0};w.games++;w[r.outcome==='V'?'wins':r.outcome==='E'?'draws':'losses']++;weights[key]=w;}return {matches:records.sort((a,b)=>b.at.localeCompare(a.at)),weights};}

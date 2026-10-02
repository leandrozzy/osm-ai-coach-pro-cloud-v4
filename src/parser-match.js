import {NI,normalize} from './utils.js';
const lines=text=>String(text).split(/\r?\n/).map(s=>s.trim()).filter(Boolean);
export function parseReferee(text=''){
 for(const line of lines(text)){const m=normalize(line).match(/(?:arbitro|referee|juiz)\s*[:\-]?\s*(verde|azul|amarelo|laranja|vermelho)\b/);if(m)return m[1][0].toUpperCase()+m[1].slice(1);}return NI;
}
export function parseFormation(text=''){
 for(const line of lines(text)){const m=line.match(/(?:forma[cç][aã]o(?:\s+(?:do\s+)?rival)?|rival|t[aá]tica)\s*[:\-]?\s*([345]\s*-\s*[1-5]\s*-\s*[1-5](?:\s*-\s*[1-5])?)(?:\s*([AB]))?\b/i);if(m)return m[1].replace(/\s/g,'')+(m[2]?' '+m[2].toUpperCase():'');}return NI;
}
// The squad header also contains a formation. Only a label naming the rival,
// or the actual scout report wording, can associate it with rivalFormation.
function parseExplicitRivalFormation(text=''){
 for(const line of lines(text)){const m=line.match(/^(?:forma[cç][aã]o\s+(?:(?:do\s+)?rival|(?:do\s+)?advers[aá]rio)|rival|t[aá]tica\s+(?:do\s+)?rival)\s*[:\-]?\s*([345]\s*-\s*[1-5]\s*-\s*[1-5](?:\s*-\s*[1-5])?)(?:\s*([AB]))?\b/i);if(m)return m[1].replace(/\s/g,'')+(m[2]?' '+m[2].toUpperCase():'');}return NI;
}
export function parseRivalReportText(text='',context={}){
 const n=normalize(text).replace(/\s+/g,' ');
 const isReport=/\bpelo que pude ver\b/.test(n)&&/\b(?:deu ordens aos jogadores|consegui descobrir a formacao)\b/.test(n);
 if(!isReport)return {};
 const out={};
 const club=String(text).replace(/\s+/g,' ').match(/pelo que pude ver,\s*(.+?)\s+deu ordens aos jogadores/i);if(club){if(context.myTeam&&normalize(context.myTeam)===normalize(club[1]))return {};out.rivalName=club[1].trim();}
 const formation=n.match(/\bparece que vao jogar num\s+([345]\s*-\s*[1-5]\s*-\s*[1-5](?:\s*-\s*[1-5])?)(?:\s*([ab]))?\b/);
 if(formation)out.rivalFormation=formation[1].replace(/\s/g,'')+(formation[2]?' '+formation[2].toUpperCase():'');
 const tackling=n.match(/\bentradas\s+(cauteloso|normal|agressivo|imprudente)\b/);if(tackling)out.rivalTackling=tackling[1][0].toUpperCase()+tackling[1].slice(1);
 const stadium=n.match(/\bnivel do estadio\s*:?\s*(\d{1,2})\b/);if(stadium)out.stadium=+stadium[1];
 if(/\beles nao foram em estagio\b/.test(n))out.trainingCamp='Não';else if(/\beles foram em estagio\b/.test(n))out.trainingCamp='Sim';
 const plan=parsePlan(text);if(plan!==NI)out.rivalPlan=plan;
 const marking=n.match(/\bmarcacao\s*:?\s*(a zona|zona|individual)\b/);if(marking)out.rivalMarking=marking[1]==='individual'?'Individual':'À zona';
 const offside=n.match(/\b(?:fazer\s+)?(?:fora[- ]de[- ]jogo|impedimento)\s*:?\s*(sim|nao)\b/);if(offside)out.rivalOffside=offside[1]==='sim'?'Sim':'Não';
 return out;
}
export function parsePlan(text=''){
 const n=normalize(text);for(const [key,value] of [['jogo de passes','Jogo de passes'],['jogar pelas alas','Jogar pelas alas'],['contra-ataque','Contra-ataque'],['contra ataque','Contra-ataque'],['remate a vista','Remate à vista'],['bola longa','Bola longa']])if(n.includes(key))return value;return NI;
}
export function parseMarking(text=''){
 for(const line of lines(text)){const m=normalize(line).match(/marcacao(?:\s+rival)?\s*[:\-]?\s*(a zona|zona|individual)\b/);if(m)return m[1]==='individual'?'Individual':'À zona';}return NI;
}
export function parseOffside(text=''){
 for(const line of lines(text)){const m=normalize(line).match(/(?:impedimento|fora de jogo)(?:\s+rival)?\s*[:\-]?\s*(sim|nao)\b/);if(m)return m[1]==='sim'?'Sim':'Não';}return NI;
}
export function parseYesNoNear(text,label){
 for(const line of lines(text)){const n=normalize(line),l=normalize(label);if(!n.startsWith(l))continue;const m=n.slice(l.length).match(/^\s*[:\-]?\s*(sim|nao)\b/);if(m)return m[1]==='sim'?'Sim':'Não';}return NI;
}
export function parseStrengthPair(text=''){
 const my=pick(text,['minha força','força do meu time','meu time força']),rival=pick(text,['força rival','força do rival']);return {my:integer(my),rival:integer(rival)};
}
function pick(text,labels){
 for(const line of lines(text)){for(const label of labels){const n=normalize(line),l=normalize(label);if(n.startsWith(l)&&/^[\s:\-]/.test(n.slice(l.length))){const rest=line.slice(label.length).replace(/^\s*[:\-]?\s*/,'').trim();if(rest)return rest;}}}return NI;
}
function integer(v){if(v===NI)return null;const m=String(v).match(/^\d{1,3}$/);return m?+m[0]:null;}
export function parseMatchText(text='',context={}){
 const strengths=parseStrengthPair(text);
 const out={_schemaVersion:3,myName:pick(text,['meu time','minha equipe','minha equipa']),rivalName:pick(text,['time rival','adversário','rival']),rivalNickname:pick(text,['nickname rival','treinador rival','nickname']),myStrength:strengths.my??NI,rivalStrength:strengths.rival??NI,referee:parseReferee(text),rivalFormation:parseExplicitRivalFormation(text),rivalPlan:parsePlan(pick(text,['plano rival'])),rivalMarking:parseMarking(lines(text).filter(l=>/marcacao\s+(?:(?:do\s+)?rival|adversario)/.test(normalize(l))).join('\n')),rivalOffside:parseOffside(lines(text).filter(l=>/(?:impedimento|fora de jogo)\s+(?:(?:do\s+)?rival|adversario)/.test(normalize(l))).join('\n')),secretTraining:parseYesNoNear(text,'treino secreto'),trainingCamp:parseYesNoNear(text,'campo de treinamento'),myTrainingCamp:parseYesNoNear(text,'meu campo de treinamento')};
 for(const [key,label] of [['mySquadValue','valor do meu elenco'],['rivalSquadValue','valor do elenco rival'],['myPlayers','meus jogadores'],['rivalPlayers','jogadores rival'],['stadium','estádio'],['myBonus','meu bônus'],['rivalBonus','bônus rival']])out[key]=pick(text,[label]);
 for(const [key,label] of [['GK','GOL'],['DEF','DEF'],['MID','MEI'],['ATT','ATA']]){out['my'+key]=integer(pick(text,['meu '+label]))??NI;out['rival'+key]=integer(pick(text,['rival '+label]))??NI;}
 const loc=normalize(pick(text,['local','casa/fora']));out.location=loc==='casa'?'Casa':loc==='fora'?'Fora':NI;
 out.rivalTackling=pick(text,['desarme rival','entrada rival']);return {...out,...parseRivalReportText(text,context)};
}
export function mergeMatchTexts(texts=[],context={}){const out={_schemaVersion:3,_sources:{}};for(let i=0;i<texts.length;i++){const obj=parseMatchText(texts[i],context);for(const [k,v] of Object.entries(obj)){if(k.startsWith('_')||v==null||v===NI)continue;out[k]=v;out._sources[k]='OCR tela '+(i+1);}}return out;}

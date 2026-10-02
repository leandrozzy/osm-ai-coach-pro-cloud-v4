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
 if(club){
  const preceding=lines(text).slice(0,lines(text).findIndex(line=>/pelo que pude ver/i.test(line)));
  const nameIndex=preceding.findIndex(line=>normalize(line)===normalize(club[1]));
  const nickname=nameIndex>=0?preceding[nameIndex+1]:null;
  if(nickname&&nickname.length<=60&&!/formacao|marcacao|jogar|estadio|relatorio/i.test(normalize(nickname)))out.rivalNickname=nickname;
 }
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
 const n=normalize(text).replace(/\s+/g,' ');const m=n.match(/marcacao(?:\s+(?:(?:do\s+)?rival|adversario))?\s*[:\-]?\s*(a zona|zona|individual)\b/);return m?(m[1]==='individual'?'Individual':'À zona'):NI;
}
export function parseOffside(text=''){
 const n=normalize(text).replace(/\s+/g,' ');const m=n.match(/(?:impedimento|fora[- ]de[- ]jogo)(?:\s+(?:(?:do\s+)?rival|adversario))?\s*[:\-]?\s*(sim|nao)\b/);return m?(m[1]==='sim'?'Sim':'Não'):NI;
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
 for(const [key,labels] of [['mySquadValue',['valor do meu elenco','valor da minha equipa','valor do meu plantel']],['rivalSquadValue',['valor do elenco rival','valor do plantel rival','valor da equipa rival']],['myPlayers',['meus jogadores','jogadores do meu elenco']],['rivalPlayers',['jogadores rival','jogadores do rival']],['stadium',['estádio','nível do estádio']],['myBonus',['meu bônus','bônus do meu time']],['rivalBonus',['bônus rival','bônus do rival']]])out[key]=pick(text,labels);
 for(const [key,label] of [['GK','GOL'],['DEF','DEF'],['MID','MEI'],['ATT','ATA']]){out['my'+key]=integer(pick(text,['meu '+label]))??NI;out['rival'+key]=integer(pick(text,['rival '+label]))??NI;}
 const loc=normalize(pick(text,['local','casa/fora']));out.location=loc==='casa'?'Casa':loc==='fora'?'Fora':NI;
 out.rivalTackling=pick(text,['desarme rival','entrada rival','entradas rival']);
 const human=normalize(pick(text,['humano/cpu','tipo do rival','rival humano']));if(human==='humano'||human==='sim')out.human=true;else if(human==='cpu'||human==='nao')out.human=false;
 return {...out,...parseRivalReportText(text,context)};
}
export function mergeMatchTexts(texts=[],context={}){const out={_schemaVersion:3,_sources:{}};for(let i=0;i<texts.length;i++){const obj=parseMatchText(texts[i],context);for(const [k,v] of Object.entries(obj)){if(k.startsWith('_')||v==null||v===NI)continue;out[k]=v;out._sources[k]='OCR tela '+(i+1);}}return out;}

export function matchOcrRows(ocr={}){
 return (ocr.lines||[]).map(line=>{
  const words=(line.Words||[]).filter(word=>String(word.WordText||'').trim());
  if(!words.length)return null;
  const left=Math.min(...words.map(word=>Number(word.Left))),top=Math.min(...words.map(word=>Number(word.Top??line.MinTop))),right=Math.max(...words.map(word=>Number(word.Left)+Number(word.Width))),bottom=Math.max(...words.map(word=>Number(word.Top??line.MinTop)+Number(word.Height??line.MaxHeight)));
  return {text:words.map(word=>word.WordText).join(' '),left,top,right,bottom,words};
 }).filter(row=>row&&[row.left,row.top,row.right,row.bottom].every(Number.isFinite));
}
export function parseMatchOverlay(ocr={},width,height,context={}){
 const out=mergeMatchTexts([ocr.text||''],context);
 if(!width||!height||width/height<1.8||width/height>2.6)return out;
 const rows=matchOcrRows(ocr),words=rows.flatMap(row=>row.words.map(word=>({text:String(word.WordText),x:Number(word.Left),y:Number(word.Top??row.top),w:Number(word.Width),h:Number(word.Height??row.bottom-row.top)})));
 const vs=words.find(word=>normalize(word.text)==='vs'&&word.x>width*.44&&word.x<width*.56&&word.y>height*.20&&word.y<height*.39);
 if(vs){
  const own=rows.find(row=>normalize(row.text)===normalize(context.username||'leandrozzy')&&row.top>height*.34&&row.bottom<height*.47);
  if(own){
   const ownLeft=own.left<width*.5;
   const sameSide=(row,left)=>left?row.left>width*.14&&row.right<width*.44:row.left>width*.66&&row.right<width*.94;
   const club=(left)=>rows.filter(row=>sameSide(row,left)&&row.bottom<own.top&&own.top-row.bottom<height*.07&&/[a-z]/i.test(row.text)&&!/^vs$/i.test(row.text)).sort((a,b)=>b.bottom-a.bottom)[0];
   const mine=club(ownLeft),rival=club(!ownLeft);if(mine)out.myName=mine.text;if(rival)out.rivalName=rival.text;
   const nickname=rows.find(row=>sameSide(row,!ownLeft)&&Math.abs(row.top-own.top)<height*.02&&row.text.length<=60);if(nickname)out.rivalNickname=nickname.text;
   for(const word of words){
    if(word.y<height*.20||word.y>height*.31)continue;
    const left=word.x>width*.28&&word.x<width*.38,right=word.x>width*.62&&word.x<width*.72;if(!left&&!right)continue;
    const prefix=left===ownLeft?'my':'rival';
    if(/^\+?\d{1,2}%$/.test(word.text))out[prefix+'Bonus']=+word.text.replace(/[^\d]/g,'');
    else if(/^\d{1,3}$/.test(word.text)&&+word.text>0&&+word.text<=400)out[prefix+'Strength']=+word.text;
   }
  }
  return out;
 }
 const validTeam=row=>row.left<width*.4&&row.top>height*.16&&row.bottom<height*.30&&/[a-zA-Z]/.test(row.text)&&!/(?:nota mais|jogador|marcador|idade|posicao|objetivo|analista)/.test(normalize(row.text));
 const username=normalize(context.username||'leandrozzy');
 const ownUser=rows.find(row=>normalize(row.text)===username&&row.left<width*.4&&row.top>height*.16&&row.bottom<height*.32);
 const ownName=ownUser?rows.filter(row=>validTeam(row)&&row.bottom<ownUser.top&&ownUser.top-row.bottom<height*.08).sort((a,b)=>b.bottom-a.bottom)[0]:null;
 let teamRow=ownName||rows.find(row=>validTeam(row)&&context.myTeam&&normalize(row.text)===normalize(context.myTeam));
 let prefix=teamRow?'my':null;
 const lockedLabel=rows.find(row=>/analista de dados/.test(normalize(row.text))&&row.left>width*.65&&row.top>height*.20&&row.bottom<height*.37);
 if(!teamRow&&lockedLabel){teamRow=rows.filter(validTeam).sort((a,b)=>a.top-b.top)[0];if(teamRow&&(!context.myTeam||normalize(teamRow.text)!==normalize(context.myTeam)))prefix='rival';}
 if(!teamRow&&context.rivalName){teamRow=rows.find(row=>validTeam(row)&&normalize(row.text)===normalize(context.rivalName));if(teamRow)prefix='rival';}
 if(!prefix)return out;
 out[prefix+'Name']=teamRow.text;
 if(prefix==='rival'){
  const nickname=rows.filter(row=>row.left<width*.4&&row.top>teamRow.bottom&&row.top-teamRow.bottom<height*.065&&row.text.length<60&&!/nota mais|jogador|marcador|posicao/.test(normalize(row.text))).sort((a,b)=>a.top-b.top)[0];
  if(nickname)out.rivalNickname=nickname.text;
 }
 // The money at the very top is cash. Only the squad-value header is accepted.
 const value=words.find(word=>word.x>width*.91&&word.y>height*.105&&word.y<height*.20&&/^\d+(?:[.,]\d+)?\s*[MKB]$/i.test(word.text));if(value)out[prefix+'SquadValue']=value.text;
 const sectorMap={gol:'GK',gr:'GK',gk:'GK',def:'DEF',med:'MID',mei:'MID',ata:'ATT'};
 for(const label of words){
  const sector=sectorMap[normalize(label.text)];if(!sector||label.x<width*.58||label.x>width*.8||label.y<height*.12||label.y>height*.32)continue;
  const number=words.filter(word=>/^\d{1,3}$/.test(word.text)&&Math.abs(word.x+word.w/2-label.x-label.w/2)<width*.018&&word.y>label.y&&word.y-label.y<height*.06).sort((a,b)=>a.y-b.y)[0];
  if(number&&+number.text>0&&+number.text<=400)out[prefix+sector]=+number.text;
 }
 const force=words.find(word=>normalize(word.text)==='equipa'&&word.x>width*.79&&word.x<width*.92&&word.y>height*.18&&word.y<height*.35);
 if(force){const number=words.filter(word=>/^\d{1,3}$/.test(word.text)&&Math.abs(word.x+word.w/2-force.x-force.w/2)<width*.026&&word.y>force.y&&word.y-force.y<height*.10).sort((a,b)=>a.y-b.y)[0];if(number&&+number.text>0&&+number.text<=400)out[prefix+'Strength']=+number.text;}
 if(prefix==='rival'){
  const formation=rows.find(row=>row.left>width*.67&&row.left<width*.78&&row.top>height*.105&&row.bottom<height*.20&&/^[345]\s*-\s*[1-5]\s*-\s*[1-5](?:\s*-\s*[1-5])?(?:\s*[AB])?$/i.test(row.text));
  if(formation)out.rivalFormation=formation.text.replace(/\s*([AB])$/i,' $1').replace(/\s*-\s*/g,'-');
 }
 return out;
}

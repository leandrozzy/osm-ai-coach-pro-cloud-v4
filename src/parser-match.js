import {NI, normalize, toNumber} from './utils.js';

const formationRe=/\b([345]\s*[- ]\s*[2345]\s*[- ]\s*[12345](?:\s*[AB])?)\b/i;
const moneyRe=/(?:R\$\s*)?(\d+(?:[.,]\d+)?)\s*(K|M|MM|B)?\b/gi;
const pctRe=/(\d{1,3})\s*%/g;
const numberRe=/\b(\d{1,3})\b/g;
const clean=v=>String(v??'').replace(/\s+/g,' ').trim();
const linesOf=text=>String(text||'').split(/\r?\n/).map(clean).filter(Boolean);
const ni=v=>v==null||v===''?NI:v;

function afterLabel(lines, labels, maxAhead=2){
  const labs=labels.map(normalize);
  for(let i=0;i<lines.length;i++){
    const n=normalize(lines[i]);
    const lab=labs.find(l=>n.includes(l));
    if(!lab) continue;
    const raw=lines[i];
    const idx=n.indexOf(lab);
    const rest=clean(raw.slice(Math.min(raw.length,idx+lab.length)).replace(/^\s*[:\-–—]\s*/,''));
    if(rest && normalize(rest)!==lab && !labs.some(l=>normalize(rest)===l)) return rest;
    for(let j=1;j<=maxAhead && i+j<lines.length;j++){
      const c=clean(lines[i+j]);
      if(c && c.length<80) return c;
    }
  }
  return '';
}
function allMatches(text,re){return [...String(text||'').matchAll(re)].map(m=>m[1]??m[0]);}
function firstInt(s){const m=String(s||'').match(/\b\d{1,3}\b/);return m?Number(m[0]):null;}
function parsePairNear(lines,labels,parser=firstInt){
  const labs=labels.map(normalize);
  for(let i=0;i<lines.length;i++){
    if(!labs.some(l=>normalize(lines[i]).includes(l))) continue;
    const block=lines.slice(i,i+3).join(' ');
    const vals=allMatches(block,/\b(\d{1,3})\b/g).map(Number).filter(n=>n>=0&&n<=999);
    if(vals.length>=2)return [vals[0],vals[1]];
    const p=parser(block); if(p!=null)return [p,null];
  }
  return [null,null];
}
function parseMoneyToken(s=''){
  const m=String(s).match(/(?:R\$\s*)?(\d+(?:[.,]\d+)?)\s*(K|M|MM|B)\b/i); if(!m)return null;
  const n=Number(m[1].replace(',','.')); const u=m[2].toUpperCase(); const mul=u==='K'?1e3:(u==='B'?1e9:1e6); return Math.round(n*mul);
}
function parseMoneyPairNear(lines,labels){
  const labs=labels.map(normalize);
  for(let i=0;i<lines.length;i++){
    if(!labs.some(l=>normalize(lines[i]).includes(l)))continue;
    const block=lines.slice(i,i+3).join(' ');
    const vals=[]; for(const m of block.matchAll(/(?:R\$\s*)?(\d+(?:[.,]\d+)?)\s*(K|M|MM|B)\b/gi)){const v=parseMoneyToken(m[0]);if(v!=null)vals.push(v);}
    if(vals.length>=2)return [vals[0],vals[1]];
    if(vals.length===1)return [vals[0],null];
  }
  return [null,null];
}
function parsePctPairNear(lines,labels){
  const labs=labels.map(normalize);
  for(let i=0;i<lines.length;i++){
    if(!labs.some(l=>normalize(lines[i]).includes(l)))continue;
    const vals=[...lines.slice(i,i+3).join(' ').matchAll(pctRe)].map(m=>Number(m[1]));
    if(vals.length>=2)return [vals[0],vals[1]];
    if(vals.length===1)return [vals[0],null];
  }
  return [null,null];
}

export function parseReferee(text=''){
  const n=normalize(text);
  const refereeContext=/(arbitro|árbitro|referee|juiz)/i.test(text);
  if(!refereeContext)return NI;
  for(const c of ['vermelho','laranja','amarelo','azul','verde']) if(n.includes(c)) return c[0].toUpperCase()+c.slice(1);
  return NI;
}
export function parseFormation(text=''){
  const lines=linesOf(text); const tactical=/formacao|formação|tatica|tática|plano de jogo|marcacao|marcação|impedimento|fora de jogo/i.test(text);
  if(!tactical)return NI;
  for(let i=0;i<lines.length;i++){
    const line=lines[i]; const m=line.match(formationRe); if(!m)continue;
    const nearby=lines.slice(Math.max(0,i-2),i+3).join(' ');
    if(/formacao|formação|tatica|tática|plano|marcacao|marcação|impedimento|fora de jogo/i.test(nearby)) return m[1].replace(/\s+/g,'').replace(/(\d)-(\d)-(\d)([AB])/i,'$1-$2-$3 $4').toUpperCase();
  }
  return NI;
}
export function parsePlan(text=''){const n=normalize(text);const map=[['jogo de passes','Jogo de passes'],['jogar pelas alas','Jogar pelas alas'],['pelas alas','Jogar pelas alas'],['contra-ataque','Contra-ataque'],['contra ataque','Contra-ataque'],['remate a vista','Remate à vista'],['bola longa','Bola longa']];return map.find(([k])=>n.includes(k))?.[1]||NI;}
export function parseMarking(text=''){const n=normalize(text);if(!/(marcacao|marcação|zona|individual)/i.test(text))return NI;if(/\bindividual\b/.test(n))return 'Individual';if(/\bzona\b/.test(n))return 'À zona';return NI;}
export function parseOffside(text=''){const n=normalize(text);const m=n.match(/(?:impedimento|fora de jogo)[^\n]{0,40}\b(sim|nao)\b/);return m?(m[1]==='sim'?'Sim':'Não'):NI;}
export function parseYesNoNear(text,label){const n=normalize(text),l=normalize(label),i=n.indexOf(l);if(i<0)return NI;const s=n.slice(i,i+100);if(/\bsim\b/.test(s))return 'Sim';if(/\bnao\b/.test(s))return 'Não';return NI;}
export function parseStrengthPair(text=''){const lines=linesOf(text);for(const line of lines){const m=line.match(/\b(\d{2,3})\s*[x×-]\s*(\d{2,3})\b/i);if(m){const a=Number(m[1]),b=Number(m[2]);if(a>=20&&a<=250&&b>=20&&b<=250)return {my:a,rival:b};}}return {my:null,rival:null};}

function parseSectorPair(lines,label){
  const aliases={GK:['gol','gk','goleiro'],DEF:['def','defesa'],MID:['mei','mid','meio'],ATT:['ata','att','ataque']}[label];
  return parsePairNear(lines,aliases);
}
function parseLocation(text){const n=normalize(text);if(/\b(casa|home)\b/.test(n))return 'Casa';if(/\b(fora|away)\b/.test(n))return 'Fora';return NI;}
function parseNameByLabel(lines,labels){const v=afterLabel(lines,labels,1);if(!v)return NI;const n=normalize(v);if(/^(ni|sim|nao|casa|fora)$/.test(n)||/^\d/.test(v))return NI;return v.slice(0,60);}
function parseNickname(lines){const v=afterLabel(lines,['treinador','manager','técnico','tecnico','nickname'],1);if(!v)return NI;const m=v.match(/@?([\w.-]{3,30})/);return m?m[1]:NI;}

export function parseMatchText(text=''){
  const lines=linesOf(text), strengths=parseStrengthPair(text);
  const [myValue,rivalValue]=parseMoneyPairNear(lines,['valor do elenco','valor elenco','squad value']);
  const [myPlayers,rivalPlayers]=parsePairNear(lines,['jogadores','players']);
  const [myBonus,rivalBonus]=parsePctPairNear(lines,['bonus','bônus']);
  const [myGK,rivalGK]=parseSectorPair(lines,'GK'); const [myDEF,rivalDEF]=parseSectorPair(lines,'DEF'); const [myMID,rivalMID]=parseSectorPair(lines,'MID'); const [myATT,rivalATT]=parseSectorPair(lines,'ATT');
  const stadiumRaw=afterLabel(lines,['estádio','estadio','stadium'],1); const stadium=firstInt(stadiumRaw);
  const tackle=afterLabel(lines,['tipo de entrada','desarme','tackling'],1);
  return {
    _schemaVersion:2, rawText:text,
    myName:parseNameByLabel(lines,['meu time','minha equipa','minha equipe']),
    rivalName:parseNameByLabel(lines,['adversário','adversario','rival','oponente']),
    rivalNickname:parseNickname(lines),
    myStrength:strengths.my??NI,rivalStrength:strengths.rival??NI,
    mySquadValue:myValue??NI,rivalSquadValue:rivalValue??NI,myPlayers:myPlayers??NI,rivalPlayers:rivalPlayers??NI,
    myGK:myGK??NI,rivalGK:rivalGK??NI,myDEF:myDEF??NI,rivalDEF:rivalDEF??NI,myMID:myMID??NI,rivalMID:rivalMID??NI,myATT:myATT??NI,rivalATT:rivalATT??NI,
    stadium:stadium??NI,myBonus:myBonus??NI,rivalBonus:rivalBonus??NI,location:parseLocation(text),
    referee:parseReferee(text),rivalFormation:parseFormation(text),rivalPlan:parsePlan(text),rivalMarking:parseMarking(text),rivalOffside:parseOffside(text),
    secretTraining:parseYesNoNear(text,'treino secreto'),trainingCamp:parseYesNoNear(text,'campo de treinamento'),rivalTackling:tackle||NI
  };
}

export function mergeMatchTexts(texts=[]){
  const out={_schemaVersion:2,_sources:{}};
  for(let i=0;i<texts.length;i++){
    const p=parseMatchText(texts[i]);
    for(const [k,v] of Object.entries(p)){
      if(k.startsWith('_')||k==='rawText'||v==null||v===''||v===NI)continue;
      if(out[k]==null||out[k]===NI){out[k]=v;out._sources[k]=`frame:${i+1}`;}
    }
  }
  return out;
}
export const parseMoney=v=>toNumber(v);

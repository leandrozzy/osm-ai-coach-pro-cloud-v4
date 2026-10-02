import {NI, normalize, toNumber} from './utils.js';
const formations=/\b([345]\s*[- ]\s*[2345]\s*[- ]\s*[12345](?:\s*[AB])?)\b/i;
export function parseReferee(text=''){
  const n=normalize(text); for(const c of ['vermelho','laranja','amarelo','azul','verde']) if(n.includes(c)) return c[0].toUpperCase()+c.slice(1); return NI;
}
export function parseFormation(text=''){const m=text.match(formations);return m?m[1].replace(/\s+/g,'').replace(/(\d)-(\d)-(\d)([AB])/i,'$1-$2-$3 $4').toUpperCase():NI;}
export function parsePlan(text=''){const n=normalize(text); const map=[['jogo de passes','Jogo de passes'],['jogar pelas alas','Jogar pelas alas'],['pelas alas','Jogar pelas alas'],['contra-ataque','Contra-ataque'],['contra ataque','Contra-ataque'],['remate a vista','Remate à vista'],['bola longa','Bola longa']]; return map.find(([k])=>n.includes(k))?.[1]||NI;}
export function parseMarking(text=''){const n=normalize(text); if(n.includes('zona'))return 'À zona'; if(n.includes('individual'))return 'Individual'; return NI;}
export function parseOffside(text=''){const n=normalize(text); const m=n.match(/(?:impedimento|fora de jogo)[^\n]{0,25}\b(sim|nao)\b/); return m?(m[1]==='sim'?'Sim':'Não'):NI;}
export function parseYesNoNear(text,label){const n=normalize(text);const l=normalize(label);const i=n.indexOf(l);if(i<0)return NI;const s=n.slice(i,i+80);if(/\bsim\b/.test(s))return 'Sim';if(/\bnao\b/.test(s))return 'Não';return NI;}
export function parseStrengthPair(text=''){const lines=text.split(/\n+/);for(const line of lines){const m=line.match(/\b(\d{2,3})\s*[x×-]\s*(\d{2,3})\b/i);if(m)return {my:Number(m[1]),rival:Number(m[2])};}return {my:null,rival:null};}
export function parseMatchText(text=''){
 const strengths=parseStrengthPair(text);
 return {rawText:text,referee:parseReferee(text),rivalFormation:parseFormation(text),rivalPlan:parsePlan(text),rivalMarking:parseMarking(text),rivalOffside:parseOffside(text),secretTraining:parseYesNoNear(text,'treino secreto'),trainingCamp:parseYesNoNear(text,'campo de treinamento'),myStrength:strengths.my??NI,rivalStrength:strengths.rival??NI};
}
export const parseMoney=v=>toNumber(v);

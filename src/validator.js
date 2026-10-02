import {NI} from './utils.js';
const validForm=/^(3|4|5)-(2|3|4|5)-(1|2|3|4|5)(?: [AB])?$/;
export function confidenceValue(v){return v==null||v===''||v===NI?0:1;}
export function validateMatch(m={}){
 const issues=[]; if(m.rivalFormation!==NI&&m.rivalFormation&&!validForm.test(m.rivalFormation))issues.push('Formação rival inválida');
 for(const k of ['myStrength','rivalStrength']){const v=m[k];if(v!==NI&&v!=null&&(Number(v)<20||Number(v)>250))issues.push(`${k} fora do intervalo plausível`);}
 const secret=m.secretTraining==='Sim';
 const fields=['myStrength','rivalStrength','referee','rivalName','rivalNickname','mySquadValue','rivalSquadValue','myPlayers','rivalPlayers','myGK','myDEF','myMID','myATT','rivalGK','rivalDEF','rivalMID','rivalATT','stadium','myBonus','rivalBonus','location','secretTraining','trainingCamp','rivalFormation','rivalPlan','rivalMarking','rivalOffside'];
 const hidden=secret?new Set(['rivalStrength','rivalGK','rivalDEF','rivalMID','rivalATT','rivalFormation','rivalPlan']):new Set();
 const considered=fields.filter(k=>!hidden.has(k)); const present=considered.filter(k=>confidenceValue(m[k])).length;
 return {valid:issues.length===0,issues,coverage:Math.round(100*present/Math.max(1,considered.length)),hiddenByGame:[...hidden]};
}
export function chooseBetter(oldValue,newValue){if(newValue==null||newValue===''||newValue===NI)return oldValue??NI;return newValue;}
export function mergeBetter(oldObj={},newObj={}){const out={...oldObj};for(const [k,v] of Object.entries(newObj)){if(k==='_sources'){out._sources={...(oldObj._sources||{}),...(v||{})};continue;}out[k]=chooseBetter(oldObj[k],v);}return out;}

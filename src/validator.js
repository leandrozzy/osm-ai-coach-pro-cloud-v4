import {NI} from './utils.js';
import {autoMatchFields,reportTacticFields,confirmedMatchSecretTraining,confirmedMatchField,formations,known} from './domain.js';
export function confidenceValue(v){return v==null||v===''||v===NI?0:1;}
export function validateMatch(m={}){
 const issues=[]; if(m.rivalFormation!==NI&&m.rivalFormation&&!formations.includes(m.rivalFormation))issues.push('Formação rival inválida');
 for(const k of ['myStrength','rivalStrength']){const v=m[k];if(v!==NI&&v!=null&&(Number(v)<20||Number(v)>250))issues.push(`${k} fora do intervalo plausível`);}
 const secret=confirmedMatchSecretTraining(m);
 const fields=autoMatchFields.map(([field])=>field);
 const hidden=secret?new Set(reportTacticFields.filter(field=>!confirmedMatchField(m,field))):new Set();
 const considered=fields.filter(k=>!hidden.has(k)&&!(k==='rivalNickname'&&m.human===false&&(m._fieldSources?.human?.rank||0)>=2));const present=considered.filter(k=>confirmedMatchField(m,k)).length;
 return {valid:issues.length===0,issues,coverage:Math.floor(100*present/Math.max(1,considered.length)),hiddenByGame:[...hidden]};
}
export function chooseBetter(oldValue,newValue){if(newValue==null||newValue===''||newValue===NI)return oldValue??NI;return newValue;}
export function mergeBetter(oldObj={},newObj={}){const out={...oldObj};for(const [k,v] of Object.entries(newObj)){if(k==='_sources'){out._sources={...(oldObj._sources||{}),...(v||{})};continue;}out[k]=chooseBetter(oldObj[k],v);}return out;}

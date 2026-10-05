import {allowed,requestBody,keyFor,errorInfo} from '../lib/http.js';
import {groqRead,googleRead} from '../lib/providers.js';
import {validateTactic} from '../src/tactics-engine.js';
import {num,known,confirmedMatchSecretTraining,confirmedMatchField} from '../src/domain.js';
const TACTIC_DEADLINE_MS=24000;
function invalidTactic(data,context){
 const tactic=data?.tactic;
 if(!validateTactic(tactic)||typeof tactic.reason!=='string'||!tactic.reason.trim()||tactic.reason.length>4000)return Object.assign(Error('A IA retornou uma tática incompleta.'),{code:'TACTIC_INVALID'});
 const own=num(context.match?.myStrength),rival=num(context.match?.rivalStrength);
 if(tactic.formation.startsWith('4-3-3')&&(own===null||rival===null||own-rival<13))return Object.assign(Error('A IA sugeriu 4-3-3 sem a vantagem de força confirmada exigida.'),{code:'TACTIC_INVALID'});
 return null;
}
function recoverable(provider,error){
 // Groq's JSON helper already retries malformed JSON once. Do not repeat
 // that pair, authentication failures, quota limits or a network timeout.
 return error.code==='TACTIC_INVALID'||provider==='groq'&&error.code==='GROQ_OUTPUT_SPLIT'||provider==='google'&&(error instanceof SyntaxError||['GOOGLE_OUTPUT_SPLIT','GOOGLE_RESPONSE_INCOMPLETE'].includes(error.code));
}
function requestedModel(provider,body){
 if(provider==='google')return body.models?.googleText||body.models?.google||body.models?.gemini||(/^gemini-/.test(body.model||'')?body.model:undefined);
 return body.models?.groqText||(!/^gemini-/.test(body.model||'')?body.model:undefined);
}
export default async function handler(req,res){
 if(!allowed(req,res))return;
 try{
 const body=requestBody(req);if(body.task!=='tactic')return res.status(400).json({error:'Use /api/analyze para leitura de mídia.'});
 const context=body.payload?.context||{};if(typeof context!=='object'||Array.isArray(context))return res.status(400).json({error:'Contexto da partida inválido.'});
 const failures=[],started=Date.now();
 const providers=['groq','google'].map(provider=>({provider,key:keyFor(provider,body)})).filter(entry=>entry.key);
 for(let index=0;index<providers.length;index++){
  const {provider,key}=providers[index],remaining=TACTIC_DEADLINE_MS-(Date.now()-started);
  if(remaining<700)break;
  // askAI stops after 27 seconds. Both providers share this shorter server
  // deadline; the first cannot consume the second provider's entire window.
  const budgetMs=index<providers.length-1?Math.min(11000,remaining-9000):remaining;
  if(budgetMs<700)continue;
  const providerStarted=Date.now();
  for(let attempt=0;attempt<2;attempt++){
   const requestBudget=budgetMs-(Date.now()-providerStarted);if(requestBudget<700)break;
   try{
    const args={key,task:'tactic',context:attempt?{...context,_tacticRepair:true}:context,model:requestedModel(provider,body),budgetMs:requestBudget};
    const raw=await (provider==='groq'?groqRead(args):googleRead(args)),invalid=invalidTactic(raw,context);if(invalid)throw invalid;
    const tactic=Object.fromEntries(['formation','style','pressure','mentality','tempo','marking','offside','tackling','attack','midfield','defense','reason'].map(field=>[field,raw.tactic[field]]));
    const match=context.match||{};
    tactic.provisional=['myStrength','rivalStrength','referee','location'].some(field=>!known(match[field]))||!confirmedMatchSecretTraining(match)&&!confirmedMatchField(match,'rivalMarking');
    tactic.source='IA · '+provider;tactic.createdAt=new Date().toISOString();
    return res.status(200).json({provider,model:raw._providerModel||null,fallback:false,data:{tactic},...(failures.length?{failures}:{})});
   }catch(error){
    if(attempt===0&&recoverable(provider,error)&&budgetMs-(Date.now()-providerStarted)>=700)continue;
    failures.push(errorInfo(provider,error));break;
   }
  }
 }
 return res.status(503).json({error:'Nenhuma IA gerou uma tática válida. Confira acesso e cota de Google/Gemini ou Groq. Os dados da partida foram preservados.',details:failures,fallback:false});
 }catch(e){return res.status(e.message==='JSON inválido.'?400:503).json({error:'Solicitação inválida. Dados preservados.'});}
}

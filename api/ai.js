import {allowed,requestBody,keyFor,errorInfo} from '../lib/http.js';
import {groqRead,googleRead} from '../lib/providers.js';
import {validateTactic} from '../src/tactics-engine.js';
export default async function handler(req,res){
 if(!allowed(req,res))return;
 try{
 const body=requestBody(req);if(body.task!=='tactic')return res.status(400).json({error:'Use /api/analyze para leitura de mídia.'});
 const failures=[];
 for(const provider of ['groq','google']){
  const key=keyFor(provider,body);if(!key)continue;
  try{const args={key,task:'tactic',context:body.payload?.context||{},model:body.models?.groqText||body.model};const data=await (provider==='groq'?groqRead(args):googleRead(args));if(!validateTactic(data.tactic))throw Error('Tática incompleta.');return res.status(200).json({provider,data});}catch(e){failures.push(errorInfo(provider,e));}
 }
 return res.status(503).json({error:'IA indisponível. Configure Google/Gemini ou Groq; a tática local continua disponível.',details:failures});
 }catch(e){return res.status(e.message==='JSON inválido.'?400:503).json({error:'Solicitação inválida. Dados preservados.'});}
}

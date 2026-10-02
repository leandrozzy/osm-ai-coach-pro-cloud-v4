import {allowed,requestBody,keyFor,errorInfo} from '../lib/http.js';
import {groqRead} from '../lib/providers.js';
import {validateTactic} from '../src/tactics-engine.js';
export default async function handler(req,res){
 if(!allowed(req,res))return;
 try{
 const body=requestBody(req);
 if(body.task!=='tactic')return res.status(400).json({error:'Use /api/analyze para leitura de mídia.'});
 const key=keyFor('groq',body);if(!key)return res.status(503).json({error:'Configure Groq em Configurações. A tática local continua disponível.'});
 const data=await groqRead({key,task:'tactic',context:body.payload?.context||{},model:body.models?.groqText||body.model});
 if(!validateTactic(data.tactic))throw Error('Groq retornou tática incompleta.');
 return res.status(200).json({provider:'groq',data});
 }catch(e){return res.status(e.message==='JSON inválido.'?400:503).json({error:'Groq indisponível. Dados preservados.',details:[errorInfo('groq',e)]});}
}


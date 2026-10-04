import {allowed,requestBody,keyFor} from '../lib/http.js';

function cleanJson(text){
  const s=String(text||'').trim().replace(/^```json\s*/i,'').replace(/^```\s*/,'').replace(/```$/,'').trim();
  try{return JSON.parse(s)}catch{}
  const a=s.indexOf('{'),b=s.lastIndexOf('}');
  if(a>=0&&b>a){try{return JSON.parse(s.slice(a,b+1))}catch{}}
  return null;
}

async function google(prompt,key){
  const model=process.env.GEMINI_MODEL||'gemini-2.5-flash';
  const r=await fetch('https://generativelanguage.googleapis.com/v1beta/models/'+model+':generateContent',{
    method:'POST',
    headers:{'content-type':'application/json','x-goog-api-key':key},
    body:JSON.stringify({
      contents:[{role:'user',parts:[{text:prompt}]}],
      generationConfig:{
        temperature:0.1,
        responseMimeType:'application/json',
        maxOutputTokens:3000,
        ...(/^gemini-2\.5-flash/.test(model)?{thinkingConfig:{thinkingBudget:0}}:{})
      }
    })
  });
  if(!r.ok)throw Error('Google HTTP '+r.status);
  const d=await r.json();
  const text=d?.candidates?.[0]?.content?.parts?.filter(p=>!p.thought).map(p=>p.text||'').join('')||'';
  const data=cleanJson(text);
  if(!data)throw Error('Google retornou JSON inválido.');
  return {provider:'Gemini',model,data};
}

async function groq(prompt,key){
  const model=process.env.GROQ_TEXT_MODEL||'llama-3.3-70b-versatile';
  const r=await fetch('https://api.groq.com/openai/v1/chat/completions',{
    method:'POST',
    headers:{'content-type':'application/json','authorization':'Bearer '+key},
    body:JSON.stringify({
      model,
      messages:[{role:'user',content:prompt}],
      temperature:0.1,
      max_tokens:3000,
      response_format:{type:'json_object'}
    })
  });
  if(!r.ok)throw Error('Groq HTTP '+r.status);
  const d=await r.json();
  const data=cleanJson(d?.choices?.[0]?.message?.content||'');
  if(!data)throw Error('Groq retornou JSON inválido.');
  return {provider:'Groq',model,data};
}

export default async function handler(req,res){
  if(!allowed(req,res))return;
  try{
    const body=requestBody(req);
    if(req.headers['x-game-ai-coach']!=='central-coach-v1')return res.status(403).json({error:'Cliente não autorizado.'});
    const prompt=typeof body.prompt==='string'?body.prompt:'';
    if(prompt.length<20||prompt.length>120000)return res.status(400).json({error:'Prompt inválido.'});

    const googleKey=keyFor('google',body);
    const groqKey=keyFor('groq',body);
    const failures=[];

    if(googleKey){
      try{return res.status(200).json({ok:true,...await google(prompt,googleKey)})}
      catch(e){failures.push('Gemini: '+String(e.message||e).slice(0,160))}
    }
    if(groqKey){
      try{return res.status(200).json({ok:true,...await groq(prompt,groqKey)})}
      catch(e){failures.push('Groq: '+String(e.message||e).slice(0,160))}
    }

    return res.status(503).json({
      error:'Nenhuma IA disponível no backend OSM.',
      failures
    });
  }catch(e){
    return res.status(400).json({error:'Falha no Game Coach: '+String(e.message||e).slice(0,180)});
  }
}

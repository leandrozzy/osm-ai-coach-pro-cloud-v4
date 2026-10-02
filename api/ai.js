const MODELS={
  openrouter:{
    url:'https://openrouter.ai/api/v1/chat/completions',
    key:'OPENROUTER_API_KEY',
    model:'google/gemini-3-flash-preview'
  },
  groq:{
    url:'https://api.groq.com/openai/v1/chat/completions',
    key:'GROQ_API_KEY',
    model:'qwen/qwen3.8-27b'
  },
  gemini:{
    url:'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions',
    key:'GEMINI_API_KEY',
    model:'gemini-3.8-flash'
  }
};

const MATCH_SCHEMA=`Retorne SOMENTE um objeto JSON. Analise visualmente as screenshots do OSM 26 Android.
Não adivinhe. Se um campo não estiver visível ou não puder ser associado com segurança, use "NI".
Diferencie meu time do rival pelo layout/contexto. O usuário do meu time é leandrozzy.
Não confunda idade, camisa, preço ou posição com força.

Campos:
{
 "screenTypes": [],
 "myName":"NI",
 "rivalName":"NI",
 "rivalNickname":"NI",
 "myStrength":"NI",
 "rivalStrength":"NI",
 "mySquadValue":"NI",
 "rivalSquadValue":"NI",
 "myPlayers":"NI",
 "rivalPlayers":"NI",
 "myGK":"NI",
 "rivalGK":"NI",
 "myDEF":"NI",
 "rivalDEF":"NI",
 "myMID":"NI",
 "rivalMID":"NI",
 "myATT":"NI",
 "rivalATT":"NI",
 "stadium":"NI",
 "myBonus":"NI",
 "rivalBonus":"NI",
 "location":"NI",
 "referee":"NI",
 "secretTraining":"NI",
 "trainingCamp":"NI",
 "rivalFormation":"NI",
 "rivalPlan":"NI",
 "rivalMarking":"NI",
 "rivalOffside":"NI",
 "rivalTackling":"NI"
}

Regras visuais:
- referee: apenas Verde, Azul, Amarelo, Laranja ou Vermelho; use a COR visual do árbitro quando visível.
- location: Casa ou Fora somente quando o layout/ícone permitir concluir.
- secretTraining e trainingCamp: Sim/Não somente se houver indicador visual claro; caso contrário NI.
- rivalFormation: preservar A/B quando aparecer.
- rivalPlan: Jogo de passes, Jogar pelas alas, Contra-ataque, Remate à vista ou Bola longa quando visível.
- rivalMarking: À zona ou Individual.
- rivalOffside: Sim ou Não.
- valores de elenco podem aparecer como M/MM/B; devolva o texto exatamente como visto.
- setores GOL/DEF/MEI/ATA devem ser associados ao lado correto.
- screenTypes deve listar tipos reconhecidos, ex.: "comparação", "análise-rival", "pré-jogo", "bônus", "treino-secreto".
`;

const TEXT_SYSTEM=`Você auxilia um parser do OSM 26. Responda somente JSON válido. Nunca invente. Valor ausente deve ser "NI".`;

function cleanJson(raw=''){
  let s=String(raw).trim().replace(/^```(?:json)?\s*/i,'').replace(/```$/,'').trim();
  const a=s.indexOf('{'),b=s.lastIndexOf('}');
  if(a>=0&&b>a)s=s.slice(a,b+1);
  return JSON.parse(s);
}

function buildMessages(task,payload){
  if(task==='vision-match'){
    const images=Array.isArray(payload?.images)?payload.images.slice(0,3):[];
    const content=[
      {type:'text',text:MATCH_SCHEMA+'\nContexto adicional: '+JSON.stringify(payload?.context||{})},
      ...images.map(url=>({type:'image_url',image_url:{url}}))
    ];
    return [{role:'user',content}];
  }
  return [
    {role:'system',content:TEXT_SYSTEM},
    {role:'user',content:JSON.stringify({task,payload})}
  ];
}

async function callProvider(name,cfg,task,payload){
  const key=process.env[cfg.key];
  if(!key)throw new Error(`${name}:missing`);

  const controller=new AbortController();
  const timer=setTimeout(()=>controller.abort(),18000);
  try{
    const body={
      model:cfg.model,
      temperature:0,
      messages:buildMessages(task,payload)
    };
    if(task==='vision-match')body.response_format={type:'json_object'};

    const r=await fetch(cfg.url,{
      method:'POST',
      headers:{authorization:`Bearer ${key}`,'content-type':'application/json'},
      body:JSON.stringify(body),
      signal:controller.signal
    });

    if(!r.ok){
      const detail=await r.text().catch(()=>'');
      throw new Error(`${name}:${r.status}:${detail.slice(0,180)}`);
    }
    const d=await r.json();
    const raw=d.choices?.[0]?.message?.content||'{}';
    return {provider:name,model:cfg.model,data:cleanJson(raw)};
  } finally {
    clearTimeout(timer);
  }
}

export default async function handler(req,res){
  if(req.method!=='POST')return res.status(405).json({error:'Method not allowed'});
  const body=typeof req.body==='string'?JSON.parse(req.body):req.body||{};
  const task=body.task||'parse';
  const payload=body.payload||{};
  const errors=[];

  for(const [name,cfg] of Object.entries(MODELS)){
    try{
      const out=await callProvider(name,cfg,task,payload);
      return res.status(200).json(out);
    }catch(e){
      errors.push(e.message);
      if(!/:missing$/.test(e.message) && !/:429|:500|:502|:503|:504|AbortError|aborted/i.test(e.message)){
        continue;
      }
    }
  }
  return res.status(503).json({
    error:'Nenhum provedor multimodal disponível',
    details:errors.slice(-6)
  });
}

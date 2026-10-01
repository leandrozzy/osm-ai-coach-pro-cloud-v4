'use strict';

/*
  OSM AI Coach Pro — ANALYSIS HOTFIX 3.3.0
  Objetivo principal: Elenco não depende mais de multimodal remoto.
  1) Extrai quadros normalmente.
  2) OCR local é a fonte primária.
  3) Parser determinístico monta o elenco mesmo sem IA.
  4) Gemini 3.8/3.7 recebe apenas TEXTO OCR para refinar nomes/posições.
  5) Falha/timeout da IA nunca invalida um elenco obtido localmente.
  Também mantém perfis de timeout separados para Partida/Calendário/Resultado.
*/
(function(){
  const HOTFIX='3.3.0';

  function diag(msg){
    const el=document.getElementById('analysisDiagnostics');
    if(el)el.textContent=msg;
  }
  function banner(msg,kind=''){
    const el=document.getElementById('jobBanner');
    if(!el)return;
    el.textContent=msg;
    el.className='job-banner '+kind;
    el.classList.remove('hidden');
  }
  function safeErr(err){
    return String(err?.message||err||'erro desconhecido')
      .replace(/AIza[0-9A-Za-z_-]+/g,'[chave oculta]')
      .slice(0,220);
  }
  function withTimeout(promise,ms,label){
    let timer;
    return Promise.race([
      Promise.resolve(promise).finally(()=>clearTimeout(timer)),
      new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error(`${label}: tempo excedido (${Math.round(ms/1000)}s)`)),ms)})
    ]);
  }
  function normalizeText(v){
    return String(v||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/\s+/g,' ').trim();
  }
  function playerKey(v){
    return normalizeText(v).replace(/[^a-z0-9]/g,'');
  }
  function normalizePositionCode(raw){
    const x=String(raw||'').toUpperCase().replace(/[^A-Z]/g,'');
    if(['GR','GK','GOL','POR'].includes(x))return 'GOL';
    if(['DD','DC','DE','DF','DEF','ZAG','CB','RB','LB'].includes(x))return 'DEF';
    if(['MDC','MC','MCO','MD','ME','MF','MID','VOL','CM','CDM','CAM','LM','RM'].includes(x))return 'MEI';
    if(['PL','ED','EE','ATA','ATT','FW','FWD','ST','CA','PE','PD','LW','RW','CF'].includes(x))return 'ATA';
    return null;
  }

  const UI_WORDS=new Set([
    'jogador','jogadores','pos','posicao','posição','idade','valor','preco','preço','forca','força','overall','rating',
    'elenco','plantel','time','equipa','equipe','mercado','transferencia','transferências','treino','treinando','forma',
    'moral','condicao','condição','nacionalidade','nome','titulares','suplentes','reservas','ordenar','filtro','filtros',
    'ata','mei','def','gol','gr','dc','dd','de','mc','md','me','mdc','mco','pl','ed','ee'
  ]);

  function cleanName(raw){
    let x=String(raw||'')
      .replace(/\b\d{1,3}(?:[.,]\d+)?\s*[mkb]?\b/gi,' ')
      .replace(/[|•·]/g,' ')
      .replace(/[^A-Za-zÀ-ÿ'’.-]+/g,' ')
      .replace(/\s+/g,' ').trim();
    const parts=x.split(' ').filter(Boolean).filter(t=>!UI_WORDS.has(normalizeText(t)));
    x=parts.join(' ').replace(/^[.-]+|[.-]+$/g,'').trim();
    if(x.length<2 || !/[A-Za-zÀ-ÿ]/.test(x))return null;
    if(UI_WORDS.has(normalizeText(x)))return null;
    return x.slice(0,60);
  }

  function numericCandidates(text){
    return [...String(text||'').matchAll(/(?<![A-Za-z])([1-9]\d{0,2})(?![A-Za-z])/g)].map(m=>Number(m[1]));
  }

  function parsePlayerWindow(text){
    const posMatch=String(text||'').match(/\b(GR|GK|GOL|POR|DD|DC|DE|DF|DEF|ZAG|CB|RB|LB|MDC|MC|MCO|MD|ME|MF|MID|VOL|CM|CDM|CAM|LM|RM|PL|ED|EE|ATA|ATT|FW|FWD|ST|CA|PE|PD|LW|RW|CF)\b/i);
    if(!posMatch)return null;
    const pos=normalizePositionCode(posMatch[1]);
    if(!pos)return null;

    const before=String(text||'').slice(0,posMatch.index);
    const after=String(text||'').slice((posMatch.index||0)+posMatch[0].length);
    let name=cleanName(before);
    if(!name || name.split(' ').length>6){
      const fallback=cleanName(after);
      if(fallback && fallback.split(' ').length<=5)name=fallback;
    }
    if(!name)return null;

    const nums=numericCandidates(text);
    const ratingCandidates=nums.filter(n=>n>=45&&n<=200);
    let rating=ratingCandidates.length?Math.max(...ratingCandidates):null;
    const ageCandidates=nums.filter(n=>n>=15&&n<=45&&n!==rating);
    const age=ageCandidates.length?ageCandidates[0]:null;

    // Evita linhas de cabeçalho/classificação que por acaso contêm uma sigla de posição.
    const bad=normalizeText(name);
    if(/^(meu time|adversario|classificacao|liga|jornada|rodada|analista)/.test(bad))return null;

    return {name,position:pos,rating:Number.isFinite(rating)?rating:null,age:Number.isFinite(age)?age:null,training:false,forSale:false,source:'local_ocr'};
  }

  function parseRosterLocal(ocr){
    const found=[];
    for(const frame of (ocr?.frames||[])){
      const lines=String(frame?.text||'').split(/\n+/).map(x=>x.trim()).filter(Boolean);
      for(let i=0;i<lines.length;i++){
        if(!/\b(GR|GK|GOL|POR|DD|DC|DE|DF|DEF|ZAG|CB|RB|LB|MDC|MC|MCO|MD|ME|MF|MID|VOL|CM|CDM|CAM|LM|RM|PL|ED|EE|ATA|ATT|FW|FWD|ST|CA|PE|PD|LW|RW|CF)\b/i.test(lines[i]))continue;
        const variants=[
          lines[i],
          [lines[i-1],lines[i]].filter(Boolean).join(' '),
          [lines[i],lines[i+1]].filter(Boolean).join(' '),
          [lines[i-1],lines[i],lines[i+1]].filter(Boolean).join(' ')
        ];
        let best=null;
        for(const v of variants){
          const p=parsePlayerWindow(v);
          if(!p)continue;
          const score=(p.rating?3:0)+(p.age?1:0)+Math.min(3,p.name.split(' ').length);
          if(!best||score>best.score)best={p,score};
        }
        if(best)found.push(best.p);
      }
    }

    const map=new Map();
    for(const p of found){
      const key=playerKey(p.name);
      if(!key||key.length<2)continue;
      const prev=map.get(key);
      if(!prev){map.set(key,p);continue}
      map.set(key,{
        ...prev,
        position:prev.position||p.position,
        rating:prev.rating??p.rating,
        age:prev.age??p.age,
        training:prev.training||p.training,
        forSale:prev.forSale||p.forSale
      });
    }
    return [...map.values()];
  }

  function compactOcrForAi(ocr){
    const out=[];const seen=new Set();
    for(const f of (ocr?.frames||[])){
      const lines=String(f?.text||'').split(/\n+/).map(x=>x.replace(/\s+/g,' ').trim()).filter(Boolean);
      const useful=lines.filter((line,i)=>{
        if(/\b(GR|GK|GOL|POR|DD|DC|DE|DF|DEF|ZAG|CB|RB|LB|MDC|MC|MCO|MD|ME|MF|MID|VOL|CM|CDM|CAM|LM|RM|PL|ED|EE|ATA|ATT|FW|FWD|ST|CA|PE|PD|LW|RW|CF)\b/i.test(line))return true;
        const around=lines.slice(Math.max(0,i-1),Math.min(lines.length,i+2)).join(' ');
        return /\b(pos|posicao|posição|idade|valor|jogador)\b/i.test(around)&&/\d/.test(line);
      });
      for(const line of useful){
        const k=normalizeText(line);
        if(!seen.has(k)){seen.add(k);out.push(line)}
      }
    }
    return out.join('\n').slice(0,18000);
  }

  async function callTextRosterAi(ocr){
    const key=localStorage.getItem(API_KEY_STORAGE);
    if(!key)return null;
    const compact=compactOcrForAi(ocr);
    if(compact.length<30)return null;
    const prompt=`Você recebe OCR local de telas do ELENCO do OSM 26. Consolide somente jogadores realmente presentes.\n\nCódigos: GR=GOL; DD/DC/DE=DEF; MDC/MC/MCO/MD/ME=MEI; PL/ED/EE=ATA.\nNão invente jogador. Corrija apenas erros óbvios de OCR. Se não souber rating/idade, use null.\nRetorne JSON puro exatamente neste formato:\n{"roster":[{"name":"","position":"ATA|MEI|DEF|GOL","rating":null,"age":null,"training":false,"forSale":false}]}\n\nOCR:\n${compact}`;
    const models=['gemini-3.8-flash','gemini-3.7-flash'];
    const errors=[];
    for(const model of models){
      try{
        const body={contents:[{role:'user',parts:[{text:prompt}]}],generationConfig:{temperature:0,maxOutputTokens:6000,responseMimeType:'application/json'}};
        const res=await withTimeout(geminiFetch(model,key,body),14000,model);
        if(!res.ok){
          let txt='';try{txt=await res.text()}catch{}
          throw new Error(`${model} HTTP ${res.status}${txt?`: ${txt.slice(0,100)}`:''}`);
        }
        const data=await res.json();
        const text=(data?.candidates?.[0]?.content?.parts||[]).map(x=>x?.text||'').join('').trim();
        if(!text)throw new Error(`${model}: resposta vazia`);
        const parsed=parseJsonText(text);
        const roster=Array.isArray(parsed?.roster)?parsed.roster:[];
        if(roster.length){
          try{settings.model=model;saveSettings();hydrateSettings?.()}catch{}
          return {model,roster};
        }
        throw new Error(`${model}: nenhum jogador retornado`);
      }catch(err){errors.push(`${model}: ${safeErr(err)}`)}
    }
    window.__osmRosterRefineErrors=errors;
    return null;
  }

  function mergeRosters(localRows,aiRows,existingRows){
    const map=new Map();
    const add=(p,priority)=>{
      const name=String(p?.name||p?.playerName||'').trim();
      if(!name)return;
      const key=playerKey(name);if(!key)return;
      const pos=normalizePositionCode(p?.position)||normalizePositionCode(p?.pos)||p?.position||null;
      const rating=Number(p?.rating??p?.overall??p?.power);
      const age=Number(p?.age);
      const normalized={
        ...p,name,position:pos,
        rating:Number.isFinite(rating)?rating:null,
        age:Number.isFinite(age)?age:null,
        training:p?.training===true,
        forSale:p?.forSale===true
      };
      const prev=map.get(key);
      if(!prev){map.set(key,{...normalized,_priority:priority});return}
      // Mantém sinais visuais já conhecidos (treino/venda) e deixa IA corrigir texto quando disponível.
      map.set(key,{
        ...(priority>=prev._priority?prev:normalized),
        ...(priority>=prev._priority?normalized:prev),
        training:prev.training||normalized.training,
        forSale:prev.forSale||normalized.forSale,
        _priority:Math.max(prev._priority,priority)
      });
    };
    for(const p of (existingRows||[]))add(p,0);
    for(const p of (localRows||[]))add(p,1);
    for(const p of (aiRows||[]))add(p,2);
    return [...map.values()].map(({_priority,...p})=>p);
  }

  // Evita iniciar novas análises de Elenco com o modelo antigo que ficou memorizado após fallback.
  try{
    if(/^gemini-(3\.5|3\.6)-flash/i.test(String(settings?.model||'')) || settings?.model==='gemini-flash-latest'){
      settings.model='gemini-3.8-flash';
      saveSettings();
      try{hydrateSettings?.()}catch{}
    }
  }catch{}

  // Mantém o hotfix 3.2 para os outros modos, mas substitui completamente o modo Elenco.
  const previousAnalyze=v21Analyze;
  v21Analyze=async function(files){
    if(analysisMode!=='market')return previousAnalyze(files);

    const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot;
    state.selectedSlot=slotNo;
    const s=selectedSlot();
    const video=(files||[]).find(f=>String(f.type||'').startsWith('video/'));
    const images=(files||[]).filter(f=>String(f.type||'').startsWith('image/'));
    let frames=[];

    setProgress(5,'Capturando toda a rolagem do elenco…');
    if(video)frames=await v21ExtractVideoFrames(video,32);
    else if(images.length){for(const f of images)frames.push(await v21ImageToFrame(f));}
    else throw new Error('Selecione um vídeo ou imagens do elenco.');

    v21RenderEvidence(frames);
    setProgress(18,`OCR local em ${frames.length} quadro(s)…`);
    const ocr=await v21RunLocalOcr(frames);

    setProgress(54,'Montando elenco localmente…');
    const localRoster=parseRosterLocal(ocr);
    diag(`Hotfix ${HOTFIX} · OCR local encontrou ${localRoster.length} jogador(es). A IA agora só refina texto.`);

    // Aplica imediatamente o que já foi lido. Se a IA falhar daqui em diante, o usuário não perde o resultado.
    const existing=Array.isArray(s.roster)?s.roster:[];
    let merged=mergeRosters(localRoster,[],existing);
    if(localRoster.length){
      v21ApplyRoster({roster:merged,myTeam:{playerCount:merged.length}});
      try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
      renderMarket();
    }

    setProgress(66,'Refinando nomes e posições com IA leve…');
    banner('Elenco lido localmente · refinando texto com IA…');
    let refined=null;
    try{refined=await callTextRosterAi(ocr)}catch{}

    if(refined?.roster?.length){
      merged=mergeRosters(localRoster,refined.roster,existing);
      v21ApplyRoster({roster:merged,myTeam:{playerCount:merged.length}});
      try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
      diag(`Hotfix ${HOTFIX} · ${localRoster.length} local(is) · ${refined.roster.length} refinado(s) com ${refined.model} · ${merged.length} consolidado(s).`);
    }else if(localRoster.length){
      // Continua com resultado local; não transforma indisponibilidade da IA em erro do Elenco.
      merged=mergeRosters(localRoster,[],existing);
      v21ApplyRoster({roster:merged,myTeam:{playerCount:merged.length}});
      diag(`Hotfix ${HOTFIX} · IA de refinamento indisponível. Elenco local preservado (${merged.length}).`);
    }

    s.marketPlan=buildMarketPlan(s);
    s.lastAnalysisAt=new Date().toISOString();
    try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
    renderMarket();

    const count=(s.roster||[]).length;
    const expected=Number(s.myTeam?.playerCount);
    const localCount=localRoster.length;
    const status=count>0?'success':'warning';
    const msg=count>0
      ? `${count} jogador(es) consolidados. ${refined?'IA refinou o OCR.':'IA de refinamento não respondeu; leitura local mantida.'}`
      : 'OCR local não identificou linhas de jogadores. Grave a rolagem um pouco mais devagar e mantenha a lista visível.';

    const target=document.getElementById('analysisContent');
    if(target)target.innerHTML=`<div class="card" style="margin-top:12px">
      <span class="eyebrow">ELENCO · HOTFIX ${HOTFIX}</span>
      <h3>${count>0?'Elenco atualizado sem depender da IA':'Leitura local insuficiente'}</h3>
      <p class="small muted">${msg}</p>
      <div class="fallback-kpis">
        <div><span>OCR local</span><b>${localCount}</b></div>
        <div><span>Consolidado</span><b>${count}</b></div>
        <div><span>Refino IA</span><b>${refined?'Sim':'Não necessário'}</b></div>
      </div>
    </div>`;

    setAnalysisRun(s,'market',status,msg,{rosterCount:count,localCount,refinedByAI:!!refined,hotfix:HOTFIX});
    setProgress(100,count>0?'Elenco atualizado':'Revisar gravação do elenco');
    banner(count>0?'Elenco atualizado.':'OCR local não encontrou jogadores.','done');
    job(count>0?'Elenco atualizado sem depender da IA multimodal.':'Revise a gravação do elenco.',count>0?'done':'warn');
  };
  window.v21Analyze=v21Analyze;
  window.OSM_ANALYSIS_HOTFIX_VERSION=HOTFIX;
  try{console.info(`[OSM] Analysis Hotfix ${HOTFIX} ativo — Elenco local-first`)}catch{}
})();

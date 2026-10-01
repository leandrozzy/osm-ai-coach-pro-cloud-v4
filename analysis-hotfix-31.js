'use strict';

/*
  OSM AI Coach Pro — ANALYSIS HOTFIX 3.1.0
  Corrige falhas do pré-jogo multimodal sem alterar Coach IA 3.0.
  - reduz imagens por chamada para evitar payload/latência excessivos
  - amplia timeout de forma controlada
  - tenta modelos Flash atuais em fallback
  - mostra diagnóstico real quando todas as IAs falham
*/
(function(){
  const HOTFIX='3.1.0';

  function diag(msg){
    const el=document.getElementById('analysisDiagnostics');
    if(el)el.textContent=msg;
  }
  function setBanner(msg,kind=''){
    const b=document.getElementById('jobBanner');
    if(!b)return;
    b.textContent=msg;
    b.className='job-banner '+kind;
    b.classList.remove('hidden');
  }
  function wait(ms){return new Promise(r=>setTimeout(r,ms))}
  function withTimeout(promise,ms,label){
    let timer;
    return Promise.race([
      promise.finally(()=>clearTimeout(timer)),
      new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error(`${label}: tempo excedido (${Math.round(ms/1000)}s)`)),ms)})
    ]);
  }
  function compactParts(parts,maxImages){
    const textParts=(parts||[]).filter(p=>p && typeof p.text==='string');
    const imageParts=(parts||[]).filter(p=>p?.inlineData?.data);
    if(imageParts.length<=maxImages)return [...textParts,...imageParts];

    // As primeiras imagens recebidas são as telas obrigatórias selecionadas pelo app
    // (partida, meu elenco, rival e Analista). Mantê-las tem prioridade sobre extras.
    const chosen=imageParts.slice(0,maxImages);
    return [...textParts,...chosen];
  }
  function safeErr(err){
    const raw=String(err?.message||err||'erro desconhecido')
      .replace(/AIza[0-9A-Za-z_-]+/g,'[chave oculta]');
    return raw.length>220?raw.slice(0,220)+'…':raw;
  }
  async function callModel(model,key,body,timeoutMs){
    const res=await withTimeout(geminiFetch(model,key,body),timeoutMs,model);
    if(!res.ok){
      let txt='';
      try{txt=await res.text()}catch{}
      const er=new Error(`${model} HTTP ${res.status}${txt?`: ${txt.slice(0,150)}`:''}`);
      er.status=res.status;
      throw er;
    }
    const data=await res.json();
    const text=(data?.candidates?.[0]?.content?.parts||[]).map(p=>p?.text||'').join('').trim();
    if(!text)throw new Error(`${model}: resposta vazia`);
    return {model,text};
  }

  // Substitui apenas a comunicação multimodal. Todo OCR, seleção de telas,
  // preenchimento e geração tática existentes continuam iguais.
  geminiJson=async function(parts,temperature=.1,maxOutputTokens=5000){
    const key=localStorage.getItem(API_KEY_STORAGE);
    if(!key)throw new Error('API Gemini não configurada.');

    const preferred=settings?.model||'gemini-3.5-flash';
    const pool=[
      preferred,
      'gemini-3.8-flash',
      'gemini-3.7-flash',
      'gemini-3.6-flash',
      'gemini-3.5-flash',
      'gemini-3.5-flash-lite'
    ].filter((x,i,a)=>x&&a.indexOf(x)===i);

    // 8 imagens é suficiente para as 6 telas do pré-jogo + até 2 extras.
    // Antes eram enviados até 14 quadros na mesma chamada.
    const primaryParts=compactParts(parts,8);
    const fallbackParts=compactParts(parts,6);
    const imageCount=(parts||[]).filter(p=>p?.inlineData?.data).length;
    const bodyFor=p=>({
      contents:[{role:'user',parts:p}],
      generationConfig:{temperature,maxOutputTokens,responseMimeType:'application/json'}
    });

    const errors=[];
    setBanner(`IA: lendo ${Math.min(imageCount,8)} tela(s) essenciais com ${preferred}…`);
    diag(`Hotfix ${HOTFIX}: ${imageCount} quadro(s) recebidos; usando até 8 na tentativa principal.`);

    try{
      const out=await callModel(preferred,key,bodyFor(primaryParts),15000);
      setBanner(`Análise concluída com ${out.model}.`,'done');
      diag(`Análise concluída com ${out.model} · ${Math.min(imageCount,8)} tela(s) essenciais.`);
      return parseJsonText(out.text);
    }catch(err){
      errors.push(`${preferred}: ${safeErr(err)}`);
    }

    const alternatives=pool.filter(m=>m!==preferred).slice(0,3);
    setBanner('IA principal não respondeu · tentando alternativas em paralelo…');
    diag(`Principal falhou. Tentando ${alternatives.join(' / ')} com 6 telas essenciais.`);

    const attempts=alternatives.map(model=>
      callModel(model,key,bodyFor(fallbackParts),13000)
        .then(r=>({ok:true,...r}))
        .catch(error=>({ok:false,model,error}))
    );
    const settled=await Promise.all(attempts);
    const winner=settled.find(x=>x.ok);
    for(const x of settled.filter(x=>!x.ok))errors.push(`${x.model}: ${safeErr(x.error)}`);

    if(winner){
      settings.model=winner.model;
      try{saveSettings();hydrateSettings?.()}catch{}
      setBanner(`Análise concluída com ${winner.model}.`,'done');
      diag(`Fallback concluído com ${winner.model} · 6 telas essenciais.`);
      return parseJsonText(winner.text);
    }

    // Uma repetição curta ajuda quando houve 429/503 transitório sem transformar
    // o fluxo em uma espera infinita.
    const retryModel=pool.includes('gemini-3.5-flash-lite')?'gemini-3.5-flash-lite':preferred;
    await wait(700);
    try{
      setBanner(`Última tentativa rápida com ${retryModel}…`);
      const out=await callModel(retryModel,key,bodyFor(fallbackParts),12000);
      settings.model=out.model;
      try{saveSettings();hydrateSettings?.()}catch{}
      setBanner(`Análise concluída com ${out.model}.`,'done');
      diag(`Recuperado com ${out.model} após falha temporária.`);
      return parseJsonText(out.text);
    }catch(err){
      errors.push(`${retryModel}: ${safeErr(err)}`);
    }

    window.__osmLastAiErrors=errors.slice(-5);
    const short=errors.slice(-3).join(' | ');
    diag(`Falha real da IA: ${short}`);
    setBanner('IAs indisponíveis · dados locais preservados','warn');
    throw new Error(`Todas as tentativas de IA falharam. ${short}`);
  };

  window.OSM_ANALYSIS_HOTFIX_VERSION=HOTFIX;
  try{console.info(`[OSM] Analysis Hotfix ${HOTFIX} ativo`)}catch{}
})();

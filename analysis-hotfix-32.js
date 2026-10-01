'use strict';

/*
  OSM AI Coach Pro — ANALYSIS HOTFIX 3.2.0
  Corrige o erro do Hotfix 3.1 no modo Elenco.
  - Pré-jogo continua usando poucas telas e timeout curto/controlado
  - Elenco usa amostragem distribuída pela rolagem e timeout maior
  - Calendário mantém perfil intermediário
  - fallback prioriza alias Flash estável antes de variantes versionadas
*/
(function(){
  const HOTFIX='3.2.0';

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
  function safeErr(err){
    const raw=String(err?.message||err||'erro desconhecido').replace(/AIza[0-9A-Za-z_-]+/g,'[chave oculta]');
    return raw.length>240?raw.slice(0,240)+'…':raw;
  }
  function detectMode(parts){
    const text=(parts||[]).filter(p=>typeof p?.text==='string').map(p=>p.text).join('\n');
    if(/Analise SOMENTE MEU ELENCO|"roster"|jogadores visíveis ao longo/i.test(text))return 'market';
    if(/Analise SOMENTE o calendário|"matches"/i.test(text))return 'calendar';
    if(/Analise SOMENTE o resultado final|"stats"/i.test(text))return 'result';
    return 'tactic';
  }
  function sampleImages(parts,maxImages,distributed=false){
    const textParts=(parts||[]).filter(p=>p && typeof p.text==='string');
    const imgs=(parts||[]).filter(p=>p?.inlineData?.data);
    if(imgs.length<=maxImages)return [...textParts,...imgs];
    if(!distributed)return [...textParts,...imgs.slice(0,maxImages)];
    const chosen=[];
    for(let i=0;i<maxImages;i++){
      const idx=Math.round(i*(imgs.length-1)/Math.max(1,maxImages-1));
      if(imgs[idx]&&!chosen.includes(imgs[idx]))chosen.push(imgs[idx]);
    }
    return [...textParts,...chosen];
  }
  async function callModel(model,key,body,timeoutMs){
    const res=await withTimeout(geminiFetch(model,key,body),timeoutMs,model);
    if(!res.ok){
      let txt=''; try{txt=await res.text()}catch{}
      const er=new Error(`${model} HTTP ${res.status}${txt?`: ${txt.slice(0,160)}`:''}`);
      er.status=res.status; throw er;
    }
    const data=await res.json();
    const text=(data?.candidates?.[0]?.content?.parts||[]).map(p=>p?.text||'').join('').trim();
    if(!text)throw new Error(`${model}: resposta vazia`);
    return {model,text};
  }

  geminiJson=async function(parts,temperature=.1,maxOutputTokens=5000){
    const key=localStorage.getItem(API_KEY_STORAGE);
    if(!key)throw new Error('API Gemini não configurada.');

    const mode=detectMode(parts);
    const preferred=settings?.model||'gemini-flash-latest';
    const pool=[preferred,'gemini-flash-latest','gemini-3.5-flash','gemini-3.5-flash-lite','gemini-3.8-flash','gemini-3.7-flash','gemini-3.6-flash']
      .filter((x,i,a)=>x&&a.indexOf(x)===i);

    const allImages=(parts||[]).filter(p=>p?.inlineData?.data).length;
    const profile={
      tactic:{primaryImages:8,fallbackImages:6,primaryTimeout:18000,fallbackTimeout:16000,distributed:false},
      market:{primaryImages:14,fallbackImages:10,primaryTimeout:45000,fallbackTimeout:35000,distributed:true},
      calendar:{primaryImages:10,fallbackImages:8,primaryTimeout:28000,fallbackTimeout:24000,distributed:true},
      result:{primaryImages:8,fallbackImages:6,primaryTimeout:22000,fallbackTimeout:18000,distributed:true}
    }[mode];

    const primaryParts=sampleImages(parts,profile.primaryImages,profile.distributed);
    const fallbackParts=sampleImages(parts,profile.fallbackImages,profile.distributed);
    const bodyFor=p=>({contents:[{role:'user',parts:p}],generationConfig:{temperature,maxOutputTokens,responseMimeType:'application/json'}});
    const errors=[];

    setBanner(`IA ${mode==='market'?'Elenco':mode==='calendar'?'Calendário':mode==='result'?'Resultado':'Partida'}: analisando ${Math.min(allImages,profile.primaryImages)} tela(s)…`);
    diag(`Hotfix ${HOTFIX} · modo ${mode} · ${allImages} quadro(s) recebidos · usando ${Math.min(allImages,profile.primaryImages)}.`);

    try{
      const out=await callModel(preferred,key,bodyFor(primaryParts),profile.primaryTimeout);
      setBanner(`Análise concluída com ${out.model}.`,'done');
      diag(`Concluído com ${out.model} · modo ${mode}.`);
      return parseJsonText(out.text);
    }catch(err){errors.push(`${preferred}: ${safeErr(err)}`)}

    const alternatives=pool.filter(m=>m!==preferred).slice(0,2);
    setBanner(`Modelo principal não respondeu · tentando ${alternatives.length} alternativa(s)…`);
    diag(`Principal falhou no modo ${mode}. Tentando ${alternatives.join(' / ')}.`);

    const attempts=alternatives.map(model=>callModel(model,key,bodyFor(fallbackParts),profile.fallbackTimeout)
      .then(r=>({ok:true,...r})).catch(error=>({ok:false,model,error})));
    const settled=await Promise.all(attempts);
    const winner=settled.find(x=>x.ok);
    for(const x of settled.filter(x=>!x.ok))errors.push(`${x.model}: ${safeErr(x.error)}`);

    if(winner){
      settings.model=winner.model;
      try{saveSettings();hydrateSettings?.()}catch{}
      setBanner(`Análise concluída com ${winner.model}.`,'done');
      diag(`Fallback concluído com ${winner.model} · modo ${mode}.`);
      return parseJsonText(winner.text);
    }

    // No Elenco não faz uma terceira tentativa curta: ela só repetia o mesmo timeout.
    // Em modos leves, mantém uma última tentativa para falhas transitórias.
    if(mode!=='market'){
      const retryModel='gemini-flash-latest';
      await wait(500);
      try{
        const out=await callModel(retryModel,key,bodyFor(fallbackParts),profile.fallbackTimeout);
        settings.model=out.model; try{saveSettings();hydrateSettings?.()}catch{}
        setBanner(`Análise concluída com ${out.model}.`,'done');
        return parseJsonText(out.text);
      }catch(err){errors.push(`${retryModel}: ${safeErr(err)}`)}
    }

    window.__osmLastAiErrors=errors.slice(-6);
    const short=errors.slice(-3).join(' | ');
    diag(`Falha real da IA (${mode}): ${short}`);
    setBanner('IAs indisponíveis · dados locais preservados','warn');
    throw new Error(`Todas as tentativas de IA falharam. ${short}`);
  };

  window.OSM_ANALYSIS_HOTFIX_VERSION=HOTFIX;
  try{console.info(`[OSM] Analysis Hotfix ${HOTFIX} ativo`)}catch{}
})();

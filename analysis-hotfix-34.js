'use strict';
/* OSM AI Coach Pro — Analysis Hotfix 3.4.0: strict roster consensus */
(function(){
  const V='3.4.0';
  const norm=v=>String(v||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/\s+/g,' ').trim();
  const num=v=>{if(v===null||v===undefined||v===''||v==='NI')return null;const n=Number(v);return Number.isFinite(n)?n:null};
  function posCode(raw){const x=String(raw||'').toUpperCase().replace(/[^A-Z]/g,'');if(['GR','GK','GOL','POR'].includes(x))return'GOL';if(['DD','DC','DE','DF','DEF','ZAG','CB','RB','LB'].includes(x))return'DEF';if(['MDC','MC','MCO','MD','ME','MF','MID','VOL','CM','CDM','CAM','LM','RM'].includes(x))return'MEI';if(['PL','ED','EE','ATA','ATT','FW','FWD','ST','CA','PE','PD','LW','RW','CF'].includes(x))return'ATA';return null;}
  const POS=/\b(GR|GK|GOL|POR|DD|DC|DE|DF|DEF|ZAG|CB|RB|LB|MDC|MC|MCO|MD|ME|MF|MID|VOL|CM|CDM|CAM|LM|RM|PL|ED|EE|ATA|ATT|FW|FWD|ST|CA|PE|PD|LW|RW|CF)\b/i;
  const BAD=/\b(jogador|jogadores|elenco|plantel|posicao|posição|idade|valor|mercado|transferencia|treino|titulares|suplentes|reservas|reinos?|rondoghia|remo|would|onde|bonde|dine|toncogia|qeins|temer|oukou)\b/i;
  function cleanName(x){
    x=String(x||'').replace(/[|•·]/g,' ').replace(/\b\d+(?:[.,]\d+)?\s*[mkb]?\b/gi,' ').replace(/[^A-Za-zÀ-ÿ'’.-]+/g,' ').replace(/\s+/g,' ').trim();
    if(!x||x.length<3||x.length>42||BAD.test(norm(x)))return null;
    const t=x.split(' ').filter(Boolean); if(t.length>5||t.filter(z=>z.replace(/[^A-Za-zÀ-ÿ]/g,'').length<=1).length>1)return null;
    if(!t.some(z=>z.replace(/[^A-Za-zÀ-ÿ]/g,'').length>=3))return null;
    return x;
  }
  function parseCandidate(text,frameIndex){
    const m=String(text||'').match(POS); if(!m)return null; const position=posCode(m[1]); if(!position)return null;
    const before=String(text).slice(0,m.index),after=String(text).slice((m.index||0)+m[0].length);
    let name=cleanName(before); if(!name)name=cleanName(after); if(!name)return null;
    const nums=[...String(text).matchAll(/(?<![A-Za-z])([1-9]\d{0,2})(?![A-Za-z])/g)].map(x=>Number(x[1]));
    const rating=nums.filter(n=>n>=40&&n<=200).sort((a,b)=>b-a)[0]??null;
    const age=nums.find(n=>n>=15&&n<=45&&n!==rating)??null;
    if(rating===null)return null; // sem força lida, não entra no elenco
    if(age!==null&&(age<15||age>45))return null;
    const money=String(text).match(/([0-9]+(?:[.,][0-9]+)?)\s*([MK])\b/i);
    return {name,position,rating,age,value:money?`${money[1]}${money[2].toUpperCase()}`:null,training:false,forSale:false,source:'local_ocr_strict',frameIndex};
  }
  function key(p){return norm(p.name).replace(/[^a-z0-9]/g,'')}
  function strictRoster(ocr){
    const all=[];
    for(const f of (ocr?.frames||[])){
      const lines=String(f.text||'').split(/\n+/).map(x=>x.replace(/\s+/g,' ').trim()).filter(Boolean);
      for(let i=0;i<lines.length;i++){
        if(!POS.test(lines[i]))continue;
        const variants=[lines[i],[lines[i-1],lines[i]].filter(Boolean).join(' '),[lines[i],lines[i+1]].filter(Boolean).join(' ')];
        let best=null;
        for(const v of variants){const p=parseCandidate(v,f.frame);if(!p)continue;const score=(p.age?2:0)+(p.value?2:0)+Math.min(3,p.name.split(' ').length);if(!best||score>best.score)best={p,score};}
        if(best)all.push(best.p);
      }
    }
    const groups=new Map();
    for(const p of all){const k=key(p);if(!k)continue;const g=groups.get(k)||[];g.push(p);groups.set(k,g);}
    const out=[];
    for(const g of groups.values()){
      const frames=new Set(g.map(x=>x.frameIndex));
      const rich=g.some(x=>x.age!==null&&x.value!==null);
      if(frames.size<2&&!rich)continue; // consenso entre frames ou linha muito completa
      const ratings=g.map(x=>x.rating).filter(Number.isFinite).sort((a,b)=>a-b);const mid=ratings[Math.floor(ratings.length/2)];
      const base=g.find(x=>x.age!==null&&x.value!==null)||g[0];
      out.push({...base,rating:mid,sourceConfidence:frames.size>=2?'multi_frame':'single_rich',support:frames.size});
    }
    return out;
  }
  async function refineExistingOnly(ocr,local){
    if(!local.length)return local;
    const api=localStorage.getItem(API_KEY_STORAGE); if(!api)return local;
    const payload=local.map((p,i)=>({id:i,name:p.name,position:p.position,rating:p.rating,age:p.age,value:p.value}));
    const prompt=`Revise apenas estes jogadores já detectados por OCR no OSM 26. NÃO adicione e NÃO remova IDs. Pode corrigir apenas o nome quando for erro óbvio. Não altere posição, força, idade ou valor sem evidência textual clara. Retorne JSON puro: {"rows":[{"id":0,"name":"..."}]}.\n\nCANDIDATOS:\n${JSON.stringify(payload)}`;
    for(const model of ['gemini-3.8-flash','gemini-3.7-flash']){
      try{
        const body={contents:[{role:'user',parts:[{text:prompt}]}],generationConfig:{temperature:0,maxOutputTokens:2500,responseMimeType:'application/json'}};
        const res=await Promise.race([geminiFetch(model,api,body),new Promise((_,rej)=>setTimeout(()=>rej(new Error('timeout')),10000))]);
        if(!res.ok)continue;const data=await res.json();const txt=(data?.candidates?.[0]?.content?.parts||[]).map(x=>x.text||'').join('');const j=parseJsonText(txt);const rows=Array.isArray(j?.rows)?j.rows:[];
        const byId=new Map(rows.filter(x=>Number.isInteger(x.id)).map(x=>[x.id,String(x.name||'').trim()]));
        return local.map((p,i)=>{const n=byId.get(i);return n&&n.length>=3?{...p,name:n,refinedBy:model}:p});
      }catch{}
    }
    return local;
  }
  const previous=v21Analyze;
  v21Analyze=async function(files){
    if(analysisMode!=='market')return previous(files);
    const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot;state.selectedSlot=slotNo;const s=selectedSlot();
    const video=(files||[]).find(f=>String(f.type||'').startsWith('video/')),images=(files||[]).filter(f=>String(f.type||'').startsWith('image/'));let frames=[];
    setProgress(5,'Capturando a rolagem do elenco…');if(video)frames=await v21ExtractVideoFrames(video,36);else if(images.length){for(const f of images)frames.push(await v21ImageToFrame(f));}else throw new Error('Selecione vídeo ou imagens do elenco.');
    v21RenderEvidence(frames);setProgress(18,`OCR local em ${frames.length} quadro(s)…`);const ocr=await v21RunLocalOcr(frames);
    setProgress(58,'Validando jogadores em mais de um quadro…');let roster=strictRoster(ocr);
    setProgress(72,'Corrigindo somente nomes já detectados…');roster=await refineExistingOnly(ocr,roster);
    // Substitui o elenco anterior: não mistura lixo de análises passadas.
    s.roster=roster.map(p=>({...p,verifiedRoster:true}));
    s.myTeam=s.myTeam||{};s.myTeam.validatedPlayerCount=s.roster.length;s.marketPlan=null;s.lastAnalysisAt=new Date().toISOString();
    try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
    try{if(window.OSM_DIRECTOR_HOTFIX_34)window.OSM_DIRECTOR_HOTFIX_34.sanitizeSlot(s)}catch{}
    renderMarket();
    const expected=num(s.myTeam?.playerCount),count=s.roster.length,warning=expected!==null&&Math.abs(expected-count)>2;
    const msg=`${count} jogador(es) validados. ${warning?`O OSM indica ${expected}; revise a gravação porque a diferença é grande.`:'Linhas sem força ou sem consenso entre quadros foram descartadas.'}`;
    const target=document.getElementById('analysisContent');if(target)target.innerHTML=`<div class="card" style="margin-top:12px"><span class="eyebrow">ELENCO · VALIDAÇÃO ESTRITA ${V}</span><h3>${warning?'Elenco parcial — sem invenções':'Elenco validado'}</h3><p class="small muted">${msg}</p><div class="fallback-kpis"><div><span>Validados</span><b>${count}</b></div><div><span>Esperados</span><b>${expected??'NI'}</b></div><div><span>Regra</span><b>2 quadros</b></div></div></div>`;
    setAnalysisRun(s,'market',warning?'warning':'success',msg,{rosterCount:count,expected,hotfix:V,strict:true});setProgress(100,'Elenco validado');job('Elenco validado sem criar jogadores.','done');
  };
  window.v21Analyze=v21Analyze;window.OSM_ANALYSIS_HOTFIX_VERSION=V;
})();

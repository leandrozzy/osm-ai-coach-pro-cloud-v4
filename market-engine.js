'use strict';
/* OSM AI Coach Pro — Market Engine 5.0
   Roster + finances reader with continuous section sweep. Replaces market hotfixes 3.4/3.5/3.6.
   Key rules:
   - one visual source of truth for the roster
   - no Tesseract name gate
   - one cropped table frame per AI request
   - consensus by position/rating/value/age across overlapping frames
   - cash and squad value read from dedicated screen regions
   - never overwrite roster unless completeness/consistency checks pass
   - Director only decides from validated roster + real finances
*/
(function(){
  const VERSION='5.4.0';
  const TARGET={ATA:4,MEI:6,DEF:6,GOL:2};
  const VALID_POS=new Set(Object.keys(TARGET));
  const oldRenderMarket=typeof renderMarket==='function'?renderMarket:null;
  const oldRenderDashboard=typeof renderDashboard==='function'?renderDashboard:null;
  const oldAnalyze=typeof v21Analyze==='function'?v21Analyze:null;

  const esc=v=>String(v??'NI').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[m]));
  const norm=v=>String(v||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/\s+/g,' ').trim();
  const num=v=>{ if(v===null||v===undefined||v===''||v==='NI')return null; const x=Number(v); return Number.isFinite(x)?x:null; };
  function money(v){
    if(v===null||v===undefined||v==='')return null;
    if(typeof v==='number'&&Number.isFinite(v))return v;
    const s=String(v).trim().toLowerCase().replace(/\s/g,'').replace(',','.');
    const m=s.match(/([0-9]+(?:\.[0-9]+)?)([kmb])?/i); if(!m)return null;
    let x=Number(m[1]); if(!Number.isFinite(x))return null;
    const u=(m[2]||'').toLowerCase(); if(u==='k')x*=1e3; else if(u==='m')x*=1e6; else if(u==='b')x*=1e9;
    return x;
  }
  function fmtMoney(v){
    const x=money(v); if(x===null)return 'NI';
    if(x>=1e9)return (x/1e9).toFixed(x>=1e10?0:1).replace('.',',')+'B';
    if(x>=1e6)return (x/1e6).toFixed(x>=1e8?0:1).replace('.',',')+'M';
    if(x>=1e3)return Math.round(x/1e3)+'K';
    return String(Math.round(x));
  }
  function positionFromCode(raw){
    const x=String(raw||'').toUpperCase().replace(/[^A-Z]/g,'');
    if(['GR','GK','GOL','POR'].includes(x))return 'GOL';
    if(['DD','DC','DE','DF','DEF','ZAG','CB','RB','LB'].includes(x))return 'DEF';
    if(['MDC','MC','MCO','MD','ME','MF','MID','VOL','CM','CDM','CAM','LM','RM'].includes(x))return 'MEI';
    if(['PL','ED','EE','ATA','ATT','FW','FWD','ST','CA','PE','PD','LW','RW','CF'].includes(x))return 'ATA';
    return null;
  }
  function cleanName(v){
    let s=String(v||'').replace(/\s+/g,' ').trim();
    s=s.replace(/^[^A-Za-zÀ-ÿ]+|[^A-Za-zÀ-ÿ'’.\- ]+$/g,'').trim();
    if(s.length<2||s.length>48)return null;
    if(/\b(jogador|idade|valor|estado|nac|pos|ata|def|med|avancados|defesas|medios|guarda)\b/i.test(s))return null;
    if(!/[A-Za-zÀ-ÿ]{2}/.test(s))return null;
    return s;
  }
  function compactName(v){return norm(v).replace(/[^a-z0-9]/g,'');}
  function sigFor(row){
    const mv=money(row.value); const bucket=mv===null?'?':Math.round(mv/100000);
    return `${row.position}|${row.rating}|${row.age??'?'}|${bucket}`;
  }
  function betterName(a,b){
    if(!a)return b;if(!b)return a;
    const score=x=>String(x).split(' ').filter(Boolean).length*10+String(x).length;
    return score(b)>score(a)?b:a;
  }

  async function cropFrame(frame,box,maxW=1280,quality=.86){
    const img=await v21LoadImage(frame.dataUrl);
    const sx=Math.max(0,Math.round(img.naturalWidth*box.x)),sy=Math.max(0,Math.round(img.naturalHeight*box.y));
    const sw=Math.max(1,Math.round(img.naturalWidth*box.w)),sh=Math.max(1,Math.round(img.naturalHeight*box.h));
    const scale=Math.min(1,maxW/sw),cw=Math.max(1,Math.round(sw*scale)),ch=Math.max(1,Math.round(sh*scale));
    const c=document.createElement('canvas');c.width=cw;c.height=ch;
    c.getContext('2d').drawImage(img,sx,sy,sw,sh,0,0,cw,ch);
    const dataUrl=c.toDataURL('image/jpeg',quality);
    return {dataUrl,base64:dataUrl.split(',')[1],mimeType:'image/jpeg',time:frame.time||0};
  }

  async function cropForFreeOcr(frame,box){
    let p=await cropFrame(frame,box,1850,.90);
    let bytes=Math.round(String(p.base64||'').length*.75);
    if(bytes>850000){
      p=await cropFrame(frame,box,1500,.82);
      bytes=Math.round(String(p.base64||'').length*.75);
    }
    p.approxBytes=bytes;
    return p;
  }

  function overlayLinesToText(overlay){
    const lines=overlay?.Lines||overlay?.lines||[];
    return lines.map(line=>{
      const words=(line.Words||line.words||[]).map(w=>({
        t:String(w.WordText||w.wordText||w.Text||w.text||'').trim(),
        l:Number(w.Left??w.left??0)
      })).filter(w=>w.t).sort((a,b)=>a.l-b.l);
      return words.map(w=>w.t).join(' ').trim();
    }).filter(Boolean).join('\n');
  }

  const OPENROUTER_KEY='osm_ai_coach_openrouter_key';
  const GROQ_KEY='osm_ai_coach_groq_key';
  const OCRSPACE_KEY='osm_ai_coach_ocrspace_key';
  const GEMINI_COOLDOWN='osm_ai_coach_gemini_cooldown_until';

  function backupKeys(){
    return {
      openrouter:String(localStorage.getItem(OPENROUTER_KEY)||'').trim(),
      groq:String(localStorage.getItem(GROQ_KEY)||'').trim()
    };
  }
  function withAbort(ms){
    const controller=new AbortController();
    const timer=setTimeout(()=>controller.abort(),ms);
    return {controller,clear:()=>clearTimeout(timer)};
  }
  function imageParts(parts){
    return (parts||[]).filter(p=>p?.inlineData?.data).map(p=>({
      type:'image_url',
      image_url:{url:`data:${p.inlineData.mimeType||'image/jpeg'};base64,${p.inlineData.data}`}
    }));
  }
  function textFromParts(parts){return (parts||[]).filter(p=>p?.text).map(p=>p.text).join('\n\n');}
  function parseProviderText(txt,label){
    if(!txt)throw new Error(`${label}: resposta vazia`);
    try{return parseJsonText(txt)}catch(err){throw new Error(`${label}: JSON inválido`)}
  }
  function markGeminiQuota(raw){
    const t=String(raw||'').toLowerCase();
    if(t.includes('429')&&(t.includes('quota')||t.includes('exceeded'))){
      localStorage.setItem(GEMINI_COOLDOWN,String(Date.now()+6*60*60*1000));
      return true;
    }
    return false;
  }
  function geminiCoolingDown(){return Number(localStorage.getItem(GEMINI_COOLDOWN)||0)>Date.now();}

  async function geminiOnce(parts,timeoutMs=10000){
    const key=localStorage.getItem(API_KEY_STORAGE);
    if(!key)throw new Error('Gemini: chave não configurada');
    if(geminiCoolingDown())throw new Error('Gemini: cota em cooldown');
    const model=(settings?.model&&/gemini/i.test(settings.model))?settings.model:'gemini-flash-latest';
    const guard=withAbort(timeoutMs);
    try{
      const body={contents:[{role:'user',parts}],generationConfig:{temperature:0,maxOutputTokens:900,responseMimeType:'application/json'}};
      const res=await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,{
        method:'POST',headers:{'Content-Type':'application/json','x-goog-api-key':key},body:JSON.stringify(body),signal:guard.controller.signal
      });
      const raw=await res.text();
      if(!res.ok){markGeminiQuota(`HTTP ${res.status} ${raw}`);throw new Error(`Gemini HTTP ${res.status}: ${raw.slice(0,180)}`)}
      const data=JSON.parse(raw);
      const txt=(data?.candidates?.[0]?.content?.parts||[]).map(p=>p.text||'').join('').trim();
      return {provider:'Gemini',model,data:parseProviderText(txt,'Gemini')};
    }catch(err){
      if(err?.name==='AbortError')throw new Error(`Gemini: tempo excedido (${Math.round(timeoutMs/1000)}s)`);
      throw err;
    }finally{guard.clear()}
  }

  async function openRouterOnce(parts,timeoutMs=20000){
    const key=backupKeys().openrouter;if(!key)throw new Error('OpenRouter: chave não configurada');
    const guard=withAbort(timeoutMs);
    try{
      const content=[{type:'text',text:textFromParts(parts)},...imageParts(parts)];
      const res=await fetch('https://openrouter.ai/api/v1/chat/completions',{
        method:'POST',signal:guard.controller.signal,
        headers:{'Content-Type':'application/json','Authorization':`Bearer ${key}`,'HTTP-Referer':location.origin,'X-Title':'OSM AI Coach Pro'},
        body:JSON.stringify({model:'google/gemma-4-26b-a4b-it:free',messages:[{role:'user',content}],temperature:0,max_tokens:900,response_format:{type:'json_object'}})
      });
      const raw=await res.text();
      if(!res.ok)throw new Error(`OpenRouter HTTP ${res.status}: ${raw.slice(0,180)}`);
      const data=JSON.parse(raw);const txt=String(data?.choices?.[0]?.message?.content||'').trim();
      return {provider:'OpenRouter',model:data?.model||'google/gemma-4-26b-a4b-it:free',data:parseProviderText(txt,'OpenRouter')};
    }catch(err){if(err?.name==='AbortError')throw new Error(`OpenRouter: tempo excedido (${Math.round(timeoutMs/1000)}s)`);throw err}
    finally{guard.clear()}
  }

  async function groqOnce(parts,timeoutMs=16000){
    const key=backupKeys().groq;if(!key)throw new Error('Groq: chave não configurada');
    const guard=withAbort(timeoutMs);
    try{
      const imgs=imageParts(parts).slice(0,3);
      const content=[{type:'text',text:textFromParts(parts)},...imgs];
      const res=await fetch('https://api.groq.com/openai/v1/chat/completions',{
        method:'POST',signal:guard.controller.signal,
        headers:{'Content-Type':'application/json','Authorization':`Bearer ${key}`},
        body:JSON.stringify({model:'qwen/qwen3.8-27b',messages:[{role:'user',content}],temperature:0,max_completion_tokens:850,response_format:{type:'json_object'},reasoning_effort:'none'})
      });
      const raw=await res.text();
      if(!res.ok)throw new Error(`Groq HTTP ${res.status}: ${raw.slice(0,180)}`);
      const data=JSON.parse(raw);const txt=String(data?.choices?.[0]?.message?.content||'').trim();
      return {provider:'Groq',model:data?.model||'qwen/qwen3.8-27b',data:parseProviderText(txt,'Groq')};
    }catch(err){if(err?.name==='AbortError')throw new Error(`Groq: tempo excedido (${Math.round(timeoutMs/1000)}s)`);throw err}
    finally{guard.clear()}
  }

  async function modelJson(parts,{timeoutMs=42000,maxOutputTokens=12000,mode='visual'}={}){
    const started=Date.now(), errors=[];
    const diag=document.getElementById('analysisDiagnostics');
    if(diag)diag.textContent='Elenco 4.6 · roteamento multi-IA';

    if(!geminiCoolingDown() && localStorage.getItem(API_KEY_STORAGE)){
      try{
        job('Elenco 4.6 · tentando Gemini…');
        const g=await geminiOnce(parts,Math.min(10000,timeoutMs));
        if(diag)diag.textContent=`Elenco 4.6 concluído com ${g.provider} · ${g.model}`;
        return g;
      }catch(err){errors.push(String(err?.message||err));}
    }

    const keys=backupKeys();
    const fallbacks=[];
    if(keys.openrouter)fallbacks.push(()=>openRouterOnce(parts,Math.min(19000,timeoutMs)));
    if(keys.groq)fallbacks.push(()=>groqOnce(parts,Math.min(15000,timeoutMs)));
    if(!fallbacks.length){
      throw new Error(`${errors.join(' | ')}${errors.length?' | ':''}Configure OpenRouter ou Groq gratuito em Config > IA de backup.`);
    }

    job('Gemini indisponível · usando IA gratuita de backup…');
    const attempts=fallbacks.map(fn=>fn().then(x=>({ok:true,x})).catch(error=>({ok:false,error})));
    const settled=await Promise.all(attempts);
    const winner=settled.find(x=>x.ok)?.x;
    if(winner){
      if(diag)diag.textContent=`Elenco 4.6 concluído com ${winner.provider} · ${winner.model} · ${Math.round((Date.now()-started)/1000)}s`;
      return winner;
    }
    for(const x of settled)if(!x.ok)errors.push(String(x.error?.message||x.error));
    throw new Error(errors.join(' | ')||'Todas as IAs falharam.');
  }

  function chooseFrames(frames,max=9){
    const sorted=[...(frames||[])].sort((a,b)=>(a.time||0)-(b.time||0));
    if(sorted.length<=max)return sorted;
    const out=[];
    for(let i=0;i<max;i++){
      const idx=Math.round(i*(sorted.length-1)/Math.max(1,max-1));
      if(sorted[idx]&&!out.includes(sorted[idx]))out.push(sorted[idx]);
    }
    return out;
  }

  async function frameFingerprint(frame){
    const img=await v21LoadImage(frame.dataUrl);
    const sx=0,sy=Math.round(img.naturalHeight*.33),sw=img.naturalWidth,sh=Math.round(img.naturalHeight*.64);
    const c=document.createElement('canvas');c.width=48;c.height=32;
    const x=c.getContext('2d',{willReadFrequently:true});
    x.drawImage(img,sx,sy,sw,sh,0,0,48,32);
    const d=x.getImageData(0,0,48,32).data;
    const v=new Uint8Array(48*32);let sum=0,idx=0;
    for(let i=0;i<d.length;i+=4){const g=Math.round(.299*d[i]+.587*d[i+1]+.114*d[i+2]);v[idx++]=g;sum+=g;}
    return {v,mean:sum/v.length};
  }
  function fpDiff(a,b){
    if(!a||!b)return 999;
    let s=0;for(let i=0;i<a.v.length;i++)s+=Math.abs(a.v[i]-b.v[i]);
    return s/a.v.length;
  }
  async function smartChooseFrames(frames,max=6){
    const sorted=[...(frames||[])].sort((a,b)=>(a.time||0)-(b.time||0));
    const enriched=[];
    for(let i=0;i<sorted.length;i++){
      try{enriched.push({frame:sorted[i],fp:await frameFingerprint(sorted[i]),i});}catch{}
    }
    const usable=enriched.filter(x=>x.fp.mean>55); // descarta quadro preto/carregando
    if(usable.length<=max)return usable.map(x=>x.frame);
    for(let j=0;j<usable.length;j++){
      const prev=j?usable[j-1].fp:null,next=j<usable.length-1?usable[j+1].fp:null;
      const ds=[];if(prev)ds.push(fpDiff(usable[j].fp,prev));if(next)ds.push(fpDiff(usable[j].fp,next));
      usable[j].motion=ds.length?ds.reduce((a,b)=>a+b,0)/ds.length:0;
    }
    // favorece pausas/quadros nítidos; depois exige diversidade visual.
    const candidates=usable.filter(x=>x.motion<18);
    const pool=candidates.length>=max?candidates:usable;
    const selected=[];
    const add=x=>{if(x&&!selected.includes(x))selected.push(x);};
    add(pool[0]);
    add(pool[pool.length-1]);
    while(selected.length<max){
      let best=null,bestScore=-1;
      for(const x of pool){
        if(selected.includes(x))continue;
        const diversity=Math.min(...selected.map(s=>fpDiff(x.fp,s.fp)));
        const stableBonus=Math.max(0,18-Math.min(18,x.motion||0))*.35;
        const timeBonus=Math.min(...selected.map(s=>Math.abs((x.frame.time||0)-(s.frame.time||0))))*.25;
        const score=diversity+stableBonus+timeBonus;
        if(score>bestScore){bestScore=score;best=x;}
      }
      if(!best)break;add(best);
    }
    return selected.sort((a,b)=>(a.frame.time||0)-(b.frame.time||0)).map(x=>x.frame);
  }


  async function adaptiveGapFrames(allFrames,chosen,max=6){
    const sorted=[...(allFrames||[])].sort((a,b)=>(a.time||0)-(b.time||0));
    const chosenSet=new Set(chosen||[]);
    const chosenTimes=(chosen||[]).map(f=>Number(f.time||0)).sort((a,b)=>a-b);
    const candidates=[];
    for(const f of sorted){
      if(chosenSet.has(f))continue;
      try{
        const fp=await frameFingerprint(f);
        if(fp.mean<=55)continue;
        const t=Number(f.time||0);
        const nearest=chosenTimes.length?Math.min(...chosenTimes.map(x=>Math.abs(t-x))):999;
        // Prioriza justamente os intervalos que não foram cobertos na primeira passagem.
        if(nearest<0.28)continue;
        candidates.push({frame:f,fp,nearest,t});
      }catch{}
    }
    candidates.sort((a,b)=>b.nearest-a.nearest);
    const selected=[];
    for(const c of candidates){
      if(selected.length>=max)break;
      if(selected.some(s=>Math.abs((s.frame.time||0)-c.t)<0.35))continue;
      // Evita selecionar novamente uma tela visualmente quase idêntica.
      let duplicate=false;
      for(const s of selected){
        if(fpDiff(c.fp,s.fp)<5.5){duplicate=true;break;}
      }
      if(!duplicate)selected.push(c);
    }
    return selected.sort((a,b)=>(a.frame.time||0)-(b.frame.time||0)).map(x=>x.frame);
  }

  function strictRosterNeedsMore(rows){
    const c=composition(rows||[]);
    return (rows||[]).length<18 || c.ATA<TARGET.ATA || c.MEI<TARGET.MEI || c.DEF<TARGET.DEF || c.GOL<TARGET.GOL;
  }

  async function supplementalOcrPass(allFrames,chosenFrames,baseBlocks){
    const extra=await adaptiveGapFrames(allFrames,chosenFrames,6);
    if(!extra.length)return {rows:[],blocks:[],frames:[]};
    const blocks=[];
    for(let i=0;i<extra.length;i++){
      setProgress(76+Math.round((i/Math.max(1,extra.length))*12),`Segunda passagem ${i+1}/${extra.length}…`);
      const crop=await cropFrame(extra[i],{x:0,y:.31,w:1,h:.68},1536,.96);
      try{
        const b=await ocrSpaceImage(crop,`complemento ${i+1}`,14000);
        if(b?.text)blocks.push(b);
      }catch(_){}
    }
    if(!blocks.length)return {rows:[],blocks:[],frames:extra};

    const allBlocks=[...(baseBlocks||[]),...blocks];
    const joined=allBlocks.map((x,i)=>`### BLOCO ${i+1} (${x.label})\n${x.text}`).join('\n\n');
    const localRows=deterministicRowsFromOcr(joined);
    let structured=null;
    try{structured=await structureOcrText(allBlocks);}catch(_){structured=null;}
    const aiRows=Array.isArray(structured?.data?.players)?structured.data.players:[];
    return {rows:[...aiRows,...localRows],blocks,frames:extra};
  }


  function normalizedSectionName(text){
    const t=norm(text);
    if(/\b(avancados|atacantes|forwards?)\b/.test(t))return 'ATA';
    if(/\b(medios|meias|midfielders?)\b/.test(t))return 'MEI';
    if(/\b(defesas|defensores|defenders?)\b/.test(t))return 'DEF';
    if(/\b(guarda[- ]?redes|goleiros|goalkeepers?)\b/.test(t))return 'GOL';
    return null;
  }

  function sectionCoverageFromBlocks(blocks){
    const seen={ATA:0,MEI:0,DEF:0,GOL:0};
    const ordered=[];
    for(const b of (blocks||[])){
      const sec=normalizedSectionName(b.text||'');
      if(sec){
        seen[sec]++;
        if(!ordered.includes(sec))ordered.push(sec);
      }
    }
    return {seen,ordered,all:Object.values(seen).every(v=>v>0)};
  }

  async function rosterOcrFrame(frame,label){
    const crop=await cropForFreeOcr(frame,{x:0,y:.30,w:1,h:.69});
    try{
      return await ocrSpaceImage(crop,label,18000,'2');
    }catch(engine2Error){
      // Só usa Engine 3 no quadro específico que falhou.
      try{
        return await ocrSpaceImage(crop,label+' fallback',18000,'3');
      }catch(_){
        throw engine2Error;
      }
    }
  }

  async function fullRosterScan(frames){
    // 10 pontos uniformes sobre a rolagem inteira: cobertura com sobreposição,
    // sem bombardear o endpoint gratuito com 18 requisições.
    const chosen=chooseFrames(frames,10);
    if(chosen.length<6){
      throw new Error(`Poucos quadros extraídos do vídeo (${chosen.length}).`);
    }

    const blocks=[];
    const failures=[];

    setProgress(22,'Lendo cabeçalho do elenco…');
    const head=await cropForFreeOcr(chosen[0],{x:0,y:0,w:1,h:.46});
    try{
      blocks.push(await ocrSpaceImage(head,'cabeçalho',18000,'2'));
    }catch(err){
      failures.push('cabeçalho: '+String(err?.message||err));
    }

    // Máximo de duas chamadas simultâneas.
    for(let base=0;base<chosen.length;base+=2){
      const batch=chosen.slice(base,base+2);
      setProgress(
        28+Math.round((base/Math.max(1,chosen.length))*46),
        `Lendo elenco ${Math.min(base+2,chosen.length)}/${chosen.length}…`
      );

      const settled=await Promise.allSettled(
        batch.map((f,i)=>rosterOcrFrame(f,`rolagem ${String(base+i+1).padStart(2,'0')}`))
      );

      for(const r of settled){
        if(r.status==='fulfilled'&&r.value?.text){
          blocks.push(r.value);
        }else if(r.status==='rejected'){
          failures.push(String(r.reason?.message||r.reason));
        }
      }
    }

    const tableBlocks=blocks.filter(x=>/^rolagem/.test(x.label));
    if(tableBlocks.length<6){
      throw new Error(
        `OCR.Space retornou apenas ${tableBlocks.length} de ${chosen.length} quadros úteis`+
        (failures.length?` · ${failures.slice(0,2).join(' | ')}`:'')
      );
    }

    setProgress(78,'Reconstruindo todas as linhas do elenco…');

    const joined=blocks.map((x,i)=>
      `### BLOCO ${i+1} (${x.label})\n${x.text}`
    ).join('\n\n');

    // Overlay do Engine 2 preserva as linhas; parser local lê primeiro.
    const localRows=deterministicRowsFromOcr(joined);

    // IA textual é apenas consolidadora final; não lê a imagem.
    let structured=null;
    try{
      structured=await structureOcrText(blocks);
    }catch(_){
      structured=null;
    }

    const aiRows=Array.isArray(structured?.data?.players)
      ? structured.data.players
      : [];

    const finalRows=consolidate([localRows,aiRows]);

    const headerLocal=deterministicHeaderFromOcr(
      blocks.find(x=>x.label==='cabeçalho')?.text||joined
    );
    const aiHeader=structured?.data?.header||{};
    const header={
      ...headerLocal,
      ...Object.fromEntries(
        Object.entries(aiHeader).filter(([_,v])=>v!==null&&v!==undefined&&v!=='')
      )
    };

    if(header.playerCount==null){
      header.playerCount=expectedPlayerCountFromText(joined);
    }

    const coverage=sectionCoverageFromBlocks(tableBlocks);
    const first=sectionCoverageFromBlocks(tableBlocks.slice(0,3));
    const last=sectionCoverageFromBlocks(tableBlocks.slice(-3));

    const positionCoverage={
      ATA:finalRows.filter(r=>r.position==='ATA').length,
      MEI:finalRows.filter(r=>r.position==='MEI').length,
      DEF:finalRows.filter(r=>r.position==='DEF').length,
      GOL:finalRows.filter(r=>r.position==='GOL').length
    };

    const coveredStart=first.seen.ATA>0 || positionCoverage.ATA>0;
    const coveredEnd=last.seen.GOL>0 || positionCoverage.GOL>0;

    // Não existe "mínimo 18". A validação usa cobertura da rolagem.
    const coverageComplete=
      coveredStart &&
      coveredEnd &&
      Object.values(positionCoverage).every(v=>v>0);

    return {
      header,
      rows:finalRows,
      chosenFrames:chosen,
      ocrBlocks:blocks,
      distinctFrames:chosen.length,
      tableBlocks:tableBlocks.length,
      coverage,
      positionCoverage,
      coverageComplete,
      coveredStart,
      coveredEnd,
      failures,
      provider:structured?.provider
        ? `OCR.Space E2 + ${structured.provider}`
        : 'OCR.Space Engine 2',
      model:structured?.model||'overlay + parser determinístico'
    };
  }


  async function readHeader(frame){
    // Dedicated regions prevent 168M squad value from being mistaken for 9.3M cash.
    const cash=await cropFrame(frame,{x:.10,y:0,w:.16,h:.10},520,.9);
    const teamValue=await cropFrame(frame,{x:.86,y:.08,w:.14,h:.12},520,.9);
    const stats=await cropFrame(frame,{x:.58,y:.12,w:.36,h:.31},900,.88);
    const prompt=`OSM 26. Leia somente estas três imagens do cabeçalho, na ordem: (1) dinheiro/caixa no topo, (2) valor total do elenco, (3) força/setores/formação. Nunca troque caixa por valor do elenco. Retorne JSON puro exatamente: {"cash":null,"squadValue":null,"overall":null,"goalkeeper":null,"defence":null,"midfield":null,"attack":null,"formation":null}. Para dinheiro use texto com unidade, ex. "9,3M". Se não estiver legível, null.`;
    const parts=[{text:prompt},
      {inlineData:{mimeType:cash.mimeType,data:cash.base64}},
      {inlineData:{mimeType:teamValue.mimeType,data:teamValue.base64}},
      {inlineData:{mimeType:stats.mimeType,data:stats.base64}}];
    return (await modelJson(parts,{timeoutMs:70000,maxOutputTokens:1600,mode:'image'})).data||{};
  }

  async function readOneTableFrame(frame,index,total){
    const crop=await cropFrame(frame,{x:0,y:.46,w:1,h:.54},1280,.9);
    const prompt=`OSM 26 Android. Esta imagem contém uma parte da TABELA DO ELENCO. Leia TODAS as linhas de jogadores que estejam suficientemente visíveis. Não invente nem complete nome cortado. Colunas úteis: Jogador, Idade, Pos, Ata, Def, Med, Estado, Valor.\nPara cada jogador:\n- name: nome exatamente visível\n- age: idade\n- posCode: código exatamente visível (GR/DD/DC/DE/MDC/MC/MCO/MD/ME/PL/ED/EE)\n- rating: força PRINCIPAL do jogador: para PL/ED/EE use Ata; para DD/DC/DE use Def; para MDC/MC/MCO/MD/ME use Med; para GR use a força do goleiro/linha\n- value: valor monetário da última coluna, ex. 7,7M\n- training=true somente se a camisa do jogador estiver laranja\n- forSale=true somente se houver claramente o ícone/setas de transferência na linha.\nIgnore títulos, cabeçalhos e linhas parcialmente cortadas. JSON puro: {"rows":[{"name":"","age":null,"posCode":null,"rating":null,"value":null,"training":false,"forSale":false}]}.`;
    const r=await modelJson([{text:prompt},{inlineData:{mimeType:crop.mimeType,data:crop.base64}}],{timeoutMs:22000,maxOutputTokens:3600});
    return {index,total,rows:Array.isArray(r.data?.rows)?r.data.rows:[],model:r.model};
  }

  function normalizeRow(r){
    const name=cleanName(r?.name),position=positionFromCode(r?.posCode),rating=num(r?.rating),age=num(r?.age),valueNum=money(r?.value);
    if(!name||!position||rating===null||rating<40||rating>200||valueNum===null||valueNum<100000)return null;
    if(age!==null&&(age<15||age>45))return null;
    return {name,position,posCode:String(r?.posCode||'').toUpperCase(),rating,age,value:fmtMoney(valueNum),valueNum,training:r?.training===true,forSale:r?.forSale===true};
  }
  function consolidate(lists){
    const groups=new Map();
    for(const list of lists){
      for(const raw of (list||[])){
        const r=normalizeRow(raw); if(!r)continue;
        const sig=sigFor(r);
        const existing=groups.get(sig);
        if(!existing){groups.set(sig,{...r,hits:1,names:[r.name]});continue;}
        existing.hits++;
        existing.names.push(r.name);
        existing.name=betterName(existing.name,r.name);
        existing.training=existing.training||r.training;
        existing.forSale=existing.forSale||r.forSale;
      }
    }
    // Also merge same normalized name when slight numeric OCR drift produced neighboring signatures.
    let rows=[...groups.values()];
    const merged=[];
    for(const r of rows.sort((a,b)=>b.hits-a.hits)){
      const key=compactName(r.name);
      const hit=merged.find(x=>compactName(x.name)===key && x.position===r.position && Math.abs(x.rating-r.rating)<=1);
      if(hit){
        hit.hits+=r.hits; hit.name=betterName(hit.name,r.name); hit.training=hit.training||r.training;hit.forSale=hit.forSale||r.forSale;
        if(r.hits>hit._bestHits){Object.assign(hit,{rating:r.rating,age:r.age,value:r.value,valueNum:r.valueNum,posCode:r.posCode});hit._bestHits=r.hits;}
      }else merged.push({...r,_bestHits:r.hits});
    }
    return merged.map(({names,_bestHits,...r})=>({...r,verifiedRoster:true,source:'market_engine_53'}));
  }

  function composition(rows){
    const c={ATA:0,MEI:0,DEF:0,GOL:0}; for(const p of rows)if(c[p.position]!==undefined)c[p.position]++; return c;
  }
  function validateRoster(rows,header,frameResults){
    const comp=composition(rows),sum=rows.reduce((a,p)=>a+(money(p.value)||0),0),sq=money(header?.squadValue);
    const issues=[];
    if(rows.length<18)issues.push(`só ${rows.length} jogadores reconhecidos (mínimo seguro 18)`);
    if(comp.ATA<TARGET.ATA||comp.MEI<TARGET.MEI||comp.DEF<TARGET.DEF||comp.GOL<TARGET.GOL)issues.push(`composição incompleta ${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL}; mínimo ${TARGET.ATA}/${TARGET.MEI}/${TARGET.DEF}/${TARGET.GOL}`);
    if(sq!==null&&rows.length>=16){const ratio=sum/sq;if(ratio<.68||ratio>1.35)issues.push(`soma dos jogadores ${fmtMoney(sum)} incompatível com elenco ${fmtMoney(sq)}`);}
    const successful=frameResults.filter(x=>x&&Array.isArray(x.rows)&&x.rows.length).length;
    if(successful<3)issues.push(`apenas ${successful} quadros retornaram linhas`);
    return {ok:issues.length===0,issues,count:rows.length,composition:comp,sumValues:sum,squadValue:sq,successfulFrames:successful};
  }

  function applyValidated(s,rows,header,validation){
    s.roster=rows;
    s.myTeam=s.myTeam||{};
    s.myTeam.playerCount=rows.length;
    s.myTeam.validatedPlayerCount=rows.length;
    const cash=money(header?.cash),sq=money(header?.squadValue);
    if(cash!==null)s.myTeam.cash=cash;
    if(sq!==null)s.myTeam.squadValue=sq;
    for(const k of ['overall','goalkeeper','defence','midfield','attack']){const x=num(header?.[k]);if(x!==null&&x>0)s.myTeam[k]=x;}
    if(header?.formation)s.myTeam.formation=String(header.formation);
    s.rosterValidation42={...validation,ok:true,at:new Date().toISOString(),version:VERSION,header:{cash,squadValue:sq}};
    s.lastAnalysisAt=new Date().toISOString();
    s.marketPlan=null;
    if(s.coachAI)s.coachAI.marketPlan=null;
    if(s.coachAI34){s.coachAI34.budget=cash??s.coachAI34.budget??null;}
  }

  function preserveFailed(s,validation,header){
    s.rosterValidation42={...validation,ok:false,at:new Date().toISOString(),version:VERSION,header:{cash:money(header?.cash),squadValue:money(header?.squadValue)}};
    // Never keep a known-bad one-row roster from 3.4-3.6 as if it were current.
    if(!Array.isArray(s.roster)||s.roster.length<8 || s.roster.some(p=>/visual_table_35|market_engine_36|local_ocr_market/i.test(String(p?.source||'')))){
      s.roster=[];
      if(s.myTeam){s.myTeam.validatedPlayerCount=0;s.myTeam.playerCount=null;}
    }
  }


  function fileToBase64(file){
    return new Promise((resolve,reject)=>{
      const r=new FileReader();
      r.onload=()=>{const x=String(r.result||'');resolve(x.includes(',')?x.split(',')[1]:x)};
      r.onerror=()=>reject(r.error||new Error('Falha ao ler o vídeo'));
      r.readAsDataURL(file);
    });
  }

  function directRosterPrompt(){
    return `Você é um extrator visual especialista em OSM 26 Android. Analise TODO o vídeo anexado, do início ao fim. O usuário está rolando a tela do próprio elenco. A MESMA linha de jogador aparece em vários momentos durante a rolagem: consolide duplicatas e devolva cada jogador UMA única vez. Não invente, não complete nome cortado e não use conhecimento externo.

OBJETIVO 1 — CABEÇALHO DO MEU TIME:
- cash: dinheiro/caixa disponível no topo (ex.: 9,3M). NÃO confundir com valor do elenco.
- squadValue: valor total do elenco (ex.: 168M).
- overall: força geral.
- goalkeeper, defence, midfield, attack: forças dos quatro setores.
- formation: formação mostrada.

OBJETIVO 2 — TODOS OS JOGADORES DA TABELA:
Leia as colunas Jogador, Idade, Pos., Ata, Def., Med., Estado, Valor. Para cada linha:
- name: nome exatamente visível;
- age: idade;
- posCode: código exatamente visível. Códigos possíveis incluem GR, DD, DC, DE, MDC, MC, MCO, MD, ME, PL, ED, EE;
- rating: força principal: GR=goleiro; DD/DC/DE=Def; MDC/MC/MCO/MD/ME=Med; PL/ED/EE=Ata;
- value: valor da última coluna com unidade, ex. 7,7M;
- training=true SOMENTE quando a camisa/indicador do jogador estiver laranja;
- forSale=true SOMENTE quando houver as setas/ícone de transferência claramente na linha.

REGRAS CRÍTICAS:
- Percorra visualmente o vídeo inteiro; não pare nas primeiras linhas.
- Não crie jogador a partir de cabeçalho, menu ou texto solto.
- Não retorne a mesma pessoa duas vezes por aparecer em vários quadros.
- Se uma linha estiver parcialmente cortada e reaparecer completa depois, use a completa.
- cash e squadValue são campos diferentes.
- Se um dado não estiver legível, use null; nunca estime.

Retorne JSON puro EXATAMENTE neste formato:
{"header":{"cash":null,"squadValue":null,"overall":null,"goalkeeper":null,"defence":null,"midfield":null,"attack":null,"formation":null},"players":[{"name":"","age":null,"posCode":null,"rating":null,"value":null,"training":false,"forSale":false}],"observedPlayerCount":null}`;
  }

  async function makeVerticalRosterSheet(frames){
    const selected=(frames||[]).filter(Boolean);
    if(!selected.length)return null;
    const cells=[];
    for(const f of selected){
      const img=await v21LoadImage(f.dataUrl);
      const sx=0, sy=Math.round(img.naturalHeight*.34), sw=img.naturalWidth, sh=Math.round(img.naturalHeight*.64);
      const scale=Math.min(1,1280/sw), w=Math.max(1,Math.round(sw*scale)), h=Math.max(1,Math.round(sh*scale));
      const c=document.createElement('canvas'); c.width=w; c.height=h;
      c.getContext('2d').drawImage(img,sx,sy,sw,sh,0,0,w,h);
      cells.push(c);
    }
    const gap=10, width=Math.max(...cells.map(c=>c.width)), height=cells.reduce((a,c)=>a+c.height,0)+gap*(cells.length-1);
    const out=document.createElement('canvas'); out.width=width; out.height=height;
    const x=out.getContext('2d'); x.fillStyle='#fff'; x.fillRect(0,0,width,height);
    let y=0; for(const c of cells){x.drawImage(c,0,y); y+=c.height+gap;}
    const dataUrl=out.toDataURL('image/jpeg',.92);
    return {dataUrl,base64:dataUrl.split(',')[1],mimeType:'image/jpeg'};
  }

  async function makeFirstRosterPanel(frame,tableFrames){
    const top=await cropFrame(frame,{x:0,y:0,w:1,h:.46},1280,.92);
    const sheet=await makeVerticalRosterSheet(tableFrames);
    const a=await v21LoadImage(top.dataUrl),b=await v21LoadImage(sheet.dataUrl);
    const w=Math.max(a.naturalWidth,b.naturalWidth),gap=10,h=a.naturalHeight+gap+b.naturalHeight;
    const c=document.createElement('canvas');c.width=w;c.height=h;const x=c.getContext('2d');x.fillStyle='#fff';x.fillRect(0,0,w,h);x.drawImage(a,0,0);x.drawImage(b,0,a.naturalHeight+gap);
    const dataUrl=c.toDataURL('image/jpeg',.92);return {dataUrl,base64:dataUrl.split(',')[1],mimeType:'image/jpeg'};
  }


  function ocrSpaceKey(){return String(localStorage.getItem(OCRSPACE_KEY)||'').trim();}
  async function ocrSpaceImage(panel,label,timeoutMs=18000,engine='2'){
    const key=ocrSpaceKey();
    if(!key)throw new Error('OCR.Space: chave não configurada');
    const guard=withAbort(timeoutMs);
    try{
      const fd=new FormData();
      fd.append('base64Image',`data:${panel.mimeType||'image/jpeg'};base64,${panel.base64}`);
      fd.append('language','auto');
      fd.append('OCREngine',String(engine));
      fd.append('isTable','true');
      fd.append('isOverlayRequired','true');
      fd.append('scale','true');

      const res=await fetch('https://api.ocr.space/parse/image',{
        method:'POST',
        headers:{apikey:key},
        body:fd,
        signal:guard.controller.signal
      });

      const raw=await res.text();
      if(!res.ok)throw new Error(`OCR.Space HTTP ${res.status}: ${raw.slice(0,220)}`);

      let data;
      try{data=JSON.parse(raw)}catch{throw new Error('OCR.Space: resposta inválida')}

      if(data?.IsErroredOnProcessing){
        const em=Array.isArray(data?.ErrorMessage)
          ? data.ErrorMessage.join(' | ')
          : (data?.ErrorMessage||data?.ErrorDetails||'erro de OCR');
        throw new Error(`OCR.Space: ${em}`);
      }

      const parsed=data?.ParsedResults||[];
      const overlayText=parsed.map(x=>overlayLinesToText(x?.TextOverlay)).filter(Boolean).join('\n');
      const parsedText=parsed.map(x=>String(x?.ParsedText||'')).join('\n').trim();
      const text=(overlayText||parsedText).trim();

      if(!text)throw new Error(`OCR.Space: ${label} sem texto`);
      return {
        label,
        text,
        engine:String(engine),
        approxBytes:panel.approxBytes||Math.round(String(panel.base64||'').length*.75)
      };
    }catch(err){
      if(err?.name==='AbortError'){
        throw new Error(`OCR.Space: ${label} excedeu ${Math.round(timeoutMs/1000)}s`);
      }
      throw err;
    }finally{
      guard.clear();
    }
  }


  async function groqTextJson(prompt,timeoutMs=16000){
    const key=backupKeys().groq;if(!key)throw new Error('Groq: chave não configurada');
    const guard=withAbort(timeoutMs);
    try{
      const res=await fetch('https://api.groq.com/openai/v1/chat/completions',{method:'POST',signal:guard.controller.signal,headers:{'Content-Type':'application/json','Authorization':`Bearer ${key}`},body:JSON.stringify({model:'qwen/qwen3.8-27b',messages:[{role:'user',content:prompt}],temperature:0,max_completion_tokens:2200,response_format:{type:'json_object'},reasoning_effort:'none'})});
      const raw=await res.text();if(!res.ok)throw new Error(`Groq HTTP ${res.status}: ${raw.slice(0,180)}`);
      const data=JSON.parse(raw);return {provider:'Groq',model:data?.model||'qwen/qwen3.8-27b',data:parseProviderText(String(data?.choices?.[0]?.message?.content||''),'Groq')};
    }catch(err){if(err?.name==='AbortError')throw new Error('Groq texto: tempo excedido');throw err}finally{guard.clear()}
  }

  async function openRouterTextJson(prompt,timeoutMs=18000){
    const key=backupKeys().openrouter;if(!key)throw new Error('OpenRouter: chave não configurada');
    const guard=withAbort(timeoutMs);
    try{
      const res=await fetch('https://openrouter.ai/api/v1/chat/completions',{method:'POST',signal:guard.controller.signal,headers:{'Content-Type':'application/json','Authorization':`Bearer ${key}`,'HTTP-Referer':location.origin,'X-Title':'OSM AI Coach Pro'},body:JSON.stringify({model:'qwen/qwen3.8-27b:free',messages:[{role:'user',content:prompt}],temperature:0,max_tokens:2400,response_format:{type:'json_object'}})});
      const raw=await res.text();if(!res.ok)throw new Error(`OpenRouter HTTP ${res.status}: ${raw.slice(0,180)}`);
      const data=JSON.parse(raw);return {provider:'OpenRouter',model:data?.model||'qwen/qwen3.8-27b:free',data:parseProviderText(String(data?.choices?.[0]?.message?.content||''),'OpenRouter')};
    }catch(err){if(err?.name==='AbortError')throw new Error('OpenRouter texto: tempo excedido');throw err}finally{guard.clear()}
  }

  function deterministicRowsFromOcr(text){
    const out=[];const posRe=/(?:^|\s|\|)(GR|GK|GOL|POR|DD|DC|DE|DF|DEF|MDC|MC|MCO|MD|ME|MF|MID|VOL|PL|ED|EE|ATA|ATT|FW|FWD|ST)(?=\s|\||$)/i;
    for(const rawLine of String(text||'').split(/\r?\n/)){
      const line=rawLine.replace(/[|]+/g,' | ').replace(/\s+/g,' ').trim();if(line.length<5)continue;
      const pm=line.match(posRe);const mm=line.match(/(\d+(?:[.,]\d+)?)\s*([MK])\b/i);if(!pm||!mm)continue;
      const position=positionFromCode(pm[1]);if(!position)continue;
      const before=line.slice(0,pm.index).replace(/^\s*\|?\s*/,'').trim();
      const nums=[...line.matchAll(/\b(\d{2,3})\b/g)].map(m=>Number(m[1]));
      let age=nums.find(x=>x>=15&&x<=45)??null;
      const ratings=nums.filter(x=>x>=40&&x<=200 && x!==age);let rating=ratings.length?Math.max(...ratings):null;
      let name=before.replace(/\b\d{2}\b.*$/,'').replace(/[|]/g,' ').trim();name=cleanName(name);
      const value=mm[1].replace('.',',')+mm[2].toUpperCase();
      if(name&&rating!==null)out.push({name,age,posCode:pm[1].toUpperCase(),rating,value,training:false,forSale:false});
    }
    return out;
  }


  function expectedPlayerCountFromText(text){
    const t=String(text||'');
    const patterns=[
      /(?:jogadores|players)\s*[:\-]?\s*(\d{1,2})\b/i,
      /\b(\d{1,2})\s*(?:jogadores|players)\b/i,
      /(?:elenco|plantel)\s*[:\-]?\s*(\d{1,2})\b/i
    ];
    for(const re of patterns){
      const m=t.match(re);
      if(m){
        const n=Number(m[1]);
        if(Number.isInteger(n)&&n>=11&&n<=40)return n;
      }
    }
    return null;
  }

  function deterministicHeaderFromOcr(text){
    const ms=[...String(text||'').matchAll(/(\d+(?:[.,]\d+)?)\s*([MK])\b/ig)].map(m=>({raw:m[0],v:money(m[0]),i:m.index})).filter(x=>x.v!==null);
    let cash=ms.length?ms[0].raw:null;let squadValue=null;
    const big=ms.filter(x=>x.v>=5e7).sort((a,b)=>b.v-a.v);if(big[0])squadValue=big[0].raw;
    return {cash,squadValue,playerCount:expectedPlayerCountFromText(text),overall:null,goalkeeper:null,defence:null,midfield:null,attack:null,formation:null};
  }

  async function structureOcrText(blocks){
    const joined=blocks.map((x,i)=>`### BLOCO ${i+1} (${x.label})\n${x.text}`).join('\n\n');
    const prompt=`Você recebe OCR de 3 painéis da MESMA rolagem do elenco no OSM 26 Android. O OCR já extraiu texto/tabela. NÃO invente dados ausentes e NÃO use conhecimento externo. Consolide duplicatas da rolagem.\n\nNo BLOCO 1 também existe o cabeçalho do time. cash é o dinheiro disponível (ex. 9,3M) e squadValue é o valor total do elenco (ex. 168M); nunca troque os dois.\n\nPara jogadores, retorne somente linhas que tenham nome, idade se visível, código de posição (GR/DD/DC/DE/MDC/MC/MCO/MD/ME/PL/ED/EE), força principal e valor monetário. Se training/forSale não estiver explicitamente reconhecível no OCR, use false.\n\nRetorne JSON puro: {"header":{"cash":null,"squadValue":null,"overall":null,"goalkeeper":null,"defence":null,"midfield":null,"attack":null,"formation":null},"players":[{"name":"","age":null,"posCode":null,"rating":null,"value":null,"training":false,"forSale":false}],"observedPlayerCount":null}.\n\n${joined}`;
    const attempts=[];if(backupKeys().groq)attempts.push(groqTextJson(prompt));if(backupKeys().openrouter)attempts.push(openRouterTextJson(prompt));
    if(attempts.length){try{return await Promise.any(attempts)}catch{}}
    const rows=deterministicRowsFromOcr(joined),header=deterministicHeaderFromOcr(blocks[0]?.text||joined);
    return {provider:'OCR.Space local',model:'parser determinístico',data:{header,players:rows,observedPlayerCount:rows.length}};
  }

  async function readRosterWithOcrSpace(frames){
    // V4.9: seleciona somente pausas visualmente diferentes da rolagem.
    // Evita mandar ao OCR as mesmas linhas repetidas.
    const chosen=await smartChooseFrames(frames,6);
    if(chosen.length<4)throw new Error(`quadros distintos insuficientes (${chosen.length})`);

    const blocks=[];
    setProgress(32,'OCR.Space lendo cabeçalho…');
    const headerCrop=await cropFrame(chosen[0],{x:0,y:0,w:1,h:.46},1440,.96);
    try{blocks.push(await ocrSpaceImage(headerCrop,'cabeçalho',14000));}catch(_){}

    for(let i=0;i<chosen.length;i++){
      setProgress(38+Math.round((i/chosen.length)*34),`OCR.Space lendo trecho distinto ${i+1}/${chosen.length}…`);
      // mantém largura total e área suficiente para não cortar linhas na rolagem
      const crop=await cropFrame(chosen[i],{x:0,y:.31,w:1,h:.68},1536,.96);
      try{
        const b=await ocrSpaceImage(crop,`trecho ${i+1}`,14000);
        if(b?.text)blocks.push(b);
      }catch(_){}
    }
    const tableBlocks=blocks.filter(x=>/^trecho/.test(x.label));
    if(tableBlocks.length<4)throw new Error(`OCR.Space retornou poucos trechos úteis (${tableBlocks.length})`);

    setProgress(74,'Consolidando linhas do elenco…');
    const joined=blocks.map((x,i)=>`### BLOCO ${i+1} (${x.label})\n${x.text}`).join('\n\n');
    const localRows=deterministicRowsFromOcr(joined);
    const localHeader=deterministicHeaderFromOcr(blocks.find(x=>x.label==='cabeçalho')?.text||joined);

    // A IA recebe SOMENTE texto OCR, barato e rápido. Se falhar, parser local continua.
    let structured=null;
    try{structured=await structureOcrText(blocks);}catch(_){structured=null;}
    const aiRows=Array.isArray(structured?.data?.players)?structured.data.players:[];
    const rows=[...aiRows,...localRows];
    const aiHeader=structured?.data?.header||{};
    const header={...localHeader,...Object.fromEntries(Object.entries(aiHeader).filter(([_,v])=>v!==null&&v!==undefined&&v!==''))};
    const provider=structured?.provider?`OCR.Space + ${structured.provider}`:'OCR.Space local';
    const model=structured?.model||'parser determinístico';
    return {provider,model,header,rows,observedPlayerCount:rows.length,sheetCount:tableBlocks.length,ocrBlocks:blocks,chosenFrames:chosen};
  }

  async function readRosterPanelsOnce(frames,timeoutMs=42000){
    const chosen=chooseFrames(frames,12);
    if(chosen.length<6)throw new Error(`quadros insuficientes (${chosen.length})`);
    const panels=[];
    panels.push(await makeFirstRosterPanel(chosen[0],chosen.slice(0,4)));
    const p2=await makeVerticalRosterSheet(chosen.slice(4,8));if(p2)panels.push(p2);
    const p3=await makeVerticalRosterSheet(chosen.slice(8,12));if(p3)panels.push(p3);
    const prompt=`OSM 26 Android. Você recebeu 3 painéis da MESMA rolagem do elenco. O primeiro painel contém o cabeçalho do time e as primeiras linhas; os outros continuam a tabela. Consolide cada jogador UMA única vez.

CABEÇALHO: cash = dinheiro/caixa disponível no topo; squadValue = valor total do elenco; overall, goalkeeper, defence, midfield, attack e formation. Nunca confunda cash com squadValue.

TABELA: leia Jogador, Idade, Pos., Ata, Def, Med, Estado e Valor. Para cada linha completa retorne name exatamente visível, age, posCode, rating principal da posição, value, training e forSale. Códigos: GR/DD/DC/DE/MDC/MC/MCO/MD/ME/PL/ED/EE. Remova duplicatas da rolagem. Não invente nomes ou valores.

Retorne JSON puro exatamente:
{"header":{"cash":null,"squadValue":null,"overall":null,"goalkeeper":null,"defence":null,"midfield":null,"attack":null,"formation":null},"players":[{"name":"","age":null,"posCode":null,"rating":null,"value":null,"training":false,"forSale":false}],"observedPlayerCount":null}`;
    const parts=[{text:prompt}];for(const p of panels)parts.push({inlineData:{mimeType:p.mimeType,data:p.base64}});
    const r=await modelJson(parts,{timeoutMs,maxOutputTokens:900,mode:'image'});
    return {model:r.model,provider:r.provider,header:r.data?.header||{},rows:Array.isArray(r.data?.players)?r.data.players:[],observedPlayerCount:num(r.data?.observedPlayerCount),sheetCount:panels.length};
  }

  async function analyzeMarket(files){
    const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot; state.selectedSlot=slotNo;
    const s=selectedSlot();
    const video=(files||[]).find(f=>String(f.type||'').startsWith('video/'));
    const images=(files||[]).filter(f=>String(f.type||'').startsWith('image/'));
    let frames=[]; let header={}; let roster=[]; let source=''; let technicalError=''; let validationScan=null;
    const started=Date.now();

    try{
      if(video){
        setProgress(8,'Extraindo quadros da rolagem…');
        frames=await v21ExtractVideoFrames(video,30);
      }else if(images.length){
        setProgress(8,'Preparando imagens do elenco…');
        for(const f of images)frames.push(await v21ImageToFrame(f));
      }else throw new Error('Selecione vídeo ou imagens do elenco.');

      setProgress(20,'Iniciando varredura completa da rolagem…');
      if(!ocrSpaceKey())throw new Error('OCR.Space: chave não configurada em Config > OCR especializado');
      const result=await fullRosterScan(frames);
      header=result.header||{};
      roster=consolidate([result.rows||[]]);

      v21RenderEvidence(chooseFrames(result.chosenFrames||frames,8));
      source=`${result.provider||'OCR.Space'}:${result.model||'parser'}`;
      const diag=document.getElementById('analysisDiagnostics');
      const cov=result.positionCoverage||{};
      if(diag)diag.textContent=`Elenco 5.4 · Engine 2 · ${result.tableBlocks}/${result.distinctFrames} quadros úteis · ATA/MEI/DEF/GOL ${cov.ATA||0}/${cov.MEI||0}/${cov.DEF||0}/${cov.GOL||0} · ${roster.length} únicos · ${Math.round((Date.now()-started)/1000)}s.`;
      validationScan=result;
    }catch(err){
      technicalError=String(err?.message||err);
      const diag=document.getElementById('analysisDiagnostics');
      if(diag)diag.textContent=`Elenco 4.6 falhou: ${technicalError}`;
    }

    const comp=composition(roster), sum=roster.reduce((a,p)=>a+(money(p.value)||0),0), sq=money(header?.squadValue), issues=[];
    const expected=num(header?.playerCount);

    if(expected!==null && roster.length!==expected){
      issues.push(`vídeo indica ${expected} jogadores, mas ${roster.length} foram lidos`);
    }

    // Sem total explícito, só valida quando a própria rolagem comprova
    // início, meio e fim pelas quatro seções do elenco.
    if(!validationScan?.coverageComplete){
      const cov=validationScan?.coverage?.seen||{ATA:0,MEI:0,DEF:0,GOL:0};
      issues.push(`cobertura incompleta da lista (Avançados ${cov.ATA>0?'ok':'não'} · Médios ${cov.MEI>0?'ok':'não'} · Defesas ${cov.DEF>0?'ok':'não'} · Guarda-redes ${cov.GOL>0?'ok':'não'})`);
    }

    // Todas as seções vistas, mas alguma ficou sem nenhum jogador estruturado:
    // não pode declarar sucesso.
    if(validationScan?.coverageComplete){
      if(comp.ATA===0)issues.push('seção Avançados vista, mas nenhum atacante foi estruturado');
      if(comp.MEI===0)issues.push('seção Médios vista, mas nenhum meia foi estruturado');
      if(comp.DEF===0)issues.push('seção Defesas vista, mas nenhum defensor foi estruturado');
      if(comp.GOL===0)issues.push('seção Guarda-redes vista, mas nenhum goleiro foi estruturado');
    }

    if(sq!==null&&roster.length>0){
      const ratio=sum/sq;
      if(ratio<.55||ratio>1.50)issues.push(`soma dos jogadores ${fmtMoney(sum)} incompatível com elenco ${fmtMoney(sq)}`);
    }
    if(technicalError)issues.push(`falha técnica: ${technicalError}`);

    const validation={ok:issues.length===0,issues,count:roster.length,expectedCount:expected,composition:comp,sumValues:sum,squadValue:sq,source,technicalError,elapsedMs:Date.now()-started,scan:validationScan?{frames:validationScan.distinctFrames,blocks:validationScan.tableBlocks,coverage:validationScan.coverage?.seen,coverageComplete:validationScan.coverageComplete}:null};

    if(validation.ok){
      applyValidated(s,roster,header,validation); s.rosterValidation42.source=source; s.rosterValidation42.version=VERSION;
      try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
      if(typeof renderMarket==='function')renderMarket();
      const msg=`${roster.length} jogadores validados · ${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL} · caixa ${fmtMoney(s.myTeam?.cash)} · elenco ${fmtMoney(s.myTeam?.squadValue)}.`;
      const el=document.getElementById('analysisContent');
      if(el)el.innerHTML=`<div class="card" style="margin-top:12px"><span class="eyebrow">ELENCO VALIDADO · 5.4</span><h3>${roster.length} jogadores confirmados</h3><p class="small muted">${esc(msg)}</p><div class="fallback-kpis"><div><span>Caixa</span><b>${esc(fmtMoney(s.myTeam?.cash))}</b></div><div><span>Valor elenco</span><b>${esc(fmtMoney(s.myTeam?.squadValue))}</b></div><div><span>ATA/MEI/DEF/GOL</span><b>${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL}</b></div></div><p class="small muted">Fonte: ${esc(source)} · Engine 2 + overlay de linhas · ${Math.round(validation.elapsedMs/1000)}s.</p></div>`;
      setAnalysisRun(s,'market','success',msg,{version:VERSION,validation}); setProgress(100,'Elenco validado'); job('Elenco e finanças atualizados.','done'); return;
    }

    preserveFailed(s,validation,header); try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
    if(typeof renderMarket==='function')renderMarket();
    const el=document.getElementById('analysisContent');
    if(el)el.innerHTML=`<div class="card ai-fallback-card" style="margin-top:12px"><span class="eyebrow">VALIDAÇÃO DE ELENCO · 5.4</span><h3>Leitura incompleta — não alterei seu elenco</h3><p class="small muted">${esc(validation.issues.join(' · '))}</p><div class="fallback-kpis"><div><span>Encontrados</span><b>${validation.count}</b></div><div><span>ATA/MEI/DEF/GOL</span><b>${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL}</b></div><div><span>Tempo</span><b>${Math.round(validation.elapsedMs/1000)}s</b></div></div>${technicalError?`<p class="small muted" style="margin-top:10px">Erro técnico: ${esc(technicalError)}</p>`:''}</div>`;
    setAnalysisRun(s,'market','warning',`Leitura incompleta: ${validation.issues.join('; ')}`,{version:VERSION,validation}); setProgress(100,'Leitura incompleta'); job('Elenco não foi alterado porque a validação não fechou.','done');
  }


  // Market analysis is now a single implementation; old market hotfixes are not loaded by index 4.0.
  if(oldAnalyze){
    v21Analyze=async function(files){if(analysisMode==='market')return analyzeMarket(files);return oldAnalyze(files);};
    window.v21Analyze=v21Analyze;
  }

  function safeRows(s){return Array.isArray(s?.roster)?s.roster.filter(p=>p?.verifiedRoster&&VALID_POS.has(p.position)&&num(p.rating)!==null&&money(p.value)!==null):[];}
  function buildDirector(s){
    const rows=safeRows(s),by={ATA:[],MEI:[],DEF:[],GOL:[]};
    for(const p of rows)by[p.position].push(p);for(const k in by)by[k].sort((a,b)=>a.rating-b.rating);
    const sells=[];
    for(const [k,target] of Object.entries(TARGET)){
      const eligible=by[k].filter(p=>!p.training).sort((a,b)=>(b.forSale?1:0)-(a.forSale?1:0)||a.rating-b.rating);
      const excess=Math.max(0,eligible.length-target);
      eligible.slice(0,excess).forEach(p=>sells.push(p));
    }
    sells.sort((a,b)=>(b.forSale?1:0)-(a.forSale?1:0)||a.rating-b.rating);
    const cash=money(s?.myTeam?.cash),sq=money(s?.myTeam?.squadValue),saleRef=sells.slice(0,4).reduce((a,p)=>a+(money(p.value)||0),0);
    const weakest={};for(const k in by)weakest[k]=by[k][0]||null;
    return {rows,by,sells:sells.slice(0,4),cash,sq,saleRef,afterSales:cash===null?null:cash+saleRef,weakest};
  }
  function directorHtml(s){
    const v=s?.rosterValidation42;
    if(!v?.ok)return `<div class="card market40-card"><span class="eyebrow">DIRETOR IA · 5.4</span><h3>Plano bloqueado até ler o elenco completo</h3><p class="small muted">A versão 5.4 usa OCR.Space Engine 2, imagens abaixo de 1 MB e overlay de linhas; o Diretor só libera após cobertura completa.</p><div class="actions"><button class="btn" onclick="showView('analyze');setAnalysisMode('market')">Analisar elenco</button></div></div>`;
    const p=buildDirector(s),c={ATA:p.by.ATA.length,MEI:p.by.MEI.length,DEF:p.by.DEF.length,GOL:p.by.GOL.length};
    const sell=p.sells.length?p.sells.map((x,i)=>`<div class="radar-item"><div><b>${i+1}. ${esc(x.name)}</b><span>${esc(x.position)} · força ${esc(x.rating)} · valor ${esc(x.value)}${x.training?' · treinando':''}</span></div><span>${x.forSale?'Já à venda':'Excedente'}</span></div>`).join(''):'<p class="small muted">Nenhuma venda estrutural necessária agora.</p>';
    const weak=Object.entries(p.weakest).map(([k,x])=>x?`${k}: ${esc(x.name)} ${x.rating}`:`${k}: NI`).join(' · ');
    return `<div class="card market40-card"><div class="section-head compact-head"><div><span class="eyebrow">DIRETOR IA · DADOS REAIS · 5.4</span><h3>Plano baseado somente no vídeo validado</h3></div></div>
      <div class="coach30-kpis"><div><span>Caixa</span><b>${esc(fmtMoney(p.cash))}</b></div><div><span>Valor do elenco</span><b>${esc(fmtMoney(p.sq))}</b></div><div><span>Jogadores</span><b>${p.rows.length}</b></div><div><span>ATA/MEI/DEF/GOL</span><b>${c.ATA}/${c.MEI}/${c.DEF}/${c.GOL}</b></div></div>
      <div class="reason-box"><b>Venda agora</b>${sell}${p.sells.length?`<p class="small muted">Referência de caixa se as ${p.sells.length} vendas ocorrerem pelo valor mostrado: ${esc(fmtMoney(p.afterSales))}. O preço efetivo de venda pode variar no OSM.</p>`:''}</div>
      <div class="reason-box"><b>Compra</b><p>Caixa imediato: <b>${esc(fmtMoney(p.cash))}</b>. ${p.sells.length?`Referência após vendas: <b>${esc(fmtMoney(p.afterSales))}</b>.`:''}</p><p class="small muted">Sem ler a lista de transferências, não inventarei nome/preço disponível. O Diretor usa este teto real para indicar quais opções observadas no mercado cabem no orçamento.</p></div>
      <div class="reason-box"><b>Pontos mais fracos do elenco</b><p>${weak}</p></div>
      <div class="actions"><button class="btn" onclick="showView('analyze');setAnalysisMode('market')">Atualizar elenco</button></div></div>`;
  }

  if(oldRenderMarket){
    renderMarket=function(){
      const out=oldRenderMarket.apply(this,arguments); const s=selectedSlot(),root=document.getElementById('marketContent');if(!root||!s)return out;
      // Remove every legacy AI/market plan card. The base roster table can stay only when roster is valid.
      root.querySelectorAll('.coach30-market,.coach34-director,.director36,.finance35,.market40-card').forEach(x=>x.remove());
      [...root.querySelectorAll('.card')].forEach(card=>{
        const t=norm(card.textContent);
        if(t.includes('plano de crescimento do time')||t.includes('perfis de compra recomendados')||t.includes('plano 72h')||t.includes('subir força rapidamente')||t.startsWith('plano ativo'))card.remove();
        if(!s?.rosterValidation42?.ok&&(t.includes('saude do elenco')||t.includes('elenco reconhecido')))card.remove();
      });
      if(!s?.rosterValidation42?.ok){[...root.querySelectorAll('*')].filter(x=>/dados suficientes/i.test(String(x.textContent||''))&&x.children.length<3).forEach(x=>x.remove());}
      root.insertAdjacentHTML('afterbegin',directorHtml(s));
      return out;
    };
    window.renderMarket=renderMarket;
  }

  // Coach IA dashboard: prevent duplicate cards and purge invalid zero snapshots.
  if(oldRenderDashboard){
    renderDashboard=function(){
      document.querySelectorAll('#coach30Dashboard').forEach(x=>x.remove());
      const s=selectedSlot();
      if(s?.coachAI){
        for(const k of ['ownSnapshots','rivalSnapshots'])if(Array.isArray(s.coachAI[k]))s.coachAI[k]=s.coachAI[k].filter(x=>num(x?.overall)!==null&&num(x.overall)>0);
      }
      const out=oldRenderDashboard.apply(this,arguments);
      const nodes=[...document.querySelectorAll('#coach30Dashboard')];if(nodes.length>1)nodes.slice(0,-1).forEach(x=>x.remove());
      return out;
    };
    window.renderDashboard=renderDashboard;
  }

  // One-time migration: old failed 3.x validations must not masquerade as current roster data.
  try{
    for(const s of (state?.slots||[])){
      if(s?.rosterValidation42?.version===VERSION)continue;
      const legacyBad=s?.rosterValidation42?.ok===false || s?.rosterValidation40?.ok===false || s?.rosterValidation36?.ok===false || (Array.isArray(s?.roster)&&s.roster.length<8&&s.roster.some(p=>/visual_table_35|market_engine_36|local_ocr_market/i.test(String(p?.source||''))));
      if(legacyBad){s.roster=[];s.rosterValidation42={ok:false,version:VERSION,at:new Date().toISOString(),issues:['dados antigos descartados; reanalise o vídeo'],count:0,composition:{ATA:0,MEI:0,DEF:0,GOL:0},sumValues:0,successfulFrames:0};if(s.myTeam){s.myTeam.playerCount=null;s.myTeam.validatedPlayerCount=0;}}
    }
    localStorage.setItem(STATE_KEY,JSON.stringify(state));
  }catch(err){console.warn('[Market Engine 5.0 migrate]',err);}


  function injectBackupSettings(){
    const view=document.getElementById('view-settings');if(!view||document.getElementById('multiAiBackupCard'))return;
    const card=document.createElement('div');card.id='multiAiBackupCard';card.className='card form';card.style.marginTop='12px';
    card.innerHTML=`<span class="eyebrow">IA DE BACKUP · 5.4</span><h3>OCR especializado + IA de backup</h3><p class="small muted">Elenco: OCR.Space lê as tabelas; OpenRouter/Groq consolidam o texto. Gemini fica reservado para outras análises. As chaves ficam somente neste navegador.</p><label>OpenRouter API Key<input id="openrouterKey44" type="password" autocomplete="off" placeholder="sk-or-v1-..."></label><label>Groq API Key<input id="groqKey44" type="password" autocomplete="off" placeholder="gsk_..."></label><label>OCR.Space API Key<input id="ocrSpaceKey46" type="password" autocomplete="off" placeholder="Chave OCR.Space"></label><div class="actions"><button class="btn" id="saveBackupKeys44">Salvar chaves</button></div><p id="backupAiStatus44" class="small muted"></p>`;
    view.appendChild(card);
    const or=document.getElementById('openrouterKey44'),g=document.getElementById('groqKey44'),o=document.getElementById('ocrSpaceKey46');or.value=localStorage.getItem(OPENROUTER_KEY)||'';g.value=localStorage.getItem(GROQ_KEY)||'';o.value=localStorage.getItem(OCRSPACE_KEY)||'';
    const update=()=>{const k=backupKeys();document.getElementById('backupAiStatus44').textContent=`OCR.Space: ${ocrSpaceKey()?'configurado':'não configurado'} · OpenRouter: ${k.openrouter?'configurado':'não configurado'} · Groq: ${k.groq?'configurado':'não configurado'}${geminiCoolingDown()?' · Gemini em cooldown por cota':''}`};update();
    document.getElementById('saveBackupKeys44').onclick=()=>{localStorage.setItem(OPENROUTER_KEY,or.value.trim());localStorage.setItem(GROQ_KEY,g.value.trim());localStorage.setItem(OCRSPACE_KEY,o.value.trim());update();toast('Chaves de backup salvas neste aparelho');};
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',()=>setTimeout(injectBackupSettings,250));else setTimeout(injectBackupSettings,250);

  window.OSM_MARKET_ENGINE_54={version:VERSION,consolidate,validateRoster,buildDirector};
  try{console.info('[OSM] Market Engine '+VERSION+' ativo')}catch{}
})();

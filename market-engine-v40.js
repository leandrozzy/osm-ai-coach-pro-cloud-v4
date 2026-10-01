'use strict';
/* OSM AI Coach Pro — Market Engine 4.0
   Consolidated roster + finances reader. Replaces market hotfixes 3.4/3.5/3.6.
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
  const VERSION='4.0.0';
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
  function modelPool(){
    const pref=String(settings?.model||'');
    return ['gemini-3.8-flash','gemini-3.7-flash','gemini-flash-latest',pref]
      .filter((x,i,a)=>x&&!/lite/i.test(x)&&a.indexOf(x)===i);
  }
  async function modelJson(parts,{timeoutMs=24000,maxOutputTokens=3400}={}){
    const key=localStorage.getItem(API_KEY_STORAGE); if(!key)throw new Error('API Gemini não configurada.');
    const body={contents:[{role:'user',parts}],generationConfig:{temperature:0,maxOutputTokens,responseMimeType:'application/json'}};
    const errors=[];
    for(const model of modelPool()){
      try{
        const res=await Promise.race([
          geminiFetch(model,key,body),
          new Promise((_,rej)=>setTimeout(()=>rej(new Error(`tempo excedido (${Math.round(timeoutMs/1000)}s)`)),timeoutMs))
        ]);
        if(!res.ok){errors.push(`${model}: HTTP ${res.status}`);continue;}
        const data=await res.json();
        const txt=(data?.candidates?.[0]?.content?.parts||[]).map(p=>p.text||'').join('').trim();
        if(!txt){errors.push(`${model}: vazio`);continue;}
        return {model,data:parseJsonText(txt)};
      }catch(err){errors.push(`${model}: ${err?.message||err}`);}
    }
    throw new Error(errors.join(' | '));
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
    return (await modelJson(parts,{timeoutMs:18000,maxOutputTokens:1200})).data||{};
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
    return merged.map(({names,_bestHits,...r})=>({...r,verifiedRoster:true,source:'market_engine_40'}));
  }

  function composition(rows){
    const c={ATA:0,MEI:0,DEF:0,GOL:0}; for(const p of rows)if(c[p.position]!==undefined)c[p.position]++; return c;
  }
  function validateRoster(rows,header,frameResults){
    const comp=composition(rows),sum=rows.reduce((a,p)=>a+(money(p.value)||0),0),sq=money(header?.squadValue);
    const issues=[];
    if(rows.length<16)issues.push(`só ${rows.length} jogadores reconhecidos (mínimo seguro 16)`);
    if(comp.ATA<3||comp.MEI<4||comp.DEF<5||comp.GOL<1)issues.push(`composição incompleta ${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL}`);
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
    s.rosterValidation40={...validation,ok:true,at:new Date().toISOString(),version:VERSION,header:{cash,squadValue:sq}};
    s.lastAnalysisAt=new Date().toISOString();
    s.marketPlan=null;
    if(s.coachAI)s.coachAI.marketPlan=null;
    if(s.coachAI34){s.coachAI34.budget=cash??s.coachAI34.budget??null;}
  }

  function preserveFailed(s,validation,header){
    s.rosterValidation40={...validation,ok:false,at:new Date().toISOString(),version:VERSION,header:{cash:money(header?.cash),squadValue:money(header?.squadValue)}};
    // Never keep a known-bad one-row roster from 3.4-3.6 as if it were current.
    if(!Array.isArray(s.roster)||s.roster.length<8 || s.roster.some(p=>/visual_table_35|market_engine_36|local_ocr_market/i.test(String(p?.source||'')))){
      s.roster=[];
      if(s.myTeam){s.myTeam.validatedPlayerCount=0;s.myTeam.playerCount=null;}
    }
  }

  async function analyzeMarket(files){
    const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot;state.selectedSlot=slotNo;
    const s=selectedSlot();
    const video=(files||[]).find(f=>String(f.type||'').startsWith('video/'));
    const images=(files||[]).filter(f=>String(f.type||'').startsWith('image/'));
    let frames=[];
    setProgress(4,'Capturando o vídeo do elenco…');
    if(video)frames=await v21ExtractVideoFrames(video,28); else if(images.length){for(const f of images)frames.push(await v21ImageToFrame(f));} else throw new Error('Selecione vídeo ou imagens do elenco.');
    v21RenderEvidence(frames);
    if(!frames.length)throw new Error('Nenhum quadro foi capturado.');

    let header={};
    try{setProgress(18,'Lendo caixa, valor do elenco e força…');header=await readHeader(frames[0]);}
    catch(err){console.warn('[Market 4.0 header]',err);}

    const chosen=chooseFrames(frames,9);
    setProgress(30,`Lendo tabela em ${chosen.length} quadros independentes…`);
    const results=[];
    // Controlled concurrency: 3 at a time. Faster than sequential, less likely to hit quota than 9 simultaneous calls.
    for(let i=0;i<chosen.length;i+=3){
      const batch=chosen.slice(i,i+3);
      const part=await Promise.all(batch.map((f,j)=>readOneTableFrame(f,i+j+1,chosen.length).catch(err=>({index:i+j+1,total:chosen.length,rows:[],error:String(err?.message||err)}))));
      results.push(...part);
      setProgress(30+Math.round(results.length/chosen.length*50),`Consolidando quadro ${results.length}/${chosen.length}…`);
    }
    const roster=consolidate(results.map(x=>x.rows));
    const validation=validateRoster(roster,header,results);

    if(validation.ok){
      applyValidated(s,roster,header,validation);
      try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
      if(typeof renderMarket==='function')renderMarket();
      const c=validation.composition;
      const msg=`${roster.length} jogadores validados · ${c.ATA}/${c.MEI}/${c.DEF}/${c.GOL} · caixa ${fmtMoney(s.myTeam?.cash)} · elenco ${fmtMoney(s.myTeam?.squadValue)}.`;
      const el=document.getElementById('analysisContent');
      if(el)el.innerHTML=`<div class="card" style="margin-top:12px"><span class="eyebrow">ELENCO VALIDADO · 4.0</span><h3>${roster.length} jogadores confirmados</h3><p class="small muted">${esc(msg)}</p><div class="fallback-kpis"><div><span>Caixa</span><b>${esc(fmtMoney(s.myTeam?.cash))}</b></div><div><span>Valor elenco</span><b>${esc(fmtMoney(s.myTeam?.squadValue))}</b></div><div><span>ATA/MEI/DEF/GOL</span><b>${c.ATA}/${c.MEI}/${c.DEF}/${c.GOL}</b></div></div></div>`;
      setAnalysisRun(s,'market','success',msg,{version:VERSION,validation});
      setProgress(100,'Elenco validado');job('Elenco e finanças atualizados.','done');
      return;
    }

    preserveFailed(s,validation,header);
    try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
    if(typeof renderMarket==='function')renderMarket();
    const el=document.getElementById('analysisContent');
    if(el)el.innerHTML=`<div class="card ai-fallback-card" style="margin-top:12px"><span class="eyebrow">VALIDAÇÃO DE ELENCO · 4.0</span><h3>Leitura incompleta — não alterei seu elenco</h3><p class="small muted">${esc(validation.issues.join(' · '))}</p><div class="fallback-kpis"><div><span>Encontrados</span><b>${validation.count}</b></div><div><span>ATA/MEI/DEF/GOL</span><b>${validation.composition.ATA}/${validation.composition.MEI}/${validation.composition.DEF}/${validation.composition.GOL}</b></div><div><span>Quadros úteis</span><b>${validation.successfulFrames}</b></div></div></div>`;
    setAnalysisRun(s,'market','warning',`Leitura incompleta: ${validation.issues.join('; ')}`,{version:VERSION,validation});
    setProgress(100,'Leitura incompleta');job('Elenco não foi alterado porque a validação não fechou.','done');
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
    const v=s?.rosterValidation40;
    if(!v?.ok)return `<div class="card market40-card"><span class="eyebrow">DIRETOR IA · 4.0</span><h3>Plano bloqueado até o elenco ficar validado</h3><p class="small muted">A versão 4.0 não usa o elenco antigo/corrompido. Reanalise o vídeo; compras e vendas só aparecem depois que quantidade, composição e valores fecharem.</p><div class="actions"><button class="btn" onclick="showView('analyze');setAnalysisMode('market')">Analisar elenco</button></div></div>`;
    const p=buildDirector(s),c={ATA:p.by.ATA.length,MEI:p.by.MEI.length,DEF:p.by.DEF.length,GOL:p.by.GOL.length};
    const sell=p.sells.length?p.sells.map((x,i)=>`<div class="radar-item"><div><b>${i+1}. ${esc(x.name)}</b><span>${esc(x.position)} · força ${esc(x.rating)} · valor ${esc(x.value)}${x.training?' · treinando':''}</span></div><span>${x.forSale?'Já à venda':'Excedente'}</span></div>`).join(''):'<p class="small muted">Nenhuma venda estrutural necessária agora.</p>';
    const weak=Object.entries(p.weakest).map(([k,x])=>x?`${k}: ${esc(x.name)} ${x.rating}`:`${k}: NI`).join(' · ');
    return `<div class="card market40-card"><div class="section-head compact-head"><div><span class="eyebrow">DIRETOR IA · DADOS REAIS · 4.0</span><h3>Plano baseado somente no vídeo validado</h3></div></div>
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
        if(!s?.rosterValidation40?.ok&&(t.includes('saude do elenco')||t.includes('elenco reconhecido')))card.remove();
      });
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
      if(s?.rosterValidation40?.version===VERSION)continue;
      const legacyBad=s?.rosterValidation36?.ok===false || (Array.isArray(s?.roster)&&s.roster.length<8&&s.roster.some(p=>/visual_table_35|market_engine_36|local_ocr_market/i.test(String(p?.source||''))));
      if(legacyBad){s.roster=[];s.rosterValidation40={ok:false,version:VERSION,at:new Date().toISOString(),issues:['dados antigos descartados; reanalise o vídeo'],count:0,composition:{ATA:0,MEI:0,DEF:0,GOL:0},sumValues:0,successfulFrames:0};if(s.myTeam){s.myTeam.playerCount=null;s.myTeam.validatedPlayerCount=0;}}
    }
    localStorage.setItem(STATE_KEY,JSON.stringify(state));
  }catch(err){console.warn('[Market Engine 4.0 migrate]',err);}

  window.OSM_MARKET_ENGINE_40={version:VERSION,consolidate,validateRoster,buildDirector};
  try{console.info('[OSM] Market Engine '+VERSION+' ativo')}catch{}
})();

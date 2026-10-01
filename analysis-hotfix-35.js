'use strict';
/* OSM AI Coach Pro — Analysis Hotfix 3.5.0: visual roster + team finances */
(function(){
  const V='3.5.0';
  const norm=v=>String(v||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/\s+/g,' ').trim();
  const num=v=>{if(v===null||v===undefined||v===''||v==='NI')return null;const n=Number(v);return Number.isFinite(n)?n:null};
  function moneyNumber(v){
    if(v===null||v===undefined||v==='')return null;
    if(typeof v==='number'&&Number.isFinite(v))return v;
    const s=String(v).trim().toLowerCase().replace(/\s/g,'').replace(',','.');
    const m=s.match(/([0-9]+(?:\.[0-9]+)?)([kmb])?/i);if(!m)return null;
    let n=Number(m[1]);if(!Number.isFinite(n))return null;
    const u=m[2];if(u==='k')n*=1e3;else if(u==='m')n*=1e6;else if(u==='b')n*=1e9;return n;
  }
  function fmtMoney(v){const n=moneyNumber(v);if(n===null)return null;if(n>=1e9)return (n/1e9).toFixed(1).replace('.',',')+'B';if(n>=1e6)return (n/1e6).toFixed(n>=1e8?0:1).replace('.',',')+'M';if(n>=1e3)return Math.round(n/1e3)+'K';return String(Math.round(n));}
  function posCode(raw){
    const x=String(raw||'').toUpperCase().replace(/[^A-Z]/g,'');
    if(['GR','GK','GOL','POR'].includes(x))return'GOL';
    if(['DD','DC','DE','DF','DEF','ZAG','CB','RB','LB'].includes(x))return'DEF';
    if(['MDC','MC','MCO','MD','ME','MF','MID','VOL','CM','CDM','CAM','LM','RM'].includes(x))return'MEI';
    if(['PL','ED','EE','ATA','ATT','FW','FWD','ST','CA','PE','PD','LW','RW','CF'].includes(x))return'ATA';
    return null;
  }
  function cleanName(v){
    const s=String(v||'').replace(/\s+/g,' ').trim();
    if(s.length<3||s.length>42)return null;
    if(!/^[A-Za-zÀ-ÿ'’.-]+(?: [A-Za-zÀ-ÿ'’.-]+){0,4}$/.test(s))return null;
    return s;
  }
  function nameEvidence(name,ocr){
    const t=norm(ocr?.joined||'');
    const tokens=norm(name).split(/\s+/).map(x=>x.replace(/[^a-z0-9]/g,'')).filter(x=>x.length>=4);
    return tokens.some(tok=>t.includes(tok));
  }
  async function cropFrame(frame,box,maxW=1280,quality=.82){
    const img=await v21LoadImage(frame.dataUrl);
    const sx=Math.round(img.naturalWidth*box.x), sy=Math.round(img.naturalHeight*box.y), sw=Math.round(img.naturalWidth*box.w), sh=Math.round(img.naturalHeight*box.h);
    const scale=Math.min(1,maxW/sw),cw=Math.max(1,Math.round(sw*scale)),ch=Math.max(1,Math.round(sh*scale));
    const c=document.createElement('canvas');c.width=cw;c.height=ch;c.getContext('2d').drawImage(img,sx,sy,sw,sh,0,0,cw,ch);
    const dataUrl=c.toDataURL('image/jpeg',quality);
    return {dataUrl,base64:dataUrl.split(',')[1],mimeType:'image/jpeg',time:frame.time||0,name:frame.name||'crop'};
  }
  function modelPool(){
    const pref=String(settings?.model||'');
    return ['gemini-3.8-flash','gemini-3.7-flash','gemini-flash-latest',pref]
      .filter((x,i,a)=>x && !/lite/i.test(x) && a.indexOf(x)===i);
  }
  async function smallJson(parts,timeoutMs=22000,maxOutputTokens=4500){
    const key=localStorage.getItem(API_KEY_STORAGE);if(!key)throw new Error('API Gemini não configurada.');
    const body={contents:[{role:'user',parts}],generationConfig:{temperature:0,maxOutputTokens,responseMimeType:'application/json'}};
    const errors=[];
    for(const model of modelPool()){
      try{
        const res=await Promise.race([geminiFetch(model,key,body),new Promise((_,rej)=>setTimeout(()=>rej(new Error('timeout')),timeoutMs))]);
        if(!res.ok){errors.push(model+': HTTP '+res.status);continue;}
        const data=await res.json();const txt=(data?.candidates?.[0]?.content?.parts||[]).map(x=>x.text||'').join('').trim();
        if(!txt){errors.push(model+': vazio');continue;}
        return {model,data:parseJsonText(txt)};
      }catch(e){errors.push(model+': '+(e?.message||e));}
    }
    throw new Error(errors.join(' | '));
  }
  async function extractHeader(frame,ocr){
    const crop=await cropFrame(frame,{x:0,y:0,w:1,h:.34},1400,.86);
    const prompt=`Leia SOMENTE o cabeçalho deste time no OSM 26. Não invente. Retorne JSON puro com: {"cash":null,"squadValue":null,"overall":null,"goalkeeper":null,"defence":null,"midfield":null,"attack":null,"formation":null}. cash é o dinheiro disponível no topo esquerdo. squadValue é o valor total do elenco no topo/direita. Forças são os círculos Gr/Def/Med/Ata e overall é Equipa. Use strings monetárias como "9,3M" e "168M".`;
    try{return (await smallJson([{text:prompt},{inlineData:{mimeType:crop.mimeType,data:crop.base64}}],16000,1800)).data||{};}catch{
      // Fallback textual limitado ao que é seguro reconhecer.
      const txt=String(ocr?.frames?.[0]?.text||ocr?.joined||'');
      const monies=[...txt.matchAll(/([0-9]+(?:[.,][0-9]+)?)\s*([MK])\b/gi)].map(m=>m[1]+m[2].toUpperCase());
      return {cash:monies[0]||null,squadValue:monies.find(x=>moneyNumber(x)>=5e7)||null};
    }
  }
  async function extractRosterVisual(frames,ocr){
    if(!frames.length)return [];
    const count=Math.min(10,frames.length),picked=[];
    for(let i=0;i<count;i++){
      const idx=Math.round(i*(frames.length-1)/Math.max(1,count-1));
      if(frames[idx]&&!picked.includes(frames[idx]))picked.push(frames[idx]);
    }
    const crops=[];
    for(const f of picked)crops.push(await cropFrame(f,{x:0,y:.27,w:1,h:.73},1400,.86));
    const chunks=[];for(let i=0;i<crops.length;i+=2)chunks.push(crops.slice(i,i+2));
    const prompt=`Você está vendo recortes da TABELA DO ELENCO do OSM 26. Extraia SOMENTE jogadores que estejam visíveis nas imagens. NÃO invente nomes e não complete linhas cortadas. Para cada jogador, retorne nome, idade, código de posição exatamente como aparece (GR/DD/DC/DE/MDC/MC/MCO/MD/ME/PL/ED/EE), força principal e valor monetário visível na coluna Valor. Ignore colunas secundárias de ataque/defesa/meio que não representam a força principal da posição. Se algum campo não estiver legível, use null. Retorne JSON puro: {"rows":[{"name":"","age":null,"posCode":null,"rating":null,"value":null,"training":false,"forSale":false}]}. Uma camisa laranja significa treinamento. Só marque forSale=true se houver claramente setas de transferência.`;
    const jobs=chunks.map(async ch=>{
      const parts=[{text:prompt},...ch.map(c=>({inlineData:{mimeType:c.mimeType,data:c.base64}}))];
      try{return (await smallJson(parts,22000,5000)).data?.rows||[];}catch{return [];}
    });
    const lists=await Promise.all(jobs),raw=lists.flat(),out=[],seen=new Set();
    for(const r of raw){
      const name=cleanName(r?.name),position=posCode(r?.posCode),rating=num(r?.rating),age=num(r?.age),value=r?.value;
      if(!name||!position||rating===null||rating<40||rating>200)continue;
      if(age!==null&&(age<15||age>45))continue;
      if(moneyNumber(value)===null)continue; // vídeo mostra valor; sem valor não entra no Diretor
      if(!nameEvidence(name,ocr))continue;   // impede nome visual inventado que não tenha evidência textual
      const k=norm(name).replace(/[^a-z0-9]/g,'');if(!k||seen.has(k))continue;seen.add(k);
      out.push({name,position,rating,age,value:fmtMoney(value)||value,training:r?.training===true,forSale:r?.forSale===true,verifiedRoster:true,source:'visual_table_35'});
    }
    return out;
  }
  function applyHeader(s,h){
    s.myTeam=s.myTeam||{};
    const cash=moneyNumber(h?.cash),sq=moneyNumber(h?.squadValue);
    if(cash!==null)s.myTeam.cash=cash;
    if(sq!==null)s.myTeam.squadValue=sq;
    for(const k of ['overall','goalkeeper','defence','midfield','attack']){const n=num(h?.[k]);if(n!==null&&n>0)s.myTeam[k]=n;}
    if(h?.formation)s.myTeam.formation=String(h.formation);
    s.coachAI34=s.coachAI34&&typeof s.coachAI34==='object'?s.coachAI34:{};
    if(cash!==null)s.coachAI34.budget=cash; // Diretor usa automaticamente o caixa visto no vídeo
  }
  const previous=v21Analyze;
  v21Analyze=async function(files){
    if(analysisMode!=='market')return previous(files);
    const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot;state.selectedSlot=slotNo;const s=selectedSlot();
    const video=(files||[]).find(f=>String(f.type||'').startsWith('video/')),images=(files||[]).filter(f=>String(f.type||'').startsWith('image/'));let frames=[];
    setProgress(5,'Capturando elenco e cabeçalho financeiro…');
    if(video)frames=await v21ExtractVideoFrames(video,30);else if(images.length){for(const f of images)frames.push(await v21ImageToFrame(f));}else throw new Error('Selecione vídeo ou imagens do elenco.');
    v21RenderEvidence(frames);setProgress(16,'OCR local para confirmar nomes…');const ocr=await v21RunLocalOcr(frames);
    setProgress(50,'Lendo caixa, valor do elenco e forças…');const header=await extractHeader(frames[0],ocr);applyHeader(s,header);
    setProgress(62,'Lendo linhas reais da tabela em pequenos lotes…');const roster=await extractRosterVisual(frames,ocr);
    if(roster.length>=8){
      s.roster=roster;s.myTeam.playerCount=roster.length;s.myTeam.validatedPlayerCount=roster.length;s.marketPlan=null;s.lastAnalysisAt=new Date().toISOString();
      try{if(window.OSM_DIRECTOR_HOTFIX_34)window.OSM_DIRECTOR_HOTFIX_34.sanitizeSlot(s)}catch{}
      try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
      renderMarket();
      const cash=s.myTeam?.cash,sq=s.myTeam?.squadValue;
      const msg=`${s.roster.length} jogador(es) reais validados · caixa ${fmtMoney(cash)||'NI'} · elenco ${fmtMoney(sq)||'NI'}.`;
      const target=document.getElementById('analysisContent');if(target)target.innerHTML=`<div class="card" style="margin-top:12px"><span class="eyebrow">ELENCO VISUAL ${V}</span><h3>Elenco + finanças atualizados</h3><p class="small muted">${msg}</p><div class="fallback-kpis"><div><span>Caixa</span><b>${fmtMoney(cash)||'NI'}</b></div><div><span>Valor elenco</span><b>${fmtMoney(sq)||'NI'}</b></div><div><span>Jogadores</span><b>${s.roster.length}</b></div></div></div>`;
      setAnalysisRun(s,'market','success',msg,{rosterCount:s.roster.length,cash,squadValue:sq,hotfix:V,visual:true});setProgress(100,'Elenco e finanças atualizados');job('Elenco, caixa e valor do time atualizados.','done');return;
    }
    // Se a visão não conseguiu confirmar linhas suficientes, mantém cabeçalho e usa o leitor estrito anterior.
    try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
    await previous(files);
    applyHeader(s,header);
    try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
    renderMarket();
  };
  window.v21Analyze=v21Analyze;window.OSM_ANALYSIS_HOTFIX_VERSION=V;
})();

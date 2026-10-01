'use strict';
/* OSM AI Coach Pro — Analysis Hotfix 3.6.0
   Elenco: leitura visual por UM quadro por chamada + validação financeira/composição.
   Nunca sobrescreve o elenco se a leitura ficar incompleta/inconsistente. */
(function(){
  const V='3.6.0';
  const norm=v=>String(v||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/\s+/g,' ').trim();
  const n=v=>{ if(v===null||v===undefined||v===''||v==='NI')return null; const x=Number(v); return Number.isFinite(x)?x:null; };
  function money(v){
    if(v===null||v===undefined||v==='')return null;
    if(typeof v==='number'&&Number.isFinite(v))return v;
    const s=String(v).trim().toLowerCase().replace(/\s/g,'').replace(',','.');
    const m=s.match(/([0-9]+(?:\.[0-9]+)?)([kmb])?/i); if(!m)return null;
    let x=Number(m[1]); if(!Number.isFinite(x))return null;
    if(m[2]==='k')x*=1e3; else if(m[2]==='m')x*=1e6; else if(m[2]==='b')x*=1e9;
    return x;
  }
  function fmt(v){ const x=money(v); if(x===null)return'NI'; if(x>=1e9)return(x/1e9).toFixed(1).replace('.',',')+'B'; if(x>=1e6)return(x/1e6).toFixed(x>=1e8?0:1).replace('.',',')+'M'; if(x>=1e3)return Math.round(x/1e3)+'K'; return String(Math.round(x)); }
  function pos(raw){
    const x=String(raw||'').toUpperCase().replace(/[^A-Z]/g,'');
    if(['GR','GK','GOL','POR'].includes(x))return {position:'GOL',code:x};
    if(['DD','DC','DE','DF','DEF','ZAG','CB','RB','LB'].includes(x))return {position:'DEF',code:x};
    if(['MDC','MC','MCO','MD','ME','MF','MID','VOL','CM','CDM','CAM','LM','RM'].includes(x))return {position:'MEI',code:x};
    if(['PL','ED','EE','ATA','ATT','FW','FWD','ST','CA','PE','PD','LW','RW','CF'].includes(x))return {position:'ATA',code:x};
    return null;
  }
  function cleanName(v){
    const s=String(v||'').replace(/\s+/g,' ').trim();
    if(s.length<2||s.length>42)return null;
    if(!/^[A-Za-zÀ-ÿ'’.-]+(?: [A-Za-zÀ-ÿ'’.-]+){0,4}$/.test(s))return null;
    return s;
  }
  function keyName(v){return norm(v).replace(/[^a-z0-9]/g,'');}
  function tokenEvidence(name,ocr){
    const t=norm(ocr?.joined||'');
    return norm(name).split(/\s+/).map(x=>x.replace(/[^a-z0-9]/g,'')).filter(x=>x.length>=4).some(x=>t.includes(x));
  }
  async function cropFrame(frame,box,maxW=1280,q=.80){
    const img=await v21LoadImage(frame.dataUrl); const iw=img.naturalWidth,ih=img.naturalHeight;
    const sx=Math.round(iw*box.x),sy=Math.round(ih*box.y),sw=Math.round(iw*box.w),sh=Math.round(ih*box.h);
    const scale=Math.min(1,maxW/sw),cw=Math.max(1,Math.round(sw*scale)),ch=Math.max(1,Math.round(sh*scale));
    const c=document.createElement('canvas');c.width=cw;c.height=ch;c.getContext('2d').drawImage(img,sx,sy,sw,sh,0,0,cw,ch);
    const dataUrl=c.toDataURL('image/jpeg',q);return {dataUrl,base64:dataUrl.split(',')[1],mimeType:'image/jpeg',time:frame.time||0};
  }
  function models(){
    const pref=String(settings?.model||'');
    return ['gemini-3.8-flash','gemini-3.7-flash','gemini-flash-latest',pref].filter((x,i,a)=>x&&!/lite/i.test(x)&&a.indexOf(x)===i);
  }
  async function oneImageJson(frame,prompt,timeoutMs=60000,maxOutputTokens=5000){
    const key=localStorage.getItem(API_KEY_STORAGE);if(!key)throw new Error('API Gemini não configurada.');
    const body={contents:[{role:'user',parts:[{text:prompt},{inlineData:{mimeType:frame.mimeType,data:frame.base64}}]}],generationConfig:{temperature:0,maxOutputTokens,responseMimeType:'application/json'}};
    const errs=[];
    for(const model of models()){
      try{
        const res=await Promise.race([geminiFetch(model,key,body),new Promise((_,rej)=>setTimeout(()=>rej(new Error('tempo excedido')),timeoutMs))]);
        if(!res.ok){errs.push(model+': HTTP '+res.status);continue;}
        const data=await res.json();const txt=(data?.candidates?.[0]?.content?.parts||[]).map(p=>p.text||'').join('').trim();
        if(!txt){errs.push(model+': vazio');continue;}
        return {model,data:parseJsonText(txt)};
      }catch(e){errs.push(model+': '+(e?.message||e));}
    }
    throw new Error(errs.join(' | '));
  }
  async function mapLimit(items,limit,fn){
    const out=new Array(items.length);let idx=0;
    async function worker(){while(true){const i=idx++;if(i>=items.length)return;try{out[i]=await fn(items[i],i)}catch(e){out[i]={error:String(e?.message||e)}}}}
    await Promise.all(Array.from({length:Math.min(limit,items.length)},()=>worker()));return out;
  }
  async function readHeader(first){
    const crop=await cropFrame(first,{x:0,y:0,w:1,h:.37},1280,.84);
    const prompt=`Imagem do CABEÇALHO do meu time no OSM 26. Leia visualmente e NÃO invente.\nRetorne JSON puro: {"cash":null,"squadValue":null,"overall":null,"goalkeeper":null,"defence":null,"midfield":null,"attack":null,"formation":null}.\nREGRAS: cash é SOMENTE o dinheiro do retângulo azul no topo esquerdo (ícone de moeda; ex. 9,3M). squadValue é SOMENTE o valor do elenco com moeda branca no lado direito (ex. 168M). NÃO troque cash e squadValue. overall é o círculo Equipa. Gr/Def/Med/Ata são os quatro círculos menores. formation é a formação visível.`;
    const r=await oneImageJson(crop,prompt,55000,1600);return r.data||{};
  }
  function pickFrames(frames,count=7){
    if(frames.length<=count)return [...frames]; const out=[];
    for(let i=0;i<count;i++){const k=Math.round(i*(frames.length-1)/Math.max(1,count-1));if(frames[k]&&!out.includes(frames[k]))out.push(frames[k]);}
    return out;
  }
  async function readFrameRows(frame){
    const crop=await cropFrame(frame,{x:.015,y:.055,w:.97,h:.93},1280,.80);
    const prompt=`Leia SOMENTE as LINHAS COMPLETAS de jogadores visíveis nesta tela de ELENCO do OSM 26. Não invente e não complete nome cortado.\nRetorne JSON puro: {"rows":[{"name":"","age":null,"posCode":null,"rating":null,"value":null,"training":false,"forSale":false}]}.\nUse posCode EXATAMENTE entre GR,DD,DC,DE,MDC,MC,MCO,MD,ME,PL,ED,EE.\nrating é a FORÇA PRINCIPAL EM NEGRITO da posição: atacantes=coluna Ata; meias=Med; defensores e goleiros=Def. value é a coluna Valor (ex. 14,2M).\nCamisa LARANJA=training true. Setas vermelha/verde de transferência=forSale true. Cartão amarelo NÃO é venda.\nSó inclua a linha se nome, idade, posição, força e valor estiverem legíveis.`;
    const r=await oneImageJson(crop,prompt,60000,4800);return Array.isArray(r.data?.rows)?r.data.rows:[];
  }
  function mergeRows(results,ocr){
    const map=new Map();
    for(const rows of results){
      if(!Array.isArray(rows))continue;
      for(const r of rows){
        const name=cleanName(r?.name),pc=pos(r?.posCode),rating=n(r?.rating),age=n(r?.age),val=money(r?.value);
        if(!name||!pc||rating===null||rating<40||rating>200||age===null||age<15||age>45||val===null||val<1e5)continue;
        const k=keyName(name);if(!k)continue;
        const cur=map.get(k)||{name,position:pc.position,posCode:pc.code,rating,age,value:fmt(val),valueNumber:val,training:false,forSale:false,_seen:0,_ocr:false};
        cur._seen++;cur._ocr=cur._ocr||tokenEvidence(name,ocr);cur.training=cur.training||r?.training===true;cur.forSale=cur.forSale||r?.forSale===true;
        // prefere dados completos/repetidos mais recentes, mas nunca zera valores válidos
        cur.name=name;cur.position=pc.position;cur.posCode=pc.code;cur.rating=rating;cur.age=age;cur.value=fmt(val);cur.valueNumber=val;
        map.set(k,cur);
      }
    }
    return [...map.values()].filter(x=>x._seen>=2||x._ocr).map(x=>({name:x.name,position:x.position,posCode:x.posCode,rating:x.rating,age:x.age,value:x.value,training:x.training,forSale:x.forSale,verifiedRoster:true,source:'single_frame_visual_36'}));
  }
  function validateRoster(rows,header){
    const counts={ATA:0,MEI:0,DEF:0,GOL:0};let sum=0;
    for(const p of rows){if(counts[p.position]!==undefined)counts[p.position]++;sum+=money(p.value)||0;}
    const sq=money(header?.squadValue);const ratio=sq?sum/sq:null;
    const countOk=rows.length>=16&&rows.length<=30;
    const ratioOk=ratio===null||(ratio>=.72&&ratio<=1.18);
    const structureOk=counts.ATA>=3&&counts.MEI>=4&&counts.DEF>=5&&counts.GOL>=1;
    return {ok:countOk&&ratioOk&&structureOk,counts,sum,ratio,countOk,ratioOk,structureOk};
  }
  function applyHeader(s,h){
    s.myTeam=s.myTeam||{};const cash=money(h?.cash),sq=money(h?.squadValue);
    // nunca aceita o antigo erro cash=squadValue em valores altos sem confirmação separada
    if(cash!==null && !(sq!==null&&cash===sq&&cash>5e7))s.myTeam.cash=cash;
    if(sq!==null)s.myTeam.squadValue=sq;
    for(const k of ['overall','goalkeeper','defence','midfield','attack']){const x=n(h?.[k]);if(x!==null&&x>=40&&x<=200)s.myTeam[k]=x;}
    if(h?.formation)s.myTeam.formation=String(h.formation);
  }
  const previous=v21Analyze;
  v21Analyze=async function(files){
    if(analysisMode!=='market')return previous(files);
    const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot;state.selectedSlot=slotNo;const s=selectedSlot();
    const video=(files||[]).find(f=>String(f.type||'').startsWith('video/')),images=(files||[]).filter(f=>String(f.type||'').startsWith('image/'));let frames=[];
    setProgress(4,'Capturando a rolagem completa do elenco…');
    if(video)frames=await v21ExtractVideoFrames(video,24);else if(images.length){for(const f of images)frames.push(await v21ImageToFrame(f));}else throw new Error('Selecione vídeo ou imagens do elenco.');
    v21RenderEvidence(frames);setProgress(14,'OCR local para conferência de nomes…');const ocr=await v21RunLocalOcr(frames);
    let header={};
    try{setProgress(45,'Lendo caixa e valor do elenco separadamente…');header=await readHeader(frames[0]);applyHeader(s,header);}catch(e){console.warn('[OSM 3.6] cabeçalho visual falhou',e)}
    const chosen=pickFrames(frames,7);setProgress(52,`Lendo ${chosen.length} telas do elenco, uma por vez…`);
    const responses=await mapLimit(chosen,2,async(f,i)=>{setProgress(52+Math.round((i/chosen.length)*32),`Validando tela ${i+1}/${chosen.length}…`);return readFrameRows(f)});
    const lists=responses.map(x=>Array.isArray(x)?x:[]),rows=mergeRows(lists,ocr),check=validateRoster(rows,header);
    if(!check.ok){
      s.rosterValidation36={ok:false,at:new Date().toISOString(),found:rows.length,counts:check.counts,valueSum:check.sum,squadValue:money(header?.squadValue),ratio:check.ratio};
      try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
      const target=document.getElementById('analysisContent');if(target)target.innerHTML=`<div class="card ai-fallback-card"><span class="eyebrow">VALIDAÇÃO DE ELENCO · ${V}</span><h3>Leitura incompleta — não alterei seu elenco</h3><p class="small muted">Encontrei ${rows.length} jogador(es), mas a conferência de quantidade/composição/valor não fechou. O app manteve o elenco anterior em vez de gravar dados errados.</p><div class="fallback-kpis"><div><span>Encontrados</span><b>${rows.length}</b></div><div><span>ATA/MEI/DEF/GOL</span><b>${check.counts.ATA}/${check.counts.MEI}/${check.counts.DEF}/${check.counts.GOL}</b></div><div><span>Soma valores</span><b>${fmt(check.sum)}</b></div></div></div>`;
      setAnalysisRun(s,'market','warning',`Leitura visual incompleta: ${rows.length} jogador(es). Dados antigos preservados.`,{hotfix:V,validation:check});setProgress(100,'Elenco preservado · leitura incompleta');return;
    }
    s.roster=rows;s.myTeam=s.myTeam||{};s.myTeam.playerCount=rows.length;s.myTeam.validatedPlayerCount=rows.length;s.marketPlan=null;s.lastAnalysisAt=new Date().toISOString();s.rosterValidation36={ok:true,at:s.lastAnalysisAt,found:rows.length,counts:check.counts,valueSum:check.sum,ratio:check.ratio};
    applyHeader(s,header);if(s.myTeam?.cash!==null&&s.myTeam?.cash!==undefined){s.coachAI34=s.coachAI34&&typeof s.coachAI34==='object'?s.coachAI34:{};s.coachAI34.budget=money(s.myTeam.cash);}
    try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
    renderMarket();
    const msg=`${rows.length} jogadores validados · ${check.counts.ATA} ATA / ${check.counts.MEI} MEI / ${check.counts.DEF} DEF / ${check.counts.GOL} GOL · caixa ${fmt(s.myTeam.cash)} · elenco ${fmt(s.myTeam.squadValue)}.`;
    const target=document.getElementById('analysisContent');if(target)target.innerHTML=`<div class="card"><span class="eyebrow">ELENCO VALIDADO · ${V}</span><h3>Leitura conferida antes de salvar</h3><p class="small muted">${msg}</p><div class="fallback-kpis"><div><span>Caixa</span><b>${fmt(s.myTeam.cash)}</b></div><div><span>Valor elenco</span><b>${fmt(s.myTeam.squadValue)}</b></div><div><span>Jogadores</span><b>${rows.length}</b></div></div></div>`;
    setAnalysisRun(s,'market','success',msg,{hotfix:V,rosterCount:rows.length,counts:check.counts,cash:money(s.myTeam.cash),squadValue:money(s.myTeam.squadValue)});setProgress(100,'Elenco validado');job('Elenco validado e Diretor recalculado.','done');
  };
  window.v21Analyze=v21Analyze;window.OSM_ANALYSIS_HOTFIX_VERSION=V;
  try{console.info('[OSM] Analysis '+V+' ativo')}catch{}
})();

'use strict';
/* OSM AI Coach Pro — Market Engine 4.3
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
  const VERSION='4.3.0';
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
  async function discoverModels42(key){
    const preferred=['gemini-3.8-flash','gemini-3.7-flash','gemini-3.6-flash','gemini-3.5-flash','gemini-flash-latest','gemini-3.5-flash-lite'];
    try{
      const r=await fetch(`https://generativelanguage.googleapis.com/v1beta/models?key=${encodeURIComponent(key)}`);
      if(!r.ok)throw new Error(`lista de modelos HTTP ${r.status}`);
      const d=await r.json();
      const available=(d.models||[])
        .filter(m=>(m.supportedGenerationMethods||[]).includes('generateContent'))
        .map(m=>String(m.name||'').replace(/^models\//,''))
        .filter(Boolean);
      const flash=available.filter(x=>/flash/i.test(x));
      return [...preferred.filter(x=>available.includes(x)),...flash.filter(x=>!preferred.includes(x))].filter((x,i,a)=>a.indexOf(x)===i);
    }catch(err){
      return preferred;
    }
  }

  async function callModel42(model,key,body,timeoutMs){
    const controller=new AbortController();
    const timer=setTimeout(()=>controller.abort(),timeoutMs);
    try{
      const res=await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,{
        method:'POST',
        headers:{'Content-Type':'application/json','x-goog-api-key':key},
        body:JSON.stringify(body),
        signal:controller.signal
      });
      const raw=await res.text();
      if(!res.ok)throw new Error(`HTTP ${res.status}: ${raw.slice(0,220)}`);
      let data; try{data=JSON.parse(raw)}catch{throw new Error('resposta HTTP não era JSON')}
      const txt=(data?.candidates?.[0]?.content?.parts||[]).map(p=>p.text||'').join('').trim();
      if(!txt)throw new Error('resposta sem conteúdo');
      let parsed; try{parsed=parseJsonText(txt)}catch(err){throw new Error(`JSON inválido: ${String(err?.message||err)}`)}
      return {model,data:parsed};
    }catch(err){
      if(err?.name==='AbortError')throw new Error(`tempo excedido (${Math.round(timeoutMs/1000)}s)`);
      throw err;
    }finally{clearTimeout(timer)}
  }

  async function modelJson(parts,{timeoutMs=45000,maxOutputTokens=10000,mode='visual'}={}){
    const key=localStorage.getItem(API_KEY_STORAGE); if(!key)throw new Error('API Gemini não configurada.');
    const available=await discoverModels42(key);
    const preference=['gemini-flash-latest','gemini-3.8-flash','gemini-3.7-flash','gemini-3.6-flash','gemini-3.5-flash',settings?.model]
      .filter(Boolean);
    const ordered=[...preference.filter(x=>available.includes(x)),...available.filter(x=>!preference.includes(x))]
      .filter((x,i,a)=>a.indexOf(x)===i && !/lite/i.test(x));
    const models=ordered.slice(0,2);
    if(!models.length)throw new Error('Nenhum modelo Flash disponível para esta chave.');

    const body={contents:[{role:'user',parts}],generationConfig:{temperature:0,maxOutputTokens,responseMimeType:'application/json'}};
    const controllers=models.map(()=>new AbortController());
    const timer=setTimeout(()=>controllers.forEach(c=>c.abort()),timeoutMs);
    const errors=[];
    const diag=document.getElementById('analysisDiagnostics');
    if(diag)diag.textContent=`Elenco 4.3 · uma análise · ${models.join(' / ')}`;
    job('Elenco 4.3 · consolidando 3 painéis…');

    const attempt=async(model,controller)=>{
      try{
        const res=await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,{
          method:'POST',headers:{'Content-Type':'application/json','x-goog-api-key':key},body:JSON.stringify(body),signal:controller.signal
        });
        const raw=await res.text();
        if(!res.ok)throw new Error(`${model} HTTP ${res.status}: ${raw.slice(0,180)}`);
        let data; try{data=JSON.parse(raw)}catch{throw new Error(`${model}: resposta HTTP inválida`)}
        const txt=(data?.candidates?.[0]?.content?.parts||[]).map(p=>p.text||'').join('').trim();
        if(!txt)throw new Error(`${model}: resposta vazia`);
        let parsed; try{parsed=parseJsonText(txt)}catch(err){throw new Error(`${model}: JSON inválido`)}
        return {model,data:parsed};
      }catch(err){
        const msg=err?.name==='AbortError'?`${model}: tempo global excedido`:String(err?.message||err);
        errors.push(msg); throw new Error(msg);
      }
    };

    try{
      const promises=models.map((m,i)=>attempt(m,controllers[i]));
      const out=await Promise.any(promises);
      controllers.forEach(c=>c.abort());
      if(diag)diag.textContent=`Elenco 4.3 concluído com ${out.model}.`;
      return out;
    }catch{
      throw new Error(errors.join(' | ')||'Análise do elenco falhou.');
    }finally{clearTimeout(timer);controllers.forEach(c=>c.abort());}
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
    return merged.map(({names,_bestHits,...r})=>({...r,verifiedRoster:true,source:'market_engine_43'}));
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

  async function readRosterPanelsOnce(frames,timeoutMs=45000){
    const chosen=chooseFrames(frames,12);
    if(chosen.length<6)throw new Error(`quadros insuficientes (${chosen.length})`);
    const header=await cropFrame(chosen[0],{x:0,y:0,w:1,h:.46},1280,.92);
    const sheets=[];
    for(let i=0;i<chosen.length;i+=4){
      const sh=await makeVerticalRosterSheet(chosen.slice(i,i+4));
      if(sh)sheets.push(sh);
    }
    const prompt=`OSM 26 Android. Você recebeu 1 imagem de CABEÇALHO e ${sheets.length} painéis verticais, cada painel contendo vários momentos da MESMA rolagem do elenco. Analise tudo junto e consolide cada jogador UMA única vez.

CABEÇALHO:
- cash = dinheiro/caixa disponível no topo. NÃO confundir com valor do elenco.
- squadValue = valor total do elenco.
- overall, goalkeeper, defence, midfield, attack e formation.

TABELA:
Leia Jogador, Idade, Pos., Ata, Def, Med, Estado e Valor. Para cada linha completa:
- name exatamente visível;
- age;
- posCode exatamente visível (GR/DD/DC/DE/MDC/MC/MCO/MD/ME/PL/ED/EE);
- rating principal: GR=goleiro; DD/DC/DE=Def; MDC/MC/MCO/MD/ME=Med; PL/ED/EE=Ata;
- value da última coluna com unidade;
- training=true somente com camisa/indicador laranja;
- forSale=true somente se houver setas/ícone de transferência claramente na linha.

REGRAS: os mesmos jogadores reaparecem em quadros diferentes; remova duplicatas. Se uma linha estiver cortada em um quadro e completa em outro, use a completa. Não invente nem complete por conhecimento externo. Se não estiver legível, null.

Retorne JSON puro exatamente:
{"header":{"cash":null,"squadValue":null,"overall":null,"goalkeeper":null,"defence":null,"midfield":null,"attack":null,"formation":null},"players":[{"name":"","age":null,"posCode":null,"rating":null,"value":null,"training":false,"forSale":false}],"observedPlayerCount":null}`;
    const parts=[{text:prompt},{inlineData:{mimeType:header.mimeType,data:header.base64}}];
    for(const sh of sheets)parts.push({inlineData:{mimeType:sh.mimeType,data:sh.base64}});
    const r=await modelJson(parts,{timeoutMs,maxOutputTokens:12000,mode:'image'});
    return {model:r.model,header:r.data?.header||{},rows:Array.isArray(r.data?.players)?r.data.players:[],observedPlayerCount:num(r.data?.observedPlayerCount),sheetCount:sheets.length};
  }

  async function analyzeMarket(files){
    const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot; state.selectedSlot=slotNo;
    const s=selectedSlot();
    const video=(files||[]).find(f=>String(f.type||'').startsWith('video/'));
    const images=(files||[]).filter(f=>String(f.type||'').startsWith('image/'));
    let frames=[]; let header={}; let roster=[]; let source=''; let technicalError='';
    const started=Date.now();

    try{
      if(video){
        setProgress(8,'Extraindo quadros da rolagem…');
        frames=await v21ExtractVideoFrames(video,16);
      }else if(images.length){
        setProgress(8,'Preparando imagens do elenco…');
        for(const f of images)frames.push(await v21ImageToFrame(f));
      }else throw new Error('Selecione vídeo ou imagens do elenco.');

      v21RenderEvidence(frames);
      setProgress(28,'Montando 3 painéis legíveis do elenco…');
      const remaining=60000-(Date.now()-started);
      if(remaining<8000)throw new Error('tempo global excedido antes da IA');

      const aiBudget=Math.max(8000,Math.min(45000,remaining-1500));
      const result=await readRosterPanelsOnce(frames,aiBudget);
      header=result.header||{};
      roster=consolidate([result.rows||[]]);
      source='paineis_unicos';
      const diag=document.getElementById('analysisDiagnostics');
      if(diag)diag.textContent=`Elenco 4.3 concluído com ${result.model} · ${result.sheetCount} painéis · ${Math.round((Date.now()-started)/1000)}s.`;
    }catch(err){
      technicalError=String(err?.message||err);
      const diag=document.getElementById('analysisDiagnostics');
      if(diag)diag.textContent=`Elenco 4.3 falhou: ${technicalError}`;
    }

    const comp=composition(roster), sum=roster.reduce((a,p)=>a+(money(p.value)||0),0), sq=money(header?.squadValue), issues=[];
    if(roster.length<16)issues.push(`só ${roster.length} jogadores reconhecidos (mínimo seguro 16)`);
    if(comp.ATA<3||comp.MEI<4||comp.DEF<5||comp.GOL<1)issues.push(`composição incompleta ${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL}`);
    if(sq!==null&&roster.length>=16){const ratio=sum/sq;if(ratio<.60||ratio>1.45)issues.push(`soma dos jogadores ${fmtMoney(sum)} incompatível com elenco ${fmtMoney(sq)}`);}
    if(technicalError)issues.push(`falha técnica: ${technicalError}`);
    const validation={ok:issues.length===0,issues,count:roster.length,composition:comp,sumValues:sum,squadValue:sq,source,technicalError,elapsedMs:Date.now()-started};

    if(validation.ok){
      applyValidated(s,roster,header,validation); s.rosterValidation42.source=source; s.rosterValidation42.version=VERSION;
      try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
      if(typeof renderMarket==='function')renderMarket();
      const msg=`${roster.length} jogadores validados · ${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL} · caixa ${fmtMoney(s.myTeam?.cash)} · elenco ${fmtMoney(s.myTeam?.squadValue)}.`;
      const el=document.getElementById('analysisContent');
      if(el)el.innerHTML=`<div class="card" style="margin-top:12px"><span class="eyebrow">ELENCO VALIDADO · 4.3</span><h3>${roster.length} jogadores confirmados</h3><p class="small muted">${esc(msg)}</p><div class="fallback-kpis"><div><span>Caixa</span><b>${esc(fmtMoney(s.myTeam?.cash))}</b></div><div><span>Valor elenco</span><b>${esc(fmtMoney(s.myTeam?.squadValue))}</b></div><div><span>ATA/MEI/DEF/GOL</span><b>${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL}</b></div></div><p class="small muted">Fonte: 3 painéis consolidados · ${Math.round(validation.elapsedMs/1000)}s.</p></div>`;
      setAnalysisRun(s,'market','success',msg,{version:VERSION,validation}); setProgress(100,'Elenco validado'); job('Elenco e finanças atualizados.','done'); return;
    }

    preserveFailed(s,validation,header); try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
    if(typeof renderMarket==='function')renderMarket();
    const el=document.getElementById('analysisContent');
    if(el)el.innerHTML=`<div class="card ai-fallback-card" style="margin-top:12px"><span class="eyebrow">VALIDAÇÃO DE ELENCO · 4.3</span><h3>Leitura incompleta — não alterei seu elenco</h3><p class="small muted">${esc(validation.issues.join(' · '))}</p><div class="fallback-kpis"><div><span>Encontrados</span><b>${validation.count}</b></div><div><span>ATA/MEI/DEF/GOL</span><b>${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL}</b></div><div><span>Tempo</span><b>${Math.round(validation.elapsedMs/1000)}s</b></div></div>${technicalError?`<p class="small muted" style="margin-top:10px">Erro técnico: ${esc(technicalError)}</p>`:''}</div>`;
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
    if(!v?.ok)return `<div class="card market40-card"><span class="eyebrow">DIRETOR IA · 4.3</span><h3>Plano bloqueado até o elenco ficar validado</h3><p class="small muted">A versão 4.3 não usa o elenco antigo/corrompido. Reanalise o vídeo; compras e vendas só aparecem depois que quantidade, composição e valores fecharem.</p><div class="actions"><button class="btn" onclick="showView('analyze');setAnalysisMode('market')">Analisar elenco</button></div></div>`;
    const p=buildDirector(s),c={ATA:p.by.ATA.length,MEI:p.by.MEI.length,DEF:p.by.DEF.length,GOL:p.by.GOL.length};
    const sell=p.sells.length?p.sells.map((x,i)=>`<div class="radar-item"><div><b>${i+1}. ${esc(x.name)}</b><span>${esc(x.position)} · força ${esc(x.rating)} · valor ${esc(x.value)}${x.training?' · treinando':''}</span></div><span>${x.forSale?'Já à venda':'Excedente'}</span></div>`).join(''):'<p class="small muted">Nenhuma venda estrutural necessária agora.</p>';
    const weak=Object.entries(p.weakest).map(([k,x])=>x?`${k}: ${esc(x.name)} ${x.rating}`:`${k}: NI`).join(' · ');
    return `<div class="card market40-card"><div class="section-head compact-head"><div><span class="eyebrow">DIRETOR IA · DADOS REAIS · 4.3</span><h3>Plano baseado somente no vídeo validado</h3></div></div>
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
  }catch(err){console.warn('[Market Engine 4.3 migrate]',err);}

  window.OSM_MARKET_ENGINE_43={version:VERSION,consolidate,validateRoster,buildDirector};
  try{console.info('[OSM] Market Engine '+VERSION+' ativo')}catch{}
})();


'use strict';
/* OSM AI Coach Pro — Market Engine 4.8
   Deterministic OCR.Space table reconstruction using TextOverlay coordinates.
   Replaces the previous market-engine-v47.js file in-place.
*/
(function(){
  const VERSION='4.8.0';
  const OCRSPACE_KEY='osm_ai_coach_ocrspace_key';
  const OPENROUTER_KEY='osm_ai_coach_openrouter_key';
  const GROQ_KEY='osm_ai_coach_groq_key';
  const TARGET={ATA:4,MEI:6,DEF:6,GOL:2};
  const VALID_POS=new Set(Object.keys(TARGET));
  const oldAnalyze=typeof v21Analyze==='function'?v21Analyze:null;
  const oldRenderMarket=typeof renderMarket==='function'?renderMarket:null;

  const esc=v=>String(v??'NI').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[m]));
  const norm=v=>String(v||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/\s+/g,' ').trim();
  const num=v=>{if(v===null||v===undefined||v===''||v==='NI')return null;const x=Number(String(v).replace(',','.'));return Number.isFinite(x)?x:null};
  const money=v=>{
    if(v===null||v===undefined||v==='')return null;
    if(typeof v==='number'&&Number.isFinite(v))return v;
    const m=String(v).replace(/\s/g,'').replace(',','.').match(/([0-9]+(?:\.[0-9]+)?)([KMB])?/i);
    if(!m)return null; let x=Number(m[1]); if(!Number.isFinite(x))return null;
    const u=(m[2]||'').toUpperCase(); if(u==='K')x*=1e3; else if(u==='M')x*=1e6; else if(u==='B')x*=1e9;
    return x;
  };
  const fmtMoney=v=>{
    const x=money(v); if(x===null)return 'NI';
    if(x>=1e9)return (x/1e9).toFixed(x>=1e10?0:1).replace('.',',')+'B';
    if(x>=1e6)return (x/1e6).toFixed(x>=1e8?0:1).replace('.',',')+'M';
    if(x>=1e3)return Math.round(x/1e3)+'K';
    return String(Math.round(x));
  };

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
    if(s.length<2||s.length>45)return null;
    if(/^(jogador|idade|valor|estado|pos|ata|def|med|nac|nome)$/i.test(s))return null;
    if(!/[A-Za-zÀ-ÿ]{2}/.test(s))return null;
    return s;
  }
  function compactName(v){return norm(v).replace(/[^a-z0-9]/g,'')}

  function withAbort(ms){
    const controller=new AbortController();
    const timer=setTimeout(()=>controller.abort(),ms);
    return {controller,clear:()=>clearTimeout(timer)};
  }

  async function loadImage(src){
    if(typeof v21LoadImage==='function')return v21LoadImage(src);
    return new Promise((resolve,reject)=>{const i=new Image();i.onload=()=>resolve(i);i.onerror=reject;i.src=src});
  }

  async function cropFrame(frame,box,maxW=1500,quality=.96){
    const img=await loadImage(frame.dataUrl);
    const sx=Math.max(0,Math.round(img.naturalWidth*box.x));
    const sy=Math.max(0,Math.round(img.naturalHeight*box.y));
    const sw=Math.max(1,Math.round(img.naturalWidth*box.w));
    const sh=Math.max(1,Math.round(img.naturalHeight*box.h));
    const scale=Math.min(1.55,maxW/sw);
    const cw=Math.max(1,Math.round(sw*scale)),ch=Math.max(1,Math.round(sh*scale));
    const c=document.createElement('canvas');c.width=cw;c.height=ch;
    const ctx=c.getContext('2d');
    ctx.imageSmoothingEnabled=true;ctx.imageSmoothingQuality='high';
    ctx.drawImage(img,sx,sy,sw,sh,0,0,cw,ch);
    const dataUrl=c.toDataURL('image/jpeg',quality);
    return {dataUrl,base64:dataUrl.split(',')[1],mimeType:'image/jpeg',width:cw,height:ch,time:frame.time||0};
  }

  function ocrKey(){return String(localStorage.getItem(OCRSPACE_KEY)||'').trim()}

  async function ocrSpace(panel,label,timeoutMs=16000){
    const key=ocrKey(); if(!key)throw new Error('OCR.Space: chave não configurada');
    const guard=withAbort(timeoutMs);
    try{
      const fd=new FormData();
      fd.append('base64Image',`data:${panel.mimeType};base64,${panel.base64}`);
      fd.append('language','auto');
      fd.append('OCREngine','3');
      fd.append('isTable','true');
      fd.append('isOverlayRequired','true');
      fd.append('scale','true');
      const res=await fetch('https://api.ocr.space/parse/image',{method:'POST',headers:{apikey:key},body:fd,signal:guard.controller.signal});
      const raw=await res.text();
      if(!res.ok)throw new Error(`OCR.Space HTTP ${res.status}: ${raw.slice(0,160)}`);
      let data;try{data=JSON.parse(raw)}catch{throw new Error('OCR.Space: resposta inválida')}
      if(data?.IsErroredOnProcessing){
        const em=Array.isArray(data?.ErrorMessage)?data.ErrorMessage.join(' | '):(data?.ErrorMessage||data?.ErrorDetails||'erro');
        throw new Error(`OCR.Space: ${em}`);
      }
      const pr=(data?.ParsedResults||[])[0];
      if(!pr)throw new Error(`OCR.Space: ${label} sem resultado`);
      return {label,text:String(pr.ParsedText||''),overlay:pr.TextOverlay||null,width:panel.width,height:panel.height};
    }catch(err){
      if(err?.name==='AbortError')throw new Error(`OCR.Space: ${label} excedeu ${Math.round(timeoutMs/1000)}s`);
      throw err;
    }finally{guard.clear()}
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

  function overlayLines(block){
    const lines=block?.overlay?.Lines||block?.overlay?.lines||[];
    return lines.map(line=>{
      const words=(line.Words||line.words||[]).map(w=>({
        text:String(w.WordText||w.wordText||w.Text||w.text||'').trim(),
        left:Number(w.Left??w.left??0),
        top:Number(w.Top??w.top??0),
        width:Number(w.Width??w.width??0),
        height:Number(w.Height??w.height??0)
      })).filter(w=>w.text);
      words.sort((a,b)=>a.left-b.left);
      const left=words.length?words[0].left:0;
      const right=words.length?Math.max(...words.map(w=>w.left+w.width)):0;
      const top=words.length?Math.min(...words.map(w=>w.top)):0;
      const h=words.length?Math.max(...words.map(w=>w.height)):0;
      return {words,left,right,top,height:h,text:words.map(w=>w.text).join(' ')};
    }).filter(x=>x.words.length);
  }

  function findHeaderXs(blocks){
    const found={};
    const aliases={
      name:['jogador','player','nome'],
      age:['idade','age'],
      pos:['pos','pos.','posição','position'],
      ata:['ata','att'],
      def:['def'],
      med:['med','mei','mid'],
      value:['valor','value','preço','preco']
    };
    for(const b of blocks){
      for(const line of overlayLines(b)){
        for(const w of line.words){
          const t=norm(w.text).replace(/[.:]/g,'');
          for(const [k,arr] of Object.entries(aliases)){
            if(found[k]!==undefined)continue;
            if(arr.some(a=>t===norm(a).replace(/[.:]/g,'')))found[k]=w.left+w.width/2;
          }
        }
      }
    }
    return found;
  }

  function nearestWord(words,x,tolerance=95){
    let best=null,bd=Infinity;
    for(const w of words){
      const cx=w.left+w.width/2,d=Math.abs(cx-x);
      if(d<bd){best=w;bd=d}
    }
    return bd<=tolerance?best:null;
  }

  function detectPos(words){
    for(const w of words){
      const p=positionFromCode(w.text);
      if(p)return {word:w,position:p,posCode:String(w.text).toUpperCase().replace(/[^A-Z]/g,'')};
    }
    return null;
  }

  function detectMoney(words){
    for(let i=words.length-1;i>=0;i--){
      const t=words[i].text.replace(/\s/g,'');
      if(/^\d+(?:[.,]\d+)?[MKB]$/i.test(t))return {word:words[i],raw:t.toUpperCase()};
    }
    return null;
  }

  function detectAge(words,posX){
    const c=words.filter(w=>w.left<posX).map(w=>({w,n:Number(w.text.replace(/\D/g,''))}))
      .filter(x=>Number.isFinite(x.n)&&x.n>=15&&x.n<=45);
    return c.length?c[c.length-1]:null;
  }

  function detectRating(words,posInfo,headers){
    const nums=words.map(w=>({w,n:Number(w.text.replace(/[^\d]/g,''))}))
      .filter(x=>Number.isFinite(x.n)&&x.n>=40&&x.n<=200);
    if(!nums.length)return null;
    const targetKey=posInfo.position==='ATA'?'ata':posInfo.position==='DEF'?'def':posInfo.position==='MEI'?'med':null;
    if(targetKey&&headers[targetKey]!==undefined){
      let best=null,bd=Infinity;
      for(const x of nums){
        const d=Math.abs((x.w.left+x.w.width/2)-headers[targetKey]);
        if(d<bd){best=x;bd=d}
      }
      if(best&&bd<120)return best;
    }
    // GR usually has one main rating; otherwise choose the right-most plausible stat after position.
    const after=nums.filter(x=>x.w.left>posInfo.word.left);
    return after.length?after[after.length-1]:nums[nums.length-1];
  }

  function rowFromOverlayLine(line,headers){
    const words=line.words;
    const pos=detectPos(words), mv=detectMoney(words);
    if(!pos||!mv)return null;
    const rating=detectRating(words,pos,headers); if(!rating)return null;
    const age=detectAge(words,pos.word.left);
    let nameWords=words.filter(w=>w.left<pos.word.left);
    if(age)nameWords=nameWords.filter(w=>w!==age.w);
    // drop flags/nationality fragments and isolated digits
    nameWords=nameWords.filter(w=>!/^(\d+|[A-Z]{2,3})$/.test(w.text));
    let name=cleanName(nameWords.map(w=>w.text).join(' '));
    if(!name)return null;
    const value=mv.raw;
    return {
      name,age:age?age.n:null,posCode:pos.posCode,position:pos.position,
      rating:rating.n,value,valueNum:money(value),
      training:false,forSale:false,source:'ocrspace_overlay'
    };
  }

  function rowsFromBlocks(blocks){
    const headers=findHeaderXs(blocks);
    const out=[];
    for(const b of blocks){
      for(const line of overlayLines(b)){
        const r=rowFromOverlayLine(line,headers);
        if(r)out.push(r);
      }
    }
    return out;
  }

  function levenshtein(a,b){
    a=compactName(a);b=compactName(b);
    const m=a.length,n=b.length; if(!m)return n;if(!n)return m;
    const dp=Array(n+1).fill(0).map((_,j)=>j);
    for(let i=1;i<=m;i++){
      let prev=dp[0];dp[0]=i;
      for(let j=1;j<=n;j++){
        const tmp=dp[j];
        dp[j]=Math.min(dp[j]+1,dp[j-1]+1,prev+(a[i-1]===b[j-1]?0:1));
        prev=tmp;
      }
    }
    return dp[n];
  }

  function samePlayer(a,b){
    if(a.position!==b.position)return false;
    if(Math.abs(a.rating-b.rating)>1)return false;
    if(a.age!==null&&b.age!==null&&Math.abs(a.age-b.age)>1)return false;
    const av=money(a.value),bv=money(b.value);
    if(av&&bv&&Math.abs(av-bv)>700000)return false;
    const na=compactName(a.name),nb=compactName(b.name);
    if(na===nb)return true;
    const d=levenshtein(na,nb),mx=Math.max(na.length,nb.length);
    return mx>=5 && d<=Math.max(1,Math.floor(mx*.22));
  }

  function betterName(a,b){
    const score=s=>String(s||'').split(/\s+/).filter(Boolean).length*20+String(s||'').length;
    return score(b)>score(a)?b:a;
  }

  function consolidate(rows){
    const out=[];
    for(const r of rows){
      if(!r.name||!VALID_POS.has(r.position)||r.rating<40||r.rating>200||!r.valueNum)continue;
      const hit=out.find(x=>samePlayer(x,r));
      if(hit){
        hit.hits++;hit.name=betterName(hit.name,r.name);
        if(hit.age===null&&r.age!==null)hit.age=r.age;
      }else out.push({...r,hits:1});
    }
    return out.map(r=>({...r,verifiedRoster:true,source:'market_engine_48'}));
  }

  function composition(rows){
    const c={ATA:0,MEI:0,DEF:0,GOL:0}; for(const r of rows)if(c[r.position]!==undefined)c[r.position]++; return c;
  }

  function parseHeaderText(text){
    const all=String(text||'');
    const ms=[...all.matchAll(/(\d+(?:[.,]\d+)?)\s*([MK])\b/ig)].map(m=>({raw:m[0],v:money(m[0])})).filter(x=>x.v!==null);
    let cash=null,squadValue=null;
    const small=ms.filter(x=>x.v>0&&x.v<5e7); if(small.length)cash=small[0].raw;
    const big=ms.filter(x=>x.v>=5e7).sort((a,b)=>b.v-a.v); if(big.length)squadValue=big[0].raw;
    const nums=[...all.matchAll(/\b(\d{2,3})\b/g)].map(m=>Number(m[1])).filter(x=>x>=40&&x<=200);
    return {cash,squadValue,overall:nums.find(x=>x>=50&&x<=150)??null};
  }

  function validate(rows,header){
    const comp=composition(rows),issues=[];
    if(rows.length<16)issues.push(`só ${rows.length} jogadores reconhecidos (mínimo seguro 16)`);
    if(comp.ATA<3||comp.MEI<4||comp.DEF<5||comp.GOL<1)issues.push(`composição incompleta ${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL}`);
    return {ok:issues.length===0,issues,count:rows.length,composition:comp};
  }

  function applyValidated(s,rows,header,val){
    s.roster=rows;s.myTeam=s.myTeam||{};
    s.myTeam.playerCount=rows.length;s.myTeam.validatedPlayerCount=rows.length;
    const cash=money(header.cash),sq=money(header.squadValue);
    if(cash!==null)s.myTeam.cash=cash;if(sq!==null)s.myTeam.squadValue=sq;
    s.rosterValidation42={...val,ok:true,version:VERSION,at:new Date().toISOString(),header:{cash,squadValue:sq}};
    s.lastAnalysisAt=new Date().toISOString();s.marketPlan=null;
  }

  async function analyzeMarket(files){
    const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot;
    state.selectedSlot=slotNo; const s=selectedSlot(); const started=Date.now();
    let frames=[]; const video=(files||[]).find(f=>String(f.type||'').startsWith('video/'));
    const images=(files||[]).filter(f=>String(f.type||'').startsWith('image/'));
    try{
      setProgress(6,'Extraindo quadros da rolagem…');
      if(video)frames=await v21ExtractVideoFrames(video,20);
      else for(const f of images)frames.push(await v21ImageToFrame(f));
      if(!frames.length)throw new Error('Nenhuma mídia válida.');
      v21RenderEvidence(frames);

      const chosen=chooseFrames(frames,9);
      const blocks=[];

      setProgress(18,'OCR.Space lendo cabeçalho…');
      const head=await cropFrame(chosen[0],{x:0,y:0,w:1,h:.46},1500,.96);
      let hb=null; try{hb=await ocrSpace(head,'cabeçalho',16000);blocks.push(hb)}catch(_){}

      for(let i=0;i<chosen.length;i++){
        setProgress(25+Math.round((i/chosen.length)*55),`OCR.Space lendo tabela ${i+1}/${chosen.length}…`);
        // Wider crop: keep full row width and avoid cutting first/last line.
        const crop=await cropFrame(chosen[i],{x:0,y:.38,w:1,h:.60},1600,.97);
        try{
          const b=await ocrSpace(crop,`tabela ${i+1}`,16000);
          if(b?.overlay?.Lines?.length)blocks.push(b);
        }catch(_){}
      }
      const tableBlocks=blocks.filter(b=>/^tabela/.test(b.label));
      if(tableBlocks.length<4)throw new Error(`OCR.Space retornou poucos trechos úteis (${tableBlocks.length})`);

      setProgress(84,'Reconstruindo linhas pelas coordenadas…');
      const rawRows=rowsFromBlocks(tableBlocks);
      const roster=consolidate(rawRows);
      const header=parseHeaderText(hb?.text||'');
      const val=validate(roster,header);
      const comp=val.composition;

      const diag=document.getElementById('analysisDiagnostics');
      if(diag)diag.textContent=`Elenco 4.8 · OCR.Space overlay · ${tableBlocks.length} trechos · ${rawRows.length} linhas candidatas · ${roster.length} jogadores únicos · ${Math.round((Date.now()-started)/1000)}s`;

      if(val.ok){
        applyValidated(s,roster,header,val);
        localStorage.setItem(STATE_KEY,JSON.stringify(state));
        if(typeof renderMarket==='function')renderMarket();
        const el=document.getElementById('analysisContent');
        if(el)el.innerHTML=`<div class="card" style="margin-top:12px"><span class="eyebrow">ELENCO VALIDADO · 4.8</span><h3>${roster.length} jogadores confirmados</h3><p class="small muted">Reconstrução pela posição visual das colunas, sem IA generativa para adivinhar linhas.</p><div class="fallback-kpis"><div><span>Encontrados</span><b>${roster.length}</b></div><div><span>ATA/MEI/DEF/GOL</span><b>${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL}</b></div><div><span>Tempo</span><b>${Math.round((Date.now()-started)/1000)}s</b></div></div></div>`;
        setAnalysisRun(s,'market','success',`${roster.length} jogadores validados`,{version:VERSION,validation:val});
        setProgress(100,'Elenco validado');job('Elenco atualizado.','done');return;
      }

      s.rosterValidation42={...val,ok:false,version:VERSION,at:new Date().toISOString()};
      localStorage.setItem(STATE_KEY,JSON.stringify(state));
      const el=document.getElementById('analysisContent');
      if(el)el.innerHTML=`<div class="card ai-fallback-card" style="margin-top:12px"><span class="eyebrow">VALIDAÇÃO DE ELENCO · 4.8</span><h3>Leitura incompleta — não alterei seu elenco</h3><p class="small muted">${esc(val.issues.join(' · '))}</p><div class="fallback-kpis"><div><span>Encontrados</span><b>${roster.length}</b></div><div><span>ATA/MEI/DEF/GOL</span><b>${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL}</b></div><div><span>Linhas candidatas</span><b>${rawRows.length}</b></div></div></div>`;
      setAnalysisRun(s,'market','warning',`Leitura incompleta: ${val.issues.join('; ')}`,{version:VERSION,validation:val});
      setProgress(100,'Leitura incompleta');job('Elenco não foi alterado porque a validação não fechou.','done');
    }catch(err){
      const msg=String(err?.message||err);
      const el=document.getElementById('analysisContent');
      if(el)el.innerHTML=`<div class="card ai-fallback-card" style="margin-top:12px"><span class="eyebrow">ELENCO · 4.8</span><h3>Falha na leitura</h3><p class="small muted">${esc(msg)}</p></div>`;
      setProgress(100,'Falha na leitura');job(msg,'error');
    }
  }

  if(oldAnalyze){
    v21Analyze=async function(files){if(analysisMode==='market')return analyzeMarket(files);return oldAnalyze(files)};
    window.v21Analyze=v21Analyze;
  }

  function buildDirector(s){
    const rows=Array.isArray(s?.roster)?s.roster.filter(p=>p?.verifiedRoster&&VALID_POS.has(p.position)): [];
    const by={ATA:[],MEI:[],DEF:[],GOL:[]};rows.forEach(p=>by[p.position].push(p));
    for(const k in by)by[k].sort((a,b)=>a.rating-b.rating);
    const sells=[];
    for(const [k,target] of Object.entries(TARGET)){
      const eligible=by[k].filter(p=>!p.training);
      const excess=Math.max(0,eligible.length-target);
      eligible.slice(0,excess).forEach(p=>sells.push(p));
    }
    return {rows,by,sells:sells.slice(0,4),cash:money(s?.myTeam?.cash),sq:money(s?.myTeam?.squadValue)};
  }

  if(oldRenderMarket){
    renderMarket=function(){
      const out=oldRenderMarket.apply(this,arguments);const s=selectedSlot(),root=document.getElementById('marketContent');
      if(!root||!s)return out;
      root.querySelectorAll('.market48-director,.market40-card,.coach30-market,.coach34-director,.director36,.finance35').forEach(x=>x.remove());
      if(!s?.rosterValidation42?.ok){
        root.insertAdjacentHTML('afterbegin',`<div class="card market48-director"><span class="eyebrow">DIRETOR IA · 4.8</span><h3>Plano bloqueado até validar o elenco</h3><p class="small muted">O Diretor não usa elenco parcial. Reanalise o vídeo; quando a tabela fechar, compras e vendas são recalculadas com os dados reais.</p></div>`);
        return out;
      }
      const d=buildDirector(s),c={ATA:d.by.ATA.length,MEI:d.by.MEI.length,DEF:d.by.DEF.length,GOL:d.by.GOL.length};
      const sell=d.sells.length?d.sells.map((p,i)=>`<div class="radar-item"><div><b>${i+1}. ${esc(p.name)}</b><span>${p.position} · força ${p.rating} · ${esc(p.value)}</span></div></div>`).join(''):'<p class="small muted">Nenhuma venda estrutural necessária agora.</p>';
      root.insertAdjacentHTML('afterbegin',`<div class="card market48-director"><span class="eyebrow">DIRETOR IA · 4.8</span><h3>Plano com elenco validado</h3><div class="coach30-kpis"><div><span>Caixa</span><b>${esc(fmtMoney(d.cash))}</b></div><div><span>Valor elenco</span><b>${esc(fmtMoney(d.sq))}</b></div><div><span>Jogadores</span><b>${d.rows.length}</b></div><div><span>ATA/MEI/DEF/GOL</span><b>${c.ATA}/${c.MEI}/${c.DEF}/${c.GOL}</b></div></div><div class="reason-box"><b>Vendas estruturais</b>${sell}</div></div>`);
      return out;
    };
    window.renderMarket=renderMarket;
  }

  function injectSettings(){
    const view=document.getElementById('view-settings');if(!view||document.getElementById('market48Settings'))return;
    const card=document.createElement('div');card.className='card form';card.id='market48Settings';card.style.marginTop='12px';
    card.innerHTML=`<span class="eyebrow">OCR DO ELENCO · 4.8</span><h3>OCR.Space</h3><p class="small muted">A leitura do elenco usa coordenadas de tabela do OCR.Space. OpenRouter/Groq não são necessários para reconstruir as linhas.</p><label>OCR.Space API Key<input id="ocrSpaceKey48" type="password" autocomplete="off" placeholder="Chave OCR.Space"></label><div class="actions"><button class="btn" id="saveOcr48">Salvar OCR</button></div>`;
    view.appendChild(card);
    const inp=document.getElementById('ocrSpaceKey48');inp.value=localStorage.getItem(OCRSPACE_KEY)||'';
    document.getElementById('saveOcr48').onclick=()=>{localStorage.setItem(OCRSPACE_KEY,inp.value.trim());toast('Chave OCR.Space salva')};
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',()=>setTimeout(injectSettings,300));else setTimeout(injectSettings,300);

  window.OSM_MARKET_ENGINE_48={version:VERSION};
  try{console.info('[OSM] Market Engine 4.8 ativo')}catch{}
})();

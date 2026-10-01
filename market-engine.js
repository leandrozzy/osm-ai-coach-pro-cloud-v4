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
  const VERSION='7.0.0';
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

  function levenshtein(a,b){
    a=compactName(a); b=compactName(b);
    if(a===b)return 0;
    if(!a)return b.length;
    if(!b)return a.length;
    const prev=Array.from({length:b.length+1},(_,i)=>i);
    for(let i=1;i<=a.length;i++){
      let left=i,diag=i-1;
      for(let j=1;j<=b.length;j++){
        const up=prev[j];
        const cur=Math.min(
          up+1,
          left+1,
          diag+(a[i-1]===b[j-1]?0:1)
        );
        prev[j]=cur; diag=up; left=cur;
      }
      prev[0]=i;
    }
    return prev[b.length];
  }

  function nameSimilarity(a,b){
    const x=compactName(a),y=compactName(b);
    if(!x||!y)return 0;
    if(x===y)return 1;
    if(x.includes(y)||y.includes(x))return Math.min(x.length,y.length)/Math.max(x.length,y.length);
    const d=levenshtein(x,y);
    return Math.max(0,1-d/Math.max(x.length,y.length,1));
  }

  function likelySamePlayer(a,b){
    if(!a||!b||a.position!==b.position)return false;

    const rd=Math.abs(Number(a.rating)-Number(b.rating));
    const ad=(a.age==null||b.age==null)?99:Math.abs(Number(a.age)-Number(b.age));
    const av=money(a.value),bv=money(b.value);
    const valueClose=(av!=null&&bv!=null)
      ? (Math.abs(av-bv)<=900000 || Math.abs(av-bv)/Math.max(av,bv,1)<=0.09)
      : false;
    const ns=nameSimilarity(a.name,b.name);

    // Nome muito parecido + pelo menos um número próximo.
    if(ns>=0.78 && (rd<=2 || ad<=1 || valueClose))return true;

    // Mesmo jogador com nome OCR ruim: os três dados numéricos batem.
    if(rd<=1 && ad<=1 && valueClose)return true;

    // OCR pode perder idade; força+valor muito próximos e nome ainda razoável.
    if(rd<=1 && valueClose && ns>=0.48)return true;

    return false;
  }

  function mergePlayerInto(target,source){
    target.name=betterName(target.name,source.name);
    target.training=target.training||source.training;
    target.forSale=target.forSale||source.forSale;
    target.hits=(target.hits||1)+(source.hits||1);
    // Mantém os números da observação com mais evidências.
    if((source.hits||1)>(target._bestHits||target.hits||1)){
      target.rating=source.rating;
      target.age=source.age;
      target.value=source.value;
      target.valueNum=source.valueNum;
      target.posCode=source.posCode;
      target._bestHits=source.hits||1;
    }
    return target;
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


  /* =========================================================
     V7 STABLE CORE — roster video stays at native resolution.
     Partida/Calendário continue using the old lightweight extractor.
     ========================================================= */
  async function stableSeekVideo(v,t){
    return new Promise(resolve=>{
      let done=false;
      const finish=()=>{if(done)return;done=true;v.removeEventListener('seeked',finish);resolve();};
      v.addEventListener('seeked',finish,{once:true});
      try{v.currentTime=t;}catch(_){finish();}
      setTimeout(finish,1200);
    });
  }

  function stableThumbFromCanvas(canvas){
    const tw=96,th=42,c=document.createElement('canvas');c.width=tw;c.height=th;
    const x=c.getContext('2d',{willReadFrequently:true});x.drawImage(canvas,0,0,tw,th);
    const d=x.getImageData(0,0,tw,th).data,thumb=new Uint8Array(tw*th);let k=0;
    for(let i=0;i<d.length;i+=4)thumb[k++]=Math.round(.299*d[i]+.587*d[i+1]+.114*d[i+2]);
    return thumb;
  }

  function stableThumbDiff(a,b){
    if(!a||!b||a.length!==b.length)return 999;
    let sum=0;for(let i=0;i<a.length;i++)sum+=Math.abs(a[i]-b[i]);
    return sum/a.length;
  }

  async function stableCaptureNativeFrame(v,t,name){
    const maxW=2560;
    const nativeW=v.videoWidth||1920,nativeH=v.videoHeight||1080;
    const scale=Math.min(1,maxW/nativeW);
    const W=Math.max(640,Math.round(nativeW*scale)),H=Math.max(360,Math.round(nativeH*scale));
    const c=document.createElement('canvas');c.width=W;c.height=H;
    const x=c.getContext('2d');x.drawImage(v,0,0,W,H);
    const thumb=stableThumbFromCanvas(c);
    let dataUrl=c.toDataURL('image/jpeg',.91);
    return {dataUrl,base64:dataUrl.split(',')[1],mimeType:'image/jpeg',time:t,name,thumb,nativeWidth:W,nativeHeight:H,score:0};
  }

  async function extractNativeRosterFrames(file,maxFrames=34){
    const url=URL.createObjectURL(file),v=document.createElement('video');
    v.src=url;v.muted=true;v.playsInline=true;v.preload='metadata';
    try{
      await new Promise((res,rej)=>{v.onloadedmetadata=res;v.onerror=()=>rej(new Error('Não consegui abrir o vídeo do elenco.'));});
      const dur=Math.max(.25,v.duration||1);
      const sampleCount=Math.max(24,Math.min(42,maxFrames+8));
      const frames=[];let prev=null;
      for(let i=0;i<sampleCount;i++){
        const t=Math.min(dur-.05,Math.max(.05,dur*(i+.35)/sampleCount));
        await stableSeekVideo(v,t);
        const f=await stableCaptureNativeFrame(v,t,file.name);
        const diff=prev?stableThumbDiff(prev,f.thumb):999;f.score=diff;prev=f.thumb;
        // Keep first frames and any meaningful scroll change. Tiny changes are redundant.
        if(frames.length<4||diff>=2.25)frames.push(f);
      }
      if(frames.length<=maxFrames)return frames;
      // Even temporal coverage; do not keep dozens of near-identical thumbnails.
      const out=[];
      for(let i=0;i<maxFrames;i++){
        const idx=Math.round(i*(frames.length-1)/Math.max(1,maxFrames-1));
        if(frames[idx]&&!out.includes(frames[idx]))out.push(frames[idx]);
      }
      return out;
    } finally {URL.revokeObjectURL(url);}
  }

  async function buildSingleRowImageV7(rep,enhanced=false){
    const img=await v21LoadImage(rep.frame.dataUrl),W=rep.W||img.naturalWidth,H=rep.H||img.naturalHeight;
    // Remove only the extreme left margin; retain name, age, position, 3 attributes and value.
    const sx=Math.round(W*.018),sw=Math.round(W*.972);
    const sy=Math.max(0,Math.round(rep.cy-H*.036));
    const sh=Math.min(Math.round(H*.072),H-sy);
    const targetW=2200,targetH=150;
    const c=document.createElement('canvas');c.width=targetW;c.height=targetH;
    const x=c.getContext('2d',{willReadFrequently:enhanced});
    x.fillStyle='#fff';x.fillRect(0,0,targetW,targetH);
    x.drawImage(img,sx,sy,sw,sh,0,0,targetW,targetH);
    if(enhanced){
      const id=x.getImageData(0,0,targetW,targetH),d=id.data;
      for(let i=0;i<d.length;i+=4){
        const g=.299*d[i]+.587*d[i+1]+.114*d[i+2];
        // High contrast but preserve antialiasing around text.
        const y=g<125?20:g>225?250:Math.max(35,Math.min(245,(g-125)*2.3+20));
        d[i]=d[i+1]=d[i+2]=y;
      }
      x.putImageData(id,0,0);
    }
    const dataUrl=c.toDataURL('image/jpeg',enhanced?.94:.92),b64=dataUrl.split(',')[1];
    return {dataUrl,base64:b64,mimeType:'image/jpeg',approxBytes:Math.round(b64.length*.75)};
  }

  async function readRowsLocalTesseract(reps){
    if(!window.Tesseract)return [];
    let worker=null;const out=[];
    try{
      try{worker=await Tesseract.createWorker('eng',1);}catch(_){return [];}
      try{await worker.setParameters({tessedit_pageseg_mode:'7',preserve_interword_spaces:'1'});}catch(_){}
      for(let i=0;i<reps.length;i++){
        setProgress(33+Math.round((i/Math.max(1,reps.length))*28),`Leitura local em alta resolução ${i+1}/${reps.length}…`);
        try{
          const img=await buildSingleRowImageV7(reps[i],false);
          const r=await worker.recognize(img.dataUrl),text=String(r?.data?.text||'').replace(/\s+/g,' ').trim();
          const row=parseSingleRowText(text,i+1);
          if(row){row.training=reps[i]?.training===true;row._ocrConfidence=Number(r?.data?.confidence)||0;row._rawText=text;out.push(row);}
        }catch(_){}
      }
    } finally {try{if(worker)await worker.terminate();}catch(_){}}
    return out;
  }

  async function remoteReadDetectedRowV7(rep,rowId,engine='2',enhanced=false){
    const img=await buildSingleRowImageV7(rep,enhanced);
    const block=await ocrSpaceImage(img,`V7 jogador ${rowId}`,engine==='3'?17000:14000,engine);
    let row=parseSingleRowText(block.text,rowId);
    if(!row)row=await structureSingleRow(block.text,rowId);
    if(row){row.training=rep.training===true;row._ocrConfidence=engine==='3'?88:84;row._rawText=block.text;row.source=`v7_row_e${engine}`;}
    return {row,text:block.text};
  }

  function rosterValueTolerance(squadValue,rowCount){
    // OSM shows player values rounded; allow rounding, not a missing real player.
    // At ~18-25 rows, max expected rounding drift is around 1M.
    return Math.max(900000,Math.min(1600000,(rowCount||18)*65000),Math.abs(squadValue||0)*.006);
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


  function sectionIndexRange(blocks,target){
    let current='ATA';
    let first=-1,last=-1;
    for(let i=0;i<(blocks||[]).length;i++){
      const lines=String(blocks[i]?.text||'').split(/\r?\n/);
      let touched=false;
      for(const line of lines){
        const sec=normalizedSectionName(line);
        if(sec)current=sec;
        if(current===target)touched=true;
        const explicit=explicitBroadPosition(line);
        if(explicit===target)touched=true;
      }
      if(touched){
        if(first<0)first=i;
        last=i;
      }
    }
    return {first,last};
  }


  function rowsFromKnownSection(blocks,target){
    const rows=[];
    for(const block of (blocks||[])){
      for(const raw of String(block?.text||'').split(/\r?\n/)){
        const explicit=explicitBroadPosition(raw);
        if(explicit && explicit!==target)continue;
        const row=parseRowFromSectionLine(raw,target);
        if(row && row.position===target)rows.push(row);
      }
    }
    return rows;
  }

  async function targetedSectionRecovery(chosen,tableBlocks,existingRows,target){
    const range=sectionIndexRange(tableBlocks,target);

    // Mapeia o bloco OCR "rolagem NN" de volta ao quadro escolhido.
    const candidateIdx=new Set();
    if(range.first>=0){
      for(let i=Math.max(0,range.first-1);i<=Math.min(chosen.length-1,range.last+1);i++)candidateIdx.add(i);
    }else{
      // Fallback pela ordem normal da lista: ATA -> MEI -> DEF -> GOL.
      const from=target==='DEF'?Math.floor(chosen.length*.45):target==='MEI'?Math.floor(chosen.length*.20):0;
      const to=target==='DEF'?Math.ceil(chosen.length*.88):target==='MEI'?Math.ceil(chosen.length*.62):chosen.length-1;
      for(let i=from;i<=Math.min(chosen.length-1,to);i++)candidateIdx.add(i);
    }

    const frames=[...candidateIdx].sort((a,b)=>a-b).map(i=>chosen[i]).filter(Boolean);
    if(!frames.length)return {rows:existingRows,blocks:[],added:0};

    const recovery=[];
    // Dois recortes verticais sobrepostos por quadro: evita perder jogador na borda.
    for(let base=0;base<frames.length;base+=2){
      setProgress(82+Math.round((base/Math.max(1,frames.length))*10),
        `Recuperando ${target} ${Math.min(base+2,frames.length)}/${frames.length}…`);
      const batch=frames.slice(base,base+2);
      const tasks=[];
      batch.forEach((f,bi)=>{
        tasks.push((async()=>{
          const crop=await cropForFreeOcr(f,{x:0,y:.30,w:1,h:.43});
          return ocrSpaceImage(crop,`rec-${target}-${base+bi+1}-top`,18000,'2');
        })());
        tasks.push((async()=>{
          const crop=await cropForFreeOcr(f,{x:0,y:.50,w:1,h:.48});
          return ocrSpaceImage(crop,`rec-${target}-${base+bi+1}-bottom`,18000,'2');
        })());
      });
      const settled=await Promise.allSettled(tasks);
      for(const r of settled)if(r.status==='fulfilled'&&r.value?.text)recovery.push(r.value);
    }

    if(!recovery.length)return {rows:existingRows,blocks:[],added:0};

    // REGRA 5.7: uma recuperação de DEF só pode acrescentar DEF,
    // uma recuperação de MEI só pode acrescentar MEI, etc.
    // Não reenviamos a lista inteira para a IA porque isso gerava duplicatas.
    const candidates=rowsFromKnownSection(recovery,target);

    const before=(existingRows||[]).length;
    const merged=consolidate([
      existingRows||[],
      candidates.filter(r=>r.position===target)
    ]);

    const addedRows=merged.filter(m=>
      m.position===target &&
      !(existingRows||[]).some(old=>likelySamePlayer(old,m))
    );

    return {
      rows:merged,
      blocks:recovery,
      added:Math.max(0,merged.length-before),
      candidates:candidates.length,
      addedRows
    };
  }


  function moneyFromText(text){
    const m=String(text||'').match(/(\d+(?:[.,]\d+)?)\s*([MK])\b/i);
    return m?(m[1].replace('.',',')+m[2].toUpperCase()):null;
  }

  function meanStdFeature(data){
    const n=data.length;
    let sum=0;
    for(let i=0;i<n;i++)sum+=data[i];
    const mean=sum/Math.max(1,n);
    let v=0;
    for(let i=0;i<n;i++){const d=data[i]-mean;v+=d*d;}
    const std=Math.sqrt(v/Math.max(1,n))||1;
    const out=new Float32Array(n);
    for(let i=0;i<n;i++)out[i]=(data[i]-mean)/std;
    return out;
  }

  function featureMse(a,b){
    if(!a||!b||a.length!==b.length)return 999;
    let s=0;
    for(let i=0;i<a.length;i++){const d=a[i]-b[i];s+=d*d;}
    return s/a.length;
  }

  function rowDistance(a,b){
    return featureMse(a.nameFeat,b.nameFeat)*.60+
           featureMse(a.numFeat,b.numFeat)*.40;
  }

  function canvasGrayFeature(img,sx,sy,sw,sh,w=48,h=16){
    const c=document.createElement('canvas');
    c.width=w;c.height=h;
    const x=c.getContext('2d',{willReadFrequently:true});
    x.fillStyle='#fff';x.fillRect(0,0,w,h);
    x.drawImage(img,sx,sy,sw,sh,0,0,w,h);
    const d=x.getImageData(0,0,w,h).data;
    const gray=new Float32Array(w*h);
    let k=0;
    for(let i=0;i<d.length;i+=4){
      gray[k++]=.299*d[i]+.587*d[i+1]+.114*d[i+2];
    }
    return meanStdFeature(gray);
  }

  function rowSharpness(img,sx,sy,sw,sh){
    const c=document.createElement('canvas');
    c.width=96;c.height=18;
    const x=c.getContext('2d',{willReadFrequently:true});
    x.drawImage(img,sx,sy,sw,sh,0,0,96,18);
    const d=x.getImageData(0,0,96,18).data;
    const g=new Float32Array(96*18);
    let k=0;
    for(let i=0;i<d.length;i+=4)g[k++]=.299*d[i]+.587*d[i+1]+.114*d[i+2];
    let edge=0,n=0;
    for(let y=1;y<17;y++){
      for(let xx=1;xx<95;xx++){
        const i=y*96+xx;
        edge+=Math.abs(g[i]-g[i-1])+Math.abs(g[i]-g[i-96]);
        n+=2;
      }
    }
    return edge/Math.max(1,n);
  }

  async function detectRowsInFrame(frame,frameIndex){
    const img=await v21LoadImage(frame.dataUrl);
    const W=img.naturalWidth,H=img.naturalHeight;
    const stripW=Math.max(80,Math.round(W*.058));
    const c=document.createElement('canvas');
    c.width=stripW;c.height=H;
    const x=c.getContext('2d',{willReadFrequently:true});
    x.drawImage(img,0,0,stripW,H,0,0,stripW,H);
    const d=x.getImageData(0,0,stripW,H).data;

    const score=new Uint16Array(H);
    for(let y=0;y<H;y++){
      let count=0;
      for(let xx=0;xx<stripW;xx++){
        const i=(y*stripW+xx)*4;
        const r=d[i],g=d[i+1],b=d[i+2];
        const mx=Math.max(r,g,b),mn=Math.min(r,g,b);
        const sat=mx-mn,gray=(r+g+b)/3;
        if((sat>45&&mx>80)||gray<160)count++;
      }
      score[y]=count;
    }

    const threshold=Math.max(18,Math.round(stripW*.18));
    const gap=Math.max(2,Math.round(H*.003));
    const minH=Math.max(14,Math.round(H*.018));
    const maxH=Math.max(42,Math.round(H*.060));
    const minY=Math.round(H*.08);

    const active=[];
    for(let y=minY;y<H;y++)if(score[y]>threshold)active.push(y);
    const groups=[];
    if(active.length){
      let a=active[0],p=active[0];
      for(let i=1;i<active.length;i++){
        const y=active[i];
        if(y-p>gap){
          if(p-a>=minH&&p-a<=maxH)groups.push([a,p]);
          a=y;
        }
        p=y;
      }
      if(p-a>=minH&&p-a<=maxH)groups.push([a,p]);
    }

    const out=[];
    for(const [a,b] of groups){
      const cy=(a+b)/2;
      const rowH=Math.round(H*.086);
      const sy=Math.max(0,Math.round(cy-rowH/2));
      const sh=Math.min(rowH,H-sy);

      const nameFeat=canvasGrayFeature(
        img,
        Math.round(W*.04),sy,
        Math.round(W*.26),sh
      );
      const numFeat=canvasGrayFeature(
        img,
        Math.round(W*.54),sy,
        Math.round(W*.43),sh
      );
      const sharp=rowSharpness(
        img,
        Math.round(W*.04),sy,
        Math.round(W*.93),sh
      );

      // Camisa laranja = treinamento.
      const jc=document.createElement('canvas');
      jc.width=80;jc.height=70;
      const jx=jc.getContext('2d',{willReadFrequently:true});
      const jsx=0,jsy=Math.max(0,Math.round(cy-H*.035));
      const jsw=Math.round(W*.055),jsh=Math.round(H*.07);
      jx.drawImage(img,jsx,jsy,jsw,jsh,0,0,80,70);
      const jd=jx.getImageData(0,0,80,70).data;
      let orange=0;
      for(let i=0;i<jd.length;i+=4){
        const r=jd[i],g=jd[i+1],b2=jd[i+2];
        if(r>175&&g>65&&g<190&&b2<95)orange++;
      }

      out.push({
        frame,frameIndex,cy,W,H,sy,sh,
        nameFeat,numFeat,sharpness:sharp,
        training:orange>45
      });
    }
    return out;
  }

  async function collectUniqueVisualRows(frames){
    const sampled=chooseFrames(frames,24);
    const candidates=[];
    for(let i=0;i<sampled.length;i++){
      const rows=await detectRowsInFrame(sampled[i],i);
      candidates.push(...rows);
    }
    if(!candidates.length)throw new Error('Não consegui detectar linhas de jogadores no vídeo.');

    // Conservador: prefere sobrar uma duplicata a fundir dois jogadores diferentes.
    const TH=.022;
    const clusters=[];
    for(const cand of candidates){
      let best=null,bestD=999;
      for(const cl of clusters){
        let d=999;
        for(const m of cl.members)d=Math.min(d,rowDistance(cand,m));
        if(d<bestD){bestD=d;best=cl;}
      }
      if(best&&bestD<TH){
        best.members.push(cand);
        if(cand.sharpness>best.rep.sharpness)best.rep=cand;
      }else{
        clusters.push({members:[cand],rep:cand});
      }
    }

    // Remove clusters que apareceram só como ruído e não parecem uma linha estável.
    // Mantemos singleton se for muito nítido porque primeiro/último jogador pode aparecer uma vez.
    const reps=clusters
      .filter(cl=>cl.members.length>=2 || cl.rep.sharpness>=5.5)
      .map((cl,i)=>({...cl.rep,visualHits:cl.members.length,rowId:i+1}))
      .sort((a,b)=>(a.frame.time||0)-(b.frame.time||0) || a.cy-b.cy);

    if(reps.length<8)throw new Error(`Só ${reps.length} linhas visuais únicas foram detectadas.`);
    if(reps.length>35)throw new Error(`Detecção gerou ${reps.length} linhas candidatas; vídeo/rolagem instável.`);
    return {sampled,candidates,clusters,reps};
  }


  async function buildSingleRowImage(rep){
    const img=await v21LoadImage(rep.frame.dataUrl);
    const W=rep.W,H=rep.H;

    // A tabela útil começa após a camisa e termina no valor.
    // Mantemos idade, posição, Ata/Def/Med e valor.
    const sx=Math.round(W*.025);
    const sw=Math.round(W*.955);
    const sy=Math.max(0,Math.round(rep.cy-H*.040));
    const sh=Math.min(Math.round(H*.080),H-sy);

    const c=document.createElement('canvas');
    c.width=1850;c.height=125;
    const x=c.getContext('2d');
    x.fillStyle='#fff';x.fillRect(0,0,c.width,c.height);
    x.drawImage(img,sx,sy,sw,sh,0,0,c.width,c.height);

    let dataUrl=c.toDataURL('image/jpeg',.92);
    let b64=dataUrl.split(',')[1];
    if(b64.length*.75>700000){
      dataUrl=c.toDataURL('image/jpeg',.80);
      b64=dataUrl.split(',')[1];
    }
    return {
      dataUrl,
      base64:b64,
      mimeType:'image/jpeg',
      approxBytes:Math.round(b64.length*.75)
    };
  }

  function parseSingleRowText(text,rowId){
    const raw=String(text||'').replace(/[|]+/g,' ').replace(/\s+/g,' ').trim();
    if(!raw)return null;

    const moneyMatch=raw.match(/(\d+(?:[.,]\d+)?)\s*([MK])\b/i);
    if(!moneyMatch)return null;

    const posMatch=raw.match(/(?:^|\s)(GR|GK|GOL|POR|DD|DC|DE|DF|DEF|ZAG|CB|RB|LB|MDC|MC|MCO|MD|ME|MF|MID|VOL|CM|CDM|CAM|LM|RM|PL|ED|EE|ATA|ATT|FW|FWD|ST|CA|PE|PD|LW|RW|CF)(?=\s|$)/i);
    if(!posMatch)return null;

    const position=positionFromCode(posMatch[1]);
    if(!position)return null;

    const beforePos=raw.slice(0,posMatch.index).trim();

    // Idade é o último número 15-45 antes da posição.
    const ageMatches=[...beforePos.matchAll(/\b(\d{2})\b/g)]
      .map(m=>({n:Number(m[1]),i:m.index}))
      .filter(x=>x.n>=15&&x.n<=45);
    const ageObj=ageMatches.length?ageMatches[ageMatches.length-1]:null;
    const age=ageObj?.n??null;

    let name=ageObj?beforePos.slice(0,ageObj.i):beforePos;
    name=name.replace(/^[^A-Za-zÀ-ÿ]+/,'').trim();
    name=cleanName(name);
    if(!name)return null;

    const statText=raw.slice(posMatch.index+posMatch[0].length,moneyMatch.index);
    const nums=[...statText.matchAll(/\b(\d{1,3})\b/g)]
      .map(m=>Number(m[1]))
      .filter(n=>n>=0&&n<=200);

    if(nums.length<2)return null;

    const attack=nums[0]??null;
    const defence=nums[1]??null;
    const midfield=nums[2]??null;

    let rating=null;
    if(position==='ATA')rating=attack;
    else if(position==='DEF')rating=defence;
    else if(position==='MEI')rating=midfield;
    else if(position==='GOL')rating=defence;

    if(rating==null||rating<40||rating>200)return null;

    return {
      rowId,
      name,
      age,
      position,
      posCode:String(posMatch[1]).toUpperCase(),
      rating,
      attack,
      defence,
      midfield,
      value:moneyMatch[1].replace('.',',')+moneyMatch[2].toUpperCase(),
      training:false,
      forSale:false,
      source:'single_row_ocr'
    };
  }

  async function structureSingleRow(text,rowId){
    const prompt=`Uma única linha de jogador do elenco OSM 26 foi lida por OCR.
Não invente. Extraia somente o que estiver presente.

Retorne JSON puro:
{"name":null,"age":null,"posCode":null,"attack":null,"defence":null,"midfield":null,"value":null}

Regras:
- posCode: GR/DD/DC/DE/MDC/MC/MCO/MD/ME/PL/ED/EE ou equivalente realmente presente.
- attack, defence, midfield são as colunas Ata/Def/Med.
- value é o valor monetário final.
- se um campo não estiver legível, null.

OCR:
${text}`;

    const attempts=[];
    if(backupKeys().groq)attempts.push(groqTextJson(prompt));
    if(backupKeys().openrouter)attempts.push(openRouterTextJson(prompt));
    if(!attempts.length)return null;

    try{
      const r=await Promise.any(attempts);
      const v=r?.data||{};
      const position=positionFromCode(v.posCode);
      if(!position)return null;
      const attack=num(v.attack),defence=num(v.defence),midfield=num(v.midfield);
      let rating=position==='ATA'?attack:position==='DEF'?defence:position==='MEI'?midfield:defence;
      if(!v.name||rating==null||!v.value)return null;
      return {
        rowId,
        name:v.name,
        age:num(v.age),
        position,
        posCode:v.posCode,
        rating,
        attack,
        defence,
        midfield,
        value:v.value,
        training:false,
        forSale:false,
        source:'single_row_ai'
      };
    }catch{
      return null;
    }
  }

  async function readSingleDetectedRow(rep,rowId){
    const img=await buildSingleRowImage(rep);

    // E2 primeiro. Como é uma faixa de uma linha só, tende a ser muito mais estável.
    let block;
    try{
      block=await ocrSpaceImage(img,`jogador ${rowId}`,14000,'2');
    }catch(e2){
      try{
        block=await ocrSpaceImage(img,`jogador ${rowId} fallback`,16000,'3');
      }catch(_){
        throw e2;
      }
    }

    let row=parseSingleRowText(block.text,rowId);

    // IA textual só tenta completar esta linha se o parser local não fechar.
    if(!row){
      row=await structureSingleRow(block.text,rowId);
    }

    if(!row)return {row:null,text:block.text};

    row.training=rep.training===true;
    return {row,text:block.text};
  }

  async function buildRowSheet(rows,startIndex){
    const markerW=120,rowW=1600,rowH=72,pad=10;
    const width=markerW+rowW;
    const height=rows.length*(rowH+pad)+pad;
    const c=document.createElement('canvas');
    c.width=width;c.height=height;
    const x=c.getContext('2d');
    x.fillStyle='#fff';x.fillRect(0,0,width,height);
    x.font='bold 24px monospace';
    x.textBaseline='middle';

    for(let i=0;i<rows.length;i++){
      const r=rows[i],img=await v21LoadImage(r.frame.dataUrl);
      const y=pad+i*(rowH+pad);
      const sx=Math.round(r.W*.035);
      const sw=Math.round(r.W*.95);
      const sy=Math.max(0,Math.round(r.cy-r.H*.043));
      const sh=Math.min(Math.round(r.H*.086),r.H-sy);

      x.fillStyle='#000';
      x.fillText(`ROW${String(startIndex+i+1).padStart(2,'0')}`,8,y+rowH/2);
      x.drawImage(img,sx,sy,sw,sh,markerW,y,rowW,rowH);

      x.strokeStyle='#bbb';
      x.beginPath();x.moveTo(0,y+rowH+4);x.lineTo(width,y+rowH+4);x.stroke();
    }

    let dataUrl=c.toDataURL('image/jpeg',.90);
    let b64=dataUrl.split(',')[1];
    if(b64.length*.75>850000){
      dataUrl=c.toDataURL('image/jpeg',.78);
      b64=dataUrl.split(',')[1];
    }
    return {dataUrl,base64:b64,mimeType:'image/jpeg',rowStart:startIndex+1,rowCount:rows.length};
  }

  function parseMarkedRowChunk(chunk,rowId){
    const text=String(chunk||'').replace(/\s+/g,' ').trim();
    const moneyMatch=text.match(/(\d+(?:[.,]\d+)?)\s*([MK])\b/i);
    const posMatch=text.match(/(?:^|\s|\|)(GR|GK|GOL|POR|DD|DC|DE|DF|DEF|ZAG|CB|RB|LB|MDC|MC|MCO|MD|ME|MF|MID|VOL|CM|CDM|CAM|LM|RM|PL|ED|EE|ATA|ATT|FW|FWD|ST|CA|PE|PD|LW|RW|CF)(?=\s|\||$)/i);
    if(!moneyMatch||!posMatch)return null;

    const position=positionFromCode(posMatch[1]);
    if(!position)return null;

    const beforePos=text.slice(0,posMatch.index);
    const ageMatches=[...beforePos.matchAll(/\b(\d{2})\b/g)]
      .map(m=>({n:Number(m[1]),i:m.index}))
      .filter(x=>x.n>=15&&x.n<=45);
    const ageObj=ageMatches.length?ageMatches[ageMatches.length-1]:null;
    const age=ageObj?.n??null;

    let name=beforePos;
    if(ageObj)name=beforePos.slice(0,ageObj.i);
    name=name.replace(/^ROW\s*\d+\s*/i,'').replace(/^ROW\d+\s*/i,'').trim();
    name=cleanName(name);
    if(!name)return null;

    const afterPos=text.slice(posMatch.index+posMatch[0].length,moneyMatch.index);
    const nums=[...afterPos.matchAll(/\b(\d{1,3})\b/g)]
      .map(m=>Number(m[1]))
      .filter(n=>n>=0&&n<=200);

    if(nums.length<2)return null;
    const attack=nums[0]??null;
    const defence=nums[1]??null;
    const midfield=nums[2]??null;

    let rating=null;
    if(position==='ATA')rating=attack;
    else if(position==='DEF')rating=defence;
    else if(position==='MEI')rating=midfield;
    else if(position==='GOL')rating=defence;

    if(rating==null||rating<40||rating>200)return null;

    return {
      rowId,
      name,age,
      position,
      posCode:String(posMatch[1]).toUpperCase(),
      rating,
      attack,defence,midfield,
      value:moneyMatch[1].replace('.',',')+moneyMatch[2].toUpperCase(),
      training:false,forSale:false,
      source:'row_ocr_local'
    };
  }

  function deterministicMarkedRows(blocks){
    const rows=[];
    for(const b of (blocks||[])){
      const text=String(b.text||'');
      const re=/ROW\s*0*(\d{1,3})/gi;
      const marks=[...text.matchAll(re)];
      for(let i=0;i<marks.length;i++){
        const id=Number(marks[i][1]);
        const a=marks[i].index;
        const z=i+1<marks.length?marks[i+1].index:text.length;
        const chunk=text.slice(a,z);
        const row=parseMarkedRowChunk(chunk,id);
        if(row)rows.push(row);
      }
    }
    return rows;
  }

  async function structureMarkedRows(blocks,maxRowId){
    const joined=blocks.map((b,i)=>`### FOLHA ${i+1}\n${b.text}`).join('\n\n');
    const prompt=`OCR de fichas individuais do elenco do OSM 26.
Cada jogador começa com marcador ROW01, ROW02 etc.
Extraia SOMENTE jogadores realmente presentes. Não crie ROW que não exista.
Retorne JSON puro:
{"players":[{"rowId":1,"name":"","age":null,"posCode":null,"attack":null,"defence":null,"midfield":null,"value":null}]}

Regras:
- name exatamente como aparece.
- posCode é GR/DD/DC/DE/MDC/MC/MCO/MD/ME/PL/ED/EE ou equivalente visível.
- attack, defence, midfield são as três colunas numéricas Ata/Def/Med nessa ordem.
- value é a última coluna monetária.
- rowId entre 1 e ${maxRowId}.
- não invente campo ilegível.

OCR:
${joined}`;

    const attempts=[];
    if(backupKeys().groq)attempts.push(groqTextJson(prompt));
    if(backupKeys().openrouter)attempts.push(openRouterTextJson(prompt));
    if(!attempts.length)return [];

    try{
      const r=await Promise.any(attempts);
      const arr=Array.isArray(r?.data?.players)?r.data.players:[];
      return arr.map(v=>{
        const rowId=num(v.rowId);
        const position=positionFromCode(v.posCode);
        if(!rowId||rowId<1||rowId>maxRowId||!position)return null;
        const attack=num(v.attack),defence=num(v.defence),midfield=num(v.midfield);
        let rating=position==='ATA'?attack:position==='DEF'?defence:position==='MEI'?midfield:defence;
        return {
          rowId,
          name:v.name,age:num(v.age),
          position,posCode:v.posCode,
          rating,attack,defence,midfield,
          value:v.value,training:false,forSale:false,
          source:'row_ocr_ai'
        };
      }).filter(Boolean);
    }catch{
      return [];
    }
  }

  async function readHeaderByOcr(frame){
    const cashCrop=await cropForFreeOcr(frame,{x:.10,y:0,w:.16,h:.09});
    const valueCrop=await cropForFreeOcr(frame,{x:.86,y:.07,w:.14,h:.11});
    const [cashR,valueR]=await Promise.allSettled([
      ocrSpaceImage(cashCrop,'caixa',15000,'2'),
      ocrSpaceImage(valueCrop,'valor elenco',15000,'2')
    ]);
    const cash=cashR.status==='fulfilled'?moneyFromText(cashR.value.text):null;
    const squadValue=valueR.status==='fulfilled'?moneyFromText(valueR.value.text):null;
    return {cash,squadValue,overall:null,goalkeeper:null,defence:null,midfield:null,attack:null,formation:null};
  }

  async function fullRosterScan(frames){
    setProgress(22,'Rastreando jogadores na rolagem em resolução nativa…');
    const visual=await collectUniqueVisualRows(frames),reps=visual.reps;
    if(!reps.length)throw new Error('Nenhuma linha de jogador foi detectada.');

    setProgress(31,`${reps.length} linhas candidatas · OCR local primeiro…`);
    const localRows=await readRowsLocalTesseract(reps);
    const byId=new Map(localRows.map(r=>[r.rowId,r]));

    // Remote OCR only for a missing/low-confidence line. This keeps quota/time under control.
    const firstRemote=[];
    for(let id=1;id<=reps.length;id++){
      const r=byId.get(id);
      if(!r || Number(r._ocrConfidence||0)<78)firstRemote.push(id);
    }
    for(let base=0;base<firstRemote.length;base+=4){
      const ids=firstRemote.slice(base,base+4);
      setProgress(62+Math.round((base/Math.max(1,firstRemote.length))*16),`Confirmando linhas difíceis ${Math.min(base+4,firstRemote.length)}/${firstRemote.length}…`);
      const settled=await Promise.allSettled(ids.map(id=>remoteReadDetectedRowV7(reps[id-1],id,'2',false)));
      settled.forEach((x,i)=>{
        if(x.status!=='fulfilled'||!x.value?.row)return;
        const row=x.value.row,old=byId.get(row.rowId);
        // Remote wins when local was absent or clearly low confidence.
        if(!old||Number(old._ocrConfidence||0)<84)byId.set(row.rowId,row);
      });
    }

    let rows=[...byId.values()].sort((a,b)=>(a.rowId||0)-(b.rowId||0));
    let finalRows=consolidate([rows]);
    setProgress(80,'Lendo caixa e valor total do elenco…');
    const header=await readHeaderByOcr(frames[0]);
    const sq=money(header?.squadValue);

    // Financial checksum decides whether a second pass is needed.
    let sum=finalRows.reduce((a,p)=>a+(money(p.value)||0),0);
    let tol=rosterValueTolerance(sq,finalRows.length);
    const needFinancialRetry=sq!==null && Math.abs(sum-sq)>tol;

    if(needFinancialRetry){
      // Re-read unresolved AND suspicious/low-confidence rows with a different engine/preprocessing.
      const retry=[];
      for(let id=1;id<=reps.length;id++){
        const r=byId.get(id);
        if(!r || Number(r._ocrConfidence||0)<94)retry.push(id);
      }
      for(let base=0;base<retry.length;base+=3){
        const ids=retry.slice(base,base+3);
        setProgress(82+Math.round((base/Math.max(1,retry.length))*9),`Conferência financeira ${Math.min(base+3,retry.length)}/${retry.length}…`);
        const settled=await Promise.allSettled(ids.map(id=>remoteReadDetectedRowV7(reps[id-1],id,'3',true)));
        settled.forEach((x,i)=>{
          if(x.status!=='fulfilled'||!x.value?.row)return;
          const row=x.value.row,old=byId.get(row.rowId);
          // Prefer second pass if it resolves a missing row or carries a complete money/position tuple.
          if(!old || (money(row.value)!=null&&VALID_POS.has(row.position)))byId.set(row.rowId,row);
        });
      }
      rows=[...byId.values()].sort((a,b)=>(a.rowId||0)-(b.rowId||0));
      finalRows=consolidate([rows]);
      sum=finalRows.reduce((a,p)=>a+(money(p.value)||0),0);
      tol=rosterValueTolerance(sq,finalRows.length);
    }

    const positionCoverage={
      ATA:finalRows.filter(r=>r.position==='ATA').length,
      MEI:finalRows.filter(r=>r.position==='MEI').length,
      DEF:finalRows.filter(r=>r.position==='DEF').length,
      GOL:finalRows.filter(r=>r.position==='GOL').length
    };
    const coverageComplete=Object.values(positionCoverage).every(v=>v>0);
    const parsedIds=new Set(rows.map(r=>r.rowId).filter(Boolean));
    const unresolved=[];for(let id=1;id<=reps.length;id++)if(!parsedIds.has(id))unresolved.push(id);

    return {
      header,rows:finalRows,chosenFrames:visual.sampled,distinctFrames:visual.sampled.length,
      tableBlocks:reps.length,visualRows:reps.length,parsedRows:rows.length,unresolved,
      positionCoverage,coverage:{seen:{ATA:positionCoverage.ATA?1:0,MEI:positionCoverage.MEI?1:0,DEF:positionCoverage.DEF?1:0,GOL:positionCoverage.GOL?1:0}},
      coverageComplete,provider:'V7 nativo + Tesseract + OCR.Space seletivo',model:'multi-pass row consensus',
      financial:{sum,squadValue:sq,tolerance:tol,ok:sq===null?null:Math.abs(sum-sq)<=tol},failures:[]
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
    const broad=String(r?.position||'').toUpperCase();
    const position=VALID_POS.has(broad)?broad:positionFromCode(r?.posCode);
    const name=cleanName(r?.name),rating=num(r?.rating),age=num(r?.age),valueNum=money(r?.value);
    if(!name||!position||rating===null||rating<40||rating>200||valueNum===null||valueNum<100000)return null;
    if(age!==null&&(age<15||age>45))return null;
    return {name,position,posCode:String(r?.posCode||'').toUpperCase(),rating,age,value:fmtMoney(valueNum),valueNum,training:r?.training===true,forSale:r?.forSale===true};
  }
  function consolidate(lists){
    const exact=[];
    for(const list of lists){
      for(const raw of (list||[])){
        const r=normalizeRow(raw);
        if(!r)continue;

        // Primeiro procura duplicata tolerante diretamente.
        const hit=exact.find(x=>likelySamePlayer(x,r));
        if(hit){
          mergePlayerInto(hit,{...r,hits:1});
        }else{
          exact.push({...r,hits:1,_bestHits:1});
        }
      }
    }

    // Segunda passagem porque uma observação melhor pode aproximar clusters antes separados.
    const merged=[];
    for(const r of exact.sort((a,b)=>(b.hits||1)-(a.hits||1))){
      const hit=merged.find(x=>likelySamePlayer(x,r));
      if(hit)mergePlayerInto(hit,r);
      else merged.push({...r});
    }

    return merged.map(({_bestHits,...r})=>({
      ...r,
      verifiedRoster:true,
      source:'market_engine_61'
    }));
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


  function broadPosCode(section){
    return section==='ATA'?'ATA':section==='MEI'?'MID':section==='DEF'?'DEF':section==='GOL'?'GOL':null;
  }

  function explicitBroadPosition(line){
    const re=/(?:^|\s|\|)(GR|GK|GOL|POR|DD|DC|DE|DF|DEF|ZAG|CB|RB|LB|MDC|MC|MCO|MD|ME|MF|MID|VOL|CM|CDM|CAM|LM|RM|PL|ED|EE|ATA|ATT|FW|FWD|ST|CA|PE|PD|LW|RW|CF)(?=\s|\||$)/i;
    const m=String(line||'').match(re);
    return m?positionFromCode(m[1]):null;
  }

  function parseRowFromSectionLine(rawLine,section){
    const line=String(rawLine||'').replace(/[|]+/g,' | ').replace(/\s+/g,' ').trim();
    if(line.length<5)return null;

    const mm=line.match(/(\d+(?:[.,]\d+)?)\s*([MK])\b/i);
    if(!mm)return null;

    const explicit=explicitBroadPosition(line);
    const position=explicit||section;
    if(!VALID_POS.has(position))return null;

    // Numbers before monetary value. In OSM columns are:
    // Idade | Pos. | Ata | Def | Med | ... | Valor
    const beforeMoney=line.slice(0,mm.index);
    const nums=[...beforeMoney.matchAll(/\b(\d{2,3})\b/g)]
      .map(m=>({n:Number(m[1]),i:m.index}))
      .filter(x=>Number.isFinite(x.n));

    const ageObj=nums.find(x=>x.n>=15&&x.n<=45) || null;
    const age=ageObj?.n??null;
    const stats=nums.filter(x=>x.n>=40&&x.n<=200 && (!ageObj || x.i!==ageObj.i)).map(x=>x.n);

    let rating=null;
    if(stats.length>=3){
      if(position==='ATA')rating=stats[0];
      else if(position==='DEF')rating=stats[1];
      else if(position==='MEI')rating=stats[2];
      else rating=Math.max(...stats);
    }else if(stats.length){
      rating=Math.max(...stats);
    }
    if(rating===null)return null;

    // Prefer the text before age; otherwise before explicit position code.
    let cut=beforeMoney.length;
    if(ageObj)cut=Math.min(cut,ageObj.i);
    const posMatch=beforeMoney.match(/(?:^|\s|\|)(GR|GK|GOL|POR|DD|DC|DE|DF|DEF|ZAG|CB|RB|LB|MDC|MC|MCO|MD|ME|MF|MID|VOL|CM|CDM|CAM|LM|RM|PL|ED|EE|ATA|ATT|FW|FWD|ST|CA|PE|PD|LW|RW|CF)(?=\s|\||$)/i);
    if(posMatch)cut=Math.min(cut,posMatch.index);

    let name=beforeMoney.slice(0,cut).replace(/^\s*\|?\s*/,'').replace(/[|]/g,' ').trim();
    name=cleanName(name);
    if(!name)return null;

    const value=mm[1].replace('.',',')+mm[2].toUpperCase();
    return {
      name,age,position,posCode:broadPosCode(position),rating,value,
      training:false,forSale:false,source:'section_parser'
    };
  }

  function deterministicRowsFromSectionedBlocks(blocks){
    const rows=[];
    const seen={ATA:0,MEI:0,DEF:0,GOL:0};
    const order=[];
    let current='ATA'; // a lista do OSM começa por Avançados

    for(const block of (blocks||[])){
      const lines=String(block?.text||'').split(/\r?\n/);
      for(const raw of lines){
        const sec=normalizedSectionName(raw);
        if(sec){
          current=sec;
          seen[sec]++;
          if(!order.includes(sec))order.push(sec);
          continue;
        }

        const explicit=explicitBroadPosition(raw);
        if(explicit && VALID_POS.has(explicit) && explicit!==current){
          // Ajuda quando o OCR perde o título de uma seção.
          current=explicit;
          seen[explicit]=Math.max(1,seen[explicit]);
          if(!order.includes(explicit))order.push(explicit);
        }

        const row=parseRowFromSectionLine(raw,current);
        if(row)rows.push(row);
      }
    }
    return {rows,seen,order};
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
    const prompt=`Você recebe OCR da rolagem COMPLETA do elenco no OSM 26 Android, em ordem temporal.

REGRAS IMPORTANTES:
- A lista é dividida em seções: Avançados, Médios, Defesas e Guarda-redes.
- O título da seção define a posição ampla de TODOS os jogadores abaixo dele até o próximo título.
- Portanto NÃO descarte uma linha só porque o código de posição individual foi mal reconhecido.
- Consolide duplicatas da rolagem.
- Não invente nome, idade, força ou valor.
- cash é dinheiro disponível; squadValue é valor total do elenco.

Para cada jogador retorne:
name, age se visível, position ("ATA","MEI","DEF","GOL"), posCode se visível, rating principal e value.

JSON puro:
{"header":{"cash":null,"squadValue":null,"playerCount":null,"overall":null,"goalkeeper":null,"defence":null,"midfield":null,"attack":null,"formation":null},"players":[{"name":"","age":null,"position":null,"posCode":null,"rating":null,"value":null,"training":false,"forSale":false}],"observedPlayerCount":null}

OCR:
${joined}`;

    const attempts=[];
    if(backupKeys().groq)attempts.push(groqTextJson(prompt));
    if(backupKeys().openrouter)attempts.push(openRouterTextJson(prompt));
    if(attempts.length){
      try{return await Promise.any(attempts)}catch{}
    }

    const parsed=deterministicRowsFromSectionedBlocks(blocks);
    const header=deterministicHeaderFromOcr(blocks[0]?.text||joined);
    return {
      provider:'OCR.Space local',
      model:'parser por seção',
      data:{header,players:parsed.rows,observedPlayerCount:parsed.rows.length}
    };
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
        frames=await extractNativeRosterFrames(video,34);
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
      if(diag)diag.textContent=`Elenco 7.0 · ${result.visualRows||0} linhas rastreadas · ${result.parsedRows||0} estruturadas · revisão visual ${(result.unresolved||[]).length} · ATA/MEI/DEF/GOL ${(result.positionCoverage?.ATA)||0}/${(result.positionCoverage?.MEI)||0}/${(result.positionCoverage?.DEF)||0}/${(result.positionCoverage?.GOL)||0} · ${Math.round((Date.now()-started)/1000)}s.`;
      validationScan=result;
    }catch(err){
      technicalError=String(err?.message||err);
      const diag=document.getElementById('analysisDiagnostics');
      if(diag)diag.textContent=`Elenco 7.0 falhou: ${technicalError}`;
    }

    const comp=composition(roster), sum=roster.reduce((a,p)=>a+(money(p.value)||0),0), sq=money(header?.squadValue), issues=[];
    const expected=null;

    // A quantidade de jogadores NÃO é inferida por OCR.
    // A completude é comprovada pela rolagem + soma de valores.
    // Só valida quando a própria rolagem comprova
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
      const tolerance=rosterValueTolerance(sq,roster.length);
      const delta=Math.abs(sum-sq);
      if(delta>tolerance){
        const missing=Math.max(0,sq-sum),excess=Math.max(0,sum-sq);
        issues.push(
          `conferência financeira não fechou: ${fmtMoney(sum)} lidos de ${fmtMoney(sq)}`+
          (missing>0?` · faltam ${fmtMoney(missing)}`:'')+
          (excess>0?` · excesso ${fmtMoney(excess)}`:'')+
          ` · tolerância de arredondamento ${fmtMoney(tolerance)}`
        );
      }
    }
    if(technicalError)issues.push(`falha técnica: ${technicalError}`);

    const validation={ok:issues.length===0,issues,count:roster.length,expectedCount:expected,composition:comp,sumValues:sum,squadValue:sq,source,technicalError,elapsedMs:Date.now()-started,scan:validationScan?{frames:validationScan.distinctFrames,blocks:validationScan.tableBlocks,coverage:validationScan.coverage?.seen,coverageComplete:validationScan.coverageComplete}:null};

    if(validation.ok){
      applyValidated(s,roster,header,validation); s.rosterValidation42.source=source; s.rosterValidation42.version=VERSION;
      try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
      if(typeof renderMarket==='function')renderMarket();
      const msg=`${roster.length} jogadores validados · ${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL} · caixa ${fmtMoney(s.myTeam?.cash)} · elenco ${fmtMoney(s.myTeam?.squadValue)}.`;
      const el=document.getElementById('analysisContent');
      if(el)el.innerHTML=`<div class="card" style="margin-top:12px"><span class="eyebrow">ELENCO VALIDADO · 7.0</span><h3>${roster.length} jogadores confirmados</h3><p class="small muted">${esc(msg)}</p><div class="fallback-kpis"><div><span>Caixa</span><b>${esc(fmtMoney(s.myTeam?.cash))}</b></div><div><span>Valor elenco</span><b>${esc(fmtMoney(s.myTeam?.squadValue))}</b></div><div><span>ATA/MEI/DEF/GOL</span><b>${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL}</b></div></div><p class="small muted">Fonte: ${esc(source)} · resolução nativa + consenso local/remoto · ${Math.round(validation.elapsedMs/1000)}s.</p></div>`;
      setAnalysisRun(s,'market','success',msg,{version:VERSION,validation}); setProgress(100,'Elenco validado'); job('Elenco e finanças atualizados.','done'); return;
    }

    preserveFailed(s,validation,header); try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
    if(typeof renderMarket==='function')renderMarket();
    const el=document.getElementById('analysisContent');
    if(el)el.innerHTML=`<div class="card ai-fallback-card" style="margin-top:12px"><span class="eyebrow">VALIDAÇÃO DE ELENCO · 7.0</span><h3>Leitura incompleta — não alterei seu elenco</h3><p class="small muted">${esc(validation.issues.join(' · '))}</p><div class="fallback-kpis"><div><span>Encontrados</span><b>${validation.count}</b></div><div><span>ATA/MEI/DEF/GOL</span><b>${comp.ATA}/${comp.MEI}/${comp.DEF}/${comp.GOL}</b></div><div><span>Tempo</span><b>${Math.round(validation.elapsedMs/1000)}s</b></div></div>${technicalError?`<p class="small muted" style="margin-top:10px">Erro técnico: ${esc(technicalError)}</p>`:''}</div>`;
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
    if(!v?.ok)return `<div class="card market40-card"><span class="eyebrow">DIRETOR IA · 7.0</span><h3>Plano bloqueado até ler o elenco completo</h3><p class="small muted">A V7 mantém o vídeo do elenco em resolução nativa, usa OCR local primeiro, confirma somente linhas difíceis no OCR.Space e só libera o Diretor após conferência financeira.</p><div class="actions"><button class="btn" onclick="showView('analyze');setAnalysisMode('market')">Analisar elenco</button></div></div>`;
    const p=buildDirector(s),c={ATA:p.by.ATA.length,MEI:p.by.MEI.length,DEF:p.by.DEF.length,GOL:p.by.GOL.length};
    const sell=p.sells.length?p.sells.map((x,i)=>`<div class="radar-item"><div><b>${i+1}. ${esc(x.name)}</b><span>${esc(x.position)} · força ${esc(x.rating)} · valor ${esc(x.value)}${x.training?' · treinando':''}</span></div><span>${x.forSale?'Já à venda':'Excedente'}</span></div>`).join(''):'<p class="small muted">Nenhuma venda estrutural necessária agora.</p>';
    const weak=Object.entries(p.weakest).map(([k,x])=>x?`${k}: ${esc(x.name)} ${x.rating}`:`${k}: NI`).join(' · ');
    return `<div class="card market40-card"><div class="section-head compact-head"><div><span class="eyebrow">DIRETOR IA · DADOS REAIS · 7.0</span><h3>Plano baseado somente no vídeo validado</h3></div></div>
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
  }catch(err){console.warn('[Market Engine 7.0 migrate]',err);}


  function injectBackupSettings(){
    const view=document.getElementById('view-settings');if(!view||document.getElementById('multiAiBackupCard'))return;
    const card=document.createElement('div');card.id='multiAiBackupCard';card.className='card form';card.style.marginTop='12px';
    card.innerHTML=`<span class="eyebrow">IA DE BACKUP · 7.0</span><h3>OCR especializado + IA de backup</h3><p class="small muted">Elenco: OCR.Space lê as tabelas; OpenRouter/Groq consolidam o texto. Gemini fica reservado para outras análises. As chaves ficam somente neste navegador.</p><label>OpenRouter API Key<input id="openrouterKey44" type="password" autocomplete="off" placeholder="sk-or-v1-..."></label><label>Groq API Key<input id="groqKey44" type="password" autocomplete="off" placeholder="gsk_..."></label><label>OCR.Space API Key<input id="ocrSpaceKey46" type="password" autocomplete="off" placeholder="Chave OCR.Space"></label><div class="actions"><button class="btn" id="saveBackupKeys44">Salvar chaves</button></div><p id="backupAiStatus44" class="small muted"></p>`;
    view.appendChild(card);
    const or=document.getElementById('openrouterKey44'),g=document.getElementById('groqKey44'),o=document.getElementById('ocrSpaceKey46');or.value=localStorage.getItem(OPENROUTER_KEY)||'';g.value=localStorage.getItem(GROQ_KEY)||'';o.value=localStorage.getItem(OCRSPACE_KEY)||'';
    const update=()=>{const k=backupKeys();document.getElementById('backupAiStatus44').textContent=`OCR.Space: ${ocrSpaceKey()?'configurado':'não configurado'} · OpenRouter: ${k.openrouter?'configurado':'não configurado'} · Groq: ${k.groq?'configurado':'não configurado'}${geminiCoolingDown()?' · Gemini em cooldown por cota':''}`};update();
    document.getElementById('saveBackupKeys44').onclick=()=>{localStorage.setItem(OPENROUTER_KEY,or.value.trim());localStorage.setItem(GROQ_KEY,g.value.trim());localStorage.setItem(OCRSPACE_KEY,o.value.trim());update();toast('Chaves de backup salvas neste aparelho');};
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',()=>setTimeout(injectBackupSettings,250));else setTimeout(injectBackupSettings,250);


  /* =========================================================
     V7 STABLE CORE — results belong to the match, not to the registration day.
     ========================================================= */
  function stableFixtureDate(x){
    const raw=x?.dateTime||x?.matchAt||x?.kickoff||x?.date||x?.playedAt||null;
    if(!raw)return null;const d=new Date(raw);return Number.isNaN(d.getTime())?null:d;
  }
  function stableFixtureOpponent(x){return String(x?.opponent||x?.opponentName||x?.teamName||'').trim();}
  function stableFixtureRound(x){return x?.round??x?.rodada??x?.matchday??null;}
  function stableFixtureId(slot,fixture){
    const d=stableFixtureDate(fixture),opp=compactName(stableFixtureOpponent(fixture)),r=stableFixtureRound(fixture);
    return `S${slot.slotNumber}|${r??'R?'}|${opp||'opp'}|${d?d.toISOString():'date?'}`;
  }
  function stableUsedFixtureIds(slot){return new Set((slot?.results||[]).map(r=>r?.fixtureId).filter(Boolean));}
  function stableFindFixture(slot,opponent,preferredAt=null){
    const rows=Array.isArray(slot?.schedule)?slot.schedule:[],opp=String(opponent||slot?.opponent?.teamName||'');
    const pref=preferredAt?new Date(preferredAt):new Date();const prefMs=Number.isNaN(pref.getTime())?Date.now():pref.getTime();
    const used=stableUsedFixtureIds(slot),now=Date.now();
    const candidates=[];
    for(const f of rows){
      if(f?.placeholder)continue;
      const d=stableFixtureDate(f);if(!d)continue;
      const fo=stableFixtureOpponent(f),sim=opp&&fo?nameSimilarity(opp,fo):0;
      // Require strong opponent match unless no opponent was available at all.
      if(opp&&fo&&sim<.68)continue;
      const id=stableFixtureId(slot,f),already=used.has(id);
      const futurePenalty=d.getTime()>now+8*3600000?35:0;
      const playedBonus=f?.played?22:0;
      const unusedBonus=already?0:18;
      const timePenalty=Math.min(80,Math.abs(d.getTime()-prefMs)/86400000*7);
      const score=sim*100+playedBonus+unusedBonus-futurePenalty-timePenalty;
      candidates.push({fixture:f,date:d,id,score,already});
    }
    candidates.sort((a,b)=>b.score-a.score);
    return candidates.find(x=>!x.already)||candidates[0]||null;
  }
  function stableLocalInputValue(d){
    if(!d)return '';const x=new Date(d);if(Number.isNaN(x.getTime()))return '';
    const z=new Date(x.getTime()-x.getTimezoneOffset()*60000);return z.toISOString().slice(0,16);
  }
  function stableOutcome(gf,ga){return gf>ga?'V':gf===ga?'E':'D';}
  function stableAttachResultToFixture(slot,e){
    const match=stableFindFixture(slot,e.opponent,e.playedAt||e.registeredAt||e.createdAt);
    if(match){
      e.fixtureId=match.id;e.playedAt=match.date.toISOString();e.createdAt=e.playedAt;
      const f=match.fixture;f.played=true;f.placeholder=false;f.outcome=stableOutcome(e.gf,e.ga);f.result=e.score;f.score=e.score;
      if(!f.opponent)f.opponent=e.opponent;
    }
    return match;
  }

  // Manual result: calendar date is preselected and remains editable.
  window.resultModal=function(n){
    const s=state.slots[n-1];if(!s.tactic){toast('Gere uma tática antes de registrar o resultado');return;}
    const found=stableFindFixture(s,s.opponent?.teamName,s.match?.nextMatchAt||new Date());
    const d=found?.date || (s.match?.nextMatchAt?new Date(s.match.nextMatchAt):new Date());
    openModal(`<h2>Resultado · Slot ${n}</h2><div class="field-edit">
      <label>Adversário<input id="rOpp" value="${esc(s.opponent?.teamName||'')}"></label>
      <label>Data/hora da partida<input id="rPlayedAt" type="datetime-local" value="${stableLocalInputValue(d)}"></label>
      <p class="small muted">A data é a da partida no calendário, não o momento em que você registra o resultado.</p>
      <div class="kpis"><label>Meus gols<input id="rGF" type="number" min="0"></label><label>Gols rival<input id="rGA" type="number" min="0"></label></div>
      <label>Observação<textarea id="rNote"></textarea></label><button class="btn" onclick="saveResult(${n})">Salvar resultado</button></div>`);
  };

  window.saveResult=function(n){
    const s=state.slots[n-1],gf=Number(document.getElementById('rGF')?.value),ga=Number(document.getElementById('rGA')?.value);
    if(!Number.isFinite(gf)||!Number.isFinite(ga)){toast('Informe o placar');return;}
    const opp=document.getElementById('rOpp')?.value.trim()||s.opponent?.teamName;
    const raw=document.getElementById('rPlayedAt')?.value;
    const chosen=raw?new Date(raw):null;
    const registeredAt=new Date().toISOString();
    let playedAt=chosen&&!Number.isNaN(chosen.getTime())?chosen.toISOString():null;
    const fixture=stableFindFixture(s,opp,playedAt||registeredAt);
    if(fixture)playedAt=fixture.date.toISOString();
    if(!playedAt)playedAt=registeredAt;
    const e={
      createdAt:playedAt,playedAt,registeredAt,fixtureId:fixture?.id||null,
      opponent:opp,gf,ga,score:`${gf}-${ga}`,note:document.getElementById('rNote')?.value.trim()||null,
      tactic:clone(s.tactic),context:{myOverall:s.myTeam.overall,oppOverall:s.opponent.overall,oppFormation:s.opponent.formation,oppStyle:s.opponent.style,venue:s.match.venue,referee:s.match.refereeColor,strengthBucket:strengthBucket(s),round:stableFixtureRound(fixture?.fixture)??s.round}
    };
    s.results.push(e);stableAttachResultToFixture(s,e);s.tactic=null;
    if(Number.isFinite(Number(s.round)))s.round=Number(s.round)+1;
    saveState();closeModal();toast(`Resultado salvo na data da partida: ${fmtDate(e.playedAt)}`);
  };

  // Result-video path uses the same linkage.
  try{
    v21ApplyResult=function(r){
      const s=selectedSlot(),gf=Number(r?.gf),ga=Number(r?.ga);
      if(!Number.isFinite(gf)||!Number.isFinite(ga))throw new Error('Não consegui identificar o placar final.');
      const registeredAt=new Date().toISOString(),opp=r.opponent||s.opponent.teamName;
      const fixture=stableFindFixture(s,opp,registeredAt),playedAt=fixture?.date?.toISOString()||registeredAt;
      const e={createdAt:playedAt,playedAt,registeredAt,fixtureId:fixture?.id||null,opponent:opp,gf,ga,score:r.score||`${gf}-${ga}`,
        tactic:s.tactic?clone(s.tactic):null,stats:r.stats||{},events:r.events||[],
        context:{myOverall:s.myTeam.overall,oppOverall:s.opponent.overall,oppFormation:r.oppFormation||s.opponent.formation,myFormation:r.myFormation||s.tactic?.formation||null,oppStyle:s.opponent.style,venue:s.match.venue,referee:s.match.refereeColor,strengthBucket:strengthBucket(s),round:stableFixtureRound(fixture?.fixture)??s.round}};
      s.results.push(e);stableAttachResultToFixture(s,e);s.tactic=null;if(Number.isFinite(Number(s.round)))s.round=Number(s.round)+1;
    };
    window.v21ApplyResult=v21ApplyResult;
  }catch(_){}

  // One-time repair for old results saved on the registration day (e.g. 29/09 game registered 30/09).
  try{
    const MIG='osm_v7_result_date_migrated';
    if(localStorage.getItem(MIG)!=='1'){
      let changed=0;
      for(const slot of (state?.slots||[])){
        for(const r of (slot?.results||[])){
          if(r.playedAt&&r.fixtureId)continue;
          const original=r.createdAt||null,match=stableFindFixture(slot,r.opponent,original||new Date());
          r.registeredAt=r.registeredAt||original||new Date().toISOString();
          if(match){r.fixtureId=match.id;r.playedAt=match.date.toISOString();r.createdAt=r.playedAt;const f=match.fixture;f.played=true;f.placeholder=false;f.outcome=stableOutcome(Number(r.gf),Number(r.ga));f.result=r.score||`${r.gf}-${r.ga}`;changed++;}
          else{r.playedAt=r.playedAt||original;}
        }
      }
      localStorage.setItem(MIG,'1');
      if(changed)localStorage.setItem(STATE_KEY,JSON.stringify(state));
    }
  }catch(err){console.warn('[V7 result date migration]',err);}

  // Remove exact duplicate alert cards left by multiple legacy dashboard layers.
  const stableOldRenderDashboard=typeof renderDashboard==='function'?renderDashboard:null;
  if(stableOldRenderDashboard){
    renderDashboard=function(){
      const out=stableOldRenderDashboard.apply(this,arguments),root=document.getElementById('view-dashboard');
      if(root){const seen=new Set();for(const card of [...root.querySelectorAll('.card')]){const k=norm(card.textContent).replace(/\d{1,2}:\d{2}/g,'TIME');if(k.length>40){if(seen.has(k))card.remove();else seen.add(k);}}}
      return out;
    };window.renderDashboard=renderDashboard;
  }

  window.OSM_MARKET_ENGINE_70={version:VERSION,consolidate,validateRoster,buildDirector,extractNativeRosterFrames,stableFindFixture};
  try{console.info('[OSM] Market Engine '+VERSION+' ativo')}catch{}
})();

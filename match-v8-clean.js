'use strict';
/*
 OSM AI Coach Pro — V8 CLEAN READER
 Objetivo: leitor de PARTIDA resiliente, sem depender de Gemini/OpenRouter/OCR.Space.
 - percorre o vídeo em 32 pontos;
 - escolhe até 16 quadros distintos;
 - OCR local Tesseract;
 - extrai dados por regras determinísticas;
 - treino secreto torna os dados ocultos do rival opcionais;
 - nunca apaga dados válidos antigos quando uma nova leitura falha;
 - não trava a geração de tática por formação/plano invisíveis.
*/
(function(){
  const VERSION='8.0.0';

  function norm(s){
    return String(s??'').normalize('NFD').replace(/[\u0300-\u036f]/g,'')
      .replace(/[|]/g,'I').replace(/\s+/g,' ').trim();
  }
  function low(s){ return norm(s).toLowerCase(); }
  function nnum(x,min=0,max=999){
    const n=Number(String(x??'').replace(',','.').replace(/[^\d.]/g,''));
    return Number.isFinite(n)&&n>=min&&n<=max?n:null;
  }
  function boolFrom(t){
    const s=low(t);
    if(/\b(sim|yes|ativo|ativado)\b/.test(s))return true;
    if(/\b(nao|no|inativo|desativado)\b/.test(s))return false;
    return null;
  }
  function escErr(e){return String(e?.message||e||'erro').slice(0,240)}

  async function extractFrames(files){
    const video=[...(files||[])].find(f=>String(f.type||'').startsWith('video/'));
    const images=[...(files||[])].filter(f=>String(f.type||'').startsWith('image/'));
    let frames=[];
    if(video){
      frames=await v21ExtractVideoFrames(video,32);
    }else{
      for(const f of images)frames.push(await v21ImageToFrame(f));
    }
    if(!frames.length)throw new Error('Nenhum quadro pôde ser extraído.');
    return frames;
  }

  function frameDistance(a,b){
    try{return v21FrameDistance(a,b)}catch(_){return 100}
  }
  function choose(frames,max=16){
    if(frames.length<=max)return frames;
    const out=[frames[0],frames[frames.length-1]];
    const step=(frames.length-1)/(max-1);
    for(let i=1;i<max-1;i++){
      const idx=Math.round(i*step);
      const f=frames[idx];
      if(f&&!out.includes(f))out.push(f);
    }
    out.sort((a,b)=>(a.time||0)-(b.time||0));
    return out;
  }

  async function ocr(frames){
    if(!window.Tesseract)throw new Error('OCR local não carregou.');
    let worker;
    try{worker=await Tesseract.createWorker('por+eng',1,{logger:m=>{
      if(m?.status==='recognizing text'&&typeof m.progress==='number'){
        setProgress(18+Math.round(m.progress*50),'V8 OCR local '+Math.round(m.progress*100)+'%…');
      }
    }})}catch(_){worker=await Tesseract.createWorker('eng')}
    const rows=[];
    try{
      for(let i=0;i<frames.length;i++){
        setProgress(20+Math.round(i/Math.max(1,frames.length)*50),`V8 · lendo quadro ${i+1}/${frames.length}…`);
        const r=await worker.recognize(frames[i].dataUrl);
        const text=String(r?.data?.text||'').replace(/[ \t]+/g,' ').replace(/\n{3,}/g,'\n\n').trim();
        if(text.length>4)rows.push({i,time:frames[i].time||0,text,frame:frames[i]});
      }
    }finally{
      try{await worker.terminate()}catch(_){}
    }
    if(!rows.length)throw new Error('OCR local não retornou texto útil.');
    return rows;
  }

  function nearby(lines,keys,span=2){
    const out=[];
    const K=keys.map(low);
    lines.forEach((ln,i)=>{
      const z=low(ln);
      if(K.some(k=>z.includes(k))){
        for(let j=Math.max(0,i-span);j<=Math.min(lines.length-1,i+span);j++)out.push(lines[j]);
      }
    });
    return out;
  }
  function firstPlausibleNumber(text,min=20,max=200){
    const ms=String(text||'').match(/\b\d{2,3}\b/g)||[];
    for(const m of ms){const n=Number(m);if(n>=min&&n<=max)return n}
    return null;
  }
  function allPlausibleNumbers(text,min=20,max=200){
    return (String(text||'').match(/\b\d{2,3}\b/g)||[]).map(Number).filter(n=>n>=min&&n<=max);
  }
  function findFormation(text){
    const m=norm(text).match(/\b([3-6])\s*[-–]\s*([1-5])(?:\s*[-–]\s*([1-5]))?\s*([AB])?\b/i);
    if(!m)return null;
    return [m[1],m[2],m[3]].filter(Boolean).join('-')+(m[4]?' '+m[4].toUpperCase():'');
  }
  function findRef(text){
    const s=low(text);
    if(s.includes('vermelh'))return 'Vermelho';
    if(s.includes('laranja'))return 'Laranja';
    if(s.includes('amarel'))return 'Amarelo';
    if(s.includes('azul'))return 'Azul';
    if(s.includes('verde'))return 'Verde';
    return null;
  }
  function findStyle(text){
    const s=low(text);
    if(/jogar pelas alas|pelas alas|jogo pelas alas/.test(s))return 'Jogar pelas alas';
    if(/jogo de passes|passes curtos|jogar em passes/.test(s))return 'Jogo de passes';
    if(/bola longa/.test(s))return 'Bola longa';
    if(/contra-ataque|contra ataque/.test(s))return 'Contra-ataque';
    if(/remate a vista|chutar de longe|remates de longe/.test(s))return 'Remate à vista';
    return null;
  }
  function findMarking(text){
    const s=low(text);
    if(/individual|homem a homem|homem-a-homem/.test(s))return 'Individual';
    if(/a zona|zona|zonal/.test(s))return 'À zona';
    return null;
  }
  function findOffside(text){
    const s=low(text);
    if(!/impedimento|fora de jogo|fora-de-jogo|offside/.test(s))return null;
    const b=boolFrom(s); return b;
  }

  function parse(rows,slot){
    const joined=rows.map(r=>r.text).join('\n');
    const lines=joined.split(/\n+/).map(x=>x.trim()).filter(Boolean);
    const z=low(joined);
    const data={
      teamName:null, opponentTeamName:null,
      myTeam:{overall:null,goalkeeper:null,defence:null,midfield:null,attack:null},
      opponent:{overall:null,goalkeeper:null,defence:null,midfield:null,attack:null,human:null,manager:null,loginBonus:null,stadium:null,trainingCamp:null,secretTraining:null,formation:null,style:null,marking:null,offside:null},
      match:{venue:null,refereeColor:null}
    };

    // Keep known names as anchors only, never overwrite from OCR garbage.
    if(slot?.teamName)data.teamName=slot.teamName;
    if(slot?.opponent?.teamName)data.opponentTeamName=slot.opponent.teamName;

    // Secret training / training camp.
    const secCtx=nearby(lines,['treino secreto','secret training'],3).join(' ');
    if(secCtx){
      if(/treino secreto.{0,30}(sim|ativo|ativado)|secret training.{0,30}(yes|active)/i.test(norm(secCtx)))data.opponent.secretTraining=true;
      else if(/treino secreto.{0,30}(nao|não|inativo)|secret training.{0,30}(no|inactive)/i.test(norm(secCtx)))data.opponent.secretTraining=false;
    }
    const campCtx=nearby(lines,['campo de treinamento','training camp','campo treino'],3).join(' ');
    if(campCtx){
      if(/campo.{0,25}(sim|ativo|ativado)|training camp.{0,25}(yes|active)/i.test(norm(campCtx)))data.opponent.trainingCamp=true;
      else if(/campo.{0,25}(nao|não|inativo)|training camp.{0,25}(no|inactive)/i.test(norm(campCtx)))data.opponent.trainingCamp=false;
    }

    // Referee.
    const refCtx=nearby(lines,['arbitro','árbitro','referee'],3).join(' ');
    data.match.refereeColor=findRef(refCtx||joined);

    // Venue.
    const venueCtx=nearby(lines,['casa','fora','home','away'],2).join(' ');
    if(/\bcasa\b|\bhome\b/i.test(norm(venueCtx)))data.match.venue='Casa';
    else if(/\bfora\b|\baway\b/i.test(norm(venueCtx)))data.match.venue='Fora';

    // Login bonus 0..3%.
    const bonusCtx=nearby(lines,['bonus','bônus','login'],3).join(' ');
    const bm=norm(bonusCtx).match(/\b([0-3])\s*%/);
    if(bm)data.opponent.loginBonus=Number(bm[1]);

    // Formation/style/marking/offside from Data Analyst.
    const analyst=nearby(lines,['formacao','formação','plano','marcacao','marcação','impedimento','fora de jogo','analista','analyst'],5).join('\n');
    data.opponent.formation=findFormation(analyst);
    data.opponent.style=findStyle(analyst);
    data.opponent.marking=findMarking(analyst);
    data.opponent.offside=findOffside(analyst);

    // Manager/human.
    const mgr=nearby(lines,['manager','treinador','gestor'],2);
    for(const ln of mgr){
      const s=norm(ln);
      if(s.length>=3&&!/\b(manager|treinador|gestor)\b/i.test(s)&&!/\b\d{2,3}\b/.test(s)){
        data.opponent.manager=s.slice(0,60); data.opponent.human=true; break;
      }
    }
    if(data.opponent.human===null && slot?.opponent?.human!=null)data.opponent.human=slot.opponent.human;

    // Strength overall: use VS vicinity; first plausible pair.
    const vsText=nearby(lines,[' vs ','vs'],3).join(' ');
    const nums=allPlausibleNumbers(vsText);
    if(nums.length>=2){
      // Prefer known previous values when they appear; otherwise first distinct pair.
      let pair=[nums[0],nums.find(n=>n!==nums[0])??nums[1]];
      data.myTeam.overall=pair[0]; data.opponent.overall=pair[1];
    }

    // Sector values. We only accept values near explicit labels.
    const sectors=[
      ['goalkeeper',['gol','gk','gr','guarda-redes','goalkeeper']],
      ['defence',['def','defesa','defence']],
      ['midfield',['mei','meio','midfield']],
      ['attack',['ata','ataque','attack']]
    ];
    for(const [key,keys] of sectors){
      const ctx=nearby(lines,keys,1);
      const vals=[];
      ctx.forEach(x=>vals.push(...allPlausibleNumbers(x,20,200)));
      const uniq=[...new Set(vals)];
      if(uniq.length>=2){data.myTeam[key]=uniq[0];data.opponent[key]=uniq[1]}
      else if(uniq.length===1 && slot?.myTeam?.[key]==null)data.myTeam[key]=uniq[0];
    }

    // If secret training is active, hidden rival tactical values are deliberately null.
    if(data.opponent.secretTraining===true){
      ['overall','goalkeeper','defence','midfield','attack','formation','style','marking','offside'].forEach(k=>data.opponent[k]=null);
    }

    return data;
  }

  function setDetected(slot,path,val,conf=.90){
    if(val===null||val===undefined||val===''||val==='NI')return;
    setPath(slot,path,val);
    slot.fieldMeta=slot.fieldMeta||{};
    slot.fieldMeta[path]={source:'detected',confidence:conf,updatedAt:nowIso()};
  }
  function apply(slot,d){
    setDetected(slot,'teamName',d.teamName,.98);
    setDetected(slot,'opponent.teamName',d.opponentTeamName,.98);
    ['overall','goalkeeper','defence','midfield','attack'].forEach(k=>{
      const a=nnum(d.myTeam?.[k],20,200); if(a!==null)setDetected(slot,'myTeam.'+k,a,.90);
    });
    setDetected(slot,'opponent.secretTraining',d.opponent?.secretTraining,.98);
    setDetected(slot,'opponent.trainingCamp',d.opponent?.trainingCamp,.95);
    setDetected(slot,'opponent.human',d.opponent?.human,.90);
    setDetected(slot,'opponent.manager',d.opponent?.manager,.88);
    if(d.opponent?.loginBonus!==null)setDetected(slot,'opponent.loginBonus',d.opponent.loginBonus,.92);
    setDetected(slot,'match.refereeColor',d.match?.refereeColor,.93);
    setDetected(slot,'match.venue',d.match?.venue,.92);

    if(d.opponent?.secretTraining===true){
      for(const p of ['opponent.overall','opponent.goalkeeper','opponent.defence','opponent.midfield','opponent.attack','opponent.formation','opponent.style','opponent.marking','opponent.offside']){
        setPath(slot,p,null);
        slot.fieldMeta[p]={source:'unknown',confidence:0,updatedAt:nowIso(),hiddenBySecretTraining:true};
      }
    }else{
      ['overall','goalkeeper','defence','midfield','attack'].forEach(k=>{
        const a=nnum(d.opponent?.[k],20,200); if(a!==null)setDetected(slot,'opponent.'+k,a,.90);
      });
      setDetected(slot,'opponent.formation',d.opponent?.formation,.92);
      setDetected(slot,'opponent.style',d.opponent?.style,.92);
      setDetected(slot,'opponent.marking',d.opponent?.marking,.92);
      if(typeof d.opponent?.offside==='boolean')setDetected(slot,'opponent.offside',d.opponent.offside,.92);
    }
    slot.status='active'; slot.lastAnalysisAt=nowIso();
  }

  function usableMissing(slot){
    const required=['teamName','opponent.teamName','match.venue','match.refereeColor','myTeam.overall'];
    if(slot?.opponent?.secretTraining!==true)required.push('opponent.overall');
    return required.filter(p=>{
      const v=getPath(slot,p); return !(hasValue(v)||typeof v==='boolean');
    });
  }

  function installSecretTrainingRule(){
    // Replace blocker used by dashboard/pregame after V8 loads.
    try{
      missingRequired=function(s){
        const req=['teamName','opponent.teamName','match.venue','match.refereeColor','myTeam.overall'];
        if(s?.opponent?.secretTraining!==true)req.push('opponent.overall');
        return req.filter(p=>{const v=getPath(s,p);return !(hasValue(v)||typeof v==='boolean')});
      };
    }catch(_){}
  }

  async function analyzeV8(files){
    if(analysisMode!=='tactic'){
      // Other modes stay with the original app readers.
      if(typeof window.__V8_PREV_ANALYZE==='function')return window.__V8_PREV_ANALYZE(files);
      throw new Error('Leitor anterior indisponível para esta seção.');
    }
    const slot=selectedSlot();
    const snapshot=JSON.parse(JSON.stringify(slot));
    $('progressWrap')?.classList.remove('hidden');
    setProgress(5,'V8 · varrendo o vídeo inteiro…');
    try{
      const raw=await extractFrames(files);
      const chosen=choose(raw,16);
      setProgress(14,`V8 · ${raw.length} quadros extraídos; ${chosen.length} selecionados…`);
      const rows=await ocr(chosen);
      setProgress(74,'V8 · consolidando leitura determinística…');
      const data=parse(rows,slot);
      apply(slot,data);

      calcQuality(slot); saveState();
      renderCoverage(slot); renderAnalysisSummary(slot); renderPregame();

      const miss=usableMissing(slot);
      const hidden=slot?.opponent?.secretTraining===true;
      setAnalysisRun(slot,'tactic',miss.length?'warning':'success',
        `V8 CLEAN: ${raw.length} quadros reais · ${chosen.length} analisados · OCR local · ${hidden?'treino secreto detectado; campos ocultos não bloqueiam':'sem dependência externa'}${miss.length?' · faltam '+miss.length+' campo(s) visível(is)':' · pronto para tática'}`,
        {quality:slot.analysisQuality,currentRunValidated:!miss.length,fullVideoScan:true,version:VERSION}
      );
      const diag=$('analysisDiagnostics');
      if(diag)diag.textContent=`V8 CLEAN READER ${VERSION} · ${raw.length} quadros distribuídos no vídeo · ${rows.length} OCR úteis · sem Gemini/OpenRouter/OCR.Space para Partida.`;

      if(!miss.length && $('autoTactic')?.checked){
        setProgress(92,'V8 · gerando tática…');
        await generateTactic(state.selectedSlot);
      }else{
        setProgress(100,miss.length?'Leitura concluída; revisar somente campos realmente visíveis':'Leitura concluída');
      }
    }catch(e){
      Object.keys(slot).forEach(k=>delete slot[k]);
      Object.assign(slot,snapshot);
      saveState();
      setAnalysisRun(slot,'tactic','error',`V8 CLEAN: ${escErr(e)}. Dados anteriores preservados.`,{currentRunValidated:false,version:VERSION});
      setProgress(100,'Falha sem apagar dados anteriores');
    }finally{
      setTimeout(()=>$('progressWrap')?.classList.add('hidden'),1200);
    }
  }

  installSecretTrainingRule();
  window.__V8_PREV_ANALYZE=typeof v21Analyze==='function'?v21Analyze:null;
  try{v21Analyze=analyzeV8}catch(_){}
  window.v21Analyze=analyzeV8;
  window.OSM_COMPLETE_MATCH_SCAN_VERSION='8.0.0';
  try{console.info('[OSM] V8 CLEAN READER ativo')}catch(_){}
})();

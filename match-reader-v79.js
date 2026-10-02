
(function(){
'use strict';

var V711='7.11.0';
var OCR_KEY='osm_ai_coach_ocrspace_key';
var OR_KEY='osm_ai_coach_openrouter_key';
var previousAnalyze=(typeof v21Analyze==='function')?v21Analyze:null;

var REF=['Verde','Azul','Amarelo','Laranja','Vermelho'];
var STYLES=['Jogar pelas alas','Jogo de passes','Bola longa','Contra-ataque','Remate à vista'];
var MARK=['À zona','Individual'];

function norm(v){
  return String(v==null?'':v).normalize('NFD').replace(/[\u0300-\u036f]/g,'')
    .toLowerCase().replace(/\s+/g,' ').trim();
}
function safe(e){ return String((e&&e.message)||e||'erro').slice(0,260); }
function has(v){ return !(v===null||v===undefined||v===''||v==='NI'); }
function bool(v){
  if(v===true||v===false)return v;
  var x=norm(v);
  if(['sim','yes','true','ativo','active'].indexOf(x)>=0)return true;
  if(['não','nao','no','false','inativo','inactive'].indexOf(x)>=0)return false;
  return null;
}
function num(v){
  var n=Number(String(v==null?'':v).replace(',','.').replace(/[^\d.-]/g,''));
  return Number.isFinite(n)?n:null;
}
function pick(list,v){
  var x=norm(v);
  return list.find(function(y){return norm(y)===x})||null;
}
function formation(v){
  var x=String(v==null?'':v).toUpperCase().replace(/\s+/g,' ').trim();
  if(!x)return null;
  return (Array.isArray(FORMATIONS)?FORMATIONS:[]).find(function(f){
    return String(f).toUpperCase()===x;
  })||null;
}
function parseJsonLoose(s){
  s=String(s||'').trim().replace(/^```(?:json)?\s*/i,'').replace(/\s*```$/,'');
  try{return JSON.parse(s)}catch(_){}
  var a=s.indexOf('{'),b=s.lastIndexOf('}');
  if(a>=0&&b>a){
    return JSON.parse(s.slice(a,b+1).replace(/,\s*([}\]])/g,'$1'));
  }
  throw new Error('JSON inválido');
}

async function frameSig(frame){
  var img=await v21LoadImage(frame.dataUrl),c=document.createElement('canvas');
  c.width=20;c.height=12;
  var x=c.getContext('2d',{willReadFrequently:true});
  x.drawImage(img,0,0,20,12);
  var d=x.getImageData(0,0,20,12).data,out=[];
  for(var i=0;i<d.length;i+=4)out.push((d[i]+d[i+1]+d[i+2])/765);
  return out;
}
function dist(a,b){
  var s=0,m=Math.min(a.length,b.length);
  for(var i=0;i<m;i++)s+=Math.abs(a[i]-b[i]);
  return s/Math.max(1,m);
}
async function diverse(frames,max){
  var a=(frames||[]).filter(Boolean);
  if(a.length<=max)return a;
  var sigs=[];
  for(var i=0;i<a.length;i++){
    try{sigs.push(await frameSig(a[i]))}catch(_){sigs.push([])}
  }
  var picked=[0,a.length-1];
  while(picked.length<max){
    var best=-1,score=-1;
    for(var j=0;j<a.length;j++){
      if(picked.indexOf(j)>=0||!sigs[j].length)continue;
      var md=999;
      for(var k=0;k<picked.length;k++){
        var p=picked[k];if(sigs[p].length)md=Math.min(md,dist(sigs[j],sigs[p]));
      }
      if(md>score){score=md;best=j}
    }
    if(best<0)break;
    picked.push(best);
  }
  picked.sort(function(a,b){return a-b});
  return picked.map(function(i){return a[i]});
}

async function renderHiRes(frame, crop){
  var img=await v21LoadImage(frame.dataUrl);
  var W=img.naturalWidth,H=img.naturalHeight;
  var sx=0,sy=0,sw=W,sh=H;
  if(crop==='top'){
    sy=0; sh=Math.round(H*.62);
  }else if(crop==='bottom'){
    sy=Math.round(H*.28); sh=Math.round(H*.72);
  }
  var outW=Math.min(1800,Math.max(1400,W));
  var outH=Math.round(sh*(outW/sw));
  var c=document.createElement('canvas');c.width=outW;c.height=outH;
  var x=c.getContext('2d');
  x.imageSmoothingEnabled=true;x.imageSmoothingQuality='high';
  x.drawImage(img,sx,sy,sw,sh,0,0,outW,outH);
  var dataUrl=c.toDataURL('image/jpeg',.90);
  return {base64:dataUrl.split(',')[1],mimeType:'image/jpeg'};
}

async function ocrImage(frame,label,crop){
  var key=String(localStorage.getItem(OCR_KEY)||'').trim();
  if(!key)throw new Error('OCR.Space não configurado');
  var im=await renderHiRes(frame,crop);
  var fd=new FormData();
  fd.append('base64Image','data:image/jpeg;base64,'+im.base64);
  fd.append('language','auto');
  fd.append('OCREngine','2');
  fd.append('isTable','false');
  fd.append('isOverlayRequired','false');
  fd.append('detectOrientation','true');
  fd.append('scale','true');

  var ctl=new AbortController(),tm=setTimeout(function(){ctl.abort()},18000);
  try{
    var r=await fetch('https://api.ocr.space/parse/image',{
      method:'POST',signal:ctl.signal,headers:{apikey:key},body:fd
    });
    var raw=await r.text();
    if(!r.ok)throw new Error('OCR.Space HTTP '+r.status);
    var j=JSON.parse(raw);
    if(j&&j.IsErroredOnProcessing){
      var em=Array.isArray(j.ErrorMessage)?j.ErrorMessage.join(' | '):(j.ErrorMessage||j.ErrorDetails||'falhou');
      throw new Error('OCR.Space: '+em);
    }
    var t=(j.ParsedResults||[]).map(function(p){return p.ParsedText||''}).join('\n').trim();
    if(!t)throw new Error('OCR.Space '+label+' sem texto');
    return '### '+label+'\n'+t;
  }catch(e){
    if(e&&e.name==='AbortError')throw new Error('OCR.Space '+label+' timeout');
    throw e;
  }finally{clearTimeout(tm)}
}

async function collectOCR(files){
  var video=(files||[]).find(function(f){return String(f.type||'').indexOf('video/')===0});
  var images=(files||[]).filter(function(f){return String(f.type||'').indexOf('image/')===0});
  var frames=[];
  if(video)frames=await v21ExtractVideoFrames(video,42);
  else for(var i=0;i<images.length;i++)frames.push(await v21ImageToFrame(images[i]));
  if(!frames.length)throw new Error('nenhum quadro disponível');

  var chosen=await diverse(frames,10);
  var texts=[],errors=[];
  for(var j=0;j<chosen.length;j++){
    setProgress(72+Math.min(16,j),'V7.11 · OCR alta resolução '+(j+1)+'/'+chosen.length+'…');
    try{
      // Alterna topo/baixo para aumentar a chance de capturar a tela inicial e Data Analyst.
      var crop=(j%2===0)?'top':'bottom';
      texts.push(await ocrImage(chosen[j],'quadro '+(j+1),crop));
    }catch(e){errors.push(safe(e))}
  }
  if(!texts.length)throw new Error('OCR.Space não retornou texto útil: '+errors.join(' | '));
  return {texts:texts,frames:chosen.length,errors:errors};
}

var PROMPT=[
'Você recebe OCR de vários quadros do MESMO vídeo de análise de partida do OSM 26.',
'Os textos podem repetir, ter erros de OCR e vir de telas diferentes.',
'Consolide APENAS dados realmente presentes. NÃO estime e NÃO invente.',
'',
'REGRAS IMPORTANTES:',
'1. Meu time é o time do usuário do slot atual; rival é o adversário.',
'2. Extraia força geral e os 4 setores GOL/DEF/MEI/ATA de ambos quando visíveis.',
'3. Procure explicitamente: árbitro, casa/fora, bônus do rival, manager/humano, campo de treinamento, treino secreto, formação rival, plano, marcação e impedimento.',
'4. Se TREINO SECRETO estiver ativo, secretTraining=true e deixe null para força/setores/formação/plano/marcação/impedimento rival que estejam ocultos.',
'5. Não use cidade, país, nome de estádio ou ranking como Casa/Fora.',
'6. loginBonus só pode ser 0,1,2,3 ou null.',
'7. trainingCamp, secretTraining, human e offside são true|false|null.',
'8. refereeColor só pode ser Verde|Azul|Amarelo|Laranja|Vermelho|null.',
'9. style só pode ser Jogar pelas alas|Jogo de passes|Bola longa|Contra-ataque|Remate à vista|null.',
'10. marking só pode ser À zona|Individual|null.',
'11. Não copie números de jogadores como força do time.',
'',
'Responda SOMENTE JSON neste formato:',
'{"teamName":null,"opponentTeamName":null,"myTeam":{"overall":null,"goalkeeper":null,"defence":null,"midfield":null,"attack":null},"opponent":{"overall":null,"goalkeeper":null,"defence":null,"midfield":null,"attack":null,"human":null,"manager":null,"loginBonus":null,"trainingCamp":null,"secretTraining":null,"formation":null,"style":null,"marking":null,"offside":null},"match":{"refereeColor":null,"venue":null}}'
].join('\n');

async function structureWithOpenRouter(pack){
  var key=String(localStorage.getItem(OR_KEY)||'').trim();
  if(!key)throw new Error('OpenRouter não configurado');
  var text=pack.texts.join('\n\n');
  // texto somente: pequeno, barato e não sofre o "request too large" das imagens.
  if(text.length>28000)text=text.slice(0,28000);
  var ctl=new AbortController(),tm=setTimeout(function(){ctl.abort()},25000);
  try{
    var r=await fetch('https://openrouter.ai/api/v1/chat/completions',{
      method:'POST',signal:ctl.signal,
      headers:{
        'Content-Type':'application/json',
        'Authorization':'Bearer '+key,
        'HTTP-Referer':location.origin,
        'X-Title':'OSM AI Coach Pro'
      },
      body:JSON.stringify({
        model:'openrouter/free',
        messages:[{role:'user',content:PROMPT+'\n\nOCR REAL:\n'+text}],
        temperature:0,
        max_tokens:2200
      })
    });
    var raw=await r.text();
    if(!r.ok)throw new Error('OpenRouter-texto HTTP '+r.status+': '+raw.slice(0,140));
    var j=JSON.parse(raw);
    return parseJsonLoose(j&&j.choices&&j.choices[0]&&j.choices[0].message?j.choices[0].message.content:'');
  }catch(e){
    if(e&&e.name==='AbortError')throw new Error('OpenRouter-texto timeout');
    throw e;
  }finally{clearTimeout(tm)}
}

function apply(slot,data){
  var now=new Date().toISOString();
  slot.fieldMeta=slot.fieldMeta||{};
  function set(path,val,conf){
    if(val===null||val===undefined||val==='')return;
    setPath(slot,path,val);
    slot.fieldMeta[path]={source:'detected',confidence:conf||.93,updatedAt:now};
  }

  if(data.teamName)set('teamName',String(data.teamName).trim(),.90);
  if(data.opponentTeamName)set('opponent.teamName',String(data.opponentTeamName).trim(),.90);

  ['overall','goalkeeper','defence','midfield','attack'].forEach(function(k){
    var v=num(data.myTeam&&data.myTeam[k]);
    if(v!==null&&v>=20&&v<=200)set('myTeam.'+k,v,.96);
  });

  var sec=bool(data.opponent&&data.opponent.secretTraining);
  if(sec!==null)set('opponent.secretTraining',sec,.98);

  var camp=bool(data.opponent&&data.opponent.trainingCamp);
  if(camp!==null)set('opponent.trainingCamp',camp,.96);

  var hum=bool(data.opponent&&data.opponent.human);
  if(hum!==null)set('opponent.human',hum,.94);

  if(data.opponent&&data.opponent.manager){
    set('opponent.manager',String(data.opponent.manager).trim(),.92);
    set('opponent.human',true,.97);
  }

  var bonus=num(data.opponent&&data.opponent.loginBonus);
  if(bonus!==null&&bonus>=0&&bonus<=3)set('opponent.loginBonus',bonus,.94);

  if(slot.opponent&&slot.opponent.secretTraining===true){
    [
      'opponent.overall','opponent.goalkeeper','opponent.defence','opponent.midfield',
      'opponent.attack','opponent.formation','opponent.style','opponent.marking','opponent.offside'
    ].forEach(function(p){
      setPath(slot,p,null);
      slot.fieldMeta[p]={source:'unknown',confidence:0,updatedAt:now,hiddenBySecretTraining:true};
    });
  }else{
    ['overall','goalkeeper','defence','midfield','attack'].forEach(function(k){
      var v=num(data.opponent&&data.opponent[k]);
      if(v!==null&&v>=20&&v<=200)set('opponent.'+k,v,.95);
    });
    var f=formation(data.opponent&&data.opponent.formation);if(f)set('opponent.formation',f,.96);
    var st=pick(STYLES,data.opponent&&data.opponent.style);if(st)set('opponent.style',st,.96);
    var mk=pick(MARK,data.opponent&&data.opponent.marking);if(mk)set('opponent.marking',mk,.96);
    var of=bool(data.opponent&&data.opponent.offside);if(of!==null)set('opponent.offside',of,.96);
  }

  var ref=pick(REF,data.match&&data.match.refereeColor);if(ref)set('match.refereeColor',ref,.97);
  var v=norm(data.match&&data.match.venue);if(v==='casa')set('match.venue','Casa',.96);else if(v==='fora')set('match.venue','Fora',.96);
}

async function analyze711(files){
  var slot=selectedSlot();
  setProgress(68,'V7.11 · lendo quadros reais em alta resolução…');
  var pack=await collectOCR(files);
  setProgress(90,'V7.11 · consolidando somente o texto OCR…');
  var data=await structureWithOpenRouter(pack);
  apply(slot,data);

  calcQuality(slot);saveState();renderCoverage(slot);renderAnalysisSummary(slot);renderPregame();

  var missing=missingRequired(slot);
  setAnalysisRun(slot,'tactic',missing.length?'warning':'success',
    'V7.11: OCR alta resolução em '+pack.frames+' telas; '+(missing.length?missing.length+' campo(s) essencial(is) ainda NI':'dados essenciais completos'),
    {quality:slot.analysisQuality,currentRunValidated:true,fullVideoScan:true});

  var d=document.getElementById('analysisDiagnostics');
  if(d)d.textContent='V7.11 PRECISION · OCR.Space alta resolução + OpenRouter texto · '+pack.frames+' telas'+(pack.errors.length?' · '+pack.errors.length+' quadro(s) sem OCR':'');
  setProgress(100,missing.length?'Leitura concluída com pendências':'Partida pronta');

  if(!missing.length&&document.getElementById('autoTactic')&&document.getElementById('autoTactic').checked){
    await generateTactic(state.selectedSlot);
  }
}

v21Analyze=async function(files){
  if(analysisMode!=='tactic'){
    if(previousAnalyze)return previousAnalyze(files);
    throw new Error('analisador anterior indisponível');
  }

  var slot=selectedSlot();
  var snapshot=JSON.parse(JSON.stringify(slot));
  try{
    // Não executa a cadeia antiga da partida: ela é justamente o que vinha contaminando
    // o slot e desperdiçando chamadas em Groq. Para Partida, V7.11 é o leitor principal.
    await analyze711(files);
  }catch(e){
    // Mantém os dados anteriores se a tentativa nova falhar.
    Object.keys(slot).forEach(function(k){delete slot[k]});
    Object.assign(slot,snapshot);
    saveState();
    setAnalysisRun(slot,'tactic','error','V7.11: '+safe(e)+'. Dados anteriores preservados.',{currentRunValidated:false});
    setProgress(100,'Falha sem apagar dados anteriores');
    var d=document.getElementById('analysisDiagnostics');if(d)d.textContent='V7.11 PRECISION · '+safe(e);
  }
};

window.v21Analyze=v21Analyze;
window.OSM_COMPLETE_MATCH_SCAN_VERSION=V711;
try{console.info('[OSM] V7.11 Precision Match Reader ativo')}catch(_){}
})();

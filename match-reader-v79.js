
(function(){
'use strict';
var V79='7.9.0', GROQ_KEY='osm_ai_coach_groq_key';
var prevAnalyze79=(typeof v21Analyze==='function')?v21Analyze:null;
var REF79=['Verde','Azul','Amarelo','Laranja','Vermelho'];
var STYLE79=['Jogar pelas alas','Jogo de passes','Bola longa','Contra-ataque','Remate à vista'];
var MARK79=['À zona','Individual'];

function norm79(v){return String(v==null?'':v).normalize('NFD').replace(/[\u0300-\u036f]/g,'').trim().toLowerCase().replace(/\s+/g,' ')}
function num79(v){var n=Number(String(v==null?'':v).replace(',','.').replace(/[^\d.-]/g,''));return Number.isFinite(n)?n:null}
function bool79(v){if(v===true||v===false)return v;var x=norm79(v);if(['sim','yes','true','ativo','active'].indexOf(x)>=0)return true;if(['não','nao','no','false','inativo','inactive'].indexOf(x)>=0)return false;return null}
function pick79(list,v){var x=norm79(v);return list.find(function(y){return norm79(y)===x})||null}
function form79(v){var x=String(v==null?'':v).toUpperCase().replace(/\s+/g,' ').trim();if(!x)return null;return (Array.isArray(FORMATIONS)?FORMATIONS:[]).find(function(y){return String(y).toUpperCase()===x})||null}
function safe79(e){return String((e&&e.message)||e||'erro').slice(0,220)}

async function sig79(frame){
  var img=await v21LoadImage(frame.dataUrl),c=document.createElement('canvas');
  c.width=18;c.height=10;
  var x=c.getContext('2d',{willReadFrequently:true});x.drawImage(img,0,0,18,10);
  var d=x.getImageData(0,0,18,10).data,out=[];
  for(var i=0;i<d.length;i+=4)out.push((d[i]*.299+d[i+1]*.587+d[i+2]*.114)/255);
  return out;
}
function dist79(a,b){var s=0,m=Math.min(a.length,b.length);for(var i=0;i<m;i++)s+=Math.abs(a[i]-b[i]);return s/Math.max(1,m)}
async function diverse79(frames,max){
  max=max||12;var a=(frames||[]).filter(Boolean);if(a.length<=max)return a;
  var sigs=[];for(var i=0;i<a.length;i++){try{sigs.push(await sig79(a[i]))}catch(e){sigs.push([])}}
  var picked=[0,a.length-1];
  while(picked.length<max){
    var best=-1,bscore=-1;
    for(var j=0;j<a.length;j++){
      if(picked.indexOf(j)>=0||!sigs[j].length)continue;
      var md=Infinity;
      for(var q=0;q<picked.length;q++){var p=picked[q];if(sigs[p].length)md=Math.min(md,dist79(sigs[j],sigs[p]))}
      if(md>bscore){bscore=md;best=j}
    }
    if(best<0)break;picked.push(best);
  }
  picked.sort(function(a,b){return a-b});return picked.map(function(i){return a[i]});
}
async function slim79(frame){
  var img=await v21LoadImage(frame.dataUrl),scale=Math.min(1,680/img.naturalWidth);
  var w=Math.max(1,Math.round(img.naturalWidth*scale)),h=Math.max(1,Math.round(img.naturalHeight*scale));
  var c=document.createElement('canvas');c.width=w;c.height=h;c.getContext('2d').drawImage(img,0,0,w,h);
  var dataUrl=c.toDataURL('image/jpeg',.60);
  return {base64:dataUrl.split(',')[1],dataUrl:dataUrl,mimeType:'image/jpeg',time:frame.time||0};
}
function parse79(t){
  var s=String(t||'').trim().replace(/^```(?:json)?\s*/i,'').replace(/\s*```$/,'');
  try{return JSON.parse(s)}catch(e){}
  var a=s.indexOf('{'),b=s.lastIndexOf('}');
  if(a>=0&&b>a)return JSON.parse(s.slice(a,b+1).replace(/,\s*([}\]])/g,'$1'));
  throw new Error('JSON inválido');
}

var PROMPT79='Leia estes quadros do MESMO vídeo de análise de partida no OSM 26. O vídeo navega pela tela inicial, forças e Data Analyst. Não estime nada. IMPORTANTE: 1) Leia também os QUATRO SETORES DO MEU TIME: GOL, DEF, MEI, ATA. 2) Leia os quatro setores do RIVAL somente se visíveis. 3) Procure explicitamente Campo de Treinamento e Treino Secreto. 4) Procure Formação rival, Plano rival, Marcação e Impedimento. 5) Se houver Treino Secreto, retorne secretTraining=true e deixe null nos dados rivais que o OSM ocultou. 6) Se a tela inicial estiver nítida e não houver indicador de Campo/Treino Secreto, pode retornar false. 7) Não confunda cidade/país com Casa/Fora. 8) Use null quando não estiver visível neste lote. Responda SOMENTE JSON: {"myTeam":{"overall":null,"goalkeeper":null,"defence":null,"midfield":null,"attack":null},"opponent":{"overall":null,"goalkeeper":null,"defence":null,"midfield":null,"attack":null,"human":null,"manager":null,"loginBonus":null,"trainingCamp":null,"secretTraining":null,"formation":null,"style":null,"marking":null,"offside":null},"match":{"refereeColor":null,"venue":null}}';

async function visionBatch79(frames){
  var key=String(localStorage.getItem(GROQ_KEY)||'').trim();if(!key)throw new Error('Groq não configurado');
  var parts=[{type:'text',text:PROMPT79}];
  for(var i=0;i<frames.length;i++){var f=await slim79(frames[i]);parts.push({type:'image_url',image_url:{url:'data:image/jpeg;base64,'+f.base64}})}
  var ctl=new AbortController(),tm=setTimeout(function(){ctl.abort()},20000);
  try{
    var r=await fetch('https://api.groq.com/openai/v1/chat/completions',{
      method:'POST',signal:ctl.signal,
      headers:{'Content-Type':'application/json','Authorization':'Bearer '+key},
      body:JSON.stringify({model:'qwen/qwen3.8-27b',messages:[{role:'user',content:parts}],temperature:0,max_completion_tokens:1500,response_format:{type:'json_object'},reasoning_effort:'none'})
    });
    var raw=await r.text();if(!r.ok)throw new Error('Groq HTTP '+r.status);
    var j=JSON.parse(raw);return parse79(j&&j.choices&&j.choices[0]&&j.choices[0].message?j.choices[0].message.content:'');
  }catch(e){if(e&&e.name==='AbortError')throw new Error('Groq visão: timeout');throw e}
  finally{clearTimeout(tm)}
}
function vals79(results,path){
  var p=path.split('.');
  return results.map(function(r){return p.reduce(function(a,k){return a?a[k]:undefined},r)}).filter(function(v){return v!==null&&v!==undefined&&v!==''});
}
function chooseNum79(results,path){
  var a=vals79(results,path).map(num79).filter(function(n){return n!==null&&n>=20&&n<=200});
  if(!a.length)return null;var counts={},best=a[0],cnt=0;
  a.forEach(function(n){var k=Math.round(n);counts[k]=(counts[k]||0)+1});
  Object.keys(counts).forEach(function(k){if(counts[k]>cnt){best=Number(k);cnt=counts[k]}});
  return best;
}
function chooseBool79(results,path,trueDominates){
  var a=vals79(results,path).map(bool79).filter(function(v){return v!==null});
  if(!a.length)return null;if(trueDominates&&a.indexOf(true)>=0)return true;
  var t=a.filter(Boolean).length,f=a.length-t;return t>f?true:f>t?false:a[a.length-1];
}
function chooseText79(results,path,normalizer){
  var a=vals79(results,path);
  for(var i=a.length-1;i>=0;i--){var v=normalizer?normalizer(a[i]):String(a[i]).trim();if(v)return v}
  return null;
}
async function fullScan79(files){
  var video=(files||[]).find(function(f){return String(f.type||'').indexOf('video/')===0});
  var images=(files||[]).filter(function(f){return String(f.type||'').indexOf('image/')===0});
  var frames=[];
  if(video)frames=await v21ExtractVideoFrames(video,36);
  else for(var i=0;i<images.length;i++)frames.push(await v21ImageToFrame(images[i]));
  if(!frames.length)throw new Error('nenhum quadro disponível');

  var diverse=await diverse79(frames,12),batches=[];
  for(var j=0;j<diverse.length;j+=3)batches.push(diverse.slice(j,j+3));
  var settled=await Promise.allSettled(batches.map(visionBatch79));
  var results=settled.filter(function(x){return x.status==='fulfilled'}).map(function(x){return x.value});
  if(!results.length)throw new Error('leitura visual falhou');
  return {results:results,frames:diverse.length,batches:results.length};
}
function apply79(slot,pack){
  var R=pack.results,now=new Date().toISOString();slot.fieldMeta=slot.fieldMeta||{};
  function set(path,v,conf){if(v===null||v===undefined||v==='')return;setPath(slot,path,v);slot.fieldMeta[path]={source:'detected',confidence:conf||.92,updatedAt:now}}
  ['overall','goalkeeper','defence','midfield','attack'].forEach(function(k){var v=chooseNum79(R,'myTeam.'+k);if(v!==null)set('myTeam.'+k,v,.94)});

  var secret=chooseBool79(R,'opponent.secretTraining',true);if(secret!==null)set('opponent.secretTraining',secret,.97);
  var camp=chooseBool79(R,'opponent.trainingCamp',true);if(camp!==null)set('opponent.trainingCamp',camp,.95);
  var human=chooseBool79(R,'opponent.human',false);if(human!==null)set('opponent.human',human,.92);

  var manager=chooseText79(R,'opponent.manager',function(v){return String(v||'').trim()});
  if(manager){set('opponent.manager',manager,.91);set('opponent.human',true,.96)}

  var bonus=vals79(R,'opponent.loginBonus').map(num79).find(function(n){return n!==null&&n>=0&&n<=3});
  if(bonus!==undefined)set('opponent.loginBonus',bonus,.92);

  if(slot.opponent.secretTraining===true){
    ['opponent.overall','opponent.goalkeeper','opponent.defence','opponent.midfield','opponent.attack','opponent.formation','opponent.style','opponent.marking','opponent.offside'].forEach(function(p){
      setPath(slot,p,null);slot.fieldMeta[p]={source:'unknown',confidence:0,updatedAt:now,hiddenBySecretTraining:true};
    });
  }else{
    ['overall','goalkeeper','defence','midfield','attack'].forEach(function(k){var v=chooseNum79(R,'opponent.'+k);if(v!==null)set('opponent.'+k,v,.93)});
    var formation=chooseText79(R,'opponent.formation',form79);if(formation)set('opponent.formation',formation,.95);
    var style=chooseText79(R,'opponent.style',function(v){return pick79(STYLE79,v)});if(style)set('opponent.style',style,.95);
    var marking=chooseText79(R,'opponent.marking',function(v){return pick79(MARK79,v)});if(marking)set('opponent.marking',marking,.95);
    var off=chooseBool79(R,'opponent.offside',false);if(off!==null)set('opponent.offside',off,.95);
  }

  var ref=chooseText79(R,'match.refereeColor',function(v){return pick79(REF79,v)});if(ref)set('match.refereeColor',ref,.95);
  var venue=chooseText79(R,'match.venue',function(v){var x=norm79(v);return x==='casa'?'Casa':x==='fora'?'Fora':null});if(venue)set('match.venue',venue,.95);
}

var oldBulk79=window.editAllFields;
window.editAllFields=function(n,missingOnly){
  var slot=state.slots[n-1];
  if(!(slot&&slot.opponent&&slot.opponent.secretTraining===true))return oldBulk79(n,missingOnly);
  var hidden=new Set(['opponent.overall','opponent.goalkeeper','opponent.defence','opponent.midfield','opponent.attack','opponent.formation','opponent.style','opponent.marking','opponent.offside']);
  var rows=FIELD_DEFS.filter(function(x){return !hidden.has(x[0])}).filter(function(x){var p=x[0];return !missingOnly||!(hasValue(getPath(slot,p))||typeof getPath(slot,p)==='boolean')});
  openModal('<h2>'+(missingOnly?'Completar campos visíveis ausentes':'Corrigir dados')+' · Slot '+n+'</h2><div class="field-edit"><p class="small muted"><b>Treino secreto ativo:</b> campos ocultos pelo OSM foram removidos desta lista.</p>'+rows.map(function(x,i){return '<label>'+esc(x[1])+'<input id="bulk_'+i+'" data-path="'+x[0]+'" value="'+esc(getPath(slot,x[0])==null?'':getPath(slot,x[0]))+'"></label>'}).join('')+'<p class="small muted">Use NI ou deixe vazio apenas quando realmente não souber.</p><button class="btn" onclick="saveBulkFields('+n+')">Salvar alterações</button></div>');
};

v21Analyze=async function(files){
  if(!prevAnalyze79)throw new Error('analisador anterior indisponível');
  await prevAnalyze79(files);
  if(analysisMode!=='tactic')return;

  var slot=selectedSlot(),pack=null;
  try{
    setProgress(78,'V7.9 · varrendo todo o vídeo e removendo quadros repetidos…');
    pack=await fullScan79(files);
    apply79(slot,pack);
    calcQuality(slot);saveState();renderCoverage(slot);renderAnalysisSummary(slot);renderPregame();

    var missing=missingRequired(slot);
    setAnalysisRun(slot,'tactic',missing.length?'warning':'success',
      'V7.9: varredura completa em '+pack.frames+' telas distintas; '+(missing.length?missing.length+' campo(s) essencial(is) ainda NI':'dados essenciais completos'),
      {quality:slot.analysisQuality,currentRunValidated:true,fullVideoScan:true});

    if(!missing.length&&document.getElementById('autoTactic')&&document.getElementById('autoTactic').checked){
      setProgress(94,'V7.9 · gerando tática com leitura completa…');
      await generateTactic(state.selectedSlot);
    }
    setProgress(100,missing.length?'Leitura completa com pendências':'Partida pronta');
  }catch(e){
    var d=document.getElementById('analysisDiagnostics');
    if(d)d.textContent='V7.9 varredura complementar: '+safe79(e);
  }
  var d2=document.getElementById('analysisDiagnostics');
  if(d2&&pack)d2.textContent='V7.9 COMPLETE SCAN · '+pack.frames+' quadros distintos · '+pack.batches+' lotes · setores próprios + Data Analyst';
};
window.v21Analyze=v21Analyze;
window.OSM_COMPLETE_MATCH_SCAN_VERSION=V79;
})();


(function(){
'use strict';

var V711='7.14.0';
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


function textLines(text){
  return String(text||'').split(/\r?\n/).map(function(x){return x.trim()}).filter(Boolean);
}
function allOCR(pack){
  return (pack&&pack.texts?pack.texts:[]).join('\n');
}
function findNear(lines, keys, windowSize){
  var ks=keys.map(norm), out=[];
  for(var i=0;i<lines.length;i++){
    var n=norm(lines[i]);
    if(ks.some(function(k){return n.indexOf(k)>=0})){
      for(var j=Math.max(0,i-2);j<=Math.min(lines.length-1,i+(windowSize||3));j++){
        if(j!==i)out.push(lines[j]);
      }
    }
  }
  return out;
}
function firstNum(s,min,max){
  var ms=String(s||'').replace(',','.').match(/\b\d{1,3}(?:\.\d+)?\b/g)||[];
  for(var i=0;i<ms.length;i++){
    var n=Number(ms[i]);
    if(Number.isFinite(n)&&(min==null||n>=min)&&(max==null||n<=max))return n;
  }
  return null;
}
function parseRef(text){
  var n=norm(text);
  if(n.indexOf('vermelho')>=0)return 'Vermelho';
  if(n.indexOf('laranja')>=0)return 'Laranja';
  if(n.indexOf('amarelo')>=0)return 'Amarelo';
  if(n.indexOf('azul')>=0)return 'Azul';
  if(n.indexOf('verde')>=0)return 'Verde';
  return null;
}
function parseBoolText(text, positive, negative){
  var n=norm(text);
  if((negative||[]).some(function(x){return n.indexOf(norm(x))>=0}))return false;
  if((positive||[]).some(function(x){return n.indexOf(norm(x))>=0}))return true;
  return null;
}
function parseLocalOCR(pack){
  var text=allOCR(pack), lines=textLines(text), n=norm(text);
  var out={
    teamName:null,opponentTeamName:null,
    myTeam:{overall:null,goalkeeper:null,defence:null,midfield:null,attack:null},
    opponent:{overall:null,goalkeeper:null,defence:null,midfield:null,attack:null,human:null,manager:null,loginBonus:null,trainingCamp:null,secretTraining:null,formation:null,style:null,marking:null,offside:null},
    match:{refereeColor:null,venue:null}
  };

  // Árbitro.
  var refContext=findNear(lines,['arbitro','referee'],3).join(' ');
  out.match.refereeColor=parseRef(refContext)||parseRef(text);

  // Casa/Fora.
  var venueContext=findNear(lines,['casa','fora','home','away'],1).join(' ');
  var nv=norm(venueContext);
  if(/\bfora\b|\baway\b/.test(nv))out.match.venue='Fora';
  else if(/\bcasa\b|\bhome\b/.test(nv))out.match.venue='Casa';

  // Treino secreto e campo de treinamento.
  var secretCtx=findNear(lines,['treino secreto','treino secr','secret training','secret'],4).join(' ');
  if(secretCtx){
    out.opponent.secretTraining=parseBoolText(secretCtx,
      ['sim','ativo','activated','yes'],
      ['não','nao','inativo','not active','no']);
  }
  var campCtx=findNear(lines,['campo de treinamento','campo treinamento','campo treino','training camp'],4).join(' ');
  if(campCtx){
    out.opponent.trainingCamp=parseBoolText(campCtx,
      ['sim','ativo','activated','yes'],
      ['não','nao','inativo','not active','no']);
  }

  // Formação.
  var fm = String(text).match(/\b(?:3|4|5|6)[-–](?:2|3|4|5|6)[-–](?:1|2|3|4|5)(?:\s*[AB])?\b/ig) || [];
  for(var i=0;i<fm.length;i++){
    var f=formation(fm[i].replace(/–/g,'-').replace(/\s+/g,' '));
    if(f){out.opponent.formation=f;break;}
  }

  // Plano.
  var styles=[
    ['Jogar pelas alas',['jogar pelas alas','wing play','pelas alas']],
    ['Jogo de passes',['jogo de passes','passing game','passes']],
    ['Bola longa',['bola longa','long ball']],
    ['Contra-ataque',['contra-ataque','contra ataque','counter attack']],
    ['Remate à vista',['remate a vista','remate à vista','shoot on sight']]
  ];
  styles.some(function(it){
    if(it[1].some(function(k){return n.indexOf(norm(k))>=0})){out.opponent.style=it[0];return true;}
    return false;
  });

  // Marcação.
  if(n.indexOf('individual')>=0||n.indexOf('man marking')>=0)out.opponent.marking='Individual';
  else if(n.indexOf('zona')>=0||n.indexOf('zonal')>=0)out.opponent.marking='À zona';

  // Impedimento.
  var offCtx=findNear(lines,['impedimento','fora de jogo','fora-de-jogo','offside'],4).join(' ');
  if(offCtx){
    out.opponent.offside=parseBoolText(offCtx,['sim','yes','ativo'],['não','nao','no','inativo']);
  }

  // Bônus.
  var bonusCtx=findNear(lines,['bonus','bônus','login'],3).join(' ');
  var bm=String(bonusCtx).match(/\b([0-3])\s*%/);
  if(bm)out.opponent.loginBonus=Number(bm[1]);

  // Humano/manager.
  var mgrCtx=findNear(lines,['manager','treinador','gestor'],2);
  if(mgrCtx.length){
    var cand=mgrCtx.find(function(x){
      var z=norm(x);
      return z.length>=3 && !/\b\d+\b/.test(z) && ['manager','treinador','gestor'].every(function(k){return z.indexOf(k)<0});
    });
    if(cand){out.opponent.manager=cand.slice(0,50);out.opponent.human=true;}
  }

  // Setores: procura linhas que tenham rótulo e um número plausível perto.
  var sectorMap=[
    ['goalkeeper',['gol','gk','guarda-redes','goalkeeper']],
    ['defence',['def','defesa','defence']],
    ['midfield',['mei','meio','midfield']],
    ['attack',['ata','ataque','attack']]
  ];
  sectorMap.forEach(function(it){
    var ctx=findNear(lines,it[1],2);
    for(var i=0;i<ctx.length;i++){
      var v=firstNum(ctx[i],20,200);
      if(v!==null){
        // primeiro conjunto encontrado vai para meu time; segundo, rival
        if(out.myTeam[it[0]]===null)out.myTeam[it[0]]=v;
        else if(out.opponent[it[0]]===null && v!==out.myTeam[it[0]])out.opponent[it[0]]=v;
      }
    }
  });

  // Força geral: tenta capturar padrões VS.
  var vsLines=lines.filter(function(x){return /\bvs\b/i.test(x)});
  var nums=[];
  vsLines.concat(findNear(lines,['vs'],2)).forEach(function(x){
    var mm=String(x).match(/\b\d{2,3}\b/g)||[];
    mm.forEach(function(v){var q=Number(v);if(q>=20&&q<=200)nums.push(q);});
  });
  if(nums.length>=2){out.myTeam.overall=nums[0];out.opponent.overall=nums[1];}

  return out;
}
function mergeData(base, extra){
  if(!extra)return base;
  function rec(a,b){
    Object.keys(b||{}).forEach(function(k){
      if(b[k]&&typeof b[k]==='object'&&!Array.isArray(b[k])){
        if(!a[k]||typeof a[k]!=='object')a[k]={};
        rec(a[k],b[k]);
      }else if((a[k]===null||a[k]===undefined||a[k]===''||a[k]==='NI') &&
               b[k]!==null&&b[k]!==undefined&&b[k]!==''){
        a[k]=b[k];
      }
    });
  }
  rec(base,extra);
  return base;
}
function usefulCountData(d){
  var c=0;
  function walk(x){
    Object.keys(x||{}).forEach(function(k){
      var v=x[k];
      if(v&&typeof v==='object'&&!Array.isArray(v))walk(v);
      else if(v!==null&&v!==undefined&&v!=='')c++;
    });
  }
  walk(d);return c;
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


var _tessPromise=null;
async function ensureTesseract(){
  if(window.Tesseract)return window.Tesseract;
  if(_tessPromise)return _tessPromise;
  _tessPromise=new Promise(function(resolve,reject){
    var s=document.createElement('script');
    s.src='https://cdn.jsdelivr.net/npm/tesseract.js@5/dist/tesseract.min.js';
    s.async=true;
    s.onload=function(){window.Tesseract?resolve(window.Tesseract):reject(new Error('Tesseract indisponível'));};
    s.onerror=function(){reject(new Error('Falha ao carregar OCR local'));};
    document.head.appendChild(s);
  });
  return _tessPromise;
}

async function renderLocalOCR(frame,crop){
  var img=await v21LoadImage(frame.dataUrl);
  var W=img.naturalWidth,H=img.naturalHeight;
  var sx=0,sy=0,sw=W,sh=H;
  if(crop==='top'){ sy=0; sh=Math.round(H*.68); }
  else if(crop==='bottom'){ sy=Math.round(H*.22); sh=Math.round(H*.78); }
  var outW=Math.min(1400,Math.max(1000,W));
  var outH=Math.round(sh*(outW/sw));
  var cv=document.createElement('canvas');cv.width=outW;cv.height=outH;
  var x=cv.getContext('2d');
  x.imageSmoothingEnabled=true;x.imageSmoothingQuality='high';
  x.drawImage(img,sx,sy,sw,sh,0,0,outW,outH);
  return cv;
}

async function ocrLocal(frame,label,crop){
  var T=await ensureTesseract();
  var cv=await renderLocalOCR(frame,crop);
  var r=await T.recognize(cv,'por+eng',{
    logger:function(m){
      if(m&&m.status==='recognizing text'&&typeof m.progress==='number'){
        setProgress(76+Math.round(m.progress*10),'OCR local '+label+' '+Math.round(m.progress*100)+'%…');
      }
    }
  });
  var t=r&&r.data&&r.data.text?String(r.data.text).trim():'';
  if(!t)throw new Error('OCR local '+label+' sem texto');
  return '### '+label+' [local]\n'+t;
}

async function remoteOCRRequest(frame,label,crop,engine){
  var key=String(localStorage.getItem(OCR_KEY)||'').trim();
  if(!key)throw new Error('OCR.Space não configurado');
  var im=await renderHiRes(frame,crop);
  var fd=new FormData();
  fd.append('base64Image','data:image/jpeg;base64,'+im.base64);
  fd.append('language','auto');
  fd.append('OCREngine',String(engine||2));
  fd.append('isTable','false');
  fd.append('isOverlayRequired','false');
  fd.append('detectOrientation','true');
  fd.append('scale','true');

  var ctl=new AbortController(),tm=setTimeout(function(){ctl.abort()},15000);
  try{
    var r=await fetch('https://api.ocr.space/parse/image',{
      method:'POST',signal:ctl.signal,headers:{apikey:key},body:fd
    });
    var raw=await r.text();
    if(!r.ok){
      var err=new Error('OCR.Space HTTP '+r.status);
      err.httpStatus=r.status;
      throw err;
    }
    var j=JSON.parse(raw);
    if(j&&j.IsErroredOnProcessing){
      var em=Array.isArray(j.ErrorMessage)?j.ErrorMessage.join(' | '):(j.ErrorMessage||j.ErrorDetails||'falhou');
      throw new Error('OCR.Space: '+em);
    }
    var t=(j.ParsedResults||[]).map(function(p){return p.ParsedText||''}).join('\n').trim();
    if(!t)throw new Error('OCR.Space '+label+' sem texto');
    return '### '+label+' [OCR.Space E'+engine+']\n'+t;
  }catch(e){
    if(e&&e.name==='AbortError')throw new Error('OCR.Space '+label+' timeout');
    throw e;
  }finally{clearTimeout(tm)}
}

async function ocrImage(frame,label,crop){
  var errs=[];
  // 1) tenta engine 2
  try{
    return {text:await remoteOCRRequest(frame,label,crop,2),source:'remote'};
  }catch(e){errs.push(safe(e));}
  // 2) em indisponibilidade do serviço, tenta engine 1 uma vez
  try{
    await new Promise(function(r){setTimeout(r,450);});
    return {text:await remoteOCRRequest(frame,label,crop,1),source:'remote'};
  }catch(e){errs.push(safe(e));}
  // 3) fallback gratuito no próprio Android/navegador
  try{
    return {text:await ocrLocal(frame,label,crop),source:'local'};
  }catch(e){errs.push(safe(e));}
  throw new Error(errs.join(' | '));
}

async function collectOCR(files){
  var video=(files||[]).find(function(f){return String(f.type||'').indexOf('video/')===0});
  var images=(files||[]).filter(function(f){return String(f.type||'').indexOf('image/')===0});
  var frames=[];
  if(video)frames=await v21ExtractVideoFrames(video,36);
  else for(var i=0;i<images.length;i++)frames.push(await v21ImageToFrame(images[i]));
  if(!frames.length)throw new Error('nenhum quadro disponível');

  // O vídeo é gravado percorrendo as telas em sequência. A seleção por diversidade
  // visual podia pular o Data Analyst porque várias telas do OSM são parecidas.
  // Na V7.14 cobrimos a linha do tempo inteira, em ordem.
  var targetCount=Math.min(12,frames.length), chosen=[], used={};
  for(var ci=0;ci<targetCount;ci++){
    var fi=targetCount===1?0:Math.round(ci*(frames.length-1)/(targetCount-1));
    if(!used[fi]){used[fi]=true;chosen.push(frames[fi]);}
  }
  var texts=[],errors=[],remoteCount=0,localCount=0;

  for(var j=0;j<chosen.length;j++){
    setProgress(72+Math.min(14,j),'V7.14 · lendo tela '+(j+1)+'/'+chosen.length+'…');
    // Alterna topo, base e tela inteira. A tela inteira captura o centro do Data Analyst.
    var crop=(j%3===0)?'full':((j%3===1)?'top':'bottom');
    try{
      var res=await ocrImage(chosen[j],'quadro '+(j+1),crop);
      texts.push(res.text);
      if(res.source==='local')localCount++; else remoteCount++;
    }catch(e){errors.push(safe(e));}
  }

  // Reforço local sempre em quatro pontos diferentes do vídeo.
  // É propositalmente em tela inteira para procurar formação, plano, marcação,
  // impedimento, campo de treinamento e treino secreto.
  if(chosen.length){
    var indexes=[0,Math.floor(chosen.length/3),Math.floor(chosen.length*2/3),chosen.length-1]
      .filter(function(v,i,a){return a.indexOf(v)===i;});
    for(var q=0;q<indexes.length;q++){
      try{
        var txt=await ocrLocal(chosen[indexes[q]],'reforço '+(q+1),'full');
        texts.push(txt);localCount++;
      }catch(e){errors.push(safe(e));}
    }
  }

  if(!texts.length)throw new Error('OCR remoto e OCR local não retornaram texto útil: '+errors.join(' | '));
  return {texts:texts,frames:chosen.length,errors:errors,remoteCount:remoteCount,localCount:localCount};
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
  var local=parseLocalOCR(pack);
  var key=String(localStorage.getItem(OR_KEY)||'').trim();
  if(!key)return local;

  var text=allOCR(pack);
  if(!text.trim())return local;

  // Chamadas curtas e independentes. Se alguma falhar, o OCR local continua valendo.
  var chunks=[];
  var max=8500;
  for(var i=0;i<text.length;i+=max)chunks.push(text.slice(i,i+max));
  chunks=chunks.slice(0,3);

  var attempts=[];
  for(var j=0;j<chunks.length;j++){
    attempts.push((async function(chunk,idx){
      var ctl=new AbortController(),tm=setTimeout(function(){ctl.abort()},12000);
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
            messages:[{role:'user',content:PROMPT+'\n\nTRECHO OCR '+(idx+1)+':\n'+chunk}],
            temperature:0,
            max_tokens:1200
          })
        });
        var raw=await r.text();
        if(!r.ok)throw new Error('OpenRouter '+r.status);
        var jj=JSON.parse(raw);
        return parseJsonLoose(jj&&jj.choices&&jj.choices[0]&&jj.choices[0].message?jj.choices[0].message.content:'');
      }catch(e){
        return null;
      }finally{clearTimeout(tm)}
    })(chunks[j],j));
  }

  var rs=await Promise.all(attempts);
  rs.forEach(function(x){if(x)mergeData(local,x);});
  return local;
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

async function analyze713(files){
  var slot=selectedSlot();
  setProgress(68,'V7.14 · lendo quadros; OCR local entra automaticamente se a API falhar…');
  var pack=await collectOCR(files);
  setProgress(90,'V7.14 · consolidando OCR; IA é apenas complemento…');
  var data=await structureWithOpenRouter(pack);
  if(usefulCountData(data)===0)throw new Error('OCR sem campos reconhecíveis');
  apply(slot,data);

  calcQuality(slot);saveState();renderCoverage(slot);renderAnalysisSummary(slot);renderPregame();

  var missing=missingRequired(slot);
  setAnalysisRun(slot,'tactic',missing.length?'warning':'success',
    'V7.14: '+pack.frames+' telas; OCR.Space '+pack.remoteCount+' / OCR local '+pack.localCount+'; '+(missing.length?missing.length+' campo(s) realmente obrigatório(s) ainda NI':'pronto para gerar tática; demais NI não bloqueiam'),
    {quality:slot.analysisQuality,currentRunValidated:true,fullVideoScan:true});

  var d=document.getElementById('analysisDiagnostics');
  if(d)d.textContent='V7.14 STABLE SCAN · '+pack.frames+' telas distribuídas pelo vídeo · OCR.Space '+pack.remoteCount+' · OCR local '+pack.localCount+(pack.errors.length?' · '+pack.errors.length+' tentativa(s) falharam':'');
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
    await analyze713(files);
  }catch(e){
    // Mantém os dados anteriores se a tentativa nova falhar.
    Object.keys(slot).forEach(function(k){delete slot[k]});
    Object.assign(slot,snapshot);
    saveState();
    setAnalysisRun(slot,'tactic','error','V7.14: '+safe(e)+'. Dados anteriores preservados.',{currentRunValidated:false});
    setProgress(100,'Falha sem apagar dados anteriores');
    var d=document.getElementById('analysisDiagnostics');if(d)d.textContent='V7.14 RESILIENT · '+safe(e);
  }
};

window.v21Analyze=v21Analyze;
window.OSM_COMPLETE_MATCH_SCAN_VERSION=V711;
try{console.info('[OSM] V7.14 Stable Scan Match Reader ativo')}catch(_){}
})();

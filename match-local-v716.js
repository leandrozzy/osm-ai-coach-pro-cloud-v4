
(function(){
'use strict';

const VERSION='7.16.0';

function nrm(v){
  return String(v ?? '').normalize('NFD')
    .replace(/[\u0300-\u036f]/g,'')
    .toLowerCase().replace(/\s+/g,' ').trim();
}
function item(v,c=.86){ return {value:v,confidence:c}; }
function nums(s,min=20,max=200){
  return (String(s||'').match(/\b\d{2,3}\b/g)||[])
    .map(Number).filter(x=>x>=min&&x<=max);
}
function ctx(lines,keys,span=2){
  const ks=keys.map(nrm),out=[];
  for(let i=0;i<lines.length;i++){
    const z=nrm(lines[i]);
    if(ks.some(k=>z.includes(k))){
      for(let j=Math.max(0,i-span);j<=Math.min(lines.length-1,i+span);j++) out.push(lines[j]);
    }
  }
  return out;
}
function boolFrom(s){
  const z=nrm(s);
  if(/\b(nao|no|inactive|inativo|desativado|off)\b/.test(z)) return false;
  if(/\b(sim|yes|active|ativo|ativado|on)\b/.test(z)) return true;
  return null;
}
function formationFrom(text){
  const ms=String(text||'').match(/\b(?:3|4|5|6)\s*[-–]\s*(?:2|3|4|5|6)\s*[-–]\s*(?:1|2|3|4|5)(?:\s*[AB])?\b/ig)||[];
  for(const m of ms){
    const f=m.replace(/–/g,'-').replace(/\s*-\s*/g,'-').replace(/\s+/g,' ').toUpperCase();
    const hit=(Array.isArray(FORMATIONS)?FORMATIONS:[]).find(x=>String(x).toUpperCase()===f);
    if(hit)return hit;
  }
  return null;
}
function merge(a,b){
  const out={...(a||{})};
  for(const [k,v] of Object.entries(b||{})){
    if(v===undefined)continue;
    const old=out[k];
    const oldConf=old&&typeof old==='object'&&Number.isFinite(Number(old.confidence))?Number(old.confidence):0;
    const newConf=v&&typeof v==='object'&&Number.isFinite(Number(v.confidence))?Number(v.confidence):.5;
    const oldVal=old&&typeof old==='object'&&'value'in old?old.value:old;
    if(oldVal===null||oldVal===undefined||oldVal===''||String(oldVal).toUpperCase()==='NI'||newConf>oldConf)out[k]=v;
  }
  return out;
}

function parseLocal(text,slot){
  const lines=String(text||'').split(/\r?\n/).map(x=>x.trim()).filter(Boolean);
  const all=nrm(text), out={};

  const rz=nrm(ctx(lines,['arbitro','referee','juiz'],2).join(' '));
  for(const c of ['Vermelho','Laranja','Amarelo','Azul','Verde']){
    if(rz.includes(nrm(c))){out.referee=item(c,.97);break;}
  }

  const f=formationFrom(text);
  if(f)out.opponentFormation=item(f,.93);

  const styles=[
    ['Jogar pelas alas',['jogar pelas alas','pelas alas','wing play']],
    ['Jogo de passes',['jogo de passes','passing game','passes']],
    ['Bola longa',['bola longa','long ball']],
    ['Contra-ataque',['contra-ataque','contra ataque','counter attack']],
    ['Remate à vista',['remate a vista','shoot on sight']]
  ];
  for(const [name,ks] of styles){
    if(ks.some(k=>all.includes(nrm(k)))){out.opponentStyle=item(name,.92);break;}
  }

  if(/\bindividual\b|\bman marking\b/.test(all)) out.opponentMarking=item('Individual',.93);
  else if(/\bzona\b|\bzonal\b/.test(all)) out.opponentMarking=item('À zona',.93);

  const off=boolFrom(ctx(lines,['impedimento','fora de jogo','fora-de-jogo','offside'],2).join(' '));
  if(off!==null)out.opponentOffside=item(off,.93);

  const secret=boolFrom(ctx(lines,['treino secreto','secret training'],3).join(' '));
  if(secret!==null)out.opponentSecretTraining=item(secret,.95);

  const camp=boolFrom(ctx(lines,['campo de treinamento','campo treinamento','training camp'],3).join(' '));
  if(camp!==null)out.opponentTrainingCamp=item(camp,.94);

  const bonus=ctx(lines,['bonus','bônus','login'],2).join(' ');
  const bm=String(bonus).match(/\b([0-3])\s*%/);
  if(bm)out.opponentLoginBonus=item(Number(bm[1]),.92);

  const vs=ctx(lines,['vs'],2);
  let pair=null;
  for(const l of vs){
    const a=nums(l);
    if(a.length>=2){pair=[a[0],a[1]];break;}
  }
  if(!pair){
    const a=nums(vs.join(' '));
    if(a.length>=2)pair=[a[0],a[1]];
  }
  if(pair){
    out.myOverall=item(pair[0],.90);
    out.oppOverall=item(pair[1],.90);
  }

  const sectors=[
    ['myGoalkeeper','oppGoalkeeper',['goalkeeper','gk','gol','guarda-redes','goleiro']],
    ['myDefence','oppDefence',['defence','defesa','def']],
    ['myMidfield','oppMidfield',['midfield','meio','mei']],
    ['myAttack','oppAttack',['attack','ataque','ata']]
  ];
  for(const [mine,rival,keys] of sectors){
    const cand=ctx(lines,keys,1);
    let found=null;
    for(const l of cand){
      const a=nums(l);
      if(a.length>=2){found=[a[0],a[1]];break;}
    }
    if(!found){
      const a=nums(cand.join(' '));
      if(a.length>=2)found=[a[0],a[1]];
    }
    if(found){
      out[mine]=item(found[0],.85);
      out[rival]=item(found[1],.85);
    }
  }

  if(slot?.teamName)out.teamName=item(slot.teamName,1);
  if(slot?.opponent?.teamName)out.opponentName=item(slot.opponent.teamName,1);
  return out;
}

async function imgFromB64(b64){
  return new Promise((resolve,reject)=>{
    const im=new Image();
    im.onload=()=>resolve(im);
    im.onerror=reject;
    im.src='data:image/jpeg;base64,'+b64;
  });
}
async function prepCanvas(b64){
  const im=await imgFromB64(b64);
  const W=im.naturalWidth,H=im.naturalHeight;
  const sy=Math.round(H*.08), sh=Math.round(H*.86);
  const outW=Math.min(1500,Math.max(1100,Math.round(W*1.35)));
  const outH=Math.round(sh*outW/W);
  const c=document.createElement('canvas');
  c.width=outW;c.height=outH;
  const x=c.getContext('2d',{willReadFrequently:true});
  x.drawImage(im,0,sy,W,sh,0,0,outW,outH);
  const id=x.getImageData(0,0,outW,outH),d=id.data;
  for(let i=0;i<d.length;i+=4){
    const y=.299*d[i]+.587*d[i+1]+.114*d[i+2];
    const v=y>180?255:y<60?0:Math.max(0,Math.min(255,(y-128)*1.65+128));
    d[i]=d[i+1]=d[i+2]=v;
  }
  x.putImageData(id,0,0);
  return c;
}
async function localOcrFrame(b64,label){
  if(!window.Tesseract)throw new Error('OCR local não carregado');
  const c=await prepCanvas(b64);
  const r=await window.Tesseract.recognize(c,'por+eng',{
    logger:m=>{
      if(m?.status==='recognizing text'&&Number.isFinite(m.progress)){
        setProgress(30+Math.round(m.progress*8),'OCR local '+label+' '+Math.round(m.progress*100)+'%…');
      }
    }
  });
  return String(r?.data?.text||'').trim();
}
async function extractFrames(files){
  const out=[];
  for(const f of files||[]){
    if(String(f.type||'').startsWith('image/')){
      out.push(await fileToInline(f));
    }else if(String(f.type||'').startsWith('video/')){
      const fs=await videoFrames(f,8);
      out.push(...fs);
    }
  }
  return out.slice(0,10);
}

async function analyzeLocalOnly(files){
  const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot;
  state.selectedSlot=slotNo;
  const slot=state.slots[slotNo-1];

  setProgress(8,'V7.16 · extraindo quadros…');
  const frames=await extractFrames(files);
  if(!frames.length)throw new Error('Nenhum quadro utilizável.');

  let merged={},texts=[];
  const picks=[0,1,2,3,4,5,6,7].filter(i=>i<frames.length);

  for(let k=0;k<picks.length;k++){
    setProgress(16+Math.round((k/picks.length)*62),'V7.16 · OCR local '+(k+1)+'/'+picks.length+'…');
    try{
      const t=await localOcrFrame(frames[picks[k]],(k+1)+'/'+picks.length);
      if(t){
        texts.push(t);
        merged=merge(merged,parseLocal(t,slot));
      }
    }catch(_){}
  }

  if(texts.length)merged=merge(merged,parseLocal(texts.join('\n'),slot));
  if(!Object.keys(merged).length)throw new Error('OCR local não encontrou dados reconhecíveis.');

  // Limpa somente campos que não devem sobreviver como leitura velha/falsa.
  const clear=[
    'myTeam.goalkeeper','myTeam.defence','myTeam.midfield','myTeam.attack',
    'opponent.goalkeeper','opponent.defence','opponent.midfield','opponent.attack',
    'opponent.formation','opponent.style','opponent.marking','opponent.offside',
    'opponent.trainingCamp','opponent.secretTraining'
  ];
  for(const p of clear){
    setPath(slot,p,null);
    if(slot.fieldMeta)slot.fieldMeta[p]={source:'unknown',confidence:0,updatedAt:new Date().toISOString()};
  }

  setProgress(84,'V7.16 · consolidando leitura…');
  applyAnalysis(slotNo,merged);
  const s=state.slots[slotNo-1];

  if(s?.opponent?.secretTraining===true){
    for(const p of ['overall','goalkeeper','defence','midfield','attack','formation','style','marking','offside']){
      s.opponent[p]=null;
      if(s.fieldMeta)s.fieldMeta['opponent.'+p]={
        source:'unknown',confidence:0,updatedAt:new Date().toISOString(),hiddenBySecretTraining:true
      };
    }
  }

  calcQuality(s);
  saveState();
  renderCoverage(s);
  renderAnalysisSummary(s);
  renderPregame();

  const req=missingRequired(s);
  setAnalysisRun(
    s,'tactic',req.length?'warning':'success',
    'V7.16 LOCAL: '+texts.length+'/'+picks.length+' quadros lidos no aparelho; sem Gemini, sem OCR.Space e sem OpenRouter'+
      (req.length?' · '+req.length+' campo(s) obrigatório(s) ainda NI':' · pronto para tática'),
    {quality:s.analysisQuality,currentRunValidated:true,localOnly:true}
  );

  const d=document.getElementById('analysisDiagnostics');
  if(d)d.textContent='V7.16 LOCAL ONLY · '+texts.length+'/'+picks.length+' quadros · nenhuma API externa usada';

  setProgress(100,req.length?'Leitura local concluída com pendência real':'Leitura local concluída');

  if(!req.length&&document.getElementById('autoTactic')?.checked){
    await generateTactic(slotNo);
  }
}

const previous=typeof v21Analyze==='function'?v21Analyze:null;

v21Analyze=async function(files){
  if(analysisMode!=='tactic'){
    if(previous)return previous(files);
    throw new Error('Analisador anterior indisponível.');
  }

  const slot=selectedSlot();
  const snapshot=JSON.parse(JSON.stringify(slot));

  try{
    await analyzeLocalOnly(files);
  }catch(e){
    Object.keys(slot).forEach(k=>delete slot[k]);
    Object.assign(slot,snapshot);
    saveState();
    setAnalysisRun(
      slot,'tactic','error',
      'V7.16 LOCAL: '+String(e?.message||e).slice(0,220)+'. Dados anteriores preservados.',
      {currentRunValidated:false}
    );
    setProgress(100,'Falha local sem apagar dados anteriores');
  }
};

window.v21Analyze=v21Analyze;
window.OSM_MATCH_READER_VERSION=VERSION;
try{console.info('[OSM] V7.16 Local Only ativo')}catch(_){}
})();

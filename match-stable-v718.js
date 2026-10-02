(function(){
'use strict';

const VERSION='7.18.0';
const BASE_MISSING = typeof missingRequired==='function' ? missingRequired : null;
const BASE_QUALITY = typeof calcQuality==='function' ? calcQuality : null;

function norm(v){return String(v??'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/\s+/g,' ').trim()}
function valid(v){return !(v===null||v===undefined||v===''||String(v).toUpperCase()==='NI')}
function W(v,c=.96){return {value:v,confidence:c}}
function mode(nums){
  const a=nums.filter(n=>Number.isFinite(n)); if(!a.length)return null;
  const m=new Map(); for(const n of a)m.set(n,(m.get(n)||0)+1);
  return [...m.entries()].sort((x,y)=>y[1]-x[1]||x[0]-y[0])[0][0];
}
function mergeFields(a,b){
  const out={...(a||{})};
  for(const [k,v] of Object.entries(b||{})){
    if(v==null)continue;
    const ac=Number(out[k]?.confidence||0),bc=Number(v?.confidence||.5);
    if(!valid(out[k]?.value??out[k])||bc>=ac)out[k]=v;
  }
  return out;
}
function crop(img,x,y,w,h,scale=4,threshold=null){
  const IW=img.naturalWidth||img.width,IH=img.naturalHeight||img.height;
  const sx=Math.max(0,Math.round(x*IW)),sy=Math.max(0,Math.round(y*IH));
  const sw=Math.max(2,Math.min(IW-sx,Math.round(w*IW))),sh=Math.max(2,Math.min(IH-sy,Math.round(h*IH)));
  const c=document.createElement('canvas');c.width=Math.max(2,Math.round(sw*scale));c.height=Math.max(2,Math.round(sh*scale));
  const g=c.getContext('2d',{willReadFrequently:true});g.imageSmoothingEnabled=true;g.imageSmoothingQuality='high';g.drawImage(img,sx,sy,sw,sh,0,0,c.width,c.height);
  if(threshold!==null){
    const id=g.getImageData(0,0,c.width,c.height),d=id.data;
    for(let i=0;i<d.length;i+=4){const yv=.299*d[i]+.587*d[i+1]+.114*d[i+2];const v=yv>=threshold?255:0;d[i]=d[i+1]=d[i+2]=v}
    g.putImageData(id,0,0);
  }
  return c;
}
async function frameImage(frame){return new Promise((res,rej)=>{const im=new Image();im.onload=()=>res(im);im.onerror=rej;im.src=frame.dataUrl||('data:image/jpeg;base64,'+frame.base64)})}

function colorStats(img){
  const c=document.createElement('canvas');c.width=96;c.height=48;const g=c.getContext('2d',{willReadFrequently:true});g.drawImage(img,0,0,96,48);
  const d=g.getImageData(0,0,96,48).data;
  let whiteBottom=0,whiteLeft=0,blueRight=0,yellowLeft=0,darkTop=0,darkAll=0,nBottom=0,nLeft=0,nRight=0,nTop=0;
  for(let yy=0;yy<48;yy++)for(let xx=0;xx<96;xx++){
    const i=(yy*96+xx)*4,r=d[i],gg=d[i+1],b=d[i+2];
    const white=Math.min(r,gg,b)>150&&Math.max(r,gg,b)-Math.min(r,gg,b)<55, blue=b>125&&b>r*1.25&&b>gg*1.05, yellow=r>175&&gg>120&&gg<210&&b<110, dark=r+gg+b<185;
    if(yy>=23){nBottom++;if(white)whiteBottom++}
    if(xx<42){nLeft++;if(white)whiteLeft++;if(yellow)yellowLeft++}
    if(xx>=42){nRight++;if(blue)blueRight++}
    if(yy<22){nTop++;if(dark)darkTop++}
    if(dark)darkAll++;
  }
  return {whiteBottom:whiteBottom/nBottom,whiteLeft:whiteLeft/nLeft,blueRight:blueRight/nRight,yellowLeft:yellowLeft/nLeft,darkTop:darkTop/nTop,darkAll:darkAll/(96*48)};
}
function screenType(img){
  const s=colorStats(img);
  // Relatório aberto: papel branco à esquerda + painel azul à direita.
  if(s.whiteLeft>.25 && s.blueRight>.22)return 'analyst';
  // Elenco: metade inferior majoritariamente branca, topo escuro.
  if(s.whiteBottom>.42 && s.darkTop>.20)return 'squad';
  // Tela inicial/versus e páginas de equipa escuras.
  if(s.darkAll>.30 && s.whiteBottom<.25)return 'overview';
  if(s.yellowLeft>.20 && s.blueRight>.16)return 'analystIntro';
  return 'other';
}
function groups(items,type){
  const arr=items.filter(x=>x.type===type);if(!arr.length)return [];
  const out=[];let cur=[arr[0]];
  for(let i=1;i<arr.length;i++){
    if(arr[i].i-arr[i-1].i<=2)cur.push(arr[i]);else{out.push(cur);cur=[arr[i]]}
  }
  out.push(cur);return out.filter(g=>g.length>=1);
}
function reps(group,n=3){
  if(!group?.length)return [];
  if(group.length<=n)return group;
  const idx=[];for(let i=0;i<n;i++)idx.push(Math.round((group.length-1)*(i+1)/(n+1)));
  return [...new Set(idx)].map(i=>group[i]);
}
function drawerShift(img){
  const c=document.createElement('canvas');c.width=140;c.height=60;const g=c.getContext('2d',{willReadFrequently:true});g.drawImage(img,0,0,140,60);
  const d=g.getImageData(0,0,140,60).data;
  for(let x=92;x<138;x++){
    let blue=0,total=0;
    for(let y=5;y<58;y++){const i=(y*140+x)*4,r=d[i],gg=d[i+1],b=d[i+2];total++;if(b>100&&gg>45&&gg<200&&r<120&&b>r*1.4)blue++}
    if(blue/total>.50){
      let sustained=true;for(let xx=x;xx<Math.min(140,x+5);xx++){let bb=0;for(let y=5;y<58;y++){const i=(y*140+xx)*4,r=d[i],gg=d[i+1],b=d[i+2];if(b>100&&gg>45&&gg<200&&r<120&&b>r*1.4)bb++}if(bb/53<.42)sustained=false}
      if(sustained)return Math.min(.16,(140-x)/140+.01);
    }
  }
  return 0;
}
function refereeColor(img){
  const c=crop(img,.472,.39,.060,.16,1,null),g=c.getContext('2d',{willReadFrequently:true}),d=g.getImageData(0,0,c.width,c.height).data;
  const b={Verde:0,Azul:0,Amarelo:0,Laranja:0,Vermelho:0};
  for(let i=0;i<d.length;i+=4){const r=d[i],gg=d[i+1],bl=d[i+2],mx=Math.max(r,gg,bl),mn=Math.min(r,gg,bl);if(mx<95||mx-mn<35)continue;
    if(r>175&&gg<105&&bl<105)b.Vermelho++;else if(r>180&&gg>85&&gg<175&&bl<95)b.Laranja++;else if(r>170&&gg>155&&bl<105)b.Amarelo++;else if(bl>145&&bl>r*1.25&&bl>gg*1.04)b.Azul++;else if(gg>125&&gg>r*1.14&&gg>bl*1.03)b.Verde++}
  const best=Object.entries(b).sort((a,z)=>z[1]-a[1])[0];return best&&best[1]>8?best[0]:null;
}
function secretLock(img){
  // O OSM substitui a força do rival por um círculo cinzento com cadeado quando há Treino Secreto.
  const c=crop(img,.615,.17,.105,.20,1,null),g=c.getContext('2d',{willReadFrequently:true}),d=g.getImageData(0,0,c.width,c.height).data;
  let blue=0,yellow=0,gray=0,total=d.length/4;
  for(let i=0;i<d.length;i+=4){const r=d[i],gg=d[i+1],b=d[i+2];if(b>140&&b>r*1.35&&b>gg*1.05)blue++;if(r>180&&gg>130&&b<100)yellow++;if(Math.abs(r-gg)<18&&Math.abs(gg-b)<18&&r>110&&r<230)gray++}
  return blue/total>.004 && yellow/total>.001 && gray/total>.04;
}


function parseForceNumber(text){
  const m=String(text||'').match(/\d{2,3}/g);if(!m)return null;
  for(const q of m){
    let n=Number(q);if(n>=20&&n<=200)return n;
    // O algarismo 7 do OSM é frequentemente confundido com 1 pelo Tesseract.
    if(n>=10&&n<=19)return 70+(n-10);
  }
  return null;
}
async function readOverviewForces(reader,img){
  const out=[];
  for(const [cx,cy] of [[.338,.257],[.659,.257]]){
    let value=null;
    for(const th of [150,170,190,null]){
      const c=crop(img,cx-.032,cy-.050,.064,.100,5,th);
      const t=await reader.read(c,8,'0123456789');
      value=parseForceNumber(t);if(value!==null)break;
    }
    out.push(value);
  }
  return out;
}
async function createReader(){
  if(!window.Tesseract)throw new Error('OCR local não carregou');
  const worker=await Tesseract.createWorker('por+eng',1,{logger:m=>{if(m?.status==='recognizing text'&&Number.isFinite(m.progress)&&typeof setProgress==='function')setProgress(35+Math.round(m.progress*10),'V7.18 · OCR local '+Math.round(m.progress*100)+'%…')}});
  async function read(canvas,psm=6,whitelist=''){
    try{await worker.setParameters({tessedit_pageseg_mode:String(psm),tessedit_char_whitelist:whitelist||''})}catch(_){}
    const r=await worker.recognize(canvas);return String(r?.data?.text||'').trim();
  }
  return {worker,read,close:()=>worker.terminate()};
}
async function readNumber(reader,img,cx,cy,overall=false,shift=0){
  const x=cx-shift, xr=overall?.024:.018, yr=overall?.042:.032;
  const vals=[];
  for(const th of [145,165,185,null]){
    const c=crop(img,x-xr,cy-yr,xr*2,yr*2,overall?5:6,th);
    const t=await reader.read(c,8,'0123456789');
    const m=String(t).match(/\d{2,3}/g);if(m){for(const q of m){const n=Number(q);if(n>=20&&n<=200)vals.push(n)}}
    if(vals.length)break;
  }
  return vals[0]??null;
}
async function readSquadFrame(reader,img){
  const shift=drawerShift(img);
  const coords=[[.630,.286,false],[.675,.286,false],[.724,.286,false],[.768,.286,false],[.839,.350,true]];
  const vals=[];for(const [x,y,o] of coords)vals.push(await readNumber(reader,img,x,y,o,shift));
  return vals;
}
async function readSquadGroup(reader,group){
  const all=[];for(const x of reps(group,3))all.push(await readSquadFrame(reader,x.img));
  const out=[];for(let j=0;j<5;j++)out.push(mode(all.map(a=>a[j])));return out;
}
function formationFrom(s){const m=String(s||'').match(/\b(?:3|4|5|6)\s*[-–]\s*(?:2|3|4|5|6)\s*[-–]\s*(?:1|2|3|4|5)(?:\s*[AB])?\b/i);if(!m)return null;const x=m[0].replace(/–/g,'-').replace(/\s*-\s*/g,'-').replace(/\s+/g,' ').toUpperCase();return (Array.isArray(FORMATIONS)?FORMATIONS:[]).find(f=>String(f).toUpperCase()===x)||x}
function styleFrom(s){const z=norm(s);if(z.includes('jogo de passe'))return 'Jogo de passes';if(z.includes('pelas alas'))return 'Jogar pelas alas';if(z.includes('bola longa'))return 'Bola longa';if(z.includes('contra-ataque')||z.includes('contra ataque'))return 'Contra-ataque';if(z.includes('remate a vista'))return 'Remate à vista';return null}
function parseAnalyst(left,title,mark,off){
  const out={},all=[left,title,mark,off].join('\n'),z=norm(all),l=norm(left),m=norm(mark),o=norm(off);
  const f=formationFrom(all);if(f)out.opponentFormation=W(f,.99);
  const st=styleFrom(title)||styleFrom(all);if(st)out.opponentStyle=W(st,.99);
  if(m.includes('zona'))out.opponentMarking=W('À zona',.99);else if(m.includes('individual')||m.includes('homem'))out.opponentMarking=W('Individual',.99);
  if(/\bsim\b/.test(o))out.opponentOffside=W(true,.99);else if(/\bnao\b/.test(o))out.opponentOffside=W(false,.99);
  if(l.includes('entradas normal'))out.opponentTackling=W('Normal',.98);else if(l.includes('entradas agress'))out.opponentTackling=W('Agressivo',.98);else if(l.includes('entradas cuidad'))out.opponentTackling=W('Cuidadoso',.98);
  const sm=z.match(/nivel do estadio\s*[:\-]?\s*([0-3o])/i);if(sm)out.opponentStadium=W(sm[1].toLowerCase()==='o'?0:Number(sm[1]),.98);
  if(l.includes('nao foram em estagio'))out.opponentTrainingCamp=W(false,.99);else if(l.includes('foram em estagio'))out.opponentTrainingCamp=W(true,.97);
  return out;
}
async function readAnalystFrame(reader,img){
  const left=await reader.read(crop(img,.018,.20,.385,.36,3,null),6,'');
  const title=await reader.read(crop(img,.49,.00,.48,.085,4,null),7,'');
  const mark=await reader.read(crop(img,.61,.62,.16,.16,4,null),6,'');
  const off=await reader.read(crop(img,.88,.62,.115,.16,4,null),6,'');
  return parseAnalyst(left,title,mark,off);
}

async function denseFrames(files){
  const out=[];
  for(const f of files||[]){
    if(String(f.type||'').startsWith('image/'))out.push(await imageFileToFrame(f));
    else if(String(f.type||'').startsWith('video/')){
      const arr=await videoFrames(f,40);for(const b of arr)out.push({base64:b,mimeType:'image/jpeg',dataUrl:'data:image/jpeg;base64,'+b});
    }
  }
  return out.slice(0,44);
}

function patchSecretAwareRules(){
  if(BASE_MISSING){
    missingRequired=function(s){
      let miss=BASE_MISSING(s);
      if(s?.opponent?.secretTraining===true){
        const hidden=new Set(['opponent.overall','opponent.formation','opponent.style','opponent.marking','opponent.offside']);
        miss=miss.filter(p=>!hidden.has(p));
      }
      return miss;
    };
  }
  calcQuality=function(s){
    const hidden=s?.opponent?.secretTraining===true
      ? new Set(['opponent.overall','opponent.formation','opponent.style','opponent.marking','opponent.offside'])
      : new Set();
    const essential=REQUIRED_TACTIC.filter(p=>!hidden.has(p));
    let ef=0;for(const p of essential){const v=getPath(s,p);if(valid(v)||typeof v==='boolean')ef++}
    s.analysisQuality=essential.length?Math.round(ef/essential.length*100):100;
    let tf=0;for(const [p] of FIELD_DEFS){const v=getPath(s,p);if(valid(v)||typeof v==='boolean')tf++}
    s.optionalCompleteness=FIELD_DEFS.length?Math.round(tf/FIELD_DEFS.length*100):0;
    return s.analysisQuality;
  };
  renderCoverage=function(s){
    calcQuality(s);const essentialMissing=missingRequired(s);const optionalMissing=FIELD_DEFS.filter(([p])=>!(valid(getPath(s,p))||typeof getPath(s,p)==='boolean'));
    const ready=essentialMissing.length===0;
    $('coverageContent').innerHTML=`<div class="audit-card"><div class="audit-top"><div><span class="eyebrow">LEITURA TÁTICA</span><h3>${ready?'Dados essenciais prontos':'Leitura incompleta'}</h3></div><div class="quality-score">${s.analysisQuality}%</div></div><p class="small muted">Essenciais: ${s.analysisQuality}% · cobertura total: ${s.optionalCompleteness}%${s.opponent?.secretTraining===true?' · treino secreto ativo: dados ocultados pelo OSM não bloqueiam a tática':''}.</p>${essentialMissing.length?`<p class="small warn-text">Faltam para gerar tática: ${essentialMissing.map(p=>FIELD_DEFS.find(x=>x[0]===p)?.[1]||p).join(', ')}.</p>`:`<p class="small good-text">A tática pode ser gerada agora. Campos opcionais em NI não bloqueiam o jogo.</p>`}<div class="actions"><button class="btn ghost" onclick="editAllFields(${s.slotNumber},true)">Revisar campos</button><button class="btn" onclick="showView('pregame')">Abrir pré-jogo</button></div></div>`;
  };
}
patchSecretAwareRules();

async function analyzeStable(files){
  const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot;state.selectedSlot=slotNo;const slot=state.slots[slotNo-1];
  setProgress(6,'V7.18 · extraindo 40 pontos do vídeo…');
  const frames=await denseFrames(files);if(!frames.length)throw new Error('Nenhum quadro foi extraído.');
  const typed=[];
  for(let i=0;i<frames.length;i++){const img=await frameImage(frames[i]);typed.push({i,img,frame:frames[i],type:screenType(img)})}
  const squadGroups=groups(typed,'squad').sort((a,b)=>a[0].i-b[0].i);
  const analystGroups=groups(typed,'analyst').sort((a,b)=>a[0].i-b[0].i);
  const overviewFrames=typed.filter(x=>x.type==='overview');

  let secret=false,ref=null;
  for(const x of overviewFrames.slice(0,12)){if(secretLock(x.img))secret=true;const r=refereeColor(x.img);if(r)ref=r}

  setProgress(18,'V7.18 · identificando elenco e Data Analyst…');
  const reader=await createReader();
  let merged={};
  try{
    if(ref)merged.referee=W(ref,.98);
    if(secret)merged.opponentSecretTraining=W(true,.995);

    // Força geral vem da tela inicial, onde os números são grandes e estáveis.
    // Isto elimina o erro recorrente NI × NI mesmo quando o vídeo mostra claramente 84 × 74, etc.
    const overviewReadings=[];
    for(const x of reps(overviewFrames.slice(0,12),3))overviewReadings.push(await readOverviewForces(reader,x.img));
    const myOv=mode(overviewReadings.map(v=>v?.[0]));
    const oppOv=mode(overviewReadings.map(v=>v?.[1]));
    if(myOv!==null)merged.myOverall=W(myOv,.995);
    if(!secret&&oppOv!==null)merged.opponentOverall=W(oppOv,.995);

    // O padrão real dos vídeos é: primeiro o nosso elenco, depois o rival. Não dependemos mais de OCR do nome para decidir o lado.
    if(squadGroups[0]){
      const [g,d,m,a,o]=await readSquadGroup(reader,squadGroups[0]);
      if(g!==null)merged.myGoalkeeper=W(g,.98);if(d!==null)merged.myDefence=W(d,.98);if(m!==null)merged.myMidfield=W(m,.98);if(a!==null)merged.myAttack=W(a,.98);if(o!==null&&!merged.myOverall)merged.myOverall=W(o,.97);
    }
    if(!secret&&squadGroups[1]){
      const [g,d,m,a,o]=await readSquadGroup(reader,squadGroups[1]);
      if(g!==null)merged.opponentGoalkeeper=W(g,.98);if(d!==null)merged.opponentDefence=W(d,.98);if(m!==null)merged.opponentMidfield=W(m,.98);if(a!==null)merged.opponentAttack=W(a,.98);if(o!==null&&!merged.opponentOverall)merged.opponentOverall=W(o,.97);
    }

    // Lemos páginas distintas do relatório: formação e tática/marcação/impedimento. A última página não substitui a anterior.
    const analystFrames=[];for(const g of analystGroups)analystFrames.push(...reps(g,2));
    for(const x of analystFrames.slice(-6))merged=mergeFields(merged,await readAnalystFrame(reader,x.img));
  }finally{await reader.close().catch(()=>{})}

  if(slot?.teamName)merged.teamName=W(slot.teamName,1);if(slot?.opponent?.teamName)merged.opponentName=W(slot.opponent.teamName,1);
  if(!Object.keys(merged).length)throw new Error('O vídeo foi aberto, mas nenhuma tela útil do OSM foi reconhecida.');

  // Limpa os campos específicos desta partida somente depois de termos uma leitura válida.
  const clear=['myTeam.overall','myTeam.goalkeeper','myTeam.defence','myTeam.midfield','myTeam.attack','opponent.overall','opponent.goalkeeper','opponent.defence','opponent.midfield','opponent.attack','match.refereeColor','opponent.formation','opponent.style','opponent.marking','opponent.offside','opponent.trainingCamp','opponent.secretTraining'];
  for(const p of clear){setPath(slot,p,null);if(slot.fieldMeta)slot.fieldMeta[p]={source:'unknown',confidence:0,updatedAt:new Date().toISOString()}}

  applyAnalysis(slotNo,merged);const s=state.slots[slotNo-1];
  if(secret||s?.opponent?.secretTraining===true){
    s.opponent.secretTraining=true;if(s.fieldMeta)s.fieldMeta['opponent.secretTraining']={source:'detected',confidence:.995,updatedAt:new Date().toISOString()};
    for(const p of ['overall','goalkeeper','defence','midfield','attack','formation','style','marking','offside']){s.opponent[p]=null;if(s.fieldMeta)s.fieldMeta['opponent.'+p]={source:'unknown',confidence:0,updatedAt:new Date().toISOString(),hiddenBySecretTraining:true}}
  }else if(s.opponent.secretTraining==null){s.opponent.secretTraining=false;if(s.fieldMeta)s.fieldMeta['opponent.secretTraining']={source:'detected',confidence:.8,updatedAt:new Date().toISOString()}}

  calcQuality(s);saveState();renderCoverage(s);renderAnalysisSummary(s);renderPregame();
  const miss=missingRequired(s);
  const info=`V7.18 STABLE: ${frames.length} quadros · ${squadGroups.length} grupo(s) de elenco · ${analystGroups.length} grupo(s) do Data Analyst${s.opponent.secretTraining===true?' · TREINO SECRETO detectado; dados ocultos não são obrigatórios':''}`;
  setAnalysisRun(s,'tactic',miss.length?'warning':'success',info+(miss.length?` · faltam ${miss.length} campo(s) não encontrados`:' · dados essenciais prontos'),{quality:s.analysisQuality,currentRunValidated:true,stableReader:true,secretTraining:s.opponent.secretTraining===true});
  const d=document.getElementById('analysisDiagnostics');if(d)d.textContent=info+' · sem Gemini/OpenRouter/OCR.Space na leitura da partida';
  setProgress(100,miss.length?'Leitura concluída':'Leitura pronta para tática');
  if(!miss.length&&document.getElementById('autoTactic')?.checked)await generateTactic(slotNo);
}

const previous=typeof v21Analyze==='function'?v21Analyze:null;
v21Analyze=async function(files){
  if(analysisMode!=='tactic'){if(previous)return previous(files);throw new Error('Analisador anterior indisponível.')}
  const slot=selectedSlot(),snapshot=JSON.parse(JSON.stringify(slot));
  try{await analyzeStable(files)}catch(e){Object.keys(slot).forEach(k=>delete slot[k]);Object.assign(slot,snapshot);saveState();setAnalysisRun(slot,'tactic','error','V7.18: '+String(e?.message||e).slice(0,260)+'. Dados anteriores preservados.',{currentRunValidated:false});setProgress(100,'Falha sem apagar os dados anteriores')}
};
window.v21Analyze=v21Analyze;window.OSM_MATCH_READER_VERSION=VERSION;
try{console.info('[OSM] V7.18 Stable Reader ativo')}catch(_){}
})();

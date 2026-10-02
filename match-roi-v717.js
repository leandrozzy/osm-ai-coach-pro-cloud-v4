
(function(){
'use strict';

const VERSION='7.17.0';

function N(v){return String(v??'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/\s+/g,' ').trim()}
function ok(v){return !(v===null||v===undefined||v===''||String(v).toUpperCase()==='NI')}
function wrapped(v,c=.92){return {value:v,confidence:c}}
function numlist(s,min=0,max=999){
  return (String(s||'').match(/\b\d{1,3}\b/g)||[]).map(Number).filter(x=>x>=min&&x<=max)
}
function fieldMerge(a,b){
  const out={...(a||{})};
  for(const [k,v] of Object.entries(b||{})){
    if(v===undefined||v===null)continue;
    const av=out[k]?.value!==undefined?out[k].value:out[k];
    const ac=Number(out[k]?.confidence||0),bc=Number(v?.confidence||.5);
    if(!ok(av)||bc>ac)out[k]=v;
  }
  return out;
}
function cropCanvas(img,x,y,w,h,scale=3,gray=true){
  const W=img.naturalWidth,H=img.naturalHeight;
  const sx=Math.max(0,Math.round(x*W)), sy=Math.max(0,Math.round(y*H));
  const sw=Math.max(1,Math.round(w*W)), sh=Math.max(1,Math.round(h*H));
  const c=document.createElement('canvas');
  c.width=Math.max(1,Math.round(sw*scale));
  c.height=Math.max(1,Math.round(sh*scale));
  const g=c.getContext('2d',{willReadFrequently:true});
  g.imageSmoothingEnabled=true;g.imageSmoothingQuality='high';
  g.drawImage(img,sx,sy,sw,sh,0,0,c.width,c.height);
  if(gray){
    const id=g.getImageData(0,0,c.width,c.height),d=id.data;
    for(let i=0;i<d.length;i+=4){
      const yv=.299*d[i]+.587*d[i+1]+.114*d[i+2];
      let v=(yv-128)*1.75+128;
      if(yv>205)v=255;
      if(yv<45)v=0;
      v=Math.max(0,Math.min(255,v));
      d[i]=d[i+1]=d[i+2]=v;
    }
    g.putImageData(id,0,0);
  }
  return c;
}
async function imageFromFrame(frame){
  return new Promise((resolve,reject)=>{
    const img=new Image();
    img.onload=()=>resolve(img);
    img.onerror=reject;
    img.src=frame.dataUrl || ('data:image/jpeg;base64,'+frame.base64);
  });
}
async function tess(c,psm=6,whitelist=null){
  if(!window.Tesseract)throw new Error('Tesseract local não carregado');
  const opts={};
  if(whitelist)opts.tessedit_char_whitelist=whitelist;
  const r=await window.Tesseract.recognize(c,'por+eng',{
    logger:m=>{
      if(m?.status==='recognizing text'&&Number.isFinite(m.progress)){
        const p=34+Math.round(m.progress*8);
        if(typeof setProgress==='function')setProgress(p,'V7.17 · OCR por regiões '+Math.round(m.progress*100)+'%…');
      }
    },
    ...opts
  });
  return String(r?.data?.text||'').trim();
}
async function ocrRoi(img,roi,psm=6,whitelist=null){
  return tess(cropCanvas(img,...roi),psm,whitelist);
}
function formationFrom(s){
  const m=String(s||'').match(/\b(?:3|4|5|6)\s*[-–]\s*(?:2|3|4|5|6)\s*[-–]\s*(?:1|2|3|4|5)(?:\s*[AB])?\b/i);
  if(!m)return null;
  const x=m[0].replace(/–/g,'-').replace(/\s*-\s*/g,'-').replace(/\s+/g,' ').toUpperCase();
  const fs=Array.isArray(FORMATIONS)?FORMATIONS:[];
  return fs.find(f=>String(f).toUpperCase()===x)||null;
}
function styleFrom(s){
  const z=N(s);
  if(z.includes('jogo de passe')||z.includes('jogo de passes')||z.includes('passing'))return 'Jogo de passes';
  if(z.includes('jogar pelas alas')||z.includes('pelas alas')||z.includes('wing'))return 'Jogar pelas alas';
  if(z.includes('bola longa')||z.includes('long ball'))return 'Bola longa';
  if(z.includes('contra-ataque')||z.includes('contra ataque')||z.includes('counter'))return 'Contra-ataque';
  if(z.includes('remate a vista')||z.includes('shoot on sight'))return 'Remate à vista';
  return null;
}
function boolFrom(s){
  const z=N(s);
  if(/\b(nao|no|inativo|inactive|desativado)\b/.test(z))return false;
  if(/\b(sim|yes|ativo|active|ativado)\b/.test(z))return true;
  return null;
}
function analystFromTexts(left,title,mid,right){
  const out={};
  const all=[left,title,mid,right].join('\n');
  const f=formationFrom(all); if(f)out.opponentFormation=wrapped(f,.98);
  const st=styleFrom(title)||styleFrom(all); if(st)out.opponentStyle=wrapped(st,.98);
  const mz=N(mid);
  if(mz.includes('zona'))out.opponentMarking=wrapped('À zona',.98);
  else if(mz.includes('individual')||mz.includes('homem'))out.opponentMarking=wrapped('Individual',.98);
  const rb=boolFrom(right); if(rb!==null)out.opponentOffside=wrapped(rb,.98);

  const l=N(left);
  if(l.includes('entradas normal'))out.opponentTackling=wrapped('Normal',.97);
  else if(l.includes('entradas agress'))out.opponentTackling=wrapped('Agressivo',.97);
  else if(l.includes('entradas cuidad'))out.opponentTackling=wrapped('Cuidadoso',.97);

  const sm=String(left).match(/nivel do estadio\s*[:\-]?\s*([0-3o])/i);
  if(sm)out.opponentStadium=wrapped(sm[1].toLowerCase()==='o'?0:Number(sm[1]),.96);

  if(l.includes('nao foram em estagio')||l.includes('não foram em estágio'))out.opponentTrainingCamp=wrapped(false,.98);
  if(l.includes('foram em estagio')&&!l.includes('nao foram em estagio'))out.opponentTrainingCamp=wrapped(true,.96);

  return out;
}
function hueReferee(img){
  // O termómetro do árbitro fica sempre na região central da tela inicial.
  const c=cropCanvas(img,.475,.39,.055,.15,1,false);
  const g=c.getContext('2d',{willReadFrequently:true});
  const d=g.getImageData(0,0,c.width,c.height).data;
  let buckets={green:0,blue:0,yellow:0,orange:0,red:0};
  for(let i=0;i<d.length;i+=4){
    const r=d[i],gg=d[i+1],b=d[i+2],mx=Math.max(r,gg,b),mn=Math.min(r,gg,b);
    if(mx<100||mx-mn<35)continue;
    if(r>180&&gg<120&&b<110)buckets.red++;
    else if(r>190&&gg>90&&gg<180&&b<90)buckets.orange++;
    else if(r>175&&gg>160&&b<100)buckets.yellow++;
    else if(b>150&&b>r*1.2&&b>gg*1.05)buckets.blue++;
    else if(gg>130&&gg>r*1.15&&gg>b*1.05)buckets.green++;
  }
  const best=Object.entries(buckets).sort((a,b)=>b[1]-a[1])[0];
  if(!best||best[1]<8)return null;
  return ({green:'Verde',blue:'Azul',yellow:'Amarelo',orange:'Laranja',red:'Vermelho'})[best[0]]||null;
}
async function readOverview(img,slot){
  const out={};
  const top=await ocrRoi(img,[.13,.12,.74,.38],6);
  const z=N(top);
  if(slot?.teamName&&z.includes(N(slot.teamName)))out.teamName=wrapped(slot.teamName,1);
  if(slot?.opponent?.teamName&&z.includes(N(slot.opponent.teamName)))out.opponentName=wrapped(slot.opponent.teamName,1);

  const pct=String(top).match(/([0-3])\s*%/g)||[];
  if(pct.length)out.opponentLoginBonus=wrapped(Number(pct[pct.length-1].match(/\d/)[0]),.91);

  const ref=hueReferee(img);
  if(ref)out.referee=wrapped(ref,.96);

  // Tela inicial do vídeo: lado esquerdo é do utilizador, lado direito é rival.
  // O nick fixo confirma o lado quando OCR consegue lê-lo.
  const nick=N(settings?.userNick||'leandrozzy');
  if(nick&&z.includes(nick))out.opponentHuman=wrapped(true,.92);

  return out;
}
async function readSquad(img,slot){
  const out={};
  const left=await ocrRoi(img,[.00,.12,.43,.28],6);
  const zl=N(left);
  let side=null;
  if(slot?.teamName&&zl.includes(N(slot.teamName)))side='mine';
  else if(slot?.opponent?.teamName&&zl.includes(N(slot.opponent.teamName)))side='opp';
  else if(zl.includes(N(settings?.userNick||'leandrozzy')))side='mine';
  if(!side)return out;

  // Cada bolha tem OCR próprio: evita perder GR/GOL como acontecia no OCR da tela inteira.
  const boxes=[
    [.595,.16,.060,.16], [.642,.16,.060,.16], [.690,.16,.060,.16], [.738,.16,.060,.16], [.790,.16,.105,.22]
  ];
  const values=[];
  for(const b of boxes){
    const t=await ocrRoi(img,b,7,'0123456789');
    const a=numlist(t,20,200);
    values.push(a.length?a[a.length-1]:null);
  }

  const [g,d,m,a,overall]=values;
  const prefix=side==='mine'?'my':'opp';
  if(g!==null)out[prefix+'Goalkeeper']=wrapped(g,.95);
  if(d!==null)out[prefix+'Defence']=wrapped(d,.95);
  if(m!==null)out[prefix+'Midfield']=wrapped(m,.95);
  if(a!==null)out[prefix+'Attack']=wrapped(a,.95);
  if(overall!==null)out[prefix==='my'?'myOverall':'oppOverall']=wrapped(overall,.97);

  const valueText=await ocrRoi(img,[.895,.045,.10,.10],7,'0123456789.,MK');
  if(side==='mine'&&valueText)out.mySquadValue=wrapped(valueText.replace(/\s+/g,''),.87);
  if(side==='opp'&&valueText)out.oppSquadValue=wrapped(valueText.replace(/\s+/g,''),.87);

  return out;
}
async function readAnalyst(img){
  const left=await ocrRoi(img,[.005,.12,.405,.48],6);
  const title=await ocrRoi(img,[.43,.00,.55,.11],7);
  const mid=await ocrRoi(img,[.58,.52,.20,.28],6);
  const right=await ocrRoi(img,[.84,.52,.155,.28],6);
  return analystFromTexts(left,title,mid,right);
}
function screenType(img){
  const c=document.createElement('canvas');c.width=48;c.height=24;
  const g=c.getContext('2d',{willReadFrequently:true});g.drawImage(img,0,0,48,24);
  const d=g.getImageData(0,0,48,24).data;
  let blue=0,white=0,yellow=0,dark=0;
  for(let i=0;i<d.length;i+=4){
    const r=d[i],gg=d[i+1],b=d[i+2];
    if(b>130&&b>r*1.25&&b>gg*1.05)blue++;
    if(r>210&&gg>210&&b>210)white++;
    if(r>180&&gg>130&&b<100)yellow++;
    if(r+gg+b<170)dark++;
  }
  const n=d.length/4;
  // Data Analyst: metade direita azul + pasta amarela/branca à esquerda.
  if(blue/n>.32 && (white/n>.13||yellow/n>.06))return 'analyst';
  // Elenco: faixa branca grande na metade inferior e cabeçalho escuro.
  if(white/n>.30 && dark/n>.12)return 'squad';
  // Visão geral: quase toda escura, sem grande tabela branca.
  if(dark/n>.34)return 'overview';
  return 'other';
}
async function extractDense(files){
  const frames=[];
  for(const f of files||[]){
    if(String(f.type||'').startsWith('image/')){
      frames.push(await imageFileToFrame(f));
    }else if(String(f.type||'').startsWith('video/')){
      // O vídeo real enviado tem ~30 s. 18 quadros capturam as transições sem mandar nada para API.
      const fs=await videoFrames(f,18);
      for(const b64 of fs)frames.push({base64:b64,mimeType:'image/jpeg',dataUrl:'data:image/jpeg;base64,'+b64});
    }
  }
  return frames.slice(0,22);
}
async function analyzeROI(files){
  const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot;
  state.selectedSlot=slotNo;
  const slot=state.slots[slotNo-1];

  setProgress(8,'V7.17 · lendo o vídeo por telas do OSM…');
  const frames=await extractDense(files);
  if(!frames.length)throw new Error('Não consegui extrair quadros do vídeo.');

  const typed=[];
  for(let i=0;i<frames.length;i++){
    const img=await imageFromFrame(frames[i]);
    typed.push({frame:frames[i],img,type:screenType(img),i});
  }

  // Seleciona no máximo os quadros realmente úteis, em vez de OCR em tela inteira.
  const overview=typed.filter(x=>x.type==='overview').slice(0,3);
  const squads=typed.filter(x=>x.type==='squad').slice(0,6);
  const analysts=typed.filter(x=>x.type==='analyst').slice(-4);

  let merged={};
  let used=0;

  for(const x of overview){
    setProgress(22+used*3,'V7.17 · tela inicial…');
    merged=fieldMerge(merged,await readOverview(x.img,slot)); used++;
  }
  for(const x of squads){
    setProgress(34+used*3,'V7.17 · forças por setor…');
    merged=fieldMerge(merged,await readSquad(x.img,slot)); used++;
  }
  for(const x of analysts){
    setProgress(52+used*3,'V7.17 · Data Analyst…');
    merged=fieldMerge(merged,await readAnalyst(x.img)); used++;
  }

  if(slot?.teamName&&!merged.teamName)merged.teamName=wrapped(slot.teamName,1);
  if(slot?.opponent?.teamName&&!merged.opponentName)merged.opponentName=wrapped(slot.opponent.teamName,1);

  if(!Object.keys(merged).length)throw new Error('Nenhuma tela do OSM foi reconhecida no vídeo.');

  setProgress(82,'V7.17 · validando campos lidos…');

  // Evita dados antigos/falsos nos campos que este vídeo deveria atualizar.
  const replacePaths=[
    'myTeam.overall','myTeam.goalkeeper','myTeam.defence','myTeam.midfield','myTeam.attack',
    'opponent.overall','opponent.goalkeeper','opponent.defence','opponent.midfield','opponent.attack',
    'match.refereeColor','opponent.formation','opponent.style','opponent.marking','opponent.offside',
    'opponent.trainingCamp'
  ];
  for(const p of replacePaths){
    setPath(slot,p,null);
    if(slot.fieldMeta)slot.fieldMeta[p]={source:'unknown',confidence:0,updatedAt:new Date().toISOString()};
  }

  applyAnalysis(slotNo,merged);
  const s=state.slots[slotNo-1];

  // Treino secreto é exceção: se estiver ativo, os dados ocultos do rival não podem ser exigidos.
  if(s?.opponent?.secretTraining===true){
    for(const p of ['overall','goalkeeper','defence','midfield','attack','formation','style','marking','offside']){
      s.opponent[p]=null;
      if(s.fieldMeta)s.fieldMeta['opponent.'+p]={source:'unknown',confidence:0,updatedAt:new Date().toISOString(),hiddenBySecretTraining:true};
    }
  }

  calcQuality(s);saveState();renderCoverage(s);renderAnalysisSummary(s);renderPregame();

  let req=missingRequired(s);
  // Com treino secreto ativo, informações escondidas pelo jogo não bloqueiam a tática.
  if(s?.opponent?.secretTraining===true){
    const hidden=new Set(['opponent.overall','opponent.formation','opponent.style','opponent.marking','opponent.offside']);
    req=req.filter(p=>!hidden.has(p));
  }

  setAnalysisRun(
    s,'tactic',req.length?'warning':'success',
    'V7.17 ROI: '+overview.length+' tela(s) inicial(is), '+squads.length+' elenco(s), '+analysts.length+' Data Analyst lidos por regiões específicas'+
      (req.length?' · faltam '+req.length+' campo(s) realmente não visíveis':' · pronto para tática'),
    {quality:s.analysisQuality,currentRunValidated:true,roiReader:true}
  );

  const diag=document.getElementById('analysisDiagnostics');
  if(diag)diag.textContent='V7.17 ROI READER · leitor calibrado nos vídeos reais do OSM · sem Gemini/OpenRouter/OCR.Space';

  setProgress(100,req.length?'Leitura concluída com pendência real':'Leitura completa');

  if(!req.length&&document.getElementById('autoTactic')?.checked)await generateTactic(slotNo);
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
    await analyzeROI(files);
  }catch(e){
    Object.keys(slot).forEach(k=>delete slot[k]);
    Object.assign(slot,snapshot);
    saveState();
    setAnalysisRun(slot,'tactic','error','V7.17 ROI: '+String(e?.message||e).slice(0,220)+'. Dados anteriores preservados.',{currentRunValidated:false});
    setProgress(100,'Falha sem apagar os dados anteriores');
  }
};
window.v21Analyze=v21Analyze;
window.OSM_MATCH_READER_VERSION=VERSION;

try{console.info('[OSM] V7.17 ROI Reader ativo')}catch(_){}
})();

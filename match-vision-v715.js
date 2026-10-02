
(function(){
'use strict';

const V715='7.15.0';

function v715Missing(v){
  return v===null||v===undefined||v===''||String(v).toUpperCase()==='NI';
}
function v715Value(item){
  if(item && typeof item==='object' && Object.prototype.hasOwnProperty.call(item,'value')) return item.value;
  return item;
}
function v715Conf(item){
  if(item && typeof item==='object' && Number.isFinite(Number(item.confidence))) return Number(item.confidence);
  return .55;
}
function v715MergeField(a,b){
  const av=v715Value(a), bv=v715Value(b);
  if(v715Missing(av)) return b;
  if(v715Missing(bv)) return a;
  return v715Conf(b)>v715Conf(a)?b:a;
}
function v715MergeResult(base, add){
  const out=base&&typeof base==='object'?JSON.parse(JSON.stringify(base)):{};
  const fields=[
    'teamName','opponentName','venue','referee',
    'myOverall','oppOverall','myGoalkeeper','myDefence','myMidfield','myAttack',
    'oppGoalkeeper','oppDefence','oppMidfield','oppAttack',
    'mySquadValue','oppSquadValue','opponentManager','opponentHuman',
    'opponentLoginBonus','opponentStadium','opponentTrainingCamp',
    'opponentSecretTraining','opponentFormation','opponentStyle',
    'opponentMarking','opponentOffside'
  ];
  for(const k of fields) out[k]=v715MergeField(out[k],add?add[k]:undefined);
  for(const k of ['competitionName','competitionType','round','totalRounds']){
    if(v715Missing(out[k])&&!v715Missing(add&&add[k])) out[k]=add[k];
  }
  return out;
}

function v715Prompt(slot,batchNo,totalBatches){
  const nick=(typeof settings!=='undefined'&&settings.userNick)||'leandrozzy';
  const my=slot?.teamName||null;
  const opp=slot?.opponent?.teamName||null;
  return `Você está lendo imagens REAIS do mesmo vídeo da tela do OSM 26 Android.
Este é o lote ${batchNo}/${totalBatches}. Leia VISUALMENTE as imagens; não dependa apenas de OCR.

CONTEXTO CONHECIDO:
- Nick do usuário: ${nick}
- Meu time já conhecido: ${my||'NI'}
- Rival já conhecido: ${opp||'NI'}

OBJETIVO:
Extrair tudo que estiver realmente visível nas imagens, especialmente as telas de comparação e Data Analyst.

REGRAS:
1. Se o nick "${nick}" aparecer sob um time, esse lado é SEMPRE o meu time.
2. Se o nome "${my||''}" aparecer, trate como meu time; "${opp||''}" como rival.
3. NÃO invente. Campo não visível = null.
4. Leia com prioridade:
   - força geral dos dois;
   - GOL/DEF/MEI/ATA dos dois;
   - árbitro;
   - casa/fora;
   - bônus rival;
   - estádio rival;
   - campo de treinamento;
   - treino secreto;
   - formação rival;
   - plano rival;
   - marcação rival;
   - impedimento rival;
   - manager/humano.
5. "Treino secreto" e "Campo de treinamento":
   - true somente se a tela mostrar claramente que está ativo;
   - false somente se a tela específica do recurso estiver claramente visível como não ativo;
   - caso contrário null.
6. Se treino secreto estiver ativo e ocultar dados do rival, use true para opponentSecretTraining e deixe os dados realmente ocultos como null.
7. Formação deve ser uma formação válida do OSM (ex.: 4-3-3 A, 4-5-1, 5-3-2).
8. Plano rival: Jogar pelas alas | Jogo de passes | Bola longa | Contra-ataque | Remate à vista.
9. Marcação rival: À zona | Individual.
10. Impedimento: true | false | null.
11. Árbitro: Verde | Azul | Amarelo | Laranja | Vermelho | null.
12. Não confunda idade, valor de jogador, ranking ou nível com força do time.

Retorne SOMENTE JSON:
{
 "teamName":{"value":null,"confidence":0},
 "opponentName":{"value":null,"confidence":0},
 "venue":{"value":null,"confidence":0},
 "referee":{"value":null,"confidence":0},
 "myOverall":{"value":null,"confidence":0},
 "oppOverall":{"value":null,"confidence":0},
 "myGoalkeeper":{"value":null,"confidence":0},
 "myDefence":{"value":null,"confidence":0},
 "myMidfield":{"value":null,"confidence":0},
 "myAttack":{"value":null,"confidence":0},
 "oppGoalkeeper":{"value":null,"confidence":0},
 "oppDefence":{"value":null,"confidence":0},
 "oppMidfield":{"value":null,"confidence":0},
 "oppAttack":{"value":null,"confidence":0},
 "mySquadValue":{"value":null,"confidence":0},
 "oppSquadValue":{"value":null,"confidence":0},
 "opponentManager":{"value":null,"confidence":0},
 "opponentHuman":{"value":null,"confidence":0},
 "opponentLoginBonus":{"value":null,"confidence":0},
 "opponentStadium":{"value":null,"confidence":0},
 "opponentTrainingCamp":{"value":null,"confidence":0},
 "opponentSecretTraining":{"value":null,"confidence":0},
 "opponentFormation":{"value":null,"confidence":0},
 "opponentStyle":{"value":null,"confidence":0},
 "opponentMarking":{"value":null,"confidence":0},
 "opponentOffside":{"value":null,"confidence":0},
 "competitionName":null,
 "competitionType":null,
 "round":null,
 "totalRounds":null
}`;
}

async function v715Extract(files){
  const payloads=[];
  for(const f of files||[]){
    if(String(f.type||'').startsWith('image/')){
      const b64=await fileToInline(f);
      payloads.push({b64,mimeType:f.type||'image/jpeg'});
    }else if(String(f.type||'').startsWith('video/')){
      // Usa novamente o caminho visual que funcionava nas versões antigas:
      // 8 quadros completos, igualmente distribuídos pelo vídeo.
      const frames=await videoFrames(f,8);
      for(const b64 of frames) payloads.push({b64,mimeType:'image/jpeg'});
    }
  }
  return payloads.slice(0,12);
}

async function v715VisionAnalyze(files){
  const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot;
  state.selectedSlot=slotNo;
  const slot=state.slots[slotNo-1];
  setProgress(12,'V7.15 · extraindo quadros completos…');
  const imgs=await v715Extract(files);
  if(!imgs.length) throw new Error('Nenhuma imagem utilizável foi extraída.');

  // Lotes pequenos evitam "request too large" e timeouts que ocorreram nas versões anteriores.
  const batches=[];
  const batchSize=4;
  for(let i=0;i<imgs.length;i+=batchSize) batches.push(imgs.slice(i,i+batchSize));

  let merged={};
  let successes=0;
  const errors=[];

  for(let i=0;i<batches.length;i++){
    setProgress(30+Math.round((i/batches.length)*48),`V7.15 · Gemini visão ${i+1}/${batches.length}…`);
    const parts=[{text:v715Prompt(slot,i+1,batches.length)}];
    for(const img of batches[i]) parts.push({inlineData:{mimeType:img.mimeType,data:img.b64}});
    try{
      const r=await geminiJson(parts,.02,5000);
      merged=v715MergeResult(merged,r);
      successes++;
    }catch(e){
      errors.push(String(e?.message||e).slice(0,180));
    }
  }

  if(!successes) throw new Error('Gemini não conseguiu analisar nenhum lote: '+errors.join(' | '));

  setProgress(83,'V7.15 · consolidando campos…');
  applyAnalysis(slotNo,merged);

  const s=state.slots[slotNo-1];

  // Se o treino secreto foi realmente detectado, dados ocultos não devem virar
  // "obrigatórios" nem reutilizar leitura antiga do rival.
  if(s?.opponent?.secretTraining===true){
    for(const p of ['overall','goalkeeper','defence','midfield','attack','formation','style','marking','offside']){
      s.opponent[p]=null;
      if(s.fieldMeta) s.fieldMeta['opponent.'+p]={
        source:'unknown',confidence:0,updatedAt:new Date().toISOString(),hiddenBySecretTraining:true
      };
    }
  }

  if(typeof calcQuality==='function') calcQuality(s);
  if(typeof saveState==='function') saveState();
  if(typeof renderCoverage==='function') renderCoverage(s);
  if(typeof renderAnalysisSummary==='function') renderAnalysisSummary(s);
  if(typeof renderPregame==='function') renderPregame();

  const missing=(typeof missingRequired==='function')?missingRequired(s):[];
  if(typeof setAnalysisRun==='function'){
    setAnalysisRun(s,'tactic',missing.length?'warning':'success',
      `V7.15: Gemini visual em ${imgs.length} quadro(s), ${successes}/${batches.length} lote(s) concluído(s)`+
      (missing.length?`; ${missing.length} campo(s) realmente obrigatório(s) ainda NI`:'; pronto para tática'),
      {quality:s.analysisQuality,currentRunValidated:true,geminiVision:true});
  }
  const diag=document.getElementById('analysisDiagnostics');
  if(diag) diag.textContent=`V7.15 GEMINI VISION · ${imgs.length} quadros · ${successes}/${batches.length} lotes`+
    (errors.length?` · ${errors.length} lote(s) com falha`:'');
  setProgress(100,missing.length?'Leitura concluída com pendência real':'Leitura visual concluída');

  if(!missing.length && document.getElementById('autoTactic')?.checked){
    await generateTactic(slotNo);
  }
}

// Substitui somente a leitura de PARTIDA. Elenco e calendário continuam com seus motores.
const previousV715Analyze=(typeof v21Analyze==='function')?v21Analyze:null;
v21Analyze=async function(files){
  if(analysisMode!=='tactic'){
    if(previousV715Analyze) return previousV715Analyze(files);
    throw new Error('Analisador anterior indisponível.');
  }
  const slot=selectedSlot();
  const snapshot=JSON.parse(JSON.stringify(slot));
  try{
    await v715VisionAnalyze(files);
  }catch(e){
    // Não destrói a leitura anterior.
    Object.keys(slot).forEach(k=>delete slot[k]);
    Object.assign(slot,snapshot);
    if(typeof saveState==='function')saveState();
    if(typeof setAnalysisRun==='function'){
      setAnalysisRun(slot,'tactic','error','V7.15: '+String(e?.message||e).slice(0,260)+'. Dados anteriores preservados.',
        {currentRunValidated:false});
    }
    setProgress(100,'Falha sem apagar dados anteriores');
  }
};
window.v21Analyze=v21Analyze;

// Corrige um bug do Coach IA 3.0: Number(null) = 0.
// Campos de setor ausentes estavam virando diferenças falsas, como +0/+22/+57.
if(typeof generateTactic==='function'){
  const baseGenerateV715=generateTactic;
  generateTactic=async function(n){
    const s=state?.slots?.[Number(n)-1];
    const touched=[];
    if(s){
      for(const side of ['myTeam','opponent']){
        for(const k of ['overall','goalkeeper','defence','midfield','attack']){
          if(s[side] && (s[side][k]===null||s[side][k]===undefined||s[side][k]==='')){
            touched.push([s[side],k,s[side][k]]);
            s[side][k]='NI';
          }
        }
      }
    }
    try{
      return await baseGenerateV715.apply(this,arguments);
    }finally{
      for(const [obj,k,v] of touched) obj[k]=v;
    }
  };
  window.generateTactic=generateTactic;
}

// Corrige também a exibição do quadro GOL/DEF/MEI/ATA.
if(typeof renderPregame==='function'){
  const baseRenderV715=renderPregame;
  renderPregame=function(){
    const result=baseRenderV715.apply(this,arguments);
    try{
      const s=selectedSlot();
      const box=document.querySelector('.coach30-pregame .coach30-kpis.sectors');
      if(box&&s){
        const map={
          GOL:['goalkeeper','goalkeeper'],
          DEF:['defence','defence'],
          MEI:['midfield','midfield'],
          ATA:['attack','attack']
        };
        box.querySelectorAll('div').forEach(div=>{
          const label=div.querySelector('span')?.textContent?.trim();
          const b=div.querySelector('b');
          if(!b||!map[label])return;
          const [aKey,oKey]=map[label];
          const av=s.myTeam?.[aKey],ov=s.opponent?.[oKey];
          const an=Number(av),on=Number(ov);
          const okA=av!==null&&av!==undefined&&av!==''&&String(av).toUpperCase()!=='NI'&&Number.isFinite(an);
          const okO=ov!==null&&ov!==undefined&&ov!==''&&String(ov).toUpperCase()!=='NI'&&Number.isFinite(on);
          if(!(okA&&okO)){
            b.textContent='NI';
            b.classList.remove('pos','neg');
          }else{
            const d=an-on;
            b.textContent=(d>=0?'+':'')+d;
            b.classList.toggle('neg',d<0);
            b.classList.toggle('pos',d>=0);
          }
        });
      }
    }catch(_){}
    return result;
  };
  window.renderPregame=renderPregame;
}

window.OSM_MATCH_VISION_VERSION=V715;
try{console.info('[OSM] V7.15 Gemini Vision Restore ativo')}catch(_){}
})();

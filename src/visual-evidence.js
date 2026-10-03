import {detectCalendarPixels} from './calendar-icons.js';
import {detectSquadPixels} from './squad-icons.js';
import {detectSquadAttributePixels} from './squad-attributes.js';
import {detectMatchIcons,detectMatchPixels} from './match-icons.js';
import {normalizeExtraction,fuseExtraction} from './extraction.js';
import {known} from './domain.js';

// Read each original screen once; grayscale OCR images are only coordinate references.
export function applyScreenEvidence(initial,response,frames,type){
 let data=initial;const pixels=new Map(),used=new Set();
 const imageFor=index=>{
  if(pixels.has(index))return pixels.get(index);
  const canvas=frames[index]?.canvas;let image=null;
  try{if(canvas)image=canvas.getContext('2d',{willReadFrequently:true}).getImageData(0,0,canvas.width,canvas.height);}catch{}
  pixels.set(index,image);return image;
 };
 if(type==='calendar')for(const evidence of response.calendarEvidence||[]){
  const image=imageFor(evidence.frameIndex);if(!image||!evidence.row?._card)continue;
  const facts=detectCalendarPixels(image,evidence.row._card);
  if(facts.home===null&&facts.cup===null&&!known(facts.result))continue;
  data=fuseExtraction(data,normalizeExtraction({calendar:[{...evidence.row,...facts}]},type,'Ícones nas telas',{sourceKind:'pixels',fields:['home','cup','result']}));used.add('ícones locais');
 }
 if(type==='squad')for(const evidence of response.squadEvidence||[]){
  const image=imageFor(evidence.frameIndex);if(!image)continue;
  const icons=[];
  for(const row of evidence.players||[]){
   const facts=detectSquadPixels(image,{...row,width:evidence.width,height:evidence.height});
   if(facts.training!==null||facts.forSale!==null)icons.push({name:row.name,training:facts.training,forSale:facts.forSale});
   const attributes=detectSquadAttributePixels(image,row);
   const proved=Object.fromEntries(Object.entries(attributes).filter(([,value])=>value!==null));
   if(Object.keys(proved).length){
    data=fuseExtraction(data,normalizeExtraction({players:[{name:row.name,...proved}]},type,'Posições e atributos nas telas',{sourceKind:'pixels',fields:Object.keys(proved)}));
    used.add('posições e atributos nas telas');
   }
  }
  if(icons.length){data=fuseExtraction(data,normalizeExtraction({players:icons},type,'Camisas e setas nas telas',{sourceKind:'pixels',fields:['training','forSale']}));used.add('camisas e setas locais');}
 }
 if(type==='squad')for(const evidence of response.matchEvidence||[]){
  const image=imageFor(evidence.frameIndex);if(!image)continue;
  // During squad review a newly verified club can belong to a different slot.
  // Identify this header using that read club before validating its own badge.
  const context={...evidence.context};if(data.meta?.teamVerified===true&&known(data.meta.team))context.myTeam=data.meta.team;
  const local=detectMatchPixels(image,{...evidence,context});
  const proof=local.meta?.iconEvidence?.find(row=>row.kind==='strength-numerals'&&row.field==='myStrength');
  if(proof&&known(local.match?.myStrength)){
   data=fuseExtraction(data,normalizeExtraction({meta:{strength:local.match.myStrength}},type,'Força do elenco no círculo da equipa',{sourceKind:'pixels',metaFields:['strength']}));
   used.add('força do elenco nas telas');
  }
  for(const fact of local.meta?.pendingMatchFacts||[]){
   if(fact.field!=='myStrength'||!known(data.meta?.strength)||String(data.meta.strength)!==String(fact.value)||(data.meta._fieldSources?.strength?.rank||0)>=3)continue;
   data={...data,meta:{...data.meta,_fieldSources:{...data.meta._fieldSources}},warnings:[...new Set([...(data.warnings||[]),'Força geral do elenco sem confirmação dos dígitos na imagem original: mantida NI.'])]};
   delete data.meta.strength;delete data.meta._fieldSources.strength;
  }
 }
 if(type==='match')for(const evidence of response.matchEvidence||[]){
  const canvas=frames[evidence.frameIndex]?.canvas;if(!canvas)continue;
  const local=detectMatchIcons(canvas,evidence);
  const pending=local.meta?.pendingMatchFacts||[],hasMatch=Object.entries(local.match||{}).some(([key,value])=>!key.startsWith('_')&&known(value));
  if(!hasMatch&&!pending.length)continue;
  for(const fact of pending){
   // An unconfirmed native badge must not retain the same server OCR guess.
   // Independently proved values and a different earlier literal value survive.
   if(known(data.match?.[fact.field])&&String(data.match[fact.field])===String(fact.value)&&(data._matchFieldSources?.[fact.field]?.rank||0)<3){
    data={...data,match:{...data.match},_matchFieldSources:{...data._matchFieldSources}};
    delete data.match[fact.field];delete data._matchFieldSources[fact.field];
   }
  }
  const pixelFields=[];
  if(local.meta?.iconEvidence?.some(e=>e.kind==='padlock'))pixelFields.push('secretTraining');
  if(local.meta?.iconEvidence?.some(e=>e.kind==='referee-thermometer'))pixelFields.push('referee');
  if(local.meta?.iconEvidence?.some(e=>e.kind==='cpu-empty-manager-line'))pixelFields.push('human');
  for(const proof of local.meta?.iconEvidence||[])if(proof.kind==='strength-numerals'&&['myStrength','rivalStrength'].includes(proof.field)&&known(local.match?.[proof.field]))pixelFields.push(proof.field);
  const headerFields=(local.meta?._headerFields||[]).filter(key=>known(local.match[key])&&!pixelFields.includes(key));
  if(headerFields.length)data=fuseExtraction(data,normalizeExtraction({match:Object.fromEntries(headerFields.map(key=>[key,local.match[key]]))},type,'Cabeçalhos identificados nas telas',{sourceKind:'ocr-layout',fields:headerFields}));
  const literalMatch={...local.match};for(const key of [...pixelFields,...headerFields])delete literalMatch[key];
  if(Object.keys(literalMatch).length||pending.length)data=fuseExtraction(data,normalizeExtraction({match:literalMatch,meta:{...local.meta,rivalReportLocked:false}},type,'Relatório e cabeçalhos nas telas',{sourceKind:'ocr-explicit',preserveEvidence:true,fields:['myStrength','rivalStrength','myGK','rivalGK','myDEF','rivalDEF','myMID','rivalMID','myATT','rivalATT','mySquadValue','rivalSquadValue','myPlayers','rivalPlayers','rivalFormation','rivalPlan','rivalMarking','rivalOffside','rivalTackling','stadium','trainingCamp','secretTraining','referee']}));
  if(pixelFields.length)data=fuseExtraction(data,normalizeExtraction({match:Object.fromEntries(pixelFields.map(key=>[key,local.match[key]])),meta:{rivalReportLocked:local.meta?.rivalReportLocked===true}},type,'Cadeado e árbitro nas telas',{sourceKind:'pixels',fields:pixelFields}));used.add('leitura local da partida');
 }
 return {data,used:[...used]};
}

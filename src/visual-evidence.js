import {detectCalendarPixels} from './calendar-icons.js';
import {detectSquadPixels} from './squad-icons.js';
import {detectSquadAttributePixels} from './squad-attributes.js';
import {detectMatchIcons} from './match-icons.js';
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
 if(type==='match')for(const evidence of response.matchEvidence||[]){
  const canvas=frames[evidence.frameIndex]?.canvas;if(!canvas)continue;
  const local=detectMatchIcons(canvas,evidence);
  if(!Object.entries(local.match||{}).some(([key,value])=>!key.startsWith('_')&&known(value)))continue;
  const pixelFields=[];if(local.meta?.iconEvidence?.some(e=>e.kind==='padlock'))pixelFields.push('secretTraining');if(local.meta?.iconEvidence?.some(e=>e.kind==='referee-thermometer'))pixelFields.push('referee');
  const literalMatch={...local.match};for(const key of pixelFields)delete literalMatch[key];
  if(Object.keys(literalMatch).length)data=fuseExtraction(data,normalizeExtraction({match:literalMatch,meta:{...local.meta,rivalReportLocked:false}},type,'Relatório e cabeçalhos nas telas',{sourceKind:'ocr-explicit',fields:['myStrength','rivalStrength','myGK','rivalGK','myDEF','rivalDEF','myMID','rivalMID','myATT','rivalATT','mySquadValue','rivalSquadValue','myPlayers','rivalPlayers','rivalFormation','rivalPlan','rivalMarking','rivalOffside','rivalTackling','stadium','trainingCamp','secretTraining','referee']}));
  if(pixelFields.length)data=fuseExtraction(data,normalizeExtraction({match:Object.fromEntries(pixelFields.map(key=>[key,local.match[key]])),meta:{rivalReportLocked:local.meta?.rivalReportLocked===true}},type,'Cadeado e árbitro nas telas',{sourceKind:'pixels',fields:pixelFields}));used.add('leitura local da partida');
 }
 return {data,used:[...used]};
}

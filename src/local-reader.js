import {localOCR} from './ocr.js';
import {blankExtraction,normalizeExtraction,fuseExtraction} from './extraction.js';
import {parseMatchOverlay} from './parser-match.js';
import {overlayExtraction,overlayWords,parseSquadOverlay,parseSquadMeta,parseCalendarOverlay} from './ocr-layout.js';
import {applyScreenEvidence} from './visual-evidence.js';
import {detectMatchPixels} from './match-icons.js';
import {parseReportControls,reportControlRegions} from './report-controls.js';
import {parseCalendarBoundaryWords} from './calendar-ocr.js';
import {squadShirtOcrImage,readSquadShirtNumbers} from './squad-icons.js';
import {known} from './domain.js';

const rect=(name,left,top,right,bottom,gamma=2.6,pageSegMode=11,scale=1)=>({name,left,top,right,bottom,gamma,pageSegMode,scale});
/** Only layout geometry chooses a crop. It never supplies a club or match value. */
export function localReadRegions(width,height,type,{layout,region}={}){
 const kind=typeof layout==='string'?layout:layout?.kind;
 let regions;
 // Fixed OSM panel coordinates only apply to the native landscape layout.
 // Portrait photos/screenshots need the complete page instead of losing
 // their lower text to a landscape header crop.
 if(width<=height*1.6)regions=[rect('tela completa',0,0,1,1,1,3)];
 else if(region==='header')regions=[rect('cabeçalho',0,0,1,.54)];
 else if(region==='report'||type==='match'&&['report-field','report-details'].includes(kind))regions=[rect('texto do relatório',.025,.20,.415,.80,2,6),rect('formação e tática do relatório',.42,.015,1,.92,2,11,2)];
 else if(type==='match'&&kind==='report-cover')regions=[rect('capa do relatório e cadeado',0,.09,.86,.72,2,11)];
 else if(type==='match')regions=[rect('clubes, força e árbitro',0,0,1,.54)];
 else if(type==='squad'&&kind==='roster-header')regions=[rect('cabeçalho do elenco',0,0,1,.45),rect('primeiras linhas do elenco',0,.46,1,1)];
 else if(type==='squad')regions=[rect('linhas e posições do elenco',0,.115,1,1)];
 // Calendar card headings need page segmentation; sparse text mode drops
 // Jornada anchors even when it reads the opponent and date correctly.
 else regions=[rect('jogos do calendário',0,0,1,1,1,3)];
 return regions.map(r=>({...r,x:Math.max(0,Math.floor(r.left*width)),y:Math.max(0,Math.floor(r.top*height)),width:Math.max(1,Math.ceil((r.right-r.left)*width)),height:Math.max(1,Math.ceil((r.bottom-r.top)*height))}));
}
export function localRecoveryRegions(ocr,type,data,context={}){
 if(type!=='match')return [];
 return reportControlRegions(ocr,Number(ocr.width),Number(ocr.height),context).filter(area=>!known(data.match?.[area.field]));
}
/** A fresh, enlarged regional pass replaces the same physical words, while
 * retaining the original narrative that establishes the report's club. */
export function mergeLocalRegion(ocr,recovered,area){
 const inside=word=>Number(word.Left)>=area.x-1&&Number(word.Top)>=area.y-1&&Number(word.Left)+Number(word.Width)<=area.x+area.width+1&&Number(word.Top)+Number(word.Height)<=area.y+area.height+1;
 const lines=(ocr.lines||[]).map(line=>({...line,Words:(line.Words||[]).filter(word=>!inside(word))})).filter(line=>line.Words.length);
 return {...ocr,text:[ocr.text,recovered.text].filter(Boolean).join('\n'),lines:[...lines,...(recovered.lines||[])],confidence:recovered.confidence||ocr.confidence};
}
function cropForReading(source,region){
 const scale=region.scale||1,canvas=document.createElement('canvas');canvas.width=Math.round(region.width*scale);canvas.height=Math.round(region.height*scale);
 const ctx=canvas.getContext('2d',{willReadFrequently:true});
 ctx.drawImage(source,region.x,region.y,region.width,region.height,0,0,canvas.width,canvas.height);
 const image=ctx.getImageData(0,0,canvas.width,canvas.height);
 for(let at=0;at<image.data.length;at+=4){
  const gray=.299*image.data[at]+.587*image.data[at+1]+.114*image.data[at+2],tone=Math.round(255*Math.pow(gray/255,region.gamma));
  const value=region.invert?255-tone:tone;
  image.data[at]=image.data[at+1]=image.data[at+2]=value;image.data[at+3]=255;
 }
 ctx.putImageData(image,0,0);return canvas;
}
export function localNumericRegions(canvas,evidence,context={}){
 if(!evidence)return [];
 let local;
 try{local=detectMatchPixels(canvas.getContext('2d',{willReadFrequently:true}).getImageData(0,0,canvas.width,canvas.height),{...evidence,context:{...evidence.context,...context}});}catch{return [];}
 return (local.meta?.localOcrRegions||[]).filter(area=>area.kind==='strength'&&['myStrength','rivalStrength'].includes(area.field)&&area.club&&area.box).slice(0,2).map(area=>{
  const x=Math.max(0,Math.floor(area.box.left)),y=Math.max(0,Math.floor(area.box.top)),right=Math.min(canvas.width,Math.ceil(area.box.right)),bottom=Math.min(canvas.height,Math.ceil(area.box.bottom));
  return {...area,name:'força '+area.club,x,y,width:right-x,height:bottom-y,scale:3,gamma:1,pageSegMode:7};
 }).filter(area=>area.width>=8&&area.height>=8&&area.width*area.height<=30000);
}
export function localBonusRegions(canvas,evidence,context={}){
 if(!evidence)return [];
 let local;try{local=detectMatchPixels(canvas.getContext('2d',{willReadFrequently:true}).getImageData(0,0,canvas.width,canvas.height),{...evidence,context:{...evidence.context,...context}});}catch{return [];}
 return (local.meta?.localOcrRegions||[]).filter(area=>area.kind==='bonus'&&['myBonus','rivalBonus'].includes(area.field)&&known(area.club)&&area.box).slice(0,2).map(area=>{
  const x=Math.max(0,Math.floor(area.box.left)),y=Math.max(0,Math.floor(area.box.top)),right=Math.min(canvas.width,Math.ceil(area.box.right)),bottom=Math.min(canvas.height,Math.ceil(area.box.bottom));
  return {...area,name:'bônus '+area.club,x,y,width:right-x,height:bottom-y,scale:3,gamma:1,pageSegMode:7};
 }).filter(area=>area.width>=8&&area.height>=8&&area.width*area.height<=30000);
}
export function bonusRecoveryOverlay(ocr,target){
 const text=String(ocr.text||'').replace(/\s/g,''),number=text.match(/^\+(\d{1,3})%$/);
 if(!number||+number[1]>100||!target?.box)return null;
 const words=(ocr.lines||[]).flatMap(line=>line.Words||[]);if(!words.length||words.map(word=>String(word.WordText)).join('').replace(/\s/g,'')!==text)return null;
 const box=target.box;
 if(!['left','top','right','bottom'].every(key=>Number.isFinite(box[key]))||box.right<=box.left||box.bottom<=box.top)return null;
 if(words.some(word=>![word.Left,word.Top,word.Width,word.Height].every(Number.isFinite)||word.Width<=0||word.Height<=0||Number(word.Confidence??ocr.confidence)<50||!Number.isFinite(Number(word.Confidence??ocr.confidence))||word.Left<box.left-2||word.Top<box.top-2||word.Left+word.Width>box.right+2||word.Top+word.Height>box.bottom+2))return null;
 return {...ocr,text};
}
export function numericRecoveryOverlay(ocr){
 const value=String(ocr.text||'').trim();
 // Only the isolated, verified strength circle is read in this pass. A
 // partial token or punctuation must never become a guessed club strength.
 if(!/^[1-9]\d{1,2}$/.test(value)||+value>400||!ocr.lines?.length)return null;
 const words=ocr.lines.flatMap(line=>line.Words||[]).filter(word=>/^\d{2,3}$/.test(String(word.WordText).trim()));
 if(words.length!==1||String(words[0].WordText).trim()!==value)return null;
 const word=words[0];if(![word.Left,word.Top,word.Width,word.Height].every(Number.isFinite)||word.Width<=0||word.Height<=0)return null;
 return {...ocr,text:value,lines:[{Words:[words[0]],MinTop:words[0].Top,MaxHeight:words[0].Height}]};
}
/** Two differently prepared crops of one identified circle must read the same
 * complete numeral. A font-template candidate is never a substitute pass. */
export function numericStrengthConsensus(target,readings,{frameIndex=0}={}){
 if(!['myStrength','rivalStrength'].includes(target?.field)||typeof target.club!=='string'||!known(target.club)||!target.box||!Array.isArray(readings)||!Number.isInteger(frameIndex)||frameIndex<0)return null;
 const box=target.box;if(!['left','top','right','bottom'].every(key=>Number.isFinite(box[key]))||box.right<=box.left||box.bottom<=box.top)return null;
 const passes=readings.map(reading=>{
  const literal=numericRecoveryOverlay(reading?.ocr||{});if(!literal)return null;
  const word=literal.lines[0].Words[0],confidence=Number(word.Confidence??literal.confidence)||0;
  if(confidence<50||!['native-gray','inverted-contrast'].includes(reading.variant)||![7,13].includes(reading.pageSegMode))return null;
  // Coordinates are mapped back to the original screen. A number outside
  // the verified circle cannot supply its team's strength.
  if(word.Left<box.left-2||word.Top<box.top-2||word.Left+word.Width>box.right+2||word.Top+word.Height>box.bottom+2)return null;
  return {value:+literal.text,variant:reading.variant,confidence,pageSegMode:reading.pageSegMode,wordBox:{left:word.Left,top:word.Top,right:word.Left+word.Width,bottom:word.Top+word.Height}};
 });
 if(passes.length!==2||passes.some(pass=>!pass)||passes[0].variant===passes[1].variant||passes[0].value!==passes[1].value)return null;
 return {frameIndex,field:target.field,club:target.club,value:passes[0].value,box:{...box},passes,verified:true,kind:'strength-crop-consensus'};
}
/** Pure literal extraction, also usable with native TSV verification fixtures. */
export function extractionFromLocalOcr(ocr,type,{context={},frameIndex=0}={}){
 const width=Number(ocr.width),height=Number(ocr.height),response={matchEvidence:[],squadEvidence:[],calendarEvidence:[],teamEvidence:[],strengthEvidence:[]};
 if(!width||!height)return {data:blankExtraction(),response};
 let data;
 if(type==='match'){
  const literal=parseMatchOverlay(ocr,width,height,context);delete literal.myTrainingCamp;
  const fields=(literal._headerFields||[]).filter(key=>known(literal[key]));
  data=normalizeExtraction({match:literal},type,'Texto local associado às linhas',{sourceKind:'ocr-explicit',fields:Object.keys(literal).filter(field=>!field.startsWith('_')&&!(literal._weakHeaderFields||[]).includes(field))});
  if(fields.length)data=fuseExtraction(data,normalizeExtraction({match:Object.fromEntries(fields.map(key=>[key,literal[key]]))},type,'Cabeçalhos lidos no aparelho',{sourceKind:'ocr-layout',fields}));
  const controls=parseReportControls(ocr,width,height,context),controlFields=Object.keys(controls).filter(key=>!key.startsWith('_')&&known(controls[key]));
  if(controlFields.length)data=fuseExtraction(data,normalizeExtraction({match:controls},type,'Controles do relatório no aparelho',{sourceKind:'ocr-explicit',fields:controlFields}));
  const header=parseSquadMeta(ocr.lines,width,height);
  if(header.teamVerified===true&&header.team===literal.myName&&literal._headerFields?.includes('myName'))response.teamEvidence.push({team:header.team,frameIndex,verified:true});
 }else{
  const literal=overlayExtraction(type,ocr,width,height);
  if(type==='calendar'){
   const boundaries=parseCalendarBoundaryWords(overlayWords(ocr.lines),width,height);
   for(const field of ['sawTop','sawBottom'])if(boundaries[field+'Proof'])boundaries[field+'Proof'].frameIndex=frameIndex;
   literal.meta={...literal.meta,...boundaries};
  }
  data=normalizeExtraction(literal,type,'OCR local nas linhas',{sourceKind:'ocr-layout',fields:type==='squad'?['position','strength','age','value']:['round','date','time','displayedScore']});
  if(data.meta?.teamVerified===true&&known(data.meta.team))response.teamEvidence.push({team:data.meta.team,frameIndex,verified:true});
 }
 if(type==='squad')response.squadEvidence.push({frameIndex,width,height,players:parseSquadOverlay(ocr.lines,width,height)});
 if(type==='calendar')for(const row of parseCalendarOverlay(ocr.lines,width,height)){
  const literal=normalizeExtraction({calendar:[row]},type,'Cards lidos no aparelho',{sourceKind:'ocr-layout',fields:['round','date','time','displayedScore']});
  const normalized=literal.calendar[0]||literal.calendarFragments[0];if(normalized)response.calendarEvidence.push({frameIndex,row:{...normalized,_card:row._card}});
 }
 if(type==='match'||type==='squad')response.matchEvidence.push({frameIndex,width,height,ocr:{text:ocr.text,lines:ocr.lines},context,region:'full'});
 return {data,response};
}
export async function readLocalScreen(canvas,type,{signal,context={},onUpdate,layout,region,budgetMs=12000,languages='eng',frameIndex=0}={}){
 const started=Date.now(),results=[],warnings=[],regions=localReadRegions(canvas.width,canvas.height,type,{layout,region});
 const combined=()=>({text:results.map(r=>r.text).join('\n'),lines:results.flatMap(r=>r.lines),width:canvas.width,height:canvas.height,confidence:results.length?results.reduce((sum,r)=>sum+r.confidence,0)/results.length:0,provider:'tesseract-local'});
 const earlyRecoveries=[];
 const recoverControls=async original=>{
  let current=original,parsed=extractionFromLocalOcr(current,type,{context,frameIndex});
  for(const area of localRecoveryRegions(current,type,parsed.data,context).slice(0,2)){
   for(const variant of [area,{...area,gamma:1.3,invert:true}]){
    const remaining=budgetMs-(Date.now()-started);if(signal?.aborted||remaining<500||known(parsed.data.match?.[area.field]))break;
    try{onUpdate?.('Conferindo '+area.name+' na tela original.',{phase:'report-control',field:area.field});}catch{}
    const crop=cropForReading(canvas,variant);
    try{
     const recovered=await localOCR(crop,{signal,onUpdate,languages,budgetMs:Math.min(1800,remaining),pageSegMode:variant.pageSegMode,mapping:{offsetX:area.x,offsetY:area.y,scale:area.scale,width:canvas.width,height:canvas.height}});
     if(!recovered.lines?.length)continue;
     current=mergeLocalRegion(current,recovered,area);parsed=extractionFromLocalOcr(current,type,{context,frameIndex});earlyRecoveries.push({recovered,area});
    }catch(error){if(signal?.aborted)break;warnings.push('Conferência do relatório: '+error.message);break;}
    finally{crop.width=0;crop.height=0;}
   }
  }
  return current;
 };
 for(const [index,area] of regions.entries()){
  if(signal?.aborted){if(!results.length)throw Object.assign(Error('Leitura local cancelada.'),{name:'AbortError'});break;}
  const remaining=budgetMs-(Date.now()-started);if(remaining<300){warnings.push('Prazo da leitura local atingido; os dados reconhecidos foram preservados.');break;}
  try{onUpdate?.('Leitura no aparelho: '+area.name+' · '+(index+1)+'/'+regions.length,{phase:'region',region:area.name});}catch{}
  const crop=cropForReading(canvas,area);
  try{
   const result=await localOCR(crop,{signal,onUpdate,languages,budgetMs:remaining,pageSegMode:area.pageSegMode,mapping:{offsetX:area.x,offsetY:area.y,scale:area.scale||1,width:canvas.width,height:canvas.height}});
   results.push(result);
  }catch(error){
   if(!results.length)throw error;
   warnings.push(error.message);break;
  }finally{crop.width=0;crop.height=0;}
  // The first report crop carries the club caption and narrative. Recover
  // its tiny marking control before OCR of the much larger tactical panel.
  if(type==='match'&&index===0&&regions.length>1)await recoverControls(combined());
 }
 let ocr=combined();for(const {recovered,area} of earlyRecoveries)ocr=mergeLocalRegion(ocr,recovered,area);
 let parsed=extractionFromLocalOcr(ocr,type,{context,frameIndex});
 // Layout classification is a sampling hint. A same-frame literal report
 // can identify a lower control even when that screen was classified unknown
 // and the first pass read only its upper half.
 if(!earlyRecoveries.length){ocr=await recoverControls(ocr);parsed=extractionFromLocalOcr(ocr,type,{context,frameIndex});}
 if(type==='squad'&&!signal?.aborted&&budgetMs-(Date.now()-started)>500){
  const native=canvas.getContext('2d',{willReadFrequently:true}).getImageData(0,0,canvas.width,canvas.height),prepared=squadShirtOcrImage(native,parsed.response.squadEvidence[0]?.players||[]);
  if(prepared){
   const page=document.createElement('canvas');page.width=prepared.image.width;page.height=prepared.image.height;
   try{
    const ctx=page.getContext('2d',{willReadFrequently:true}),image=ctx.getImageData(0,0,page.width,page.height);image.data.set(prepared.image.data);ctx.putImageData(image,0,0);
    const raw=await localOCR(page,{signal,onUpdate,languages,budgetMs:Math.min(2200,budgetMs-(Date.now()-started)),pageSegMode:6,characters:'0123456789',mapping:{width:page.width,height:page.height}});
    const shirts=readSquadShirtNumbers(raw,prepared);
    if(shirts.length)parsed.data=fuseExtraction(parsed.data,normalizeExtraction({players:shirts},type,'Números dentro das camisas originais',{sourceKind:'ocr-layout',fields:['shirtNumber']}));
   }catch(error){if(!signal?.aborted)warnings.push('Número da camisa: '+error.message);}
   finally{page.width=0;page.height=0;}
  }
 }
 if(type==='match'||type==='squad'){
  const numericContext=type==='squad'&&parsed.data.meta?.teamVerified===true?{...context,myTeam:parsed.data.meta.team}:context;
  const targets=localNumericRegions(canvas,parsed.response.matchEvidence[0],numericContext);
  for(const target of targets){
   const remaining=budgetMs-(Date.now()-started);if(signal?.aborted||remaining<600)break;
   try{onUpdate?.('Confirmando a força no círculo de '+target.club+' no aparelho.',{phase:'numeric',field:target.field});}catch{}
   const readings=[];
   // Native luminance and inverted contrast have different segmentation
   // failure modes. The raw-line mode also reads compact stylised digits
   // that single-line mode occasionally rejects as letters.
   const compact=target.height<canvas.height*.075;
   for(const variant of [{name:'native-gray',gamma:1,invert:false,pageSegMode:compact?13:7},{name:'inverted-contrast',gamma:2,invert:true,pageSegMode:13}]){
    const timeLeft=budgetMs-(Date.now()-started);if(signal?.aborted||timeLeft<300)break;
    const crop=cropForReading(canvas,{...target,...variant});
    try{
     const raw=await localOCR(crop,{signal,onUpdate,languages,budgetMs:Math.min(1500,timeLeft),pageSegMode:variant.pageSegMode,characters:'0123456789',mapping:{offsetX:target.x,offsetY:target.y,scale:target.scale,width:canvas.width,height:canvas.height}});
     readings.push({ocr:raw,variant:variant.name,pageSegMode:variant.pageSegMode});
    }catch(error){if(signal?.aborted)break;warnings.push('Conferência local da força: '+error.message);break;}
    finally{crop.width=0;crop.height=0;}
   }
   const consensus=numericStrengthConsensus(target,readings,{frameIndex});
   if(consensus)parsed.response.strengthEvidence.push(consensus);
  }
 }
 if(type==='match')for(const target of localBonusRegions(canvas,parsed.response.matchEvidence[0],context)){
  const remaining=budgetMs-(Date.now()-started);if(signal?.aborted||remaining<500)break;
  try{onUpdate?.('Conferindo o bônus visível de '+target.club+'.',{phase:'bonus',field:target.field});}catch{}
  const crop=cropForReading(canvas,target);
  try{
   const raw=await localOCR(crop,{signal,onUpdate,languages,budgetMs:Math.min(1500,remaining),pageSegMode:7,characters:'+0123456789%',mapping:{offsetX:target.x,offsetY:target.y,scale:target.scale,width:canvas.width,height:canvas.height}});
   const literal=bonusRecoveryOverlay(raw,target);if(!literal)continue;
   const strengthEvidence=parsed.response.strengthEvidence;
   ocr=mergeLocalRegion(ocr,literal,target);parsed=extractionFromLocalOcr(ocr,type,{context,frameIndex});
   parsed.response.strengthEvidence=strengthEvidence;
  }catch(error){if(signal?.aborted)break;warnings.push('Conferência do bônus: '+error.message);break;}
  finally{crop.width=0;crop.height=0;}
 }
 const frames=[];frames[frameIndex]={canvas};
 const screened=applyScreenEvidence(parsed.data,parsed.response,frames,type);
 const data={...screened.data,sources:[...new Set([...screened.data.sources,'Leitura no aparelho',...screened.used])],warnings:[...new Set([...screened.data.warnings,...warnings])]};
 return {data,ocr,response:parsed.response,elapsedMs:Date.now()-started,warnings};
}

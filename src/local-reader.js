import {localOCR} from './ocr.js';
import {blankExtraction,normalizeExtraction,fuseExtraction} from './extraction.js';
import {parseMatchOverlay} from './parser-match.js';
import {overlayExtraction,parseSquadOverlay,parseSquadMeta,parseCalendarOverlay} from './ocr-layout.js';
import {applyScreenEvidence} from './visual-evidence.js';
import {detectMatchPixels} from './match-icons.js';
import {parseReportControls} from './report-controls.js';
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
function cropForReading(source,region){
 const scale=region.scale||1,canvas=document.createElement('canvas');canvas.width=Math.round(region.width*scale);canvas.height=Math.round(region.height*scale);
 const ctx=canvas.getContext('2d',{willReadFrequently:true});
 ctx.drawImage(source,region.x,region.y,region.width,region.height,0,0,canvas.width,canvas.height);
 const image=ctx.getImageData(0,0,canvas.width,canvas.height);
 for(let at=0;at<image.data.length;at+=4){
  const gray=.299*image.data[at]+.587*image.data[at+1]+.114*image.data[at+2],value=Math.round(255*Math.pow(gray/255,region.gamma));
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
/** Pure literal extraction, also usable with native TSV verification fixtures. */
export function extractionFromLocalOcr(ocr,type,{context={},frameIndex=0}={}){
 const width=Number(ocr.width),height=Number(ocr.height),response={matchEvidence:[],squadEvidence:[],calendarEvidence:[],teamEvidence:[]};
 if(!width||!height)return {data:blankExtraction(),response};
 let data;
 if(type==='match'){
  const literal=parseMatchOverlay(ocr,width,height,context),fields=(literal._headerFields||[]).filter(key=>known(literal[key]));
  data=normalizeExtraction({match:literal},type,'Texto local associado às linhas',{sourceKind:'ocr-explicit',fields:Object.keys(literal).filter(field=>!field.startsWith('_')&&!(literal._weakHeaderFields||[]).includes(field))});
  if(fields.length)data=fuseExtraction(data,normalizeExtraction({match:Object.fromEntries(fields.map(key=>[key,literal[key]]))},type,'Cabeçalhos lidos no aparelho',{sourceKind:'ocr-layout',fields}));
  const controls=parseReportControls(ocr,width,height,context),controlFields=Object.keys(controls).filter(key=>!key.startsWith('_')&&known(controls[key]));
  if(controlFields.length)data=fuseExtraction(data,normalizeExtraction({match:controls},type,'Controles do relatório no aparelho',{sourceKind:'ocr-explicit',fields:controlFields}));
  const header=parseSquadMeta(ocr.lines,width,height);
  if(header.teamVerified===true&&header.team===literal.myName&&literal._headerFields?.includes('myName'))response.teamEvidence.push({team:header.team,frameIndex,verified:true});
 }else{
  const literal=overlayExtraction(type,ocr,width,height);
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
 }
 const ocr={text:results.map(r=>r.text).join('\n'),lines:results.flatMap(r=>r.lines),width:canvas.width,height:canvas.height,confidence:results.length?results.reduce((sum,r)=>sum+r.confidence,0)/results.length:0,provider:'tesseract-local'};
 let parsed=extractionFromLocalOcr(ocr,type,{context,frameIndex});
 if(type==='match'||type==='squad'){
  const numericContext=type==='squad'&&parsed.data.meta?.teamVerified===true?{...context,myTeam:parsed.data.meta.team}:context;
  const targets=localNumericRegions(canvas,parsed.response.matchEvidence[0],numericContext);
  for(const target of targets){
   const remaining=budgetMs-(Date.now()-started);if(signal?.aborted||remaining<400)break;
   try{onUpdate?.('Confirmando a força no círculo de '+target.club+' no aparelho.',{phase:'numeric',field:target.field});}catch{}
   const crop=cropForReading(canvas,target);
   try{
    const raw=await localOCR(crop,{signal,onUpdate,languages,budgetMs:Math.min(3000,remaining),pageSegMode:7,characters:'0123456789',mapping:{offsetX:target.x,offsetY:target.y,scale:target.scale,width:canvas.width,height:canvas.height}}),number=numericRecoveryOverlay(raw);
    if(number){
     // Remove only the previous token inside this same isolated circle, so
     // concatenated OCR passes cannot leave conflicting 98/86 word boxes.
     ocr.lines=ocr.lines.map(line=>({...line,Words:(line.Words||[]).filter(word=>!(word.Left>=target.x&&word.Left+word.Width<=target.x+target.width&&word.Top>=target.y&&word.Top+word.Height<=target.y+target.height))})).filter(line=>line.Words.length);
     ocr.lines.push(...number.lines);ocr.text+='\n'+number.text;
    }
   }catch(error){if(signal?.aborted)break;warnings.push('Conferência local da força: '+error.message);}
   finally{crop.width=0;crop.height=0;}
  }
  parsed=extractionFromLocalOcr(ocr,type,{context,frameIndex});
 }
 const frames=[];frames[frameIndex]={canvas};
 const screened=applyScreenEvidence(parsed.data,parsed.response,frames,type);
 const data={...screened.data,sources:[...new Set([...screened.data.sources,'Leitura no aparelho',...screened.used])],warnings:[...new Set([...screened.data.warnings,...warnings])]};
 return {data,ocr,response:parsed.response,elapsedMs:Date.now()-started,warnings};
}

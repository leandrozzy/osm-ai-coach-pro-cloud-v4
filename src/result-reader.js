import {clubKey,normalize} from './utils.js';
import {overlayWords} from './ocr-layout.js';
import {extractFrames,imageToCanvas} from './frame-extractor.js';
import {localOCR,prepareLocalOCR} from './ocr.js';

export const resultMediaFields=['score','cards','rivalCards','yellowCards','rivalYellowCards','redCards','rivalRedCards','shots','rivalShots','possession','rivalPossession','corners','rivalCorners'];
const stats=[
 {label:/\b(?:cartoes? vermelhos?|red cards?)\b/,fields:['redCards','rivalRedCards'],max:11},
 {label:/\b(?:cartoes? amarelos?|yellow cards?)\b/,fields:['yellowCards','rivalYellowCards'],max:30},
 {label:/\b(?:cartoes?|cards?)\b/,fields:['cards','rivalCards'],max:30},
 {label:/\b(?:remates?|chutes?|finalizacoes|shots)\b/,fields:['shots','rivalShots'],max:100},
 {label:/\b(?:posse(?: de bola)?|possession)\b/,fields:['possession','rivalPossession'],max:100},
 {label:/\b(?:cantos?|escanteios?|corners?)\b/,fields:['corners','rivalCorners'],max:50}
];
const observed=value=>value!==undefined&&value!==null&&value!==''&&value!=='NI';
const emptyResult=()=>Object.fromEntries(resultMediaFields.map(field=>[field,'NI']));
const proof=(source,details={})=>({kind:'ocr-explicit',rank:2,source,...details});
function resultScreen(text){
 const value=normalize(text);
 return /\b(?:resultado|placar|estatisticas|remates|chutes|finalizacoes|posse|relatorio de jogo|resumo do jogo|final score|match result|match statistics|match stats|shots|possession|full time)\b/.test(value)&&!/(?:proximo jogo|pre[- ]jogo|kick[- ]off)/.test(value);
}
function inlineOrientation(line,pair,context){
 const own=clubKey(context.myTeam||context.myName),rival=clubKey(context.rivalName);
 if(!own||!rival||own===rival)return null;
 const left=normalize(line.slice(0,pair.index)).replace(/^(?:resultado(?: final)?|placar|final score|match result|full time)\s*[:.-]?\s*/,'').trim();
 const right=normalize(line.slice(pair.index+pair[0].length)).replace(/\s*\(?\b(?:ft|full time|fim|final)\)?\s*$/,'').trim();
 if(clubKey(left)===own&&clubKey(right)===rival)return {myOnLeft:true};
 if(clubKey(left)===rival&&clubKey(right)===own)return {myOnLeft:false};
 return null;
}
function setStatPair(reading,spec,values,orientation,source){
 if(values.length!==2||values.some(value=>!Number.isInteger(value)||value<0||value>spec.max))return;
 if(spec.fields[0]==='possession'&&values[0]+values[1]!==100)return;
 const [mine,rival]=orientation.myOnLeft?values:[values[1],values[0]];
 for(const [field,value] of [[spec.fields[0],mine],[spec.fields[1],rival]]){
  if(reading.conflicts.some(conflict=>conflict.field===field))continue;
  if(observed(reading.result[field])&&reading.result[field]!==value){reading.conflicts.push({field,first:reading.result[field],second:value});reading.result[field]='NI';delete reading.fieldSources[field];continue;}
  reading.result[field]=value;reading.fieldSources[field]=proof(source);
 }
}
function finish(reading){
 for(const [total,yellow,red] of [['cards','yellowCards','redCards'],['rivalCards','rivalYellowCards','rivalRedCards']]){
  if(!observed(reading.result[total])&&observed(reading.result[yellow])&&observed(reading.result[red])){
   reading.result[total]=reading.result[yellow]+reading.result[red];reading.fieldSources[total]=proof('Soma dos cartões amarelos e vermelhos identificados');
  }
 }
 return reading;
}
function blankReading(){return {result:emptyResult(),fieldSources:{},verifiedTeams:false,warnings:[],conflicts:[]};}

/** Literal fallback: a score needs both real club names on its own line. */
export function parseResultText(text='',context={}){
 const reading=blankReading();if(!resultScreen(text))return reading;
 for(const line of String(text).split(/\r?\n/)){
  const pair=normalize(line).match(/\b(\d{1,2})\s*[x×:\-–—]\s*(\d{1,2})\b/);
  if(!pair||+pair[1]>20||+pair[2]>20)continue;
  const orientation=inlineOrientation(normalize(line),pair,context);if(!orientation)continue;
  const left=+pair[1],right=+pair[2];
  const score=orientation.myOnLeft?left+'x'+right:right+'x'+left;
  if(observed(reading.result.score)&&reading.result.score!==score){reading.conflicts.push({field:'score',first:reading.result.score,second:score});reading.result.score='NI';break;}
  reading.result.score=score;reading.fieldSources.score=proof('Placar literal entre os nomes dos dois clubes');reading.verifiedTeams=true;reading.orientation=orientation;
 }
 if(reading.verifiedTeams)for(const line of String(text).split(/\r?\n/)){
  const value=normalize(line),spec=stats.find(stat=>stat.label.test(value));if(!spec)continue;
  const numbers=(value.match(/(?<![\p{L}\p{N}])\d{1,3}(?![\p{L}\p{N}])/gu)||[]).map(Number);
  setStatPair(reading,spec,numbers,reading.orientation,'Estatística literal: '+line.trim());
 }
 return finish(reading);
}
function rowsOf(words){
 const rows=[];
 for(const word of [...words].sort((a,b)=>a.y+a.h/2-(b.y+b.h/2)||a.x-b.x)){
  const center=word.y+word.h/2,row=rows.find(candidate=>Math.abs(candidate.y-center)<Math.max(candidate.h,word.h)*.65);
  if(row){row.words.push(word);row.y=row.words.reduce((sum,w)=>sum+w.y+w.h/2,0)/row.words.length;row.h=Math.max(row.h,word.h);}
  else rows.push({y:center,h:word.h,words:[word]});
 }
 return rows.map(row=>({...row,words:row.words.sort((a,b)=>a.x-b.x),text:row.words.sort((a,b)=>a.x-b.x).map(word=>word.text).join(' ')}));
}
function clubAnchors(rows,team){
 const key=clubKey(team);if(!key)return [];
 const anchors=[];
 for(const row of rows)for(let start=0;start<row.words.length;start++)for(let end=start;end<Math.min(row.words.length,start+8);end++){
  const words=row.words.slice(start,end+1),text=words.map(word=>word.text).join(' ');
  if(clubKey(text)!==key)continue;
  const before=row.words[start-1],after=row.words[end+1],near=word=>word&&/[\p{L}]/u.test(word.text)&&Math.max(word.h,row.h)*1.5;
  if(near(before)&&words[0].x-(before.x+before.w)<near(before)||near(after)&&after.x-(words.at(-1).x+words.at(-1).w)<near(after))continue;
  const left=words[0].x,right=Math.max(...words.map(word=>word.x+word.w));anchors.push({text,x:(left+right)/2,y:row.y,left,right,h:row.h});
 }
 return anchors;
}
function headerOrientation(rows,context,width,height){
 const mine=clubAnchors(rows,context.myTeam||context.myName),rivals=clubAnchors(rows,context.rivalName),pairs=[];
 if(!mine.length||!rivals.length||clubKey(context.myTeam||context.myName)===clubKey(context.rivalName))return null;
 for(const my of mine)for(const rival of rivals){
  if(Math.abs(my.y-rival.y)>Math.max(height*.07,my.h*2,rival.h*2)||Math.abs(my.x-rival.x)<width*.16)continue;
  pairs.push({myOnLeft:my.x<rival.x,my,rival,y:(my.y+rival.y)/2});
 }
 return pairs.sort((a,b)=>a.y-b.y)[0]||null;
}
/** Coordinates associate left/right score cells and statistics with visible clubs. */
export function parseResultOverlay(ocr={},context={}){
 const fallback=parseResultText(ocr.text||'',context),width=Number(ocr.width),height=Number(ocr.height);
 if(!width||!height||!resultScreen(ocr.text||''))return fallback;
 const words=overlayWords(ocr.lines||[]).filter(word=>word.w>0&&word.h>0&&(!Number.isFinite(word.confidence)||word.confidence>=40));
 const rows=rowsOf(words),orientation=headerOrientation(rows,context,width,height);
 if(!orientation)return fallback;
 const reading=blankReading();reading.verifiedTeams=true;reading.orientation={myOnLeft:orientation.myOnLeft};
 const left=Math.min(orientation.my.left,orientation.rival.left),right=Math.max(orientation.my.right,orientation.rival.right),scoreCandidates=[];
 for(const row of rows){
  if(row.y<orientation.y-height*.09||row.y>orientation.y+height*.19)continue;
  if(stats.some(stat=>stat.label.test(normalize(row.text))))continue;
  const central=row.words.filter(word=>word.x+word.w>=left&&word.x<=right);
  const inline=central.map(word=>word.text).join(' ').match(/\b(\d{1,2})\s*[x×:\-–—]\s*(\d{1,2})\b/);
  let values;
  if(inline&&+inline[1]<=20&&+inline[2]<=20)values=[+inline[1],+inline[2]];
  else {
   const numbers=central.filter(word=>/^\d{1,2}$/.test(word.text)&&+word.text<=20);
   const letters=central.filter(word=>/\p{L}/u.test(word.text)&&clubKey(word.text)!=='x');
   // A scoreboard pair has two isolated digits, not dates, strength or events.
   if(numbers.length===2&&!letters.length&&numbers[1].x-numbers[0].x>width*.02)values=numbers.map(word=>+word.text);
  }
  if(values)scoreCandidates.push({values,row});
 }
 const unique=[...new Set(scoreCandidates.map(({values})=>orientation.myOnLeft?values[0]+'x'+values[1]:values[1]+'x'+values[0]))];
 if(unique.length===1){reading.result.score=unique[0];reading.fieldSources.score=proof('Placar associado aos clubes pelas posições na imagem');}
 else if(unique.length>1){reading.conflicts.push({field:'score',values:unique});reading.warnings.push('A imagem contém placares diferentes; confirme o resultado.');}
 else if(observed(fallback.result.score)){reading.result.score=fallback.result.score;reading.fieldSources.score=fallback.fieldSources.score;}
 // Only a verified scoreboard permits the surrounding two-sided stat table.
 if(observed(reading.result.score))for(const row of rows){
  const spec=stats.find(stat=>stat.label.test(normalize(row.text)));if(!spec)continue;
  const numbers=row.words.filter(word=>/^\d{1,3}%?$/.test(word.text)).map(word=>Number(word.text.replace('%','')));
  setStatPair(reading,spec,numbers,reading.orientation,'Estatística associada aos clubes: '+row.text);
 }
 return finish(reading);
}
function abortError(message='Leitura do resultado cancelada.'){return Object.assign(Error(message),{name:'AbortError'});}
function preview(frame){try{return {url:frame.canvas.toDataURL('image/jpeg',.3),name:frame.name,fileIndex:frame.fileIndex,time:frame.time};}catch{return {name:frame.name,fileIndex:frame.fileIndex,time:frame.time};}}

/** Reads original images/video frames locally, then leaves saving to explicit review. */
export async function analyzeResultMedia(files,context={},options={}){
 files=Array.from(files||[]);if(!files.length)throw Error('Selecione o vídeo ou as imagens do resultado.');
 if(files.length>8||files.some(file=>file.size>150*1024*1024))throw Error('Máximo 8 arquivos, 150 MB por arquivo.');
 const started=Date.now(),limit=Math.max(1000,Math.min(180000,Number(options.timeoutMs)||90000)),controller=new AbortController();
 const stop=()=>controller.abort(options.signal?.reason||abortError());if(options.signal?.aborted)stop();else options.signal?.addEventListener('abort',stop,{once:true});
 const timer=setTimeout(()=>controller.abort(abortError('Tempo limite da leitura do resultado. Confira os campos reconhecidos.')),limit);
 const onProgress=(message,detail={})=>{try{options.onProgress?.(message,detail);}catch{}},result=emptyResult(),fieldSources={},previews=[],warnings=[],conflicts=[],blocked=new Set(),frames=[];
 const merge=reading=>{
  warnings.push(...reading.warnings||[]);conflicts.push(...reading.conflicts||[]);
  for(const conflict of reading.conflicts||[])if(conflict.field){blocked.add(conflict.field);result[conflict.field]='NI';delete fieldSources[conflict.field];}
  for(const field of resultMediaFields){
   if(blocked.has(field)||!observed(reading.result[field]))continue;
   if(observed(result[field])&&result[field]!==reading.result[field]){conflicts.push({field,first:result[field],second:reading.result[field]});warnings.push('Leituras diferentes para '+field+'; confirme no vídeo.');blocked.add(field);result[field]='NI';delete fieldSources[field];}
   else {result[field]=reading.result[field];fieldSources[field]=reading.fieldSources[field];}
  }
 };
 try{
  for(const [fileIndex,file] of files.entries()){
   if(controller.signal.aborted)throw controller.signal.reason||abortError();onProgress('Extraindo telas de '+file.name,{phase:'extract',fileIndex});
   try{
    if(file.type?.startsWith('video/')||/\.(?:mp4|webm|mov|m4v)$/i.test(file.name||'')){
     const selected=await (options.extractFrames||extractFrames)(file,{type:'result',profile:'complete',maxFrames:6,candidates:48,signal:controller.signal,onProbe:detail=>onProgress('Selecionando telas do resultado…',{phase:'extract',...detail})});
     frames.push(...selected.map(frame=>({...frame,name:file.name,fileIndex})));
    }else if(file.type?.startsWith('image/')||/\.(?:jpe?g|png|webp|heic)$/i.test(file.name||''))frames.push({canvas:await (options.imageToCanvas||imageToCanvas)(file),name:file.name,fileIndex,time:0});
    else warnings.push('Formato não suportado: '+file.name);
   }catch(error){if(controller.signal.aborted)throw error;warnings.push(file.name+': '+error.message);}
  }
  if(!frames.length)throw Error('Nenhuma tela do resultado foi extraída. Use um vídeo válido ou uma imagem.');
  await (options.prepareLocalOCR||prepareLocalOCR)({signal:controller.signal,budgetMs:Math.min(35000,limit-(Date.now()-started)),onUpdate:onProgress});
  for(const [index,frame] of frames.entries()){
   if(controller.signal.aborted)throw controller.signal.reason||abortError();previews.push(preview(frame));onProgress('Lendo resultado '+(index+1)+'/'+frames.length,{phase:'recognize',done:index,total:frames.length});
   try{
    const ocr=await (options.localOCR||localOCR)(frame.canvas,{signal:controller.signal,budgetMs:Math.min(15000,limit-(Date.now()-started)),pageSegMode:11,onUpdate:onProgress});
    const reading=parseResultOverlay({...ocr,width:frame.canvas.width,height:frame.canvas.height},context);
    for(const field of Object.keys(reading.fieldSources))reading.fieldSources[field]={...reading.fieldSources[field],frame:{name:frame.name,fileIndex:frame.fileIndex,time:frame.time}};
    merge(reading);
   }catch(error){if(options.signal?.aborted)throw error;warnings.push(frame.name+': '+error.message);if(error.code==='OCR_INIT'||controller.signal.aborted)break;}
  }
 }catch(error){
  if(options.signal?.aborted)throw abortError();
  if(!previews.length||!controller.signal.aborted)throw error;
  warnings.push(error.message||'Tempo limite da leitura.');
 }finally{
  clearTimeout(timer);options.signal?.removeEventListener('abort',stop);for(const frame of frames){frame.canvas.width=0;frame.canvas.height=0;}
 }
 if(!observed(result.score))warnings.push('Placar e lado do seu clube não foram confirmados nas telas. Preencha o placar do seu time primeiro.');
 return {result,fieldSources,previews,warnings:[...new Set(warnings)],conflicts,source:'OCR local do resultado',elapsed:Math.round((Date.now()-started)/1000),recognizedFields:resultMediaFields.filter(field=>observed(result[field])).length};
}

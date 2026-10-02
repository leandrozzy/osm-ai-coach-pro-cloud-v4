import {extractFrames,imageToCanvas} from './frame-extractor.js';
import {preprocess,canvasBlob} from './image-preprocess.js';
import {layeredOCR} from './ocr.js';
import {askVision} from './ai-router.js';
import {cleanMatch,cleanPlayers,known} from './domain.js';
import {mergeBetter} from './validator.js';
import {mergeMatchTexts} from './parser-match.js';
import {parseSquadText,dedupePlayers} from './parser-squad.js';
import {parseCalendarText,mergeCalendar} from './parser-calendar.js';
async function dataUrl(canvas){
 const c=document.createElement('canvas');const scale=Math.min(1,1000/canvas.width);
 c.width=Math.round(canvas.width*scale);c.height=Math.round(canvas.height*scale);c.getContext('2d').drawImage(canvas,0,0,c.width,c.height);
 const blob=await canvasBlob(c,'image/jpeg',.7);
 return new Promise((resolve,reject)=>{const fr=new FileReader();fr.onload=()=>resolve(fr.result);fr.onerror=reject;fr.readAsDataURL(blob);});
}
export async function analyzeMedia(files,type,options={},onUpdate=()=>{}){
 if(files.length>24)throw Error('Selecione até 24 arquivos por análise.');
 if(files.some(f=>f.size>150*1024*1024))throw Error('Cada arquivo deve ter até 150 MB.');
 const check=()=>{if(options.signal?.aborted)throw Error('Análise cancelada; dados preservados.');};
 const started=Date.now(),frames=[],errors=[];
 for(let index=0;index<files.length;index++){
  check();onUpdate('Preparando arquivo '+(index+1)+'/'+files.length);
  const file=files[index];
  const maxFrames=Math.max(1,Math.floor(24/files.length));
  if(file.type.startsWith('video/')){const fs=await extractFrames(file,{maxFrames});frames.push(...fs.map(f=>({canvas:f.canvas,name:file.name,time:f.time})));}
  else if(file.type.startsWith('image/'))frames.push({canvas:await imageToCanvas(file),name:file.name,time:0});
  else errors.push(file.name+': formato não suportado.');
 }
 check();if(!frames.length)throw Error('Nenhuma tela extraída. Tente PNG/JPEG ou vídeo MP4.');
 let match={},players=[],calendar=[],mode='OCR local';
 const previews=frames.map(f=>({url:f.canvas.toDataURL('image/jpeg',.35),name:f.name,time:f.time}));
 for(let i=0;i<frames.length;i+=3){
  check();if(Date.now()-started>180000){errors.push('Limite de 3 minutos atingido; revise os dados parciais.');break;}
  const batch=frames.slice(i,i+3);let read=false;
  if(options.vision){
   onUpdate('IA visual: telas '+(i+1)+'–'+Math.min(i+3,frames.length)+' de '+frames.length);
   try{
    const images=await Promise.all(batch.map(f=>dataUrl(f.canvas)));check();
    const r=await askVision('vision-'+type,images,{username:options.username},options.model);check();
    if(type==='match'){const cleaned=cleanMatch(r.data);match=mergeBetter(match,cleaned);read=Object.keys(cleaned).length>1;}
    if(type==='squad'){const cleaned=cleanPlayers(r.data.players||[]);players=dedupePlayers([...players,...cleaned]);read=cleaned.length>0;}
    if(type==='calendar'){const cleaned=(r.data.calendar||[]).filter(r=>r&&typeof r==='object').map(r=>({...r,id:crypto.randomUUID(),home:typeof r.home==='boolean'?r.home:null,cup:typeof r.cup==='boolean'?r.cup:null,date:known(r.date)?String(r.date):'NI',time:known(r.time)?String(r.time):'NI',opponent:known(r.opponent)?String(r.opponent):'NI'}));calendar=mergeCalendar(calendar,cleaned);read=cleaned.length>0;}
    mode='IA visual: '+r.provider;
   }catch(e){errors.push(e.message);if(!options.fallback)throw e;}
  }
  if(!read&&(options.fallback||!options.vision)){
   const texts=[];
   for(let j=0;j<batch.length;j++){
    check();onUpdate('OCR local: tela '+(i+j+1)+'/'+frames.length);
    try{const r=await layeredOCR(preprocess(batch[j].canvas));check();if(r.text.trim())texts.push(r.text);}catch(e){errors.push(e.message);}
   }
   if(type==='match')match=mergeBetter(match,cleanMatch(mergeMatchTexts(texts)));
   if(type==='squad')players=dedupePlayers([...players,...cleanPlayers(parseSquadText(texts.join('\n')))]);
   if(type==='calendar')calendar=mergeCalendar(calendar,parseCalendarText(texts.join('\n')));
  }
 }
 check();
 return {type,match,players,calendar,previews,errors:[...new Set(errors)],mode,frames:frames.length,createdAt:new Date().toISOString()};
}


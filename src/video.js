import {extractFrames,imageToCanvas} from './frame-extractor.js';
import {preprocess,canvasBlob} from './image-preprocess.js';
import {layeredOCR} from './ocr.js';
import {askVision} from './ai-router.js';

async function canvasDataUrl(canvas,quality=.72){
  const maxW=1152;
  let source=canvas;
  if(canvas.width>maxW){
    const c=document.createElement('canvas');
    const scale=maxW/canvas.width;
    c.width=Math.round(canvas.width*scale);
    c.height=Math.round(canvas.height*scale);
    c.getContext('2d').drawImage(canvas,0,0,c.width,c.height);
    source=c;
  }
  const blob=await canvasBlob(source,'image/jpeg',quality);
  return new Promise((resolve,reject)=>{
    const fr=new FileReader();
    fr.onload=()=>resolve(String(fr.result));
    fr.onerror=reject;
    fr.readAsDataURL(blob);
  });
}

async function collectCanvases(files,maxFrames=9){
  const canvases=[];
  for(const file of files){
    if(file.type.startsWith('video/')){
      const frames=await extractFrames(file,{maxFrames});
      canvases.push(...frames.map(f=>({canvas:f.canvas,time:f.time,name:file.name})));
    }else if(file.type.startsWith('image/')){
      canvases.push({canvas:await imageToCanvas(file),time:0,name:file.name});
    }
  }
  return canvases.slice(0,maxFrames);
}

function mergeVisionObjects(objects=[]){
  const out={_visionSources:{}};
  for(let i=0;i<objects.length;i++){
    const obj=objects[i]||{};
    for(const [k,v] of Object.entries(obj)){
      if(k.startsWith('_')||v==null||v===''||v==='NI')continue;
      if(out[k]==null||out[k]==='NI'){
        out[k]=v;
        out._visionSources[k]=`vision:${i+1}`;
      }
    }
  }
  return out;
}

export async function analyzeMatchVision(files,onUpdate=()=>{}){
  const frames=await collectCanvases(files,9);
  if(!frames.length)throw new Error('Não consegui extrair telas válidas da mídia.');

  const batches=[];
  for(let i=0;i<frames.length;i+=3)batches.push(frames.slice(i,i+3));

  const results=[];
  const errors=[];
  for(let i=0;i<batches.length;i++){
    onUpdate({stage:'vision',current:i+1,total:batches.length,frames:frames.length});
    try{
      const images=await Promise.all(batches[i].map(x=>canvasDataUrl(x.canvas)));
      const response=await askVision('vision-match',images,{
        batch:i+1,totalBatches:batches.length,
        hint:'Frames em ordem temporal de um vídeo do OSM 26 Android.'
      });
      if(response?.data)results.push(response.data);
    }catch(e){
      errors.push(e.message);
      console.warn('Visão falhou no lote',i+1,e);
    }
  }

  if(!results.length){
    onUpdate({stage:'fallback-ocr',current:1,total:frames.length});
    const texts=[];
    for(let i=0;i<frames.length;i++){
      onUpdate({stage:'fallback-ocr',current:i+1,total:frames.length});
      try{
        const r=await layeredOCR(preprocess(frames[i].canvas));
        if(r?.text?.trim())texts.push(r.text);
      }catch{}
    }
    return {mode:'ocr-fallback',frames:frames.length,texts,vision:null,errors};
  }

  return {
    mode:'vision',
    frames:frames.length,
    batches:batches.length,
    vision:mergeVisionObjects(results),
    errors
  };
}

export async function mediaToTexts(files,onUpdate=()=>{}){
  const frames=await collectCanvases(files,12);
  if(!frames.length)throw new Error('Não consegui extrair nenhuma tela válida da mídia.');
  const texts=[];let failures=0;
  for(let i=0;i<frames.length;i++){
    onUpdate({stage:'ocr',current:i+1,total:frames.length});
    try{
      const r=await layeredOCR(preprocess(frames[i].canvas));
      if(r?.text?.trim())texts.push(r.text);
    }catch(error){failures++;}
  }
  if(!texts.length)throw new Error('Nenhuma leitura aproveitável.');
  return {texts,frameCount:frames.length,failures};
}

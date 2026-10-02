import {extractFrames,imageToCanvas} from './frame-extractor.js';
import {preprocess} from './image-preprocess.js';
import {layeredOCR} from './ocr.js';

export async function mediaToTexts(files,onUpdate=()=>{}){
  const canvases=[];
  for(const file of files){
    try{
      if(file.type.startsWith('video/')){const frames=await extractFrames(file);canvases.push(...frames.map(f=>f.canvas));}
      else if(file.type.startsWith('image/')) canvases.push(await imageToCanvas(file));
    }catch(error){console.warn('Mídia parcialmente ignorada',file.name,error);}
  }
  if(!canvases.length) throw new Error('Não consegui extrair nenhuma tela válida da mídia.');
  const texts=[];let failures=0;
  for(let i=0;i<canvases.length;i++){
    onUpdate({stage:'ocr',current:i+1,total:canvases.length});
    try{const r=await layeredOCR(preprocess(canvases[i]),p=>onUpdate({stage:'ocr',current:i+1,total:canvases.length,progress:p}));if(r?.text?.trim())texts.push(r.text);}
    catch(error){failures++;console.warn(`OCR falhou no frame ${i+1}`,error);}
  }
  if(!texts.length) throw new Error('As telas foram extraídas, mas nenhum OCR conseguiu leitura aproveitável.');
  return {texts,frameCount:canvases.length,failures};
}

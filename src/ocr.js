import {canvasBlob} from './image-preprocess.js';
let workerPromise;
async function worker(){if(!globalThis.Tesseract?.createWorker) throw new Error('Tesseract.js indisponível');if(!workerPromise)workerPromise=globalThis.Tesseract.createWorker('por+eng').catch(()=>globalThis.Tesseract.createWorker('eng'));return workerPromise;}
export async function localOCR(canvas,onProgress=()=>{}){const w=await worker();onProgress(0.15);const {data}=await w.recognize(canvas);onProgress(1);return {text:data.text||'',confidence:data.confidence||0,provider:'tesseract'};}
export async function remoteOCR(canvas){const blob=await canvasBlob(canvas);const base64=await new Promise((res,rej)=>{const fr=new FileReader();fr.onload=()=>res(String(fr.result).split(',')[1]);fr.onerror=rej;fr.readAsDataURL(blob)});const r=await fetch('/api/ocr',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({imageBase64:base64})});if(!r.ok)throw new Error(`OCR remoto HTTP ${r.status}`);return r.json();}
export async function layeredOCR(canvas,onProgress=()=>{}){
 const local=await localOCR(canvas,onProgress); let remote=null;
 // OCR local pode ter confiança alta e ainda omitir colunas; usa remoto quando o conteúdo é curto/incompleto.
 const useful=(local.text||'').trim(); const needsRemote=local.confidence<82||useful.length<180||!/força|valor|elenco|formação|marcação|treino|bônus|advers/i.test(useful);
 if(needsRemote){try{remote=await remoteOCR(canvas);}catch{}}
 const parts=[local.text,remote?.text].filter(Boolean).map(x=>x.trim()).filter(Boolean);
 const text=[...new Set(parts)].join('\n');
 return {text,confidence:Math.max(local.confidence||0,remote?.confidence||0),provider:remote?'tesseract+ocr.space':'tesseract'};
}

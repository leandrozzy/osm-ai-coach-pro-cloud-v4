import {canvasBlob} from './image-preprocess.js';
let workerPromise;
function deadline(p,ms,label){let t;return Promise.race([p,new Promise((_,reject)=>{t=setTimeout(()=>reject(Error(label)),ms);})]).finally(()=>clearTimeout(t));}
async function worker(){
 if(!globalThis.Tesseract){
 await deadline(new Promise((resolve,reject)=>{let script=document.querySelector('#ocr-script');if(!script){script=document.createElement('script');script.id='ocr-script';script.src='https://cdn.jsdelivr.net/npm/tesseract.js@6/dist/tesseract.min.js';document.head.append(script);}script.onload=resolve;script.onerror=()=>reject(Error('Não foi possível carregar o OCR. Verifique a internet.'));}),10000,'Download do OCR excedeu 10 segundos.');
 }
 if(!globalThis.Tesseract?.createWorker)throw Error('OCR local indisponível.');
 if(!workerPromise)workerPromise=deadline(globalThis.Tesseract.createWorker('por+eng'),20000,'Inicialização do OCR excedeu 20 segundos.').catch(e=>{workerPromise=null;throw e;});
 return workerPromise;
}
export async function localOCR(canvas,{signal}={}){
 if(signal?.aborted)throw Error('OCR local cancelado.');
 let stop;
 const abort=new Promise((_,reject)=>{stop=()=>reject(Error('OCR local cancelado.'));signal?.addEventListener('abort',stop,{once:true});});
 try{return await Promise.race([(async()=>{const w=await worker();if(signal?.aborted)throw Error('OCR local cancelado.');const {data}=await deadline(w.recognize(canvas),8000,'OCR local excedeu 8 segundos nesta tela.');return {text:data.text||'',confidence:data.confidence||0,provider:'tesseract'};})(),abort]);}
 catch(e){const current=workerPromise;workerPromise=null;current?.then(w=>w.terminate()).catch(()=>{});throw e;}
 finally{signal?.removeEventListener('abort',stop);}
}

export async function remoteOCR(canvas){
 const blob=await canvasBlob(canvas);const imageBase64=await new Promise((res,rej)=>{const fr=new FileReader();fr.onload=()=>res(String(fr.result).split(',')[1]);fr.onerror=rej;fr.readAsDataURL(blob);});
 const controller=new AbortController();const t=setTimeout(()=>controller.abort(),12000);
 try{const r=await fetch('/api/ocr',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({imageBase64}),signal:controller.signal});if(!r.ok)throw Error('OCR remoto indisponível');return r.json();}finally{clearTimeout(t);}
}
export async function layeredOCR(canvas){
 let local;try{local=await localOCR(canvas);}catch{local={text:'',confidence:0};}
 if(local.text.trim())return local;
 const remote=await remoteOCR(canvas);if(!remote.text?.trim())throw Error('Nenhum texto reconhecido. Use mídia mais nítida ou edição manual.');return remote;
}


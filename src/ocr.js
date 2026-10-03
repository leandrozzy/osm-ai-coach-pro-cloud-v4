import {canvasBlob} from './image-preprocess.js';

const SCRIPT='https://cdn.jsdelivr.net/npm/tesseract.js@6.0.1/dist/tesseract.min.js';
let libraryPromise,record,serial=Promise.resolve(),sequence=0;
const cancelled=reason=>Object.assign(Error(reason||'Leitura local cancelada.'),{name:'AbortError'});
function progress(callback,message,details={}){try{callback?.(message,details);}catch{}}
function wait(promise,{signal,ms,label}={}){
 let timer,stop;
 const limit=new Promise((_,reject)=>{
  if(signal?.aborted){reject(cancelled(signal.reason?.message));return;}
  stop=()=>reject(cancelled(signal.reason?.message));signal?.addEventListener('abort',stop,{once:true});
  if(ms)timer=setTimeout(()=>reject(Error(label||'Tempo limite da leitura local.')),ms);
 });
 return Promise.race([promise,limit]).finally(()=>{clearTimeout(timer);signal?.removeEventListener('abort',stop);});
}
function loadLibrary(onUpdate){
 if(globalThis.Tesseract?.createWorker)return Promise.resolve(globalThis.Tesseract);
 if(libraryPromise)return libraryPromise;
 progress(onUpdate,'Preparando OCR no aparelho: o motor gratuito é baixado na primeira leitura e reutilizado nas próximas.',{phase:'download',firstUse:true});
 libraryPromise=wait(new Promise((resolve,reject)=>{
  if(!globalThis.document){reject(Error('OCR local requer um navegador com Web Workers.'));return;}
  let script=document.querySelector('#ocr-script');
  if(script?.dataset?.failed==='true'){script.remove();script=null;}
  if(!script){script=document.createElement('script');script.id='ocr-script';script.src=SCRIPT;script.async=true;document.head.append(script);}
  script.onload=()=>globalThis.Tesseract?.createWorker?resolve(globalThis.Tesseract):reject(Error('Motor OCR não ficou disponível.'));
  script.onerror=()=>{script.dataset.failed='true';reject(Error('Falha ao baixar o motor OCR gratuito. Verifique a conexão.'));};
 }),{ms:20000,label:'Download do motor OCR excedeu 20 segundos.'}).catch(error=>{libraryPromise=null;const script=globalThis.document?.querySelector('#ocr-script');if(script)script.dataset.failed='true';throw error;});
 return libraryPromise;
}
function terminate(target){
 if(!target||target.invalid)return;
 target.invalid=true;target.onUpdate=null;if(record===target)record=null;
 // A late initialization owns its own worker. It cannot terminate the next job.
 target.promise?.then(worker=>worker.terminate()).catch(()=>{});
}
async function getWorker(job,languages){
 if(record&&record.languages!==languages)terminate(record);
 if(!record){
  const target={languages,invalid:false,instance:null,onUpdate:job.onUpdate};record=target;job.record=target;
  target.promise=(async()=>{
   const Tesseract=await wait(loadLibrary(job.onUpdate),{signal:job.signal});
   if(job.signal.aborted)throw cancelled(job.signal.reason?.message);
   progress(target.onUpdate,'Preparando leitura no aparelho; idioma '+languages+' e motor ficam em cache.',{phase:'initialize',firstUse:true});
   let instance;
   try{instance=await Tesseract.createWorker(languages,1,{workerPath:SCRIPT.replace('tesseract.min.js','worker.min.js'),logger:message=>{
    if(target.invalid)return;
    const status=String(message?.status||''),phase=/recogniz/i.test(status)?'recognize':'initialize';
    progress(target.onUpdate,phase==='recognize'?'Lendo a região selecionada no aparelho.':'Carregando motor e idioma local pela primeira vez ou do cache.',{phase,status,progress:Number(message?.progress)||0});
   }});}catch(error){throw Object.assign(Error('Não foi possível inicializar o motor OCR no aparelho: '+(error.message||error)),{code:'OCR_INIT',cause:error});}
   target.instance=instance;return instance;
  })();
  target.promise.catch(()=>{if(record===target)record=null;});
 }
 job.record=record;record.onUpdate=job.onUpdate;
 return wait(record.promise,{signal:job.signal});
}
function enqueue(operation,{signal,onUpdate,budgetMs=20000,languages='eng'}={}){
 const controller=new AbortController(),job={id:++sequence,signal:controller.signal,onUpdate,record:null};
 const stop=()=>controller.abort(signal?.reason||cancelled());if(signal?.aborted)stop();else signal?.addEventListener('abort',stop,{once:true});
 const duration=Math.max(100,Math.min(60000,Number(budgetMs)||20000));
 const timer=setTimeout(()=>controller.abort(cancelled('Tempo limite da leitura local: a tarefa foi encerrada.')),duration);
 const operationPromise=serial.catch(()=>{}).then(async()=>{
  if(job.signal.aborted)throw cancelled(job.signal.reason?.message);
  try{return await operation(await getWorker(job,languages),job);}
  catch(error){
   const initializationFailed=!job.record?.instance;terminate(job.record);
   // Cold download/initialization can end at this job's deadline before
   // createWorker rejects. Report that stage once to the caller so it does
   // not bootstrap the same unavailable engine on every selected screen.
   if(initializationFailed&&error.code!=='OCR_INIT')throw Object.assign(Error(error.message||'Falha na preparação do OCR local.'),{name:error.name||'Error',code:'OCR_INIT',cause:error});
   throw error;
  }
  finally{if(job.record?.onUpdate===job.onUpdate)job.record.onUpdate=null;}
 });
 // An aborted queued job stays behind the active job, then skips its operation.
 // Releasing it early would start two recognize calls on the same worker.
 serial=operationPromise.catch(()=>{});
 return wait(operationPromise,{signal:job.signal}).finally(()=>{clearTimeout(timer);signal?.removeEventListener('abort',stop);});
}
export const localOCRStatus=()=>({ready:!!record?.instance&&!record.invalid,loading:!!record&&!record.instance,languages:record?.languages||'eng'});
export function resetLocalOCR(){terminate(record);}
export function prepareLocalOCR(options={}){return enqueue(async(worker,job)=>{if(job.signal.aborted)throw cancelled();return {ready:true,languages:options.languages||'eng'};},{budgetMs:35000,...options});}

/** Tesseract 6 blocks must be explicitly enabled; coordinates map to the original screen. */
export function tesseractOverlay(data={},mapping={}){
 const scale=Number(mapping.scale)||1,x=Number(mapping.offsetX)||0,y=Number(mapping.offsetY)||0;
 const mapBox=box=>mapping.mapBox?mapping.mapBox(box):{x0:x+box.x0/scale,y0:y+box.y0/scale,x1:x+box.x1/scale,y1:y+box.y1/scale};
 let lines=[];
 for(const block of data.blocks||[])for(const paragraph of block.paragraphs||[])for(const line of paragraph.lines||[])lines.push(line);
 if(!lines.length&&Array.isArray(data.lines))lines=data.lines;
 if(!lines.length&&Array.isArray(data.words))lines=data.words.map(word=>({words:[word]}));
 const out=lines.map(line=>{
  const Words=(line.words||[]).map(word=>{
   const text=String(word.text||'').trim(),raw=word.bbox;
   if(!text||!raw||![raw.x0,raw.y0,raw.x1,raw.y1].every(Number.isFinite)||raw.x1<=raw.x0||raw.y1<=raw.y0)return null;
   const box=mapBox(raw);if(!box||![box.x0,box.y0,box.x1,box.y1].every(Number.isFinite))return null;
   return {WordText:text,Left:box.x0,Top:box.y0,Width:box.x1-box.x0,Height:box.y1-box.y0,Confidence:Number(word.confidence)||0};
  }).filter(Boolean);
  if(!Words.length)return null;
  return {Words,MinTop:Math.min(...Words.map(w=>w.Top)),MaxHeight:Math.max(...Words.map(w=>w.Height))};
 }).filter(Boolean);
 return {text:String(data.text||''),lines:out,confidence:Number(data.confidence)||0,provider:'tesseract-local',...(mapping.width?{width:mapping.width,height:mapping.height}:{})};
}
export function localOCR(canvas,options={}){
 return enqueue(async(worker,job)=>{
  // A number-only crop must not leave the cached worker restricted when
  // the next task reads player names or report labels.
  await wait(worker.setParameters?.({tessedit_pageseg_mode:String(options.pageSegMode??11),preserve_interword_spaces:'1',tessedit_char_whitelist:options.characters||options.whitelist||''}),{signal:job.signal});
  const {data}=await wait(worker.recognize(canvas,{}, {text:true,blocks:true}),{signal:job.signal});
  return tesseractOverlay(data,{width:canvas.width,height:canvas.height,...options.mapping});
 },options);
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

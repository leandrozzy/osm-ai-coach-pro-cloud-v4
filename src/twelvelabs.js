import {apiRequest} from './ai-router.js';
const base64=file=>new Promise((resolve,reject)=>{const r=new FileReader();r.onload=()=>resolve(String(r.result).split(',')[1]);r.onerror=reject;r.readAsDataURL(file);});
export async function analyzeVideo(file,type,context,signal,onUpdate){
 if(file.size<=3000000)return apiRequest('twelvelabs',{action:'analyze',type,context,videoBase64:await base64(file)},signal,23000);
 onUpdate('TwelveLabs: enviando vídeo original');
 const u=await apiRequest('twelvelabs',{action:'create',filename:file.name,size:file.size},signal,12000);
 if(!u.upload_id||!u.asset_id||!u.chunk_size)throw Error('Upload TwelveLabs incompleto.');
 const chunks=[];
 for(let index=1;index<=u.total_chunks;index++){
  if(signal?.aborted)throw Error('Upload cancelado.');
  let part=u.upload_urls?.find(p=>p.chunk_index===index);
  if(!part){const urls=await apiRequest('twelvelabs',{action:'urls',uploadId:u.upload_id,start:index,count:1},signal,12000);part=urls.upload_urls?.[0];}
  if(!part?.url)throw Error('URL de upload ausente.');
  const blob=file.slice((index-1)*u.chunk_size,index*u.chunk_size),c=new AbortController(),stop=()=>c.abort(),timer=setTimeout(stop,15000);signal?.addEventListener('abort',stop,{once:true});
  try{const r=await fetch(part.url,{method:'PUT',headers:u.upload_headers||{},body:blob,signal:c.signal});if(!r.ok)throw Error('Upload HTTP '+r.status);const proof=r.headers.get('etag');if(!proof)throw Error('Upload sem ETag acessível.');chunks.push({chunk_index:index,proof,proof_type:'etag',chunk_size:blob.size});}finally{clearTimeout(timer);signal?.removeEventListener('abort',stop);}
 }
 await apiRequest('twelvelabs',{action:'report',uploadId:u.upload_id,chunks},signal,12000);
 const until=Date.now()+20000;
 while(Date.now()<until){const a=await apiRequest('twelvelabs',{action:'status',assetId:u.asset_id},signal,9000);if(a.status==='ready')return apiRequest('twelvelabs',{action:'analyze',type,context,assetId:u.asset_id},signal,23000);if(a.status==='failed')throw Error('Vídeo não processado.');await new Promise(resolve=>setTimeout(resolve,1000));if(signal?.aborted)throw Error('Análise cancelada.');}
 throw Error('TwelveLabs ainda processando; dados parciais preservados.');
}

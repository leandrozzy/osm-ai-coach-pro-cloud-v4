import {allowed,requestBody,keyFor,boundedFetch,errorInfo} from '../lib/http.js';
import {twelveRead} from '../lib/providers.js';
import {coverage} from '../src/extraction.js';
const base='https://api.twelvelabs.io/v1.3';
const identifier=s=>typeof s==='string'&&/^[A-Za-z0-9_-]{8,100}$/.test(s);
export default async function handler(req,res){
 if(!allowed(req,res))return;
 try{
 const body=requestBody(req),key=keyFor('twelvelabs',body);
 if(!key)return res.status(503).json({error:'Chave TwelveLabs não configurada.'});
 const headers={'x-api-key':key,'content-type':'application/json'};
 if(body.action==='create'){
 if(!Number.isInteger(body.size)||body.size<1||body.size>150*1024*1024||typeof body.filename!=='string')return res.status(400).json({error:'Vídeo inválido ou maior que 150 MB.'});
 const data=await boundedFetch(base+'/assets/multipart-uploads',{method:'POST',headers,body:JSON.stringify({filename:body.filename.slice(0,160),type:'video',total_size:body.size,enable_hls:false,enable_thumbnail:false})},10000);
 return res.status(200).json(data);
 }
 if(body.action==='urls'){
 if(!identifier(body.uploadId)||!Number.isInteger(body.start)||!Number.isInteger(body.count)||body.start<1||body.count<1||body.count>50)return res.status(400).json({error:'Lote de upload inválido.'});
 const data=await boundedFetch(base+'/assets/multipart-uploads/'+body.uploadId+'/presigned-urls',{method:'POST',headers,body:JSON.stringify({start:body.start,count:body.count})},10000);return res.status(200).json(data);
 }
 if(body.action==='report'){
 if(!identifier(body.uploadId)||!Array.isArray(body.chunks)||body.chunks.length<1||body.chunks.length>50||body.chunks.some(c=>!Number.isInteger(c.chunk_index)||c.chunk_index<1||typeof c.proof!=='string'||c.proof.length>100||c.proof_type!=='etag'||!Number.isInteger(c.chunk_size)||c.chunk_size<1))return res.status(400).json({error:'Confirmação de upload inválida.'});
 const data=await boundedFetch(base+'/assets/multipart-uploads/'+body.uploadId,{method:'POST',headers,body:JSON.stringify({completed_chunks:body.chunks})},10000);return res.status(200).json(data);
 }
 if(body.action==='status'){
 if(!identifier(body.assetId))return res.status(400).json({error:'Asset inválido.'});
 const data=await boundedFetch(base+'/assets/'+body.assetId,{headers:{'x-api-key':key}},8000);return res.status(200).json({id:data.id,status:data.status});
 }
 if(body.action==='analyze'){
 if(!['match','squad','calendar'].includes(body.type))return res.status(400).json({error:'Tipo inválido.'});
 let video;
 if(identifier(body.assetId))video={type:'asset_id',asset_id:body.assetId};
 else if(typeof body.videoBase64==='string'&&body.videoBase64.length<=4100000&&/^[A-Za-z0-9+/=\r\n]+$/.test(body.videoBase64))video={type:'base64_string',base64_string:body.videoBase64};
 else return res.status(400).json({error:'Vídeo obrigatório; use upload multipart para arquivos acima de 3 MB.'});
 const images=Array.isArray(body.images)?body.images:[];
 if(images.length>2||images.some(i=>typeof i!=='string'||i.length>1400000||!/^data:image\/(?:jpeg|png);base64,/.test(i)))return res.status(400).json({error:'Referências de imagem inválidas.'});
 const data=await twelveRead({key,type:body.type,video,images,context:body.context||{}});
 return res.status(200).json({data,coverage:coverage(body.type,data),provider:'twelvelabs'});
 }
 return res.status(400).json({error:'Ação inválida.'});
 }catch(e){return res.status(503).json({error:'TwelveLabs indisponível; prossiga com Groq/OCR.space.',details:[errorInfo('twelvelabs',e)]});}
}


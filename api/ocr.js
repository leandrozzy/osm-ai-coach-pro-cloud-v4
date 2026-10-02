import {allowed,requestBody,keyFor,errorInfo} from '../lib/http.js';
import {ocrRead} from '../lib/providers.js';
export default async function handler(req,res){
 if(!allowed(req,res))return;
 try{const body=requestBody(req);const image=body.image||('data:image/jpeg;base64,'+(body.imageBase64||''));if(image.length>1400000||!/^data:image\/(?:jpeg|png);base64,[A-Za-z0-9+/=\r\n]+$/.test(image))return res.status(400).json({error:'Imagem JPEG/PNG inválida ou maior que 1 MB.'});const out=await ocrRead({key:keyFor('ocrspace',body),image});return res.status(200).json({...out,confidence:null,provider:'ocrspace'});}
 catch(e){return res.status(503).json({error:'OCR.space indisponível.',details:[errorInfo('ocrspace',e)]});}
}


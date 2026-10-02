const json=(res,status,data)=>res.status(status).json(data);
export default async function handler(req,res){
 if(req.method!=='POST')return json(res,405,{error:'Method not allowed'});
 if(!process.env.OCR_SPACE_API_KEY)return json(res,503,{error:'OCR_SPACE_API_KEY ausente'});
 try{
  const body=typeof req.body==='string'?JSON.parse(req.body):req.body||{}; if(!body.imageBase64)return json(res,400,{error:'imageBase64 obrigatório'});
  const form=new URLSearchParams();form.set('apikey',process.env.OCR_SPACE_API_KEY);form.set('language','por');form.set('isOverlayRequired','false');form.set('OCREngine','2');form.set('scale','true');form.set('base64Image',`data:image/jpeg;base64,${body.imageBase64}`);
  const controller=new AbortController();const t=setTimeout(()=>controller.abort(),9000);
  const r=await fetch('https://api.ocr.space/parse/image',{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:form,signal:controller.signal});clearTimeout(t);
  const data=await r.json();const text=(data.ParsedResults||[]).map(x=>x.ParsedText||'').join('\n');if(!r.ok||data.IsErroredOnProcessing)return json(res,502,{error:data.ErrorMessage||'OCR.Space falhou'});
  return json(res,200,{text,confidence:text?70:0,provider:'ocr.space'});
 }catch(e){return json(res,e.name==='AbortError'?504:500,{error:e.message});}
}


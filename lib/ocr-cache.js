import {createHash} from 'node:crypto';

// Only literal OCR is reusable across slots. Model guesses and API failures are
// never cached. Neither the API key nor the image is kept in the cache key.
const entries=new Map(),ttl=15*60*1000,maximum=96;
export async function cachedOcrRead(key,image,region,read){
 const id=createHash('sha256').update(key).update('\0').update(region).update('\0').update(image).digest('hex');
 const previous=entries.get(id);
 if(previous&&previous.expires>Date.now())return {...await previous.promise,cacheHit:true};
 if(previous)entries.delete(id);
 if(entries.size>=maximum)entries.delete(entries.keys().next().value);
 const entry={expires:Date.now()+ttl,promise:null};
 entry.promise=Promise.resolve().then(read);
 entries.set(id,entry);
 try{
  const result=await entry.promise;
  if(!result.text?.trim())entries.delete(id);
  return result;
 }catch(error){if(entries.get(id)===entry)entries.delete(id);throw error;}
}

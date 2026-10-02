export async function askAI(task,payload){
  const r=await fetch('/api/ai',{
    method:'POST',
    headers:{'content-type':'application/json'},
    body:JSON.stringify({task,payload})
  });
  const data=await r.json().catch(()=>({}));
  if(!r.ok)throw new Error(data?.error||`IA HTTP ${r.status}`);
  return data;
}

export async function askVision(task,images,context={}){
  return askAI(task,{images,context});
}

export async function apiStatus(){
  try{
    const r=await fetch('/api/status');
    return r.ok?await r.json():null;
  }catch{return null;}
}

export async function askAI(task,payload){const r=await fetch('/api/ai',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({task,payload})});if(!r.ok)throw new Error(`IA HTTP ${r.status}`);return r.json();}
export async function apiStatus(){try{const r=await fetch('/api/status');return r.ok?await r.json():null;}catch{return null;}}

export const NI = 'NI';
export const clamp = (n,min=0,max=100)=>Math.max(min,Math.min(max,Number(n)||0));
export const uid = ()=>crypto.randomUUID?.() || `${Date.now()}-${Math.random()}`;
export const sleep = ms => new Promise(r=>setTimeout(r,ms));
export const normalize = s => String(s ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().trim();
export const toNumber = v => {
  if (v == null || v === '') return null;
  const s=String(v).replace(/\s/g,'').replace(/\.(?=\d{3}(\D|$))/g,'').replace(',','.');
  const m=s.match(/-?\d+(?:\.\d+)?/); return m?Number(m[0]):null;
};
export function timeout(promise, ms=8000, label='Operação'){
  const c = new AbortController();
  const t = setTimeout(()=>c.abort(),ms);
  return Promise.race([promise(c.signal),new Promise((_,rej)=>setTimeout(()=>rej(new Error(`${label} excedeu ${ms}ms`)),ms))]).finally(()=>clearTimeout(t));
}
export const fmtDateTime = iso => iso ? new Intl.DateTimeFormat('pt-BR',{dateStyle:'short',timeStyle:'short'}).format(new Date(iso)) : NI;


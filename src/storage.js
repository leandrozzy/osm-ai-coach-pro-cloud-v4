const KEY='osm-ai-coach-pro:v1';
export const emptySlot = id => ({
  id, name:`S${id}`, competitionType:'Liga normal', competition:'', myTeam:'',
  match:{}, squad:{players:[], updatedAt:null}, calendar:[], tactics:null,
  director:{plan:null}, learning:{matches:[], weights:{}}, updatedAt:null
});
export const defaultState = ()=>({version:1, activeSlot:1, settings:{username:'leandrozzy', refereeMap:{Verde:'Agressivo',Azul:'Agressivo',Amarelo:'Normal',Laranja:'Cauteloso',Vermelho:'Cauteloso'}}, slots:[1,2,3,4].map(emptySlot)});
export function load(){try{const raw=localStorage.getItem(KEY); if(!raw)return defaultState(); return migrate(JSON.parse(raw));}catch{return defaultState();}}
export function save(state){localStorage.setItem(KEY,JSON.stringify(state));}
export function migrate(s){const base=defaultState(); return {...base,...s,settings:{...base.settings,...s.settings},slots:base.slots.map((b,i)=>({...b,...(s.slots?.[i]||{}),learning:{...b.learning,...(s.slots?.[i]?.learning||{})},squad:{...b.squad,...(s.slots?.[i]?.squad||{})}}))};}
export function exportState(state){return new Blob([JSON.stringify(state,null,2)],{type:'application/json'});}

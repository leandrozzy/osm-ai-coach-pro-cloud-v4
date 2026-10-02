const KEY='osm-ai-coach-pro:v1';
const SCHEMA_VERSION=2;

export const emptySlot = id => ({
  id, name:`S${id}`, competitionType:'Liga normal', competition:'', myTeam:'',
  match:{_schemaVersion:SCHEMA_VERSION,_sources:{}}, squad:{players:[], updatedAt:null}, calendar:[], tactics:null,
  director:{plan:null}, learning:{matches:[], weights:{}}, updatedAt:null
});
export const defaultState = ()=>({version:SCHEMA_VERSION, activeSlot:1, settings:{username:'leandrozzy', refereeMap:{Verde:'Agressivo',Azul:'Agressivo',Amarelo:'Normal',Laranja:'Cauteloso',Vermelho:'Cauteloso'}}, slots:[1,2,3,4].map(emptySlot)});

function migrateLegacyMatch(m={}){
  if (m?._schemaVersion>=SCHEMA_VERSION) return m;
  // Versão anterior podia salvar formação/marcação/CPU por falso positivo.
  // Mantém dados quantitativos e nomes, mas invalida apenas os campos automáticos suspeitos.
  const keep=['myName','rivalName','myStrength','rivalStrength','mySquadValue','rivalSquadValue','myPlayers','rivalPlayers','myGK','myDEF','myMID','myATT','rivalGK','rivalDEF','rivalMID','rivalATT','stadium','myBonus','rivalBonus','location'];
  const out={_schemaVersion:SCHEMA_VERSION,_sources:{}};
  for(const k of keep) if(m[k]!=null && m[k]!=='' && m[k]!=='NI') out[k]=m[k];
  return out;
}

export function load(){
  try{const raw=localStorage.getItem(KEY); if(!raw)return defaultState(); return migrate(JSON.parse(raw));}
  catch{return defaultState();}
}
export function save(state){localStorage.setItem(KEY,JSON.stringify(state));}
export function migrate(s){
  const base=defaultState();
  const slots=base.slots.map((b,i)=>{
    const old=s.slots?.[i]||{};
    return {...b,...old,match:migrateLegacyMatch(old.match||{}),learning:{...b.learning,...(old.learning||{})},squad:{...b.squad,...(old.squad||{})}};
  });
  return {...base,...s,version:SCHEMA_VERSION,settings:{...base.settings,...s.settings},slots};
}
export function exportState(state){return new Blob([JSON.stringify(state,null,2)],{type:'application/json'});}

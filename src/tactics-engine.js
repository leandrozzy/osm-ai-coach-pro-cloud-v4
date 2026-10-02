import {clamp,NI} from './utils.js';
const TEMPLATES={
 '4-3-3 A':{style:'Jogar pelas alas',p:74,m:76,r:78,mark:'À zona',off:'Não',atk:'Atacar apenas',mid:'Pressionar na frente',def:'Defender atrás'},
 '4-3-3 B':{style:'Jogo de passes',p:72,m:74,r:76,mark:'À zona',off:'Não',atk:'Atacar apenas',mid:'Manter posição',def:'Defender atrás'},
 '4-5-1':{style:'Remate à vista',p:46,m:39,r:63,mark:'À zona',off:'Não',atk:'Ajudar meio-campo',mid:'Manter posição',def:'Defender atrás'},
 '5-3-2':{style:'Contra-ataque',p:38,m:32,r:67,mark:'À zona',off:'Não',atk:'Atacar apenas',mid:'Ajudar a defesa',def:'Defender atrás'},
 '5-4-1 A':{style:'Contra-ataque',p:34,m:28,r:61,mark:'À zona',off:'Não',atk:'Ajudar meio-campo',mid:'Ajudar a defesa',def:'Defender atrás'},
 '4-2-3-1':{style:'Remate à vista',p:51,m:46,r:69,mark:'À zona',off:'Não',atk:'Atacar apenas',mid:'Manter posição',def:'Defender atrás'}
};
function tackle(ref,map){return map?.[ref]||({Verde:'Agressivo',Azul:'Agressivo',Amarelo:'Normal',Laranja:'Cauteloso',Vermelho:'Cauteloso'}[ref]||'Normal');}
export function generateTactic(match={},settings={},learning={}){
 const a=Number(match.myStrength), b=Number(match.rivalStrength); const diff=Number.isFinite(a)&&Number.isFinite(b)?a-b:0;
 const strong=diff>=13; let formation;
 if(strong)formation=diff>=25?'4-3-3 A':'4-3-3 B'; else if(diff<=-18)formation='5-4-1 A'; else if(diff<=-10)formation='5-3-2'; else if(diff<=-4)formation='4-5-1'; else formation=match.rivalFormation?.startsWith('4-3-3')?'4-2-3-1':'4-3-3 B';
 const t={...TEMPLATES[formation]}; const away=match.location==='Fora'; const human=match.human===true;
 t.p=clamp(t.p+(away?-4:2)+(human?-2:0)); t.m=clamp(t.m+(away?-3:2));
 if(match.rivalPlan==='Jogar pelas alas'&&formation==='4-5-1')t.mark='Individual';
 return {formation,style:t.style,pressure:t.p,mentality:t.m,tempo:t.r,marking:t.mark,offside:t.off,tackling:tackle(match.referee,settings.refereeMap),attack:t.atk,midfield:t.mid,defense:t.def,strongAvailable:strong,reason:`Diferença de força ${Number.isFinite(a)&&Number.isFinite(b)?diff:NI}; ${away?'fora':'casa'}; rival ${human?'humano':'CPU'}; árbitro ${match.referee||NI}.`};
}
export function generateStrong433(match={},settings={}){const base=generateTactic({...match,myStrength:100,rivalStrength:80},settings);const formation=match.rivalFormation==='4-3-3 A'?'4-3-3 B':'4-3-3 A';return {...base,...TEMPLATES[formation],formation,style:TEMPLATES[formation].style,pressure:TEMPLATES[formation].p,mentality:TEMPLATES[formation].m,tempo:TEMPLATES[formation].r,marking:TEMPLATES[formation].mark,offside:TEMPLATES[formation].off,attack:TEMPLATES[formation].atk,midfield:TEMPLATES[formation].mid,defense:TEMPLATES[formation].def,tackling:tackle(match.referee,settings.refereeMap),strongAvailable:true,reason:'Tática forte 4-3-3 solicitada manualmente; ajustada ao árbitro e contexto.'};}

import {getState,getSlot,setActiveSlot,subscribe,updateSlot} from './state.js';
import {mediaToTexts,analyzeMatchVision} from './video.js';
import {mergeMatchTexts} from './parser-match.js';
import {parseSquadText,dedupePlayers,squadSummary} from './parser-squad.js';
import {parseCalendarText,mergeCalendar} from './parser-calendar.js';
import {mergeBetter,validateMatch} from './validator.js';
import {rivalHuman} from './slots.js';
import {generateTactic,generateStrong433} from './tactics-engine.js';
import {buildMarketPlan} from './market-engine.js';
import {recordResult} from './learning-engine.js';
import {apiStatus} from './ai-router.js';
import {requestNotifications,scheduleLocal} from './notifications.js';

let root,tab='today',busy=false,statusText='Pronto',analysisType='match';
const esc=s=>String(s??'').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));
const show=v=>v==null||v===''?'NI':v;
function card(title,body,actions=''){return `<section class="card"><h3>${title}</h3>${body}${actions?`<div class="actions">${actions}</div>`:''}</section>`;}
function tabs(){return ['today:Hoje','pregame:Pré-jogo','analyze:Analisar','info:Informações','director:Diretor','learning:Aprendizado'].map(x=>{const[a,b]=x.split(':');return `<button class="tab ${tab===a?'active':''}" data-tab="${a}">${b}</button>`}).join('');}
function field(label,v){v=show(v);return `<div class="field"><span>${label}</span><b class="${v==='NI'?'ni':''}">${esc(v)}</b></div>`;}
function input(id,label,v,type='text'){return `<label class="manual-field"><span>${label}</span><input id="${id}" type="${type}" ${type==='number'?'inputmode="numeric"':''} value="${esc(v==='NI'?'':v??'')}" placeholder="${label}"></label>`;}
function select(id,label,v,opts){return `<label class="manual-field"><span>${label}</span><select id="${id}"><option>NI</option>${opts.map(x=>`<option ${v===x?'selected':''}>${x}</option>`).join('')}</select></label>`;}
function tacticView(t){if(!t)return '<p class="muted">Nenhuma tática gerada.</p>';return ['formation:Formação','style:Estilo de jogo','pressure:Pressão','mentality:Mentalidade / Estilo','tempo:Ritmo / Temporização','marking:Marcação','offside:Impedimento','tackling:Desarme','attack:Avançadas – Ataque','midfield:Avançadas – Meio','defense:Avançadas – Defesa'].map(x=>{const[k,l]=x.split(':');return field(l,t[k]);}).join('')+`<p class="reason">${esc(t.reason||'')}</p>`;}
function today(slot){const next=(slot.calendar||[]).find(x=>!x.result)||slot.calendar?.[0];const pending=!slot.tactics?'Gerar tática':(!slot.match?.referee||slot.match.referee==='NI'?'Atualizar partida':null);return card(`Slot ${slot.id} • ${esc(slot.myTeam||'Time não definido')}`,`${field('Próximo jogo',next?.opponent)}${field('Data',next?`${next.date} ${next.time||''}`:'NI')}${field('Local',next?(next.home?'Casa':'Fora'):'NI')}${field('Status',pending||'Preparação ok')}`,pending?`<button data-go="${pending==='Gerar tática'?'pregame':'analyze'}">Urgente: ${pending}</button>`:'');}

function matchFields(m={}){
 return field('Meu time',m.myName)+field('Rival',m.rivalName)+field('Nickname rival',m.rivalNickname)+field('Humano/CPU',m.human===true?'Humano':m.human===false?'CPU':'NI')+
 field('Minha força',m.myStrength)+field('Força rival',m.rivalStrength)+field('Meu elenco',m.mySquadValue)+field('Elenco rival',m.rivalSquadValue)+field('Meus jogadores',m.myPlayers)+field('Jogadores rival',m.rivalPlayers)+
 field('Meu GOL',m.myGK)+field('Rival GOL',m.rivalGK)+field('Meu DEF',m.myDEF)+field('Rival DEF',m.rivalDEF)+field('Meu MEI',m.myMID)+field('Rival MEI',m.rivalMID)+field('Meu ATA',m.myATT)+field('Rival ATA',m.rivalATT)+
 field('Estádio',m.stadium)+field('Meu bônus',m.myBonus)+field('Bônus rival',m.rivalBonus)+field('Local',m.location)+field('Árbitro',m.referee)+field('Treino secreto',m.secretTraining)+field('Campo treinamento',m.trainingCamp)+
 field('Formação rival',m.rivalFormation)+field('Plano rival',m.rivalPlan)+field('Marcação',m.rivalMarking)+field('Impedimento',m.rivalOffside)+field('Entrada rival',m.rivalTackling);
}
function manualForm(m={}){
 return input('mMyName','Meu time',m.myName)+input('mRivalName','Rival',m.rivalName)+input('mNickname','Nickname rival',m.rivalNickname)+
 input('mMyStrength','Minha força',m.myStrength,'number')+input('mRivalStrength','Força rival',m.rivalStrength,'number')+input('mMyValue','Meu elenco',m.mySquadValue)+input('mRivalValue','Elenco rival',m.rivalSquadValue)+
 input('mMyPlayers','Meus jogadores',m.myPlayers,'number')+input('mRivalPlayers','Jogadores rival',m.rivalPlayers,'number')+
 input('mMyGK','Meu GOL',m.myGK,'number')+input('mRivalGK','Rival GOL',m.rivalGK,'number')+input('mMyDEF','Meu DEF',m.myDEF,'number')+input('mRivalDEF','Rival DEF',m.rivalDEF,'number')+
 input('mMyMID','Meu MEI',m.myMID,'number')+input('mRivalMID','Rival MEI',m.rivalMID,'number')+input('mMyATT','Meu ATA',m.myATT,'number')+input('mRivalATT','Rival ATA',m.rivalATT,'number')+
 input('mStadium','Estádio',m.stadium,'number')+input('mMyBonus','Meu bônus %',m.myBonus,'number')+input('mRivalBonus','Bônus rival %',m.rivalBonus,'number')+
 select('mLocation','Local',m.location,['Casa','Fora'])+select('mHuman','Humano/CPU',m.human===true?'Humano':m.human===false?'CPU':'NI',['Humano','CPU'])+
 select('mReferee','Árbitro',m.referee,['Verde','Azul','Amarelo','Laranja','Vermelho'])+select('mSecret','Treino secreto',m.secretTraining,['Sim','Não'])+select('mCamp','Campo treinamento',m.trainingCamp,['Sim','Não'])+
 input('mFormation','Formação rival',m.rivalFormation)+input('mPlan','Plano rival',m.rivalPlan)+select('mMarking','Marcação',m.rivalMarking,['À zona','Individual'])+select('mOffside','Impedimento',m.rivalOffside,['Sim','Não'])+input('mTackling','Entrada rival',m.rivalTackling)+
 '<button id="saveManual">Salvar correções</button>';
}
function pregame(slot){const v=validateMatch(slot.match||{});return card('Dados da partida',`${matchFields(slot.match)}<div class="quality"><b>Cobertura real:</b> ${v.coverage}%${v.hiddenByGame.length?' • campos ocultos pelo jogo excluídos do cálculo':''}</div>${v.issues.length?`<p class="ni">${esc(v.issues.join(' • '))}</p>`:''}<details><summary>Corrigir / preencher manualmente</summary><div class="manual">${manualForm(slot.match)}</div></details>`,`<button id="genTactic">Gerar melhor tática</button>${Number(slot.match?.myStrength)-Number(slot.match?.rivalStrength)>=13?'<button class="secondary" id="genStrong">Gerar tática forte 4-3-3</button>':''}`)+card('Tática',tacticView(slot.tactics),slot.tactics?'<button class="secondary" id="registerResult">Registrar resultado</button>':'');}
function analyze(){return card('Analisar mídia',`<div class="seg"><button class="analysisType ${analysisType==='match'?'active':''}" data-type="match">Partida</button><button class="analysisType ${analysisType==='squad'?'active':''}" data-type="squad">Elenco</button><button class="analysisType ${analysisType==='calendar'?'active':''}" data-type="calendar">Calendário</button></div><input id="media" type="file" accept="image/*,video/*" multiple><p class="muted">${analysisType==='match'?'Partida usa visão multimodal: o vídeo é reduzido a até 9 telas úteis e a IA lê as imagens diretamente. OCR só entra se todas as IAs visuais falharem.':'Elenco e calendário continuam com OCR/parsers nesta etapa.'}</p><div id="progress">${esc(statusText)}</div>`,`<button id="runAnalysis" ${busy?'disabled':''}>${busy?'Analisando…':'Analisar'}</button>`);}
function info(slot){const s=squadSummary(slot.squad?.players||[]);return card('Elenco',`${field('Total',s.total)}${field('ATA',s.by.ATA)}${field('MEI',s.by.MEI)}${field('DEF',s.by.DEF)}${field('GOL',s.by.GOL)}${field('Treinando',s.training)}${field('À venda',s.forSale)}`)+card('Calendário',`<div class="list">${(slot.calendar||[]).map(r=>`<div><b>${esc(r.date)}</b> ${esc(r.time||'')} • ${esc(r.opponent||'NI')} • ${r.home?'Casa':'Fora'} ${r.cup?'• Copa':''} ${r.result?`• ${r.result}`:''}</div>`).join('')||'<span class="muted">Sem dados.</span>'}</div>`);}
function director(slot){const p=slot.director?.plan;return card('Diretor IA',p?`${field('Força atual',p.currentStrength)}${field('Meta estimada',p.targetStrength)}${field('Horizonte',p.horizon)}<h4>Vendas prioritárias</h4><div class="list">${p.sell.map(x=>`<div>${esc(x.name)} • ${x.position} • ${x.strength}</div>`).join('')||'Nenhuma'}</div><h4>Compras</h4><div class="list">${p.buy.map(x=>`<div>${x.count}× ${x.position}: ${esc(x.profile)}</div>`).join('')||'Completar qualidade, sem inventar nomes do mercado.'}</div>`:'<p class="muted">Gere o plano usando o elenco lido.</p>',`<button id="marketPlan">Atualizar plano do mercado</button>`);}
function learning(slot){return card('Aprendizado IA',`<div class="list">${Object.entries(slot.learning?.weights||{}).map(([k,w])=>`<div><b>${k}</b> • ${w.games} jogos • ${w.wins}V ${w.draws}E ${w.losses}D • peso ${w.weight}</div>`).join('')||'<span class="muted">Registre resultados para aprender.</span>'}</div>`)+card('Histórico',`<div class="list">${(slot.learning?.matches||[]).slice(0,10).map(m=>`<div>${new Date(m.at).toLocaleDateString('pt-BR')} • ${m.outcome} • ${esc(m.tactic?.formation||'NI')}</div>`).join('')||'<span class="muted">Sem resultados.</span>'}</div>`);}
function body(){const slot=getSlot();return tab==='today'?today(slot):tab==='pregame'?pregame(slot):tab==='analyze'?analyze():tab==='info'?info(slot):tab==='director'?director(slot):learning(slot);}
async function render(){const state=getState();root.innerHTML=`<header><div><div class="brand">⚽ OSM AI Coach Pro</div><div class="sub">Android • PWA • ${navigator.onLine?'Online':'Offline'}</div></div><button id="notifyBtn" class="iconbtn">🔔</button></header><div class="slots">${[1,2,3,4].map(n=>`<button data-slot="${n}" class="${state.activeSlot===n?'active':''}">S${n}</button>`).join('')}</div><nav>${tabs()}</nav><main>${body()}</main><footer><span>Usuário OSM: leandrozzy</span><span id="apiState">APIs: verificando…</span></footer>`;bind();apiStatus().then(s=>{const e=document.querySelector('#apiState');if(e)e.textContent=s?`APIs: ${Object.entries(s.providers||{}).filter(([,v])=>v).map(([k])=>k).join(', ')||'fallback local'}`:'APIs: fallback local';});}
function read(id){return document.querySelector(id)?.value?.trim()||'NI';}
function bind(){
 document.querySelectorAll('.analysisType').forEach(b=>b.onclick=()=>{analysisType=b.dataset.type;render();});
 document.querySelectorAll('[data-slot]').forEach(b=>b.onclick=()=>{setActiveSlot(Number(b.dataset.slot));render();});
 document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>{tab=b.dataset.tab;render();});
 document.querySelectorAll('[data-go]').forEach(b=>b.onclick=()=>{tab=b.dataset.go;render();});
 const nb=document.querySelector('#notifyBtn');if(nb)nb.onclick=async()=>{await requestNotifications();getState().slots.forEach(scheduleLocal);alert('Notificações locais configuradas para jogos conhecidos.');};
 const sm=document.querySelector('#saveManual');if(sm)sm.onclick=()=>{updateSlot(s=>{const h=read('#mHuman');s.match={...s.match,_manualUpdatedAt:new Date().toISOString(),myName:read('#mMyName'),rivalName:read('#mRivalName'),rivalNickname:read('#mNickname'),myStrength:read('#mMyStrength'),rivalStrength:read('#mRivalStrength'),mySquadValue:read('#mMyValue'),rivalSquadValue:read('#mRivalValue'),myPlayers:read('#mMyPlayers'),rivalPlayers:read('#mRivalPlayers'),myGK:read('#mMyGK'),rivalGK:read('#mRivalGK'),myDEF:read('#mMyDEF'),rivalDEF:read('#mRivalDEF'),myMID:read('#mMyMID'),rivalMID:read('#mRivalMID'),myATT:read('#mMyATT'),rivalATT:read('#mRivalATT'),stadium:read('#mStadium'),myBonus:read('#mMyBonus'),rivalBonus:read('#mRivalBonus'),location:read('#mLocation'),human:h==='Humano'?true:h==='CPU'?false:null,referee:read('#mReferee'),secretTraining:read('#mSecret'),trainingCamp:read('#mCamp'),rivalFormation:read('#mFormation'),rivalPlan:read('#mPlan'),rivalMarking:read('#mMarking'),rivalOffside:read('#mOffside'),rivalTackling:read('#mTackling')}});render();};
 const g=document.querySelector('#genTactic');if(g)g.onclick=()=>{updateSlot((s,state)=>{const inferred=rivalHuman(s.match.rivalNickname,s.competitionType,state.settings.username);if(s.match.human==null&&inferred!==null)s.match.human=inferred;s.tactics=generateTactic(s.match,state.settings,s.learning)});render();};
 const gs=document.querySelector('#genStrong');if(gs)gs.onclick=()=>{updateSlot((s,state)=>s.tactics=generateStrong433(s.match,state.settings));render();};
 const mp=document.querySelector('#marketPlan');if(mp)mp.onclick=()=>{updateSlot(s=>s.director.plan=buildMarketPlan(s.squad,null,s.match?.myStrength==='NI'?null:Number(s.match?.myStrength)));render();};
 const rr=document.querySelector('#registerResult');if(rr)rr.onclick=()=>{const score=prompt('Placar (ex.: 2x1):','');if(!score)return;const outcome=prompt('Resultado: V, E ou D','V')?.toUpperCase();if(!['V','E','D'].includes(outcome))return;updateSlot(s=>recordResult(s,{score,outcome}));tab='learning';render();};
 const ra=document.querySelector('#runAnalysis');if(ra)ra.onclick=runAnalysis;
}

async function runAnalysis(){
 const input=document.querySelector('#media');
 if(!input?.files?.length)return alert('Selecione ao menos um vídeo ou imagem.');
 const type=analysisType;
 busy=true;statusText='Preparando mídia…';render();
 try{
   if(type==='match'){
     const result=await analyzeMatchVision([...input.files],u=>{
       if(u.stage==='vision')statusText=`Visão IA ${u.current}/${u.total} • ${u.frames} telas úteis`;
       else statusText=`Fallback OCR ${u.current}/${u.total}`;
       const p=document.querySelector('#progress');if(p)p.textContent=statusText;
     });
     let parsed=result.vision;
     if(!parsed){
       parsed=mergeMatchTexts(result.texts||[]);
     }
     updateSlot((s,state)=>{
       const human=rivalHuman(parsed.rivalNickname,s.competitionType,state.settings.username);
       if(human!==null)parsed.human=human;
       s.match=mergeBetter(s.match,parsed);
       s.match._lastReader=result.mode;
       s.match._lastFrames=result.frames;
     });
     const found=Object.entries(parsed||{}).filter(([k,v])=>!k.startsWith('_')&&v!=='NI'&&v!=null&&v!=='').length;
     statusText=`Concluído por ${result.mode==='vision'?'visão multimodal':'OCR de contingência'}: ${result.frames} telas úteis • ${found} campos identificados${result.errors?.length?` • ${result.errors.length} fallback(s)`:''}.`;
     tab='pregame';
   }else{
     const result=await mediaToTexts([...input.files],u=>{
       statusText=`OCR ${u.current}/${u.total}`;
       const p=document.querySelector('#progress');if(p)p.textContent=statusText;
     });
     const text=result.texts.join('\n');
     updateSlot(s=>{
       if(type==='squad'){
         s.squad={players:dedupePlayers([...(s.squad?.players||[]),...parseSquadText(text)]),updatedAt:new Date().toISOString()};
       }else{
         s.calendar=mergeCalendar(s.calendar||[],parseCalendarText(text));
       }
     });
     statusText=`Concluído: ${result.frameCount} telas processadas.`;
     tab='info';
   }
 }catch(e){
   console.error(e);
   statusText=`Falha: ${e.message}. Dados antigos foram preservados.`;
   alert(statusText);
 }finally{
   busy=false;render();
 }
}
export function initApp(el){root=el;subscribe(()=>{});render();}

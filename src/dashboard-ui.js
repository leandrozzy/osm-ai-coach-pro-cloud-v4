import {known,nextMatch,rowTime,confirmedMatchSecretTraining,confirmedMatchField} from './domain.js';
import {clubKey,sameClub} from './utils.js';

const esc=value=>String(value??'').replace(/[&<>"']/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const show=value=>known(value)?value:'NI';
const rows=slot=>Array.isArray(slot.calendar)?slot.calendar:[];
const players=slot=>Array.isArray(slot.squad?.players)?slot.squad.players:[];
const team=slot=>known(slot.myTeam)?slot.myTeam:slot.match?.myName;
const hasResult=row=>known(row.result)||known(row.score);
const overdueResults=(slot,now)=>rows(slot).filter(row=>!hasResult(row)&&rowTime(row)!==null&&rowTime(row)<now-3*3600000);
const upcoming=(slot,now)=>nextMatch({...slot,calendar:rows(slot)},now);
function terminalDate(slot){
 const proof=slot.calendarMeta?.sawBottomProof;if(proof?.kind!=='calendar-terminal-day')return null;
 const match=String(proof.date||'').match(/^(\d{2})\/(\d{2})\/(\d{4})$/);if(!match)return null;
 const value=new Date(+match[3],+match[2]-1,+match[1]);
 return value.getFullYear()===+match[3]&&value.getMonth()===+match[2]-1&&value.getDate()===+match[1]?{date:proof.date,time:value.getTime()}:null;
}
function competitionEnded(slot,now){
 const terminal=terminalDate(slot),today=new Date(now);today.setHours(0,0,0,0);
 return !!terminal&&!upcoming(slot,now)&&terminal.time<today.getTime();
}

export function slotDashboardTasks(slot,now=Date.now()){
 const tasks=[],match=slot.match||{},roster=players(slot),next=upcoming(slot,now);
 const add=(label,tab,priority=4)=>tasks.push({label,tab,priority});
 if(!known(team(slot)))add(known(slot.competition)?'Definir o time deste slot':'Definir time e competição deste slot','settings');
 else if(!known(slot.competition))add('Criar ou editar a competição','settings');
 if(!known(team(slot)))return tasks;
 const overdue=overdueResults(slot,now);
 if(overdue.length)add('Registrar '+overdue.length+(overdue.length===1?' resultado pendente':' resultados pendentes'),'learning',2);
 if(!rows(slot).length&&!terminalDate(slot))add('Adicionar calendário e horário de partida','info');
 else if(next&&(!known(next.date)||!known(next.time)))add('Completar data e horário do próximo jogo','info');
 else if(!next&&!terminalDate(slot))add('Conferir o próximo jogo no calendário','info');
 if(!competitionEnded(slot,now)){
  const changedRival=next&&clubKey(next.opponent)&&!sameClub(next.opponent,match.rivalName);
  if(changedRival)add('Atualizar a partida para o próximo rival','pregame',1);
  const missing=[['myStrength','minha força'],['rivalStrength','força rival'],['referee','árbitro'],['location','casa/fora']].filter(([field])=>!known(match[field])).map(([,label])=>label);
  if(missing.length)add('Completar '+missing.join(', '),'pregame',1);
  if(!confirmedMatchSecretTraining(match)&&!confirmedMatchField(match,'rivalMarking'))add('Ler ou informar a marcação rival','pregame',1);
  if(!['Sim','Não'].includes(match.myTrainingCamp))add('Definir meu campo de treinamento','pregame',2);
  if(!slot.tactics)add('Preparar tática','pregame',2);
  else if(slot.tacticStale||changedRival)add('Revisar tática para o próximo jogo','pregame',2);
 }
 if(!roster.length)add('Ler ou cadastrar elenco','info');
 else if(!slot.director?.plan)add('Gerar plano de compras e vendas','director',5);
 return tasks;
}

function stat(label,value){return '<div><dt>'+esc(label)+'</dt><dd>'+esc(show(value))+'</dd></div>';}
function taskButton(task){
 return '<button type="button" class="global-task" data-action="'+esc(task.action)+'"><span class="global-task-slot">S'+task.slot+'</span><span class="global-task-body"><strong>'+esc(task.label)+'</strong><small>'+esc(task.context)+'</small></span><span class="global-task-arrow" aria-hidden="true">→</span></button>';
}
function taskSection(title,id,tasks){
 return tasks.length?'<section class="today-agenda" aria-labelledby="'+id+'"><h3 id="'+id+'">'+title+'</h3><ul class="global-task-list">'+tasks.map(task=>'<li>'+taskButton(task)+'</li>').join('')+'</ul></section>':'';
}
function pendingReadings(state,slots){
 const found=new Map(),labels={match:'partida',squad:'elenco',calendar:'calendário'},ids=new Set(slots.map(slot=>slot.id));
 for(const draft of Array.isArray(state?.readingDrafts)?state.readingDrafts:[]){
  if(!draft||draft.requiresReview!==true||!ids.has(draft.slot)||!Object.hasOwn(labels,draft.type))continue;
  const key=draft.slot+':'+draft.type,previous=found.get(key);
  if(!previous||String(draft.at||'')>String(previous.at||''))found.set(key,draft);
 }
 return [...found.values()].map(draft=>{
  const slot=slots.find(slot=>slot.id===draft.slot),name=known(team(slot))?team(slot):known(draft.team)?draft.team:'Time não definido';
  const pending=Number.isInteger(draft.pendingCount)&&draft.pendingCount>0?draft.pendingCount+(draft.pendingCount===1?' pendência na leitura':' pendências na leitura'):'Revise e confirme os dados';
  return {slot:draft.slot,label:'Revisar análise de '+labels[draft.type],action:'slotReading:'+draft.slot+':'+draft.type,context:name+' · '+pending,priority:0,time:-Infinity,at:String(draft.at||'')};
 });
}

export function renderSlotDashboard(state,now=Date.now()){
 const slots=Array.isArray(state?.slots)?state.slots.slice().sort((first,second)=>first.id-second.id):[];
 const readings=pendingReadings(state,slots),tasks=[...readings,...slots.flatMap(slot=>{
  const next=upcoming(slot,now),time=rowTime(next)??Infinity,name=known(team(slot))?team(slot):'Time não definido';
  const context=name+(time!==Infinity?' · Jogo '+next.date+' às '+next.time:'');
  return slotDashboardTasks(slot,now).map(task=>({...task,slot:slot.id,context,time,action:'slotTask:'+slot.id+':'+task.tab}));
 })].sort((first,second)=>first.priority-second.priority||first.time-second.time||String(second.at||'').localeCompare(String(first.at||''))||first.slot-second.slot);
 const games=slots.flatMap(slot=>rows(slot).filter(row=>!hasResult(row)&&rowTime(row)!==null&&rowTime(row)>=now&&rowTime(row)<now+24*3600000).map(row=>({slot:slot.id,time:rowTime(row),action:'slotTask:'+slot.id+':pregame',label:'Jogo '+row.date+' às '+row.time,context:known(team(slot))?team(slot):'Time não definido'}))).sort((first,second)=>first.time-second.time||first.slot-second.slot);
 const results=slots.reduce((count,slot)=>count+overdueResults(slot,now).length,0);
 const priorities=tasks.filter(task=>task.priority<=2),organization=tasks.filter(task=>task.priority>2);
 const body=taskSection('Prioridades','today-priorities',priorities)+taskSection('Organização','today-organization',organization)+(tasks.length?'':'<p class="muted">Nenhuma ação pendente. Confira os dados no OSM antes das próximas partidas.</p>')+taskSection('Jogos nas próximas 24 horas','today-games',games);
 return '<section class="slot-dashboard" aria-labelledby="slot-dashboard-title"><div class="slot-dashboard-heading"><h2 id="slot-dashboard-title">O que fazer agora</h2><p class="muted">Visão geral dos quatro slots. Toque em uma ação para abrir o slot e a tela correspondentes.</p></div><dl class="dashboard-summary">'+stat('Ações pendentes',tasks.length)+stat('Revisões pendentes',readings.length)+stat('Jogos nas próximas 24h',games.length)+stat('Resultados pendentes',results)+'</dl>'+body+'</section>';
}

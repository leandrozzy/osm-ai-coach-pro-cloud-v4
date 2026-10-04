import {known,nextMatch,rowTime,scoreOutcome,confirmedMatchSecretTraining,confirmedMatchField} from './domain.js';
import {validateMatch} from './validator.js';
import {normalize} from './utils.js';
import {preparationCalendarConflict} from './preparation.js';

const esc=value=>String(value??'').replace(/[&<>"']/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const show=value=>known(value)?value:'NI';
const rows=slot=>Array.isArray(slot.calendar)?slot.calendar:[];
const players=slot=>Array.isArray(slot.squad?.players)?slot.squad.players:[];
const team=slot=>known(slot.myTeam)?slot.myTeam:slot.match?.myName;
const sameTeam=(first,second)=>known(first)&&known(second)&&normalize(first).replace(/\s+/g,' ')===normalize(second).replace(/\s+/g,' ');
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
 const add=(label,tab)=>tasks.push({label,tab});
 if(!known(team(slot)))add('Definir o time deste slot','settings');
 if(!known(slot.competition))add('Criar ou editar a competição','settings');
 if(!known(team(slot)))return tasks;
 const overdue=overdueResults(slot,now);
 if(overdue.length)add('Registrar '+overdue.length+(overdue.length===1?' resultado pendente':' resultados pendentes'),'learning');
 if(!rows(slot).length&&!terminalDate(slot))add('Adicionar calendário e horário de partida','info');
 else if(next&&(!known(next.date)||!known(next.time)))add('Completar data e horário do próximo jogo','info');
 else if(!next&&!terminalDate(slot))add('Conferir o próximo jogo no calendário','info');
 if(!competitionEnded(slot,now)){
  const changedRival=next&&known(next.opponent)&&!sameTeam(next.opponent,match.rivalName);
  if(changedRival)add('Atualizar a partida contra '+show(next.opponent),'pregame');
  const missing=[['myStrength','minha força'],['rivalStrength','força rival'],['referee','árbitro'],['location','casa/fora']].filter(([field])=>!known(match[field])).map(([,label])=>label);
  if(missing.length)add('Completar '+missing.join(', '),'pregame');
  if(!confirmedMatchSecretTraining(match)&&!confirmedMatchField(match,'rivalMarking'))add('Ler ou informar a marcação rival','pregame');
  if(!['Sim','Não'].includes(match.myTrainingCamp))add('Definir meu campo de treinamento','pregame');
  if(!slot.tactics)add('Preparar tática','pregame');
  else if(slot.tacticStale||changedRival)add('Revisar tática para o próximo jogo','pregame');
 }
 if(!roster.length)add('Ler ou cadastrar elenco','info');
 else if(!slot.director?.plan)add('Gerar plano de compras e vendas','director');
 return tasks;
}

function taskButton(slotId,task){
 return '<button type="button" data-action="slotTask:'+slotId+':'+esc(task.tab)+'"><span>'+esc(task.label)+'</span><b aria-hidden="true">→</b></button>';
}
function stat(label,value){return '<div><dt>'+esc(label)+'</dt><dd>'+esc(show(value))+'</dd></div>';}
function renderSlotCard(slot,state,now){
 const next=upcoming(slot,now),tasks=slotDashboardTasks(slot,now),roster=players(slot),match=slot.match||{};
 const quality=Math.max(0,Math.min(100,Number(validateMatch(match).coverage)||0));
 const terminal=terminalDate(slot),ended=competitionEnded(slot,now),active=slot.id===state.activeSlot;
 const trained=roster.filter(player=>player.training===true).length,onSale=roster.filter(player=>player.forSale===true).length;
 const outcomes=rows(slot).reduce((totals,row)=>{const result=['V','E','D'].includes(row.result)?row.result:scoreOutcome(row.score);if(result)totals[result]++;return totals;},{V:0,E:0,D:0});
 const when=next?[known(next.date)?next.date:'Data não identificada',known(next.time)?next.time:'Horário não identificado'].join(' · '):ended?'Competição encerrada':'Sem próximo jogo identificado';
 const place=next?.home===true?'Casa':next?.home===false?'Fora':'Local NI';
 const tactics=slot.tactics?(slot.tacticStale||preparationCalendarConflict(slot,{now})?'Revisar · ':'')+show(slot.tactics.formation):'Pendente';
 return '<article class="slot-card'+(active?' is-active':'')+'" aria-labelledby="slot-card-title-'+slot.id+'"><div class="slot-card-head"><div><span class="badge">S'+slot.id+(active?' · ativo':'')+'</span><h3 id="slot-card-title-'+slot.id+'">'+esc(known(team(slot))?team(slot):'Time não definido')+'</h3><p>'+esc(show(slot.competition))+' · '+esc(show(slot.competitionType))+'</p></div><button type="button" class="secondary" data-action="slotTask:'+slot.id+':settings" aria-label="Configurar S'+slot.id+'">Configurar</button></div><div class="slot-card-next"><span>Próximo jogo</span><strong>'+esc(next?show(next.opponent):ended?'Competição encerrada':'NI')+'</strong><p>'+esc(when)+(next?' · '+place:'')+'</p>'+(terminal?'<small>Calendário completo até '+esc(terminal.date)+'</small>':'')+'</div><dl class="slot-card-stats">'+stat('Força',match.myStrength)+stat('Elenco cadastrado',roster.length+' jogadores')+stat('Caixa',slot.director?.cash)+stat('Em treino / à venda',trained+' / '+onSale)+stat('Tática',tactics)+stat('Resultados V / E / D',outcomes.V+' / '+outcomes.E+' / '+outcomes.D)+'</dl><div class="section-meta"><span>Dados da partida</span><span>'+quality+'%</span></div><div class="meter" role="progressbar" aria-label="Dados da partida do S'+slot.id+'" aria-valuemin="0" aria-valuemax="100" aria-valuenow="'+quality+'"><i style="width:'+quality+'%"></i></div><h4>O que fazer no S'+slot.id+'</h4>'+(tasks.length?'<ol class="slot-card-tasks">'+tasks.map(task=>'<li>'+taskButton(slot.id,task)+'</li>').join('')+'</ol>':'<p class="muted">'+(ended?'Calendário encerrado. Confira seus resultados e o histórico.':'Preparação preenchida. Confira os dados no OSM antes da partida.')+'</p>')+'</article>';
}

export function renderSlotDashboard(state,now=Date.now()){
 const slots=Array.isArray(state?.slots)?state.slots.slice().sort((first,second)=>first.id-second.id):[];
 const games=slots.flatMap(rows).filter(row=>!hasResult(row)&&rowTime(row)!==null&&rowTime(row)>=now&&rowTime(row)<now+24*3600000).length;
 const results=slots.reduce((count,slot)=>count+overdueResults(slot,now).length,0),training=slots.flatMap(players).filter(player=>player.training===true).length;
 const preparation=slots.filter(slot=>known(team(slot))&&!competitionEnded(slot,now)&&slot.tactics&&!slot.tacticStale&&!slotDashboardTasks(slot,now).some(task=>task.tab==='pregame')).length;
 return '<section class="slot-dashboard" aria-labelledby="slot-dashboard-title"><div class="slot-dashboard-heading"><h2 id="slot-dashboard-title">O que fazer nos quatro slots</h2><p class="muted">Cada ação abre o slot e a tela correspondentes.</p></div><dl class="dashboard-summary">'+stat('Jogos nas próximas 24h',games)+stat('Slots preparados',preparation+' / '+slots.length)+stat('Resultados pendentes',results)+stat('Jogadores em treino',training)+'</dl><div class="slot-dashboard-grid">'+slots.map(slot=>renderSlotCard(slot,state,now)).join('')+'</div></section>';
}

import {getState,getSlot,setState,setActiveSlot,updateSlot} from './state.js';
import {exportState,migrate} from './storage.js';
import {matchFields,known,num,colors,tackles,positions,formations,styles,cleanMatch,cleanPlayers,nextMatch,rowTime,pending,sharedLearning,scoreOutcome} from './domain.js';
import {mergeBetter,validateMatch} from './validator.js';
import {rivalHuman} from './slots.js';
import {dedupePlayers,squadSummary} from './parser-squad.js';
import {mergeCalendar} from './parser-calendar.js';
import {generateTactic,generateStrong433,validateTactic,updateTacticSliders} from './tactics-engine.js';
import {buildMarketPlan,TARGET} from './market-engine.js';
import {recordResult} from './learning-engine.js';
import {renderReview,readReview,shirt,renderReadingSummary,currentReviewWarnings} from './review-ui.js';
import {fuseExtraction,coverage} from './extraction.js';
import {cleanCalendar} from './extraction.js';
import {askAI,apiStatus,setSessionKey,hasSessionKey,sessionProviders,providerKey,apiRequest} from './ai-router.js';
import {analyzeMedia} from './video.js';
import {applyReading,slotTeam,readingTeam} from './review-application.js';
import {refineSquadRoster} from './squad-roster.js';
import {requestNotifications,pollNotifications} from './notifications.js';
let root,tab='today',busy=false,progress='',analysisType='match',review=null,analysisController=null,api=null,installPrompt=null,apiChecks=null,checkingApis=false;
const navs=[['today','Hoje','◷'],['pregame','Pré-jogo','⚑'],['analyze','Analisar','⊕'],['info','Informações','▤'],['director','Diretor','↗'],['learning','Aprendizado','◎'],['settings','Configurações','⚙']];
const esc=s=>String(s??'').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));
const show=v=>known(v)?v:'NI';
const date=v=>v?new Date(v).toLocaleString('pt-BR'):'NI';
const bool=v=>v===true?'Sim':v===false?'Não':'NI';
const local=v=>v===true?'Casa':v===false?'Fora':'NI';
function installed(){return matchMedia('(display-mode: standalone)').matches||navigator.standalone===true||installedKnown;}
let installedKnown=false;
function btn(label,action,secondary=false,extra=''){if(action==='install'&&installed()){label='App instalado';extra+=' disabled';}if(action==='notify'){const p=typeof Notification==='undefined'?'unsupported':Notification.permission;if(p!=='default'){label=p==='granted'?'Notificações permitidas':p==='denied'?'Notificações bloqueadas':'Não suportadas';extra+=' disabled';}}return '<button type="button" data-action="'+action+'" class="'+(secondary?'secondary':'primary')+'" '+extra+'>'+label+'</button>';}
function card(title,body,cls=''){return '<section class="card '+cls+'"><h2>'+title+'</h2>'+body+'</section>';}
function empty(text){return '<div class="empty"><span>◇</span><p>'+text+'</p></div>';}
function field(label,v){return '<div class="field"><span>'+esc(label)+'</span><strong class="'+(!known(v)?'ni':'')+'">'+esc(show(v))+'</strong></div>';}
function input(name,label,v,type='text',attrs=''){return '<label><span>'+esc(label)+'</span><input name="'+name+'" type="'+type+'" value="'+esc(known(v)?v:'')+'" placeholder="NI" '+attrs+'></label>';}
function select(name,label,value,options){return '<label><span>'+esc(label)+'</span><select name="'+name+'"><option value="">NI</option>'+options.map(o=>{const [v,t]=Array.isArray(o)?o:[o,o];return '<option value="'+esc(v)+'" '+(String(v)===String(value)?'selected':'')+'>'+esc(t)+'</option>';}).join('')+'</select></label>';}
function note(text){return '<p class="muted">'+text+'</p>';}
function check(name,label,value){return '<label class="check"><input name="'+name+'" type="checkbox" '+(value?'checked':'')+'><span>'+label+'</span></label>';}
function stamp(slot){return '<p class="stamp">Atualizado: '+date(slot.updatedAt)+' · dados neste dispositivo</p>';}
function pagehead(title,sub){return '<div class="football-hero"><img src="/assets/stadium.svg" alt="Ilustração de um campo de futebol iluminado"><span>PREPARE SEU TIME</span></div><div class="pagehead"><div><div class="eyebrow">CENTRO DE COMANDO · S'+getSlot().id+'</div><h1>'+title+'</h1><p>'+sub+'</p></div><span class="status-dot">'+(navigator.onLine?'Online':'Offline')+'</span></div>';}
function today(slot){
 const next=nextMatch(slot),issues=pending(slot),quality=validateMatch(slot.match).coverage;
 const t=rowTime(next),time=t===null?'Horário não identificado':new Date(t).toLocaleString('pt-BR',{dateStyle:'short',timeStyle:'short'});
 const battle=slot.competitionType==='Batalha';
 let body=pagehead('O próximo jogo começa aqui.','Leia o rival, prepare seu time e entre em campo com um plano.');
 body+='<section class="match-hero"><div class="hero-top"><span class="badge">'+(battle?'⚑ BATALHA':'PRÓXIMO JOGO')+'</span><span>'+esc(next?.cup===true?'Copa':next?.cup===false?'Liga':slot.competitionType)+'</span></div><div class="versus"><div class="team"><div class="crest">◈</div><h2>'+esc(show(slot.match.myName||slot.myTeam))+'</h2><span>Seu time</span></div><span class="vs">VS</span><div class="team"><div class="crest rival">◇</div><h2>'+esc(show(next?.opponent||slot.match.rivalName))+'</h2><span>'+esc(slot.match.human===true?'Rival humano':slot.match.human===false?'Rival CPU':'Tipo: NI')+'</span></div></div><div class="hero-bottom"><span>◷ '+esc(time)+'</span><span>⌂ '+esc(next?local(next.home):show(slot.match.location))+'</span></div><div class="hero-action">'+btn(issues.length?'Urgente · '+esc(issues[0].label):'Ver preparação',issues.length?'go:'+issues[0].tab:'go:pregame')+'</div></section>';
 body+='<div class="stats"><div><span>Preparação</span><b>'+quality+'<small>%</small></b><div class="meter"><i style="width:'+quality+'%"></i></div></div><div><span>Tática</span><b class="small">'+(slot.tactics?(slot.tacticStale?'Revisar':esc(slot.tactics.formation)):'Pendente')+'</b><small>'+esc(slot.tactics?.source||'Pronta para você gerar')+'</small></div><div><span>Pendências</span><b>'+issues.length+'</b><small>Próximas ações</small></div></div>';
 body+='<div class="columns">'+card('Sua preparação',issues.length?'<div class="todo">'+issues.map((p,i)=>'<button data-action="go:'+p.tab+'"><span class="todo-num">'+String(i+1).padStart(2,'0')+'</span><span>'+esc(p.label)+'</span><b>↗</b></button>').join('')+'</div>':note('Preparação preenchida. Confira se os dados pertencem a esta partida.'))+card('Quatro slots. Um treinador.','<div class="slot-overview">'+getState().slots.slice().sort((a,b)=>(b.competitionType==='Batalha')-(a.competitionType==='Batalha')).map(s=>'<button data-action="slot:'+s.id+'"><b>S'+s.id+'</b><span>'+esc(show(s.match.myName||s.myTeam))+'<small>'+esc(s.competitionType)+' · '+pending(s).length+' pendências</small></span><i>→</i></button>').join('')+'</div>')+'</div>';
 return body+stamp(slot);
}
function matchForm(m){return matchFields.map(([key,label,type])=>type==='human'?select(key,label,m.human===true?'true':m.human===false?'false':'',[['true','Humano'],['false','CPU']]):Array.isArray(type)?select(key,label,m[key],type):input(key,label,m[key],type||'text',type==='number'?'min="0" max="100000" step="any"':'')).join('');}
function tacticView(t){
 if(!t)return empty('Sua tática aparecerá aqui. Preencha a partida e gere uma recomendação.');
 const sliders=[['pressure','Pressão'],['mentality','Mentalidade'],['tempo','Ritmo']];
 return '<div class="tactic-title"><div><span class="badge">'+esc(t.source||'Motor local')+'</span><h3>'+esc(t.formation)+'</h3><p>'+esc(t.style)+'</p></div><div class="pitch"><div class="pitch-circle"></div>'+Array.from({length:11},(_,i)=>'<i style="left:'+([50,16,39,61,84,25,50,75,22,50,78][i])+'%;top:'+([86,66,66,66,66,45,45,45,22,17,22][i])+'%"></i>').join('')+'</div></div><p class="pitch-label">Campo ilustrativo · aplique a formação indicada no OSM</p><div class="sliders">'+sliders.map(([k,l])=>'<label><span>'+l+' <b id="'+k+'Val">'+esc(t[k])+'</b></span><input aria-label="'+l+'" type="range" name="'+k+'" min="0" max="100" value="'+esc(t[k])+'"></label>').join('')+'</div><div class="field-grid">'+[['marking','Marcação'],['offside','Impedimento'],['tackling','Desarme'],['attack','Ataque'],['midfield','Meio'],['defense','Defesa']].map(([k,l])=>field(l,t[k])).join('')+'</div><p class="reason">'+esc(t.reason||'Recomendação tática; confira os dados.')+'</p>'+note(t.provisional?'Tática provisória: informações essenciais estão NI.':'Confira a tática no OSM antes da partida.')+'<div class="actions">'+btn('Salvar sliders','saveSliders',true)+btn('Registrar resultado','go:learning',true)+'</div>';
}
function pregame(slot){
 const q=validateMatch(slot.match),diff=num(slot.match.myStrength)!==null&&num(slot.match.rivalStrength)!==null?num(slot.match.myStrength)-num(slot.match.rivalStrength):null;
 return pagehead('Pré-jogo','Uma recomendação completa, a partir dos dados que você confirmou.')+card('Dados da partida','<div class="section-meta"><span>'+q.coverage+'% dos campos preenchidos</span><span>'+esc(slot.competitionType)+'</span></div><div class="meter"><i style="width:'+q.coverage+'%"></i></div>'+note('NI = não identificado. O nome do usuário abaixo do clube adversário identifica um rival humano. Dados antigos só devem ser reutilizados para a mesma partida.')+'<details><summary>Editar e revisar dados da partida</summary><form data-form="match"><div class="form-grid">'+matchForm(slot.match)+'</div><button class="primary">Salvar dados</button></form></details><div class="field-grid">'+[['myName','Meu time'],['rivalName','Rival'],['myStrength','Minha força'],['rivalStrength','Força rival'],['location','Local'],['referee','Árbitro'],['rivalFormation','Formação rival'],['secretTraining','Treino secreto']].map(([k,l])=>field(l,slot.match[k])).join('')+'</div><div class="actions">'+btn(busy?'Preparando…':'Gerar tática com IA','aiTactic',false,busy?'disabled':'')+btn('Gerar tática local','localTactic',true,busy?'disabled':'')+(diff!==null&&diff>=13?btn('Tática forte 4-3-3 · +'+diff,'strong',true):'')+btn('Nova partida','newMatch',true)+'</div>'+note('Sem IA disponível, o fallback local pode gerar uma recomendação heurística. Nenhuma tática garante vitória.'))+card('Plano para entrar em campo',slot.tacticStale?'<p class="warning">Os dados mudaram. Gere uma nova tática antes do jogo.</p>'+tacticView(slot.tactics):tacticView(slot.tactics))+stamp(slot);
}
function analyze(){
 const labels={match:'Partida',squad:'Elenco',calendar:'Calendário'};
 let body=pagehead('Transforme telas em preparação.','Vídeos e imagens do OSM, com revisão antes de salvar.');
 body+=card('O que vamos ler?','<div class="seg">'+Object.entries(labels).map(([k,l])=>btn(l,'type:'+k,true,'aria-pressed="'+(analysisType===k)+'" '+(busy?'disabled':'')+' data-selected="'+(analysisType===k)+'"')).join('')+'</div><label class="upload"><span class="upload-icon">↑</span><b>Escolha vídeos ou imagens</b><span>PNG, JPEG, WebP ou vídeo · até 24 arquivos · 150 MB por arquivo</span><input id="media" type="file" accept="image/*,video/*" multiple '+(busy?'disabled':'')+'><small id="fileCount">Nenhum arquivo selecionado</small></label>'+select('reader','Método de leitura','hybrid',[['hybrid','Leitura local + recuperação pelas APIs'],['local','Somente leitura local · sem chave de API'],['vision','Somente APIs']])+note('O leitor local reconhece o texto e sua posição nas telas originais. Na primeira utilização, precisa de internet para baixar o motor gratuito; depois reutiliza o motor neste aparelho. As APIs configuradas ajudam a recuperar campos pendentes. Camisas, setas, cadeado e árbitro são conferidos nas imagens originais. Confira os dados antes de salvar.')+'<div class="actions">'+btn(busy?'Lendo…':'Analisar mídia','analyze',false,busy?'disabled':'')+(busy?btn('Cancelar','cancel',true):'')+'</div><div class="progress" role="status" aria-live="polite">'+esc(progress||'Pronto para analisar. Seus dados atuais serão preservados.')+'</div>');
 if(review&&review.slot===getSlot().id){
 const club=readingTeam(review.type,review.type==='match'?review.match:review,review.meta);
 const clubNotice=known(club)?note('Clube lido: '+esc(club)+'. Os dados serão salvos no slot escolhido abaixo.') : '';
 const warnings=currentReviewWarnings(review);
 body+=card('Revise a leitura',renderReadingSummary(review)+'<p class="muted">'+esc(review.mode)+' · '+review.frames+' telas. Edite os campos abaixo antes de salvar no S'+review.slot+'.</p>'+(warnings.length?'<details class="reading-warnings" open><summary>Pendências e avisos ('+warnings.length+')</summary><ul>'+warnings.map(e=>'<li>'+esc(e)+'</li>').join('')+'</ul></details>':'')+'<details><summary>Ver telas extraídas</summary><div class="frames">'+review.previews.map(p=>'<figure><img src="'+p.url+'" alt="Tela extraída de '+esc(p.name)+'" loading="lazy"><figcaption>'+esc(p.name)+' · '+p.time.toFixed(1)+'s</figcaption></figure>').join('')+'</div></details>'+clubNotice+select('reviewSlot','Salvar no slot',review.slot,getState().slots.map(s=>[s.id,'S'+s.id+' · '+(known(slotTeam(s))?slotTeam(s):'Sem time definido')]))+renderReview(review)+'<div class="actions">'+btn('Confirmar dados revisados','applyReview')+btn('Completar leitura','retryAnalysis',true)+btn('Descartar leitura','discardReview',true)+'</div>'+note('NI = não identificado. Confira os dados nas telas; valores desconhecidos preservam os dados válidos já salvos.'));

 }
 return body;
}
function playerForm(p={}){
 return '<form data-form="player" data-id="'+esc(p.id||'')+'"><div class="form-grid">'+input('name','Nome',p.name,'text','required maxlength="80"')+select('position','Posição',p.position,positions)+input('strength','Força',p.strength,'number','min="1" max="400"')+input('age','Idade',p.age,'number','min="15" max="60"')+input('value','Valor',p.value)+select('forSale','À venda',p.forSale===true?'true':p.forSale===false?'false':'',[['true','Sim · setas'],['false','Não']])+select('training','Treinando',p.training===true?'true':p.training===false?'false':'',[['true','Sim · camisa laranja'],['false','Não']])+'</div><button class="primary">Salvar jogador</button></form>';
}
function calendarForm(r={}){
 return '<form data-form="calendar" data-id="'+esc(r.id||'')+'"><div class="form-grid">'+input('opponent','Rival',r.opponent)+input('round','Rodada',r.round,'number','min="1" max="200"')+input('date','Data (DD/MM/AAAA)',r.date,'text','pattern="[0-9]{2}/[0-9]{2}/[0-9]{4}"')+input('time','Horário local',r.time,'time')+select('home','Local',r.home===true?'true':r.home===false?'false':'',[['true','Casa'],['false','Fora']])+select('cup','Competição',r.cup===true?'true':r.cup===false?'false':'',[['true','Copa'],['false','Liga']])+input('score','Placar (meu time primeiro)',r.score,'text','pattern="[0-9]{1,2}[x:-][0-9]{1,2}"')+select('result','Resultado',r.result,['V','E','D'])+'</div><button class="primary">Salvar jogo</button></form>';
}
function info(slot){
 const summary=squadSummary(slot.squad.players);
 return pagehead('Seu time, em detalhes.','Elenco e calendário independentes por slot. Corrija o que estiver NI.')+card('Elenco · '+summary.total+' jogadores','<div class="position-counts">'+positions.map(p=>'<div><b>'+summary.by[p]+'/'+TARGET[p]+'</b><span>'+p+'</span></div>').join('')+'</div>'+note('Treinando: '+summary.training+' · À venda: '+summary.forSale+'/4. Estados desconhecidos ficam NI.')+'<details><summary>+ Adicionar jogador</summary>'+playerForm()+'</details><div class="players">'+slot.squad.players.map(p=>'<details class="player"><summary><span class="pos">'+esc(p.position)+'</span><span>'+esc(p.name)+'<small>'+esc(show(p.age))+' anos · '+esc(show(p.value))+' · treino: '+bool(p.training)+' · venda: '+bool(p.forSale)+'</small></span><b>'+esc(show(p.strength))+'</b></summary>'+playerForm(p)+btn('Remover jogador','deletePlayer:'+p.id,true)+'</details>').join('')+'</div>'+(slot.squad.players.length?'':empty('Leia seu elenco ou adicione jogadores.')))+card('Calendário','<details><summary>+ Adicionar jogo</summary>'+calendarForm()+'</details><div class="calendar">'+slot.calendar.map(r=>'<details><summary><span class="date-chip">'+esc(show(r.round))+'<small>RODADA</small></span><span>'+esc(show(r.opponent))+'<small>'+esc(show(r.date))+' · '+esc(show(r.time))+' · '+local(r.home)+' · '+(r.cup===true?'Copa':r.cup===false?'Liga':'NI')+'</small></span><b class="result">'+esc(known(r.score)?r.score:show(r.result))+'</b></summary>'+calendarForm(r)+btn('Remover jogo','deleteCalendar:'+r.id,true)+'</details>').join('')+'</div>'+(slot.calendar.length?'':empty('Nenhum jogo cadastrado. Horários devem usar o fuso deste dispositivo.')))+stamp(slot);
}
function director(slot){
 const p=slot.director.plan,summary=squadSummary(slot.squad.players),values=slot.squad.players.map(p=>money(p.value)),strengths=slot.squad.players.map(p=>num(p.strength)).filter(v=>v!==null);
 let html=pagehead('Construa um elenco mais forte.','Compras, vendas e evolução com um plano que fica salvo.');
 html+=card('Situação do clube','<div class="field-grid">'+field('Força média lida',strengths.length===slot.squad.players.length&&strengths.length?(strengths.reduce((a,b)=>a+b,0)/strengths.length).toFixed(1):'NI')+field('Valor total lido',values.length&&values.every(v=>v!==null)?values.reduce((a,b)=>a+b,0).toLocaleString('pt-BR'):'NI')+field('Jogadores treinando',summary.training)+field('À venda',summary.forSale+'/4')+'</div><form data-form="director"><div class="form-grid">'+input('cash','Caixa disponível',slot.director.cash)+'<label class="full"><span>Plano e anotações persistentes</span><textarea name="notes" rows="4">'+esc(slot.director.notes||'')+'</textarea></label></div><button class="primary">Salvar caixa e plano</button></form><div class="actions">'+btn('Atualizar recomendações','market',true)+btn('Registrar evolução atual','snapshot',true)+'</div>');
 html+=card('Plano de mercado',p?'<p class="stamp">Gerado em '+date(p.createdAt)+'</p><h3>Compras por prioridade</h3><div class="recommendations">'+(p.buy.map(x=>'<div><b>'+x.count+' × '+esc(x.position)+'</b><span>'+esc(x.profile)+'</span></div>').join('')||note('Meta de quantidade atendida. Compare força e preço antes de trocar jogadores.'))+'</div><h3>Vendas possíveis</h3><div class="recommendations">'+(p.sell.map(x=>'<div><b>'+esc(x.name)+'</b><span>'+esc(x.position)+' · '+x.strength+' · '+esc(x.reason)+'</span></div>').join('')||note('Nenhuma venda recomendada sem reduzir a meta de posições.'))+'</div>'+note('Sugestões locais, sem inventar jogadores ou preços do mercado. A lista respeita as vagas disponíveis nas quatro vendas simultâneas.') :empty('Leia ou cadastre o elenco e atualize as recomendações.'));
 html+=card('Evolução',slot.director.snapshots?.length?'<div class="history">'+slot.director.snapshots.map(s=>'<div>'+date(s.at)+'<b>'+esc(show(s.strength))+' de força · '+s.total+' jogadores</b></div>').join('')+'</div>':empty('Registre um ponto de evolução após atualizar o elenco.'));
 return html;
}
function money(v){if(!known(v))return null;const m=String(v).replace(/\s|R\$/g,'').match(/^([\d.,]+)(MM|M|B|K)?$/i);if(!m)return null;const n=Number(m[1].replace(/\.(?=\d{3}(?:\D|$))/g,'').replace(',','.'));return Number.isFinite(n)?n*({K:1e3,M:1e6,MM:1e6,B:1e9}[m[2]?.toUpperCase()]||1):null;}
function learning(slot){
 const shared=sharedLearning(getState());
 return pagehead('Cada partida deixa uma lição.','Histórico compartilhado entre S1–S4. Tendências não provam a eficácia de uma tática.')+card('Registrar resultado','<form data-form="result"><div class="form-grid">'+select('calendarId','Jogo do calendário','',slot.calendar.filter(r=>!known(r.result)).map(r=>[r.id,show(r.opponent)+' · '+show(r.date)]))+input('score','Placar · meu time primeiro','','text','required pattern="[0-9]{1,2}[x:-][0-9]{1,2}" placeholder="2x1"')+input('cards','Meus cartões','','number','min="0" max="30"')+input('rivalCards','Cartões rival','','number','min="0" max="30"')+input('shots','Meus remates','','number','min="0" max="100"')+input('rivalShots','Remates rival','','number','min="0" max="100"')+input('possession','Minha posse (%)','','number','min="0" max="100"')+input('corners','Meus escanteios','','number','min="0" max="50"')+input('rivalCorners','Escanteios rival','','number','min="0" max="50"')+'<label class="full"><span>Contexto e observações</span><textarea name="notes" rows="3" placeholder="NI"></textarea></label></div>'+note('A tática e o contexto atuais serão registrados. O placar determina V/E/D automaticamente. Campos vazios permanecem NI.')+'<button class="primary">Salvar resultado do S'+slot.id+'</button></form>')+card('Tendências dos quatro slots','<div class="trend-table"><div class="trend-row heading"><span>Formação</span><span>Jogos</span><span>V / E / D</span></div>'+Object.entries(shared.weights).map(([f,w])=>'<div class="trend-row"><b>'+esc(f)+'</b><span>'+w.games+'</span><span>'+w.wins+' / '+w.draws+' / '+w.losses+'</span></div>').join('')+'</div>'+note(shared.matches.length?'O motor local considera tendências a partir de 3 jogos, com peso limitado.':'Sem resultados ainda. O app não cria aprendizado fictício.'))+card('Histórico compartilhado',shared.matches.length?'<div class="history">'+shared.matches.map(r=>'<details><summary><span class="outcome '+r.outcome+'">'+r.outcome+'</span><span>S'+r.slot+' · '+esc(show(r.context?.rivalName))+'<small>'+date(r.at)+' · '+esc(r.tactic?.formation||'NI')+'</small></span><b>'+esc(r.score)+'</b></summary><div class="field-grid">'+field('Tática',r.tactic?.formation)+field('Estilo',r.tactic?.style)+field('Sliders',r.tactic?[r.tactic.pressure,r.tactic.mentality,r.tactic.tempo].join(' / '):'NI')+field('Cartões',r.cards)+field('Remates',r.shots)+field('Posse',r.possession)+field('Escanteios',r.corners)+field('Contexto',r.notes)+'</div>'+btn('Excluir registro','deleteResult:'+r.slot+':'+r.id,true)+'</details>').join('')+'</div>':empty('Seus resultados aparecerão aqui.'));
}
function settings(slot){
 const s=getState().settings;
 const configured=Object.entries(api?.providers||{}).filter(([,v])=>v).map(([k])=>k);
 return pagehead('Tudo no seu controle.','Preferências, IA, dados e instalação no Android.')+card('Seu perfil e este slot','<form data-form="settings"><div class="form-grid">'+input('username','Usuário OSM',s.username,'text','required maxlength="40"')+input('myTeam','Nome do time neste slot',slot.myTeam)+select('competitionType','Tipo de competição',slot.competitionType,['Liga normal','Batalha','Copa'])+input('competition','Nome da competição',slot.competition)+input('model','Modelo Groq textual',s.model,'text','required maxlength="100"')+input('visionModel','Modelo Groq visual (auto = seleção automática)',s.visionModel,'text','required maxlength="100"')+select('profile','Tempo de análise',s.profile,[['fast','Rápido · orçamento de 90 segundos'],['complete','Completo · orçamento de 180 segundos']])+'</div><h3>Árbitro → desarme</h3><div class="form-grid">'+colors.map(c=>select('ref:'+c,c,s.refereeMap[c],tackles)).join('')+'</div>'+check('fallback','Fallback local quando a IA falhar',s.fallback)+check('notifications','Ativar lembretes enquanto o app estiver aberto',s.notifications)+'<button class="primary">Salvar configurações</button></form>');
}
function settingsFull(slot){
 return settings(slot)+card('IA e leitura visual','<div class="field-grid">'+field('Servidor',api===null?'Status indisponível':api.ok?'Disponível':'Indisponível')+field('Provedores configurados',configuredProviders())+field('Chaves salvas neste aparelho',sessionProviders().join(', ')||'Nenhuma')+'</div><form data-form="key">'+['google','groq','ocrspace','twelvelabs'].map(k=>input(k,'Chave '+(k==='google'?'Google / Gemini':k)+' · salva neste aparelho',providerKey(k),'password','autocomplete="off" maxlength="300"')).join('')+'<button class="primary">Salvar chaves no aparelho</button></form><div class="actions">'+btn('Remover chave','forgetKey',true)+btn('Atualizar status','status',true)+btn(checkingApis?'Testando APIs…':'Testar APIs','checkApis',true,checkingApis?'disabled':'')+'</div>'+note('As chaves ficam salvas neste navegador/aparelho e são enviadas por HTTPS ao servidor para consultar as APIs. Não entram nos backups nem são apagadas ao fechar, instalar ou limpar o cache do app. Limpar os dados do navegador remove as chaves. Alternativa: GEMINI_API_KEY, GROQ_API_KEY, OCR_SPACE_API_KEY e TWELVELABS_API_KEY na Vercel. Vídeos são enviados ao TwelveLabs quando necessário; consulte sua política de retenção. A disponibilidade gratuita depende da cota do provedor; o app não ativa cobrança nem troca para modelos pagos automaticamente.')+(apiChecks?'<div class="api-checks">'+apiChecks.map(r=>'<div class="field"><span>'+esc(r.provider)+' · '+(r.ok?'Acessível':'Verificar')+'</span><strong>'+esc(r.message||r.error||'Sem resposta')+'</strong>'+(r.warnings?.length?'<p class="warning">'+r.warnings.map(esc).join('<br>')+'</p>':'')+'</div>').join('')+'</div>':'')+note('Teste o acesso antes de analisar. Modelo acessível não garante todos os campos; cota e conteúdo também afetam a leitura.'))+card('Notificações e Android','<div class="field-grid">'+field('Permissão',('Notification'in window)?Notification.permission:'Não suportada')+field('Instalação',installed()?'App instalado':'Navegador')+'</div><div class="actions">'+btn('Permitir notificações','notify',true)+btn('Instalar app','install',true)+'</div>'+note('Lembretes de 20 e 10 minutos funcionam com o app aberto, sujeito à suspensão do Android. Se as notificações estiverem bloqueadas, libere em Android → Configurações → Aplicativos → este app/Chrome → Notificações. Com o app fechado, esta versão não envia push agendado. Hoje reúne avisos de tática, dados, resultado e Diretor, com prioridade para Batalha.')+note('Para instalar no Android: abra no Chrome → menu ⋮ → Instalar aplicativo ou Adicionar à tela inicial.'))+card('Backup e armazenamento','<div class="actions">'+btn('Exportar todos os dados','export')+'<label class="file-button secondary">Importar backup<input id="backupFile" type="file" accept="application/json,.json" hidden></label>'+btn('Limpar cache do app','cache',true)+'</div>'+note('Os dados ficam neste navegador/dispositivo. Exporte regularmente: limpar dados do navegador pode apagá-los. Importar substitui os quatro slots após confirmação. Limpar cache preserva os dados.')+stamp(slot));
}
function configuredProviders(){return [...new Set([...sessionProviders(),...Object.entries(api?.providers||{}).filter(([,v])=>v===true||v?.configured).map(([k])=>k)])].join(', ')||'Nenhuma API configurada';}
function body(){const slot=getSlot();return tab==='today'?today(slot):tab==='pregame'?pregame(slot):tab==='analyze'?analyze():tab==='info'?info(slot):tab==='director'?director(slot):tab==='learning'?learning(slot):settingsFull(slot);}
function render(){
 const state=getState();
 root.innerHTML='<div class="app-shell"><aside><a class="logo" href="#today" aria-label="OSM AI Coach Pro"><img src="/assets/favicon.svg" alt=""><span>OSM AI<span>COACH PRO</span></span><i>PRO</i></a><div class="sidebar-label">SEU VESTIÁRIO</div><nav aria-label="Navegação principal">'+navs.map(([id,label,icon])=>'<button data-action="go:'+id+'" class="'+(tab===id?'active':'')+'" '+(tab===id?'aria-current="page"':'')+'><span>'+icon+'</span>'+label+'</button>').join('')+'</nav><div class="sidebar-foot"><span class="avatar">L</span><div>'+esc(state.settings.username)+'<small>Treinador OSM</small></div></div></aside><div class="workspace"><header><div class="mobile-brand"><img src="/assets/favicon.svg" alt=""><b>OSM AI <small>COACH PRO</small></b></div><div class="slots" aria-label="Slots">'+state.slots.map(s=>'<button data-action="slot:'+s.id+'" class="'+(s.id===state.activeSlot?'active':'')+'" aria-pressed="'+(s.id===state.activeSlot)+'" '+(busy?'disabled':'')+'>S'+s.id+'</button>').join('')+'</div><div class="header-right"><span>⚑ '+esc(getSlot().competitionType)+'</span>'+btn('↧ Instalar','install',true)+'</div></header><main>'+body()+'</main><footer><span>OSM AI Coach Pro · '+esc(state.settings.username)+'</span><span>'+esc(configuredProviders())+'</span></footer></div></div><div id="toast" role="status" aria-live="polite"></div>';
 root.querySelector('#media')?.addEventListener('change',e=>{root.querySelector('#fileCount').textContent=e.target.files.length+' arquivo(s) selecionado(s)';});
 root.querySelector('#backupFile')?.addEventListener('change',importBackup);
 root.querySelectorAll('input[type=range]').forEach(e=>e.oninput=()=>{root.querySelector('#'+e.name+'Val').textContent=e.value;});
}
function toast(message){const e=root.querySelector('#toast');if(!e)return;e.textContent=message;e.classList.add('visible');clearTimeout(toast.timer);toast.timer=setTimeout(()=>e.classList.remove('visible'),6000);}
function go(next){if(busy&&tab==='analyze'){toast('Cancele ou aguarde a leitura antes de trocar de aba.');return;}tab=next;history.replaceState(null,'','#'+next);render();window.scrollTo(0,0);}
function data(form){return Object.fromEntries(new FormData(form));}
function val(v){return v===''?'NI':v;}
function truth(v){return v==='true'?true:v==='false'?false:null;}
async function submit(form){
 const d=data(form),slot=getSlot();
 if(form.dataset.form==='match'){
 const raw={...d,human:truth(d.human)},cleaned=cleanMatch(raw);
 for(const [key] of matchFields)if(!known(d[key]))cleaned[key]=key==='human'?null:'NI';
 updateSlot(s=>{s.match={...s.match,...cleaned,_fieldSources:Object.fromEntries(matchFields.filter(([field])=>known(cleaned[field])).map(([field])=>[field,{kind:'manual',rank:4,source:'Revisado no Pré-jogo'}])),_manualUpdatedAt:new Date().toISOString()};s.tacticStale=!!s.tactics;});render();toast('Dados da partida salvos.');return;
 }
 if(form.dataset.form==='player'){
 const p={...d,id:form.dataset.id||crypto.randomUUID(),forSale:truth(d.forSale),training:truth(d.training)};
 const list=cleanPlayers([p]);if(!list.length||!positions.includes(p.position))throw Error('Informe nome e posição válidos.');
 updateSlot(s=>{const i=s.squad.players.findIndex(x=>x.id===p.id);if(i<0)s.squad.players=dedupePlayers([...s.squad.players,...list]);else s.squad.players[i]=list[0];s.squad.updatedAt=new Date().toISOString();s.tacticStale=!!s.tactics;});render();toast('Jogador salvo.');return;
 }
 if(form.dataset.form==='calendar'){
 const r={...d,id:form.dataset.id||crypto.randomUUID(),round:num(d.round),date:val(d.date),time:val(d.time),opponent:val(d.opponent),home:truth(d.home),cup:truth(d.cup),result:val(d.result),score:val(d.score)};
 if(known(r.date)&&known(r.time)&&rowTime(r)===null)throw Error('Data ou horário inválido.');
 if(known(r.score)){const outcome=scoreOutcome(r.score);if(!outcome)throw Error('Placar inválido.');r.result=outcome;}
 updateSlot(s=>{const i=s.calendar.findIndex(x=>x.id===r.id);if(i<0)s.calendar=mergeCalendar(s.calendar,[r]);else s.calendar[i]=r;});render();toast('Jogo salvo.');return;
 }
 if(form.dataset.form==='director'){updateSlot(s=>{s.director.cash=val(d.cash);s.director.notes=d.notes;});render();toast('Plano e caixa salvos.');return;}
 if(form.dataset.form==='result'){
 const result={...d};for(const k of ['cards','rivalCards','shots','rivalShots','possession','corners','rivalCorners'])result[k]=num(d[k])??'NI';
 result.notes=val(d.notes);updateSlot(s=>recordResult(s,result));render();toast('Resultado registrado no histórico compartilhado.');return;
 }
 if(form.dataset.form==='settings'){
 setState(s=>{s.settings.username=d.username.trim();s.settings.model=d.model.trim();s.settings.visionModel=d.visionModel.trim();s.settings.profile=d.profile;s.settings.fallback=d.fallback==='on';s.settings.notifications=d.notifications==='on';for(const c of colors)s.settings.refereeMap[c]=d['ref:'+c]||s.settings.refereeMap[c];const sl=s.slots[s.activeSlot-1];sl.myTeam=d.myTeam;sl.competition=d.competition;sl.competitionType=d.competitionType||'Liga normal';sl.tacticStale=!!sl.tactics;return s;});render();toast('Configurações salvas.');return;
 }
 if(form.dataset.form==='key'){setSessionKey(d);render();toast(hasSessionKey()?'Chaves salvas neste aparelho.':'Informe ao menos uma chave.');return;}
}
async function makeTactic(useAI){
 const slotId=getSlot().id,state=structuredClone(getState()),slot=state.slots[slotId-1];
 const localTactic=generateTactic(slot.match,state.settings,sharedLearning(state));
 if(!useAI){updateSlot(s=>{s.tactics=localTactic;s.tacticStale=false;},slotId);render();toast('Tática local gerada.');return;}
 busy=true;progress='';render();
 try{
 const r=await askAI('tactic',{context:{match:slot.match,username:state.settings.username,refereeMap:state.settings.refereeMap,learning:sharedLearning(state).weights,localRecommendation:localTactic}},state.settings.model);
 const t=r.data.tactic;if(!validateTactic(t))throw Error('A IA retornou uma tática incompleta.');
 const a=num(slot.match.myStrength),b=num(slot.match.rivalStrength);
 if(t.formation.startsWith('4-3-3')&&(a===null||b===null||a-b<13))throw Error('A IA sugeriu 4-3-3 sem vantagem confirmada.');
 t.tackling=localTactic.tackling;t.provisional=localTactic.provisional;t.source='IA · '+r.provider;t.createdAt=new Date().toISOString();
 updateSlot(s=>{s.tactics=t;s.tacticStale=false;},slotId);progress='Tática gerada pela IA.';
 }catch(e){if(state.settings.fallback){updateSlot(s=>{s.tactics=localTactic;s.tacticStale=false;},slotId);progress=e.message+' Foi usada a recomendação local.';}else progress=e.message;}
 finally{busy=false;render();if(progress)toast(progress);}
}
async function runAnalysis(retry=false){
 const previous=retry?review:null;
 const files=retry?[...(review?.files||[])]:[...(root.querySelector('#media')?.files||[])];if(!files.length)throw Error('Escolha ao menos um vídeo ou imagem.');
 const slotId=getSlot().id,state=getState(),reader=retry?(review?.reader||'hybrid'):root.querySelector('[name=reader]').value;
 busy=true;review=null;progress='Preparando mídia…';analysisController=new AbortController();const controller=analysisController;let deadline;render();
 try{
  deadline=setTimeout(()=>controller.abort(),retry||state.settings.profile==='complete'?180000:90000);
  const result=await analyzeMedia(files,analysisType,{resume:previous,signal:controller.signal,vision:reader!=='local',localRecovery:reader!=='vision',fallback:state.settings.fallback,username:state.settings.username,myTeam:slotTeam(getSlot()),rivalName:getSlot().match.rivalName,competitionType:getSlot().competitionType,model:state.settings.model,visionModel:state.settings.visionModel,profile:retry?'complete':state.settings.profile},message=>{
   if(controller.signal.aborted)return;progress=message;const e=root.querySelector('.progress');if(e)e.textContent=message;
  });
  let merged=previous&&!result.resumed?fuseExtraction(previous,result):result;
  const evidence=result.resumed?result.rosterEvidence:[...(previous?.rosterEvidence||[]),...(result.rosterEvidence||[])];
  if(analysisType==='squad'){
   const candidates=result.resumed?result.playerCandidates:[...(previous?.playerCandidates||[]),...(result.playerCandidates||[])];
   const checked=refineSquadRoster({...merged,playerCandidates:candidates||[]},evidence);merged=checked.data;
   result.errors=[...new Set([...result.errors,...checked.warnings])];
  }
  review={...result,...merged,readingSession:result.readingSession,resumed:result.resumed,previews:result.previews,frames:result.frames,errors:result.errors,elapsedMs:result.elapsedMs,reader,rosterEvidence:evidence,readingTeams:[...new Set([...(previous?.readingTeams||[]),...(result.readingTeams||[])])],coverage:coverage(analysisType,merged),slot:slotId};
  progress=review.coverage?.complete?'Leitura pronta para revisão.':'Leitura parcial. Revise campos NI e avisos antes de aplicar.';
 }
 catch(e){if(previous)review=previous;progress=e.message+' Dados existentes preservados.';}
 finally{clearTimeout(deadline);controller.abort();busy=false;render();}
}
async function applyReview(){
 if(!review||review.slot!==getSlot().id)throw Error('Leitura pertence a outro slot.');
 const parsed=readReview(review,root.querySelector('#reviewForm'));
 const destination=Number(root.querySelector('[name=reviewSlot]')?.value||review.slot);
 const slot=getState().slots.find(s=>s.id===destination);if(!slot)throw Error('Escolha um slot válido.');
 const next=applyReading(slot,review.type,parsed,{meta:{...review.meta,...parsed.meta},teams:review.readingTeams||[],conflicts:review.conflicts||[],username:getState().settings.username,mode:review.mode});
 updateSlot(s=>Object.assign(s,next),destination);setActiveSlot(destination);
 const target=review.type==='match'?'pregame':'info';review=null;go(target);toast('Leitura confirmada. Dados válidos preservados.');
}
async function importBackup(e){
 const f=e.target.files[0];if(!f)return;if(f.size>5*1024*1024)throw Error('Backup maior que 5 MB.');
 try{const next=migrate(JSON.parse(await f.text()));if(!confirm('Substituir os quatro slots pelos dados deste backup? Exporte os dados atuais antes.'))return;setState(next);review=null;render();toast('Backup importado.');}catch(error){toast(error.message);}
}
function download(blob,name){const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),2000);}
async function action(name){
 const [a,b,c]=name.split(':');
 if(a==='go')return go(b);
 if(a==='slot'){if(busy)return;setActiveSlot(+b);render();return;}
 if(a==='type'){analysisType=b;review=null;render();return;}
 if(a==='analyze')return runAnalysis();
 if(a==='retryAnalysis'){const draft=readReview(review,root.querySelector('#reviewForm'));if(review.type==='match'){review.match=draft;review._matchFieldSources=draft._fieldSources||review._matchFieldSources;}else if(review.type==='squad'){review.players=draft.players;review.playerCandidates=draft.playerCandidates||[];review.meta=draft.meta;}else{review.calendar=draft;review.meta=draft.meta;}return runAnalysis(true);}
 if(a==='cancel'){analysisController?.abort();progress='Cancelando operação atual…';const e=root.querySelector('.progress');if(e)e.textContent=progress;return;}
 if(a==='resolveConflict'){
  if(!review||review.type!=='match')return;
  const conflict=review.conflicts?.[Number(b)];if(!conflict||conflict.resolved||!conflict.field.startsWith('match.'))return;
  const field=conflict.field.slice(6);if(!matchFields.some(([key])=>key===field))return;
  const destination=root.querySelector('[name=reviewSlot]')?.value,parsed=readReview(review,root.querySelector('#reviewForm'));
  const value=c==='unknown'?'NI':c==='first'?conflict.first:conflict.second;
  parsed[field]=value;parsed._fieldSources[field]={kind:'manual',rank:4,source:'Escolhido na revisão'};review.match=parsed;review._matchFieldSources=parsed._fieldSources;
  for(const item of review.conflicts||[])if(item.field===conflict.field){item.resolved=true;item.preferred=value;item.resolutionSource='Escolhido na revisão';}
  review.coverage=coverage('match',review);render();const select=root.querySelector('[name=reviewSlot]');if(select&&destination)select.value=destination;return;
 }
 if(a==='applyReview')return applyReview();
 if(a==='discardReview'){review=null;render();return;}
 if(a==='aiTactic')return makeTactic(true);
 if(a==='localTactic')return makeTactic(false);
 if(a==='strong'){updateSlot(s=>{s.tactics=generateStrong433(s.match,getState().settings);s.tacticStale=false;});render();return;}
 if(a==='saveSliders'){const values={};for(const key of ['pressure','mentality','tempo'])values[key]=+root.querySelector('[name='+key+']').value;updateSlot(s=>{s.tactics=updateTacticSliders(s.tactics,values);});render();toast('Sliders salvos.');return;}
 if(a==='newMatch'){if(!confirm('Iniciar nova partida neste slot? Dados da partida e tática serão limpos. Elenco, calendário e histórico serão preservados.'))return;updateSlot(s=>{s.match={_schemaVersion:3,myName:s.myTeam||s.match.myName||'NI',human:null};s.tactics=null;s.tacticStale=false;});render();return;}
 if(a==='market'){updateSlot(s=>s.director.plan=buildMarketPlan(s.squad,s.director.cash,s.match.myStrength));render();toast('Recomendações atualizadas; suas anotações foram preservadas.');return;}
 if(a==='snapshot'){updateSlot(s=>{const nums=s.squad.players.map(p=>num(p.strength));s.director.snapshots.unshift({at:new Date().toISOString(),total:nums.length,strength:nums.length&&nums.every(n=>n!==null)?Math.round(nums.reduce((a,b)=>a+b,0)/nums.length):'NI'});s.director.snapshots=s.director.snapshots.slice(0,100);});render();return;}
 if(a==='deletePlayer'||a==='deleteCalendar'){if(!confirm('Remover este registro?'))return;updateSlot(s=>{if(a==='deletePlayer')s.squad.players=s.squad.players.filter(p=>p.id!==b);else s.calendar=s.calendar.filter(r=>r.id!==b);});render();return;}
 if(a==='deleteResult'){if(!confirm('Excluir este resultado do histórico?'))return;updateSlot(s=>{s.learning.matches=s.learning.matches.filter(r=>r.id!==c);s.learning.weights=sharedLearning({slots:[s]}).weights;},+b);render();return;}
 if(a==='export'){download(exportState(getState()),'osm-coach-backup-'+new Date().toISOString().slice(0,10)+'.json');toast('Backup exportado sem a chave de sessão.');return;}
 if(a==='cache'){for(const key of await caches.keys())if(key.startsWith('osm-coach'))await caches.delete(key);toast('Cache limpo. Dados dos slots preservados. Reabra online para renovar o cache.');return;}
 if(a==='checkApis'){checkingApis=true;render();try{const state=getState();const result=await apiRequest('check',{models:{groqVision:state.settings.visionModel,groqText:state.settings.model}},null,16000);apiChecks=result.results;render();toast(result.ok?'Teste concluído. Confira cada API abaixo.':'As APIs não responderam ao teste. Confira os detalhes abaixo.');}catch(e){apiChecks=[{provider:'Servidor do app',ok:false,message:e.message}];render();toast(e.message);}finally{checkingApis=false;render();toast('Teste concluído. Veja os resultados em IA e leitura visual.');}return;}
 if(a==='status'){api=await apiStatus();render();toast(api?.ok?'Servidor disponível. A chave e a cota só são verificadas durante uma consulta.':'Servidor indisponível. Modo local continua disponível.');return;}
 if(a==='forgetKey'){if(!confirm('Remover as chaves salvas neste aparelho?'))return;setSessionKey('');render();toast('Chaves removidas do aparelho.');return;}
 if(a==='notify'){toast('Solicitando permissão ao Android…');const permission=await requestNotifications();if(permission==='granted')setState(s=>{s.settings.notifications=true;return s;});render();toast(permission==='granted'?'Notificações permitidas. Avisos dependem do app aberto.':permission==='denied'?'Bloqueadas: libere notificações nas configurações do Android/Chrome para este app.':permission==='default'?'O pedido foi fechado sem autorização. Toque novamente para solicitar.':'Notificações não suportadas neste navegador.');return;}
 if(a==='install'){if(installed()){toast('Este app já está instalado.');return;}if(installPrompt){await installPrompt.prompt();const choice=await installPrompt.userChoice;installPrompt=null;if(choice.outcome==='accepted')installedKnown=true;render();toast(choice.outcome==='accepted'?'Instalação solicitada ao Android.':'Instalação cancelada.');}else toast('No Android: Chrome → menu ⋮ → Instalar app / Adicionar à tela inicial.');}
}
export function initApp(el){
 root=el;const requested=location.hash.slice(1);if(navs.some(([id])=>id===requested))tab=requested;
 root.addEventListener('click',e=>{const b=e.target.closest('[data-action]');if(!b)return;Promise.resolve(action(b.dataset.action)).catch(error=>toast(error.message));});
 root.addEventListener('submit',e=>{const form=e.target.closest('[data-form]');if(!form)return;e.preventDefault();Promise.resolve(submit(form)).catch(error=>toast(error.message));});
 window.addEventListener('beforeinstallprompt',e=>{e.preventDefault();installPrompt=e;if(!busy)render();});
 window.addEventListener('appinstalled',()=>{installedKnown=true;installPrompt=null;if(!busy)render();});
 matchMedia('(display-mode: standalone)').addEventListener?.('change',()=>{if(!busy)render();});
 navigator.getInstalledRelatedApps?.().then(apps=>{if(apps.some(a=>a.url===location.origin+'/manifest.webmanifest')){installedKnown=true;if(!busy)render();}}).catch(()=>{});
 root.addEventListener('change',e=>{const form=e.target.closest('[data-form=key]');if(!form)return;try{setSessionKey(data(form));const footer=root.querySelector('footer span:last-child');if(footer)footer.textContent=configuredProviders();toast('Chave salva neste aparelho.');}catch(error){toast(error.message);}});
 window.addEventListener('online',render);window.addEventListener('offline',render);
 render();apiStatus().then(r=>{api=r;const footer=root.querySelector('footer span:last-child');if(footer)footer.textContent=configuredProviders();});
 setInterval(()=>pollNotifications(getState()),15000);
}

import {matchFields,known,positions} from './domain.js';
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function shirt(color='#3279e6',number=''){return '<svg class="shirt" viewBox="0 0 80 90" aria-hidden="true"><path fill="'+color+'" stroke="#ffffff" stroke-width="3" d="M24 9 8 18 2 38 18 45 22 33 22 80 58 80 58 33 62 45 78 38 72 18 56 9Q40 22 24 9Z"/><path fill="#fff" opacity=".25" d="M36 18h8v62h-8z"/><text x="40" y="55" text-anchor="middle" fill="white" font-size="25" font-weight="bold">'+esc(number)+'</text></svg>';}
function control(path,label,value,type='text',options){
 const unknown=!known(value),attrs=' data-review-path="'+esc(path)+'" data-kind="'+type+'"';
 const current=value===true?'true':value===false?'false':known(value)?String(value):'';
 const html=options?'<select'+attrs+'><option value="">NI</option>'+options.map(item=>{const [v,l]=Array.isArray(item)?item:[item,item];return '<option value="'+esc(v)+'" '+(String(v)===current?'selected':'')+'>'+esc(l)+'</option>';}).join('')+'</select>':'<input'+attrs+' type="'+(type==='number'?'number':'text')+'" value="'+esc(current)+'" placeholder="NI" '+(type==='number'?'min="0"':'')+'>';
 return '<label class="'+(unknown?'review-ni':'')+'"><span>'+esc(label)+(unknown?' · NI':'')+'</span>'+html+'</label>';
}
const yn=[['true','Sim'],['false','Não']];
export function renderReview(review){
 let content='';
 if(review.type==='match'){
  content='<div class="match-banner"><div>'+shirt('#357bea','')+'<b>'+esc(review.match.myName||'Meu time · NI')+'</b><strong>'+esc(review.match.myStrength??'NI')+'</strong></div><span>VS</span><div>'+shirt('#ec6354','')+'<b>'+esc(review.match.rivalName||'Rival · NI')+'</b><strong>'+esc(review.match.rivalStrength??'NI')+'</strong></div></div><div class="form-grid">'+matchFields.map(([key,label,type])=>control('match.'+key,label,review.match[key],type==='number'?'number':type==='human'?'boolean':'text',type==='human'?[['true','Humano'],['false','CPU']]:Array.isArray(type)?type:undefined)).join('')+'</div>';
 }else if(review.type==='squad'){
  content='<div class="form-grid">'+[['team','Time'],['cash','Caixa'],['squadValue','Valor do elenco'],['strength','Força geral'],['GK','GOL'],['DEF','DEF'],['MID','MEI'],['ATT','ATA'],['expectedPlayers','Quantidade total visível']].map(([key,label])=>control('meta.'+key,label,review.meta?.[key],['team','cash','squadValue'].includes(key)?'text':'number')).join('')+'</div><div class="review-list">'+review.players.map((p,i)=>'<article class="player-review"><header>'+shirt(p.training===true?'#f6a42e':'#3479df',p.strength??'?')+'<div><b>'+esc(p.name)+'</b><span>'+esc(p.position)+' · '+(p.training===true?'Treinando':p.training===false?'Disponível':'Treino NI')+(p.forSale===true?' · À venda':'')+'</span></div></header><div class="form-grid">'+control('players.'+i+'.name','Nome',p.name)+control('players.'+i+'.position','Posição',p.position,'text',positions)+control('players.'+i+'.strength','Força',p.strength,'number')+control('players.'+i+'.age','Idade',p.age,'number')+control('players.'+i+'.value','Valor',p.value)+control('players.'+i+'.training','Treinando',p.training,'boolean',yn)+control('players.'+i+'.forSale','À venda',p.forSale,'boolean',yn)+'</div></article>').join('')+'</div>';
 }else{
  content='<div class="review-list">'+review.calendar.map((r,i)=>'<article class="calendar-review"><header><span class="round-badge">'+esc(r.round??'?')+'</span><div><b>'+esc(r.opponent)+'</b><span>'+esc(r.stage==='NI'?'':r.stage)+' · '+(r.home===true?'Casa':r.home===false?'Fora':'Local NI')+'</span></div><strong class="result-badge">'+esc(r.result)+'</strong></header><div class="form-grid">'+[['round','Rodada','number'],['opponent','Rival'],['nickname','Nickname'],['date','Data DD/MM/AAAA'],['time','Horário HH:MM'],['score','Placar (meu time primeiro)'],['displayedScore','Placar exibido (casa primeiro)'],['stage','Fase']].map(([key,label,type])=>control('calendar.'+i+'.'+key,label,r[key],type)).join('')+control('calendar.'+i+'.home','Local',r.home,'boolean',[['true','Casa'],['false','Fora']])+control('calendar.'+i+'.cup','Competição',r.cup,'boolean',[['true','Copa'],['false','Liga']])+control('calendar.'+i+'.result','Resultado',r.result,'text',['V','E','D'])+'</div></article>').join('')+'</div>';
 }
 if(review.type!=='match'&&!review[review.type==='squad'?'players':'calendar'].length)content+='<p class="warning">Nenhum registro reconhecido. Confira os avisos e tente completar a leitura.</p>';
 const conflicts=(review.conflicts||[]).map(c=>'<li><b>'+esc(c.field)+'</b>: '+esc(c.first)+' / '+esc(c.second)+'</li>').join('');
 return '<form id="reviewForm" onsubmit="return false">'+content+'</form>'+(conflicts?'<details open><summary>Valores divergentes: confira nas telas</summary><ul>'+conflicts+'</ul></details>':'');
}
export function readReview(review,container){
 const out={match:{...review.match},players:review.players.map(p=>({...p})),calendar:review.calendar.map(r=>({...r})),meta:{...review.meta}};
 for(const el of container.querySelectorAll('[data-review-path]')){const path=el.dataset.reviewPath.split('.');let target=out;for(const segment of path.slice(0,-1))target=target[segment];const raw=el.value.trim();target[path.at(-1)]=el.dataset.kind==='boolean'?raw==='true'?true:raw==='false'?false:null:el.dataset.kind==='number'?raw===''?null:Number(raw):raw||'NI';}
 return review.type==='match'?out.match:review.type==='squad'?{players:out.players,meta:out.meta}:out.calendar;
}

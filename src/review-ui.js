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
function calendarReviewRow(r,path){
 return '<article class="calendar-review"><header><span class="round-badge">'+esc(r.round??'?')+'</span><div><b>'+esc(r.opponent)+'</b><span>'+esc(r.stage==='NI'?'':r.stage)+' · '+(r.home===true?'Casa':r.home===false?'Fora':'Local NI')+'</span></div><strong class="result-badge">'+esc(r.result)+'</strong></header><div class="form-grid">'+[['round','Rodada','number'],['opponent','Rival'],['nickname','Nickname'],['date','Data DD/MM/AAAA'],['time','Horário HH:MM'],['score','Placar (meu time primeiro)'],['displayedScore','Placar exibido (casa primeiro)'],['stage','Fase']].map(([key,label,type])=>control(path+'.'+key,label,r[key],type)).join('')+control(path+'.home','Local',r.home,'boolean',[['true','Casa'],['false','Fora']])+control(path+'.cup','Competição',r.cup,'boolean',[['true','Copa'],['false','Liga']])+control(path+'.result','Resultado',r.result,'text',['V','E','D'])+'</div></article>';
}
function conflictItem(c){
 const key=c.field.split('.').at(-1),labels={home:'Local',cup:'Competição',opponent:'Rival',nickname:'Nickname',result:'Resultado',round:'Rodada',date:'Data',time:'Horário',score:'Placar',displayedScore:'Placar exibido',name:'Jogador',position:'Posição',strength:'Força',age:'Idade',value:'Valor',training:'Treino',forSale:'Venda',...Object.fromEntries(matchFields.map(([field,label])=>[field,label]))};
 const round=c.field.match(/round:(\d+)/)?.[1],date=c.field.match(/date:(\d{2}\/\d{2}\/\d{4})/)?.[1];
 const context=round?'Rodada '+round:date||'';
 const show=value=>key==='home'&&typeof value==='boolean'?(value?'Casa':'Fora'):key==='cup'&&typeof value==='boolean'?(value?'Copa':'Liga'):typeof value==='boolean'?(value?'Sim':'Não'):value;
 return '<li><b>'+esc((context?context+' · ':'')+(labels[key]||'Campo'))+'</b>: '+esc(show(c.first))+' / '+esc(show(c.second))+(c.resolved?' · Confirmado: '+esc(show(c.preferred)):'')+'</li>';
}
function playerReviewRow(p,path,pending=false){
 const include=pending?'<label><input type="checkbox" data-review-path="'+path+'._include" data-kind="include"> Confirmo este jogador nas telas e quero incluir</label>':'';
 return '<article class="player-review"><header>'+shirt(p.training===true?'#f6a42e':'#3479df',p.strength??'?')+'<div><b>'+esc(p.name)+'</b><span>'+esc(p.position)+' · '+(p.training===true?'Treinando':p.training===false?'Disponível':'Treino NI')+(p.forSale===true?' · À venda':'')+'</span></div></header>'+include+'<div class="form-grid">'+control(path+'.name','Nome',p.name)+control(path+'.position','Posição',p.position,'text',positions)+control(path+'.strength','Força',p.strength,'number')+control(path+'.age','Idade',p.age,'number')+control(path+'.value','Valor',p.value)+control(path+'.training','Treinando',p.training,'boolean',yn)+control(path+'.forSale','À venda',p.forSale,'boolean',yn)+'</div></article>';
}
export function renderReview(review){
 let content='';
 if(review.type==='match'){
  content='<div class="match-banner"><div>'+shirt('#357bea','')+'<b>'+esc(review.match.myName||'Meu time · NI')+'</b><strong>'+esc(review.match.myStrength??'NI')+'</strong></div><span>VS</span><div>'+shirt('#ec6354','')+'<b>'+esc(review.match.rivalName||'Rival · NI')+'</b><strong>'+esc(review.match.rivalStrength??'NI')+'</strong></div></div><div class="form-grid">'+matchFields.map(([key,label,type])=>control('match.'+key,label,review.match[key],type==='number'?'number':type==='human'?'boolean':'text',type==='human'?[['true','Humano'],['false','CPU']]:Array.isArray(type)?type:undefined)).join('')+'</div>';
 }else if(review.type==='squad'){
  content='<div class="form-grid">'+[['team','Time'],['cash','Caixa'],['squadValue','Valor do elenco'],['strength','Força geral'],['GK','GOL'],['DEF','DEF'],['MID','MEI'],['ATT','ATA'],['expectedPlayers','Quantidade total visível']].map(([key,label])=>control('meta.'+key,label,review.meta?.[key],['team','cash','squadValue'].includes(key)?'text':'number')).join('')+'</div><div class="review-list">'+review.players.map((p,i)=>playerReviewRow(p,'players.'+i)).join('')+'</div>';
  if(review.playerCandidates?.length)content+='<details class="reading-warnings" open><summary>Nomes sem linha comprovada ('+review.playerCandidates.length+')</summary><p>O OCR pode não ter lido nomes válidos. Estes nomes ficam fora da contagem e do elenco até você conferir e marcar para incluir.</p><div class="review-list">'+review.playerCandidates.map((p,i)=>playerReviewRow(p,'playerCandidates.'+i,true)).join('')+'</div></details>';
 }else{
  content='<div class="review-list">'+review.calendar.map((r,i)=>calendarReviewRow(r,'calendar.'+i)).join('')+'</div>';
  if(review.calendarFragments?.length)content+='<details class="reading-warnings"><summary>Trechos sem identificação do jogo ('+review.calendarFragments.length+')</summary><p>Esses trechos não entram na contagem de jogos. Complete a rodada, data ou horário comprovado para incluir ao confirmar.</p><div class="review-list">'+review.calendarFragments.map((r,i)=>calendarReviewRow(r,'calendarFragments.'+i)).join('')+'</div></details>';
 }
 if(review.type!=='match'&&!review[review.type==='squad'?'players':'calendar'].length)content+='<p class="warning">Nenhum registro reconhecido. Confira os avisos e tente completar a leitura.</p>';
 const conflicts=(review.conflicts||[]).filter(c=>!c.resolved).map(conflictItem).join(''),resolved=(review.conflicts||[]).filter(c=>c.resolved).map(conflictItem).join('');
 return '<form id="reviewForm" onsubmit="return false">'+content+'</form>'+(conflicts?'<details open><summary>Valores divergentes: confira nas telas</summary><ul>'+conflicts+'</ul></details>':'')+(resolved?'<details><summary>Diferenças resolvidas pela leitura comprovada</summary><ul>'+resolved+'</ul></details>':'');
}
export function readReview(review,container){
 const copy=row=>({...row,_fieldSources:{...row._fieldSources}});
 const out={match:{...review.match,_fieldSources:{...review._matchFieldSources}},players:review.players.map(copy),playerCandidates:(review.playerCandidates||[]).map(copy),calendar:review.calendar.map(copy),calendarFragments:(review.calendarFragments||[]).map(copy),meta:{...review.meta}};
 for(const el of container.querySelectorAll('[data-review-path]')){const path=el.dataset.reviewPath.split('.');let target=out;for(const segment of path.slice(0,-1))target=target[segment];const raw=el.value.trim(),key=path.at(-1),value=el.dataset.kind==='include'?el.checked===true:el.dataset.kind==='boolean'?raw==='true'?true:raw==='false'?false:null:el.dataset.kind==='number'?raw===''?null:Number(raw):raw||'NI';if(path[0]!=='meta'&&key!=='_include'&&known(value)&&!Object.is(target[key],value))target._fieldSources[key]={kind:'manual',rank:4,source:'Editado na revisão'};target[key]=value;}
 const included=out.playerCandidates.filter(p=>p._include===true).map(p=>{const row={...p,_fieldSources:{...p._fieldSources}};delete row._include;delete row._rosterPending;delete row._rosterReason;for(const key of ['name','position','strength','age','value','training','forSale'])if(known(row[key]))row._fieldSources[key]={kind:'manual',rank:4,source:'Confirmado na revisão'};return row;});
 return review.type==='match'?out.match:review.type==='squad'?{players:[...out.players,...included],playerCandidates:out.playerCandidates.filter(p=>p._include!==true),meta:out.meta}:[...out.calendar,...out.calendarFragments.filter(r=>['round','date','time'].some(key=>known(r[key])))];
}

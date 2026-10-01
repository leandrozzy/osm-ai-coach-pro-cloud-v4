'use strict';
/* OSM AI Coach Pro — Director + Roster Guard 3.4.0 */
(function(){
  const V='3.4.0';
  const TARGET={ATA:4,MEI:6,DEF:6,GOL:2};
  const esc=v=>String(v??'NI').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[m]));
  const norm=v=>String(v||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/\s+/g,' ').trim();
  const num=v=>{ if(v===null||v===undefined||v===''||v==='NI')return null; const n=Number(v); return Number.isFinite(n)?n:null; };
  const arr=v=>Array.isArray(v)?v:[];
  function pos(p){
    try{ if(typeof normalizePos==='function'&&typeof playerPosValue==='function')return normalizePos(playerPosValue(p)); }catch{}
    const x=String(p?.position||p?.pos||'').toUpperCase();
    return ['ATA','MEI','DEF','GOL'].includes(x)?x:null;
  }
  function name(p){ try{if(typeof playerNameValue==='function')return String(playerNameValue(p)||'').trim()}catch{} return String(p?.name||'').trim(); }
  function rating(p){ try{if(typeof playerRatingValue==='function')return num(playerRatingValue(p))}catch{} return num(p?.rating??p?.overall??p?.strength); }
  function age(p){ try{if(typeof playerAgeValue==='function')return num(playerAgeValue(p))}catch{} return num(p?.age); }
  function valueRaw(p){ try{if(typeof playerMoneyValue==='function')return playerMoneyValue(p)}catch{} return p?.value??p?.price??p?.marketValue??p?.market_value??null; }
  function moneyNumber(v){
    if(v===null||v===undefined||v==='')return null;
    if(typeof v==='number'&&Number.isFinite(v))return v;
    const s=String(v).toLowerCase().replace(/\s/g,'').replace(',','.');
    const m=s.match(/([0-9]+(?:\.[0-9]+)?)([kmb])?/i); if(!m)return null;
    let n=Number(m[1]); if(!Number.isFinite(n))return null;
    const u=m[2]; if(u==='k')n*=1e3; else if(u==='m')n*=1e6; else if(u==='b')n*=1e9;
    return n;
  }
  function fmtMoney(v){ const n=moneyNumber(v); if(n===null)return 'NI'; if(n>=1e9)return (n/1e9).toFixed(n>=1e10?0:1).replace('.',',')+'B'; if(n>=1e6)return (n/1e6).toFixed(n>=1e7?0:1).replace('.',',')+'M'; if(n>=1e3)return Math.round(n/1e3)+'K'; return Math.round(n).toLocaleString('pt-BR'); }
  const BAD_WORDS=/\b(reinos?|rondoghia|remo|would|onde|bonde|dine|toncogia|qeins|sma|temer|oukou|sina|o\s+so|sou\s+o|em\s+do)\b/i;
  function plausibleName(n){
    const s=String(n||'').replace(/\s+/g,' ').trim();
    if(s.length<3||s.length>42||BAD_WORDS.test(norm(s)))return false;
    const tokens=s.split(' ').filter(Boolean);
    if(tokens.length<1||tokens.length>5)return false;
    const one=tokens.filter(t=>t.replace(/[^A-Za-zÀ-ÿ]/g,'').length<=1).length;
    if(one>1)return false;
    if(!tokens.some(t=>t.replace(/[^A-Za-zÀ-ÿ]/g,'').length>=3))return false;
    return /^[A-Za-zÀ-ÿ'’.-]+(?: [A-Za-zÀ-ÿ'’.-]+){0,4}$/.test(s);
  }
  function validPlayer(p){
    const n=name(p),po=pos(p),r=rating(p),a=age(p);
    if(!plausibleName(n)||!['ATA','MEI','DEF','GOL'].includes(po))return false;
    if(r===null||r<40||r>200)return false;
    if(a!==null&&(a<15||a>45))return false;
    return true;
  }
  function sanitizeSlot(s){
    if(!s)return {before:0,after:0,rejected:[]};
    const before=arr(s.roster),good=[],rejected=[];
    const seen=new Set();
    for(const p of before){
      if(!validPlayer(p)){rejected.push(p);continue;}
      const k=norm(name(p)).replace(/[^a-z0-9]/g,'');
      if(!k||seen.has(k))continue;
      seen.add(k); good.push({...p,verifiedRoster:true});
    }
    if(rejected.length){
      s.rosterRejected=arr(s.rosterRejected).concat(rejected.map(p=>({...p,rejectedAt:new Date().toISOString(),reason:'linha OCR não validada'}))).slice(-120);
    }
    s.roster=good;
    if(s.myTeam){
      // Não sobrescreve a contagem real detectada anteriormente com lixo OCR.
      s.myTeam.validatedPlayerCount=good.length;
    }
    if(s.coachAI){
      s.coachAI.ownSnapshots=arr(s.coachAI.ownSnapshots).filter(x=>num(x?.overall)!==null&&num(x.overall)>0);
      s.coachAI.rivalSnapshots=arr(s.coachAI.rivalSnapshots).filter(x=>num(x?.overall)!==null&&num(x.overall)>0);
      s.coachAI.marketPlan=null;
    }
    return {before:before.length,after:good.length,rejected};
  }
  function currentSlot(){ try{return selectedSlot()}catch{return state?.slots?.[Number(state?.selectedSlot||1)-1]||null} }
  function coach34(s){
    s.coachAI34=s.coachAI34&&typeof s.coachAI34==='object'?s.coachAI34:{};
    s.coachAI34.budget=num(s.coachAI34.budget);
    s.coachAI34.marketOptions=arr(s.coachAI34.marketOptions).filter(o=>plausibleName(o?.name)&&['ATA','MEI','DEF','GOL'].includes(o?.position)&&num(o?.rating)!==null&&moneyNumber(o?.price)!==null);
    return s.coachAI34;
  }
  function validatedRows(s){ return arr(s?.roster).filter(validPlayer); }
  function buildPlan(s){
    const c=coach34(s),rows=validatedRows(s),by={ATA:[],MEI:[],DEF:[],GOL:[]};
    for(const p of rows)by[pos(p)].push(p);
    for(const k of Object.keys(by))by[k].sort((a,b)=>rating(b)-rating(a));
    const sell=[];
    for(const [k,target] of Object.entries(TARGET)){
      const list=by[k].filter(p=>!p.training);
      const excess=Math.max(0,list.length-target);
      [...list].sort((a,b)=>rating(a)-rating(b)).slice(0,excess).forEach(p=>sell.push(p));
    }
    const needs=Object.entries(TARGET).map(([k,target])=>{
      const list=by[k]; const weakest=list.length?list.at(-1):null;
      return {position:k,count:list.length,target,weakest:weakest?rating(weakest):null,priority:list.length<target?100+(target-list.length)*10:(weakest?Math.max(0,(num(s?.myTeam?.overall)||0)-rating(weakest)):0)};
    }).sort((a,b)=>b.priority-a.priority);
    const budget=c.budget;
    const options=c.marketOptions.map(o=>({...o,priceNum:moneyNumber(o.price)})).filter(o=>o.priceNum!==null&&o.priceNum<= (budget??-1));
    options.sort((a,b)=>{
      const na=needs.find(n=>n.position===a.position)?.priority||0, nb=needs.find(n=>n.position===b.position)?.priority||0;
      return (nb + num(b.rating)/10)-(na+num(a.rating)/10);
    });
    return {rows,by,sell:sell.slice(0,4),needs,budget,marketOptions:c.marketOptions,affordable:options};
  }
  function directorHtml(s){
    const p=buildPlan(s),c=coach34(s),known=p.rows.length,reported=num(s?.myTeam?.playerCount);
    const budgetKnown=p.budget!==null;
    const top=p.affordable[0]||null;
    return `<div class="card coach34-director">
      <div class="section-head compact-head"><div><span class="eyebrow">DIRETOR IA · DADOS VALIDADOS</span><h3>Comprar e vender sem inventar jogador</h3></div><span class="status">v${V}</span></div>
      <div class="coach30-kpis"><div><span>Jogadores validados</span><b>${known}${reported!==null?` / ${esc(reported)}`:''}</b></div><div><span>Orçamento</span><b>${budgetKnown?fmtMoney(p.budget):'NI'}</b></div><div><span>Opções reais do mercado</span><b>${p.marketOptions.length}</b></div><div><span>Vendas seguras</span><b>${p.sell.length}</b></div></div>
      <div class="field-edit" style="margin-top:12px"><label>Dinheiro disponível para compras<input id="coach34Budget" inputmode="decimal" placeholder="Ex.: 12,5M" value="${budgetKnown?esc(fmtMoney(p.budget)):''}"></label><button class="btn ghost" onclick="coach34SaveBudget()">Salvar orçamento</button></div>
      ${p.sell.length?`<div class="reason-box"><b>Vendas possíveis sem quebrar a estrutura 4/6/6/2</b>${p.sell.map(x=>`<p>${esc(name(x))} · ${esc(pos(x))} · força ${esc(rating(x))} · valor ${esc(fmtMoney(valueRaw(x)))}</p>`).join('')}</div>`:`<div class="reason-box"><b>Vendas</b><p>Não vou recomendar venda apenas por ser o mais fraco. Só libero venda automática quando há jogador excedente na posição e a linha foi validada.</p></div>`}
      ${!budgetKnown?`<div class="reason-box"><b>Compra bloqueada com segurança</b><p>Informe o dinheiro disponível. Sem orçamento, a IA não vai recomendar força ou jogador que você não consegue comprar.</p></div>`:
        top?`<div class="reason-box"><b>Melhor opção real registrada que cabe no orçamento</b><p>${esc(top.name)} · ${esc(top.position)} · força ${esc(top.rating)} · ${esc(fmtMoney(top.price))}</p></div>`:
        `<div class="reason-box"><b>Compra específica ainda não liberada</b><p>Não há opção real do mercado registrada que caiba em ${esc(fmtMoney(p.budget))}. O app não inventará jogador disponível.</p></div>`}
      <div class="actions"><button class="btn" onclick="coach34AddMarketOption()">+ Registrar opção real do mercado</button><button class="btn ghost" onclick="coach34ClearBadRoster()">Revalidar elenco</button></div>
    </div>`;
  }
  window.coach34SaveBudget=function(){
    const s=currentSlot(); if(!s)return; const raw=document.getElementById('coach34Budget')?.value||''; const n=moneyNumber(raw);
    if(n===null||n<0){toast?.('Informe um valor como 12,5M');return;} coach34(s).budget=n; save(); renderMarket?.(); toast?.('Orçamento salvo');
  };
  window.coach34AddMarketOption=function(){
    const s=currentSlot();if(!s)return;
    openModal(`<h2>Opção real da lista de transferências</h2><p class="small muted">Cadastre somente um jogador que realmente aparece no mercado do seu OSM. A IA não criará nomes.</p><div class="field-edit"><label>Nome<input id="m34Name"></label><label>Posição<select id="m34Pos"><option>ATA</option><option>MEI</option><option>DEF</option><option>GOL</option></select></label><div class="kpis"><label>Força<input id="m34Rating" type="number" min="40" max="200"></label><label>Preço<input id="m34Price" placeholder="Ex.: 8,7M"></label></div><button class="btn" onclick="coach34SaveMarketOption()">Salvar opção real</button></div>`);
  };
  window.coach34SaveMarketOption=function(){
    const s=currentSlot();if(!s)return; const n=document.getElementById('m34Name')?.value?.trim(),po=document.getElementById('m34Pos')?.value,r=num(document.getElementById('m34Rating')?.value),pr=document.getElementById('m34Price')?.value;
    if(!plausibleName(n)||!['ATA','MEI','DEF','GOL'].includes(po)||r===null||r<40||r>200||moneyNumber(pr)===null){toast?.('Preencha nome, posição, força e preço reais');return;}
    coach34(s).marketOptions.unshift({name:n,position:po,rating:r,price:pr,at:new Date().toISOString()}); coach34(s).marketOptions=coach34(s).marketOptions.slice(0,30); save(); closeModal?.(); renderMarket?.(); toast?.('Opção real registrada');
  };
  window.coach34ClearBadRoster=function(){const s=currentSlot();if(!s)return;const r=sanitizeSlot(s);save();renderMarket?.();renderDashboard?.();toast?.(`${r.after} jogador(es) validados; ${r.rejected.length} linha(s) descartada(s)`)};
  function save(){try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}}

  // Limpa dados contaminados do 3.3 uma vez por slot.
  try{for(const s of arr(state?.slots)){const c=coach34(s);if(!c.cleaned34){sanitizeSlot(s);c.cleaned34=true;c.cleanedAt=new Date().toISOString();}}save();}catch{}

  // Hoje: evita cartões idênticos acumularem em renders sucessivos.
  if(typeof renderDashboard==='function'){
    const base=renderDashboard;
    renderDashboard=function(){
      document.querySelectorAll('#coach30Dashboard').forEach(x=>x.remove());
      // Corrige snapshots antigos que transformaram NI em zero antes do Coach calcular o card.
      const s=currentSlot();if(s)sanitizeSlot(s);
      const out=base.apply(this,arguments);
      const nodes=[...document.querySelectorAll('#coach30Dashboard')];
      if(nodes.length>1)nodes.slice(0,-1).forEach(x=>x.remove());
      return out;
    }; window.renderDashboard=renderDashboard;
  }

  // Diretor: remove o card antigo que calculava compras com OCR contaminado e o bloco genérico de crescimento.
  if(typeof renderMarket==='function'){
    const base=renderMarket;
    renderMarket=function(){
      const s=currentSlot(); if(s)sanitizeSlot(s);
      const out=base.apply(this,arguments);
      const root=document.getElementById('marketContent');
      if(root&&s?.status==='active'){
        root.querySelectorAll('.coach30-market').forEach(x=>x.remove());
        [...root.querySelectorAll('.card')].forEach(card=>{const t=norm(card.textContent); if(t.includes('plano de crescimento do time')||t.includes('perfis de compra recomendados')||t.startsWith('plano ativo'))card.remove();});
        root.insertAdjacentHTML('afterbegin',directorHtml(s));
      }
      return out;
    }; window.renderMarket=renderMarket;
  }

  window.OSM_DIRECTOR_HOTFIX_34={version:V,sanitizeSlot,validPlayer,buildPlan};
  try{console.info('[OSM] Director/Roster Guard '+V+' ativo')}catch{}
})();

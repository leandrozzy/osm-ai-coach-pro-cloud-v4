'use strict';
/* OSM AI Coach Pro — Director Finance Hotfix 3.5.0 */
(function(){
  const V='3.5.0';
  const esc=v=>String(v??'NI').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[m]));
  function money(v){if(v===null||v===undefined||v==='')return null;if(typeof v==='number'&&Number.isFinite(v))return v;const s=String(v).toLowerCase().replace(/\s/g,'').replace(',','.');const m=s.match(/([0-9]+(?:\.[0-9]+)?)([kmb])?/i);if(!m)return null;let n=Number(m[1]);if(m[2]==='k')n*=1e3;else if(m[2]==='m')n*=1e6;else if(m[2]==='b')n*=1e9;return Number.isFinite(n)?n:null;}
  function fmt(v){const n=money(v);if(n===null)return'NI';if(n>=1e9)return(n/1e9).toFixed(1).replace('.',',')+'B';if(n>=1e6)return(n/1e6).toFixed(n>=1e8?0:1).replace('.',',')+'M';if(n>=1e3)return Math.round(n/1e3)+'K';return String(Math.round(n));}
  function slot(){try{return selectedSlot()}catch{return null}}
  function syncBudget(s){if(!s)return;s.coachAI34=s.coachAI34&&typeof s.coachAI34==='object'?s.coachAI34:{};const c=money(s?.myTeam?.cash);if(c!==null)s.coachAI34.budget=c;}
  if(typeof renderMarket==='function'){
    const base=renderMarket;
    renderMarket=function(){
      const s=slot();syncBudget(s);const out=base.apply(this,arguments);const root=document.getElementById('marketContent');if(!root||!s)return out;
      root.querySelectorAll('.finance35').forEach(x=>x.remove());
      const cash=money(s?.myTeam?.cash),sq=money(s?.myTeam?.squadValue),rows=Array.isArray(s.roster)?s.roster.filter(x=>x?.verifiedRoster):[];
      const saleValues=rows.map(p=>({name:p.name,value:money(p.value),position:p.position,rating:p.rating,training:p.training===true})).filter(x=>x.value!==null&&!x.training).sort((a,b)=>a.rating-b.rating);
      const candidates=saleValues.slice(0,4),saleTotal=candidates.reduce((a,x)=>a+x.value,0),projected=cash!==null?cash+saleTotal:null;
      const html=`<div class="card finance35" style="margin-top:12px"><span class="eyebrow">FINANÇAS REAIS · v${V}</span><h3>Capacidade de compra baseada no vídeo</h3><div class="coach30-kpis"><div><span>Caixa atual</span><b>${fmt(cash)}</b></div><div><span>Valor do elenco</span><b>${fmt(sq)}</b></div><div><span>Jogadores validados</span><b>${rows.length}</b></div><div><span>Caixa + até 4 vendas*</span><b>${fmt(projected)}</b></div></div><div class="reason-box"><b>Regra de compra</b><p>Sem ler a lista de transferências, o app não inventará um nome disponível. O teto de compra imediata é ${fmt(cash)}; após vendas reais confirmadas, o teto é recalculado.</p></div>${candidates.length?`<div class="reason-box"><b>Valores reais dos candidatos de menor força</b>${candidates.map(x=>`<p>${esc(x.name)} · ${esc(x.position)} · força ${esc(x.rating)} · valor ${fmt(x.value)}</p>`).join('')}</div>`:''}<p class="small muted">*Estimativa simples usando valores exibidos no elenco; a venda efetiva no OSM pode ocorrer por preço diferente e só entra no caixa quando concluída.</p></div>`;
      const old=root.querySelector('.coach34-director');if(old)old.insertAdjacentHTML('afterend',html);else root.insertAdjacentHTML('afterbegin',html);return out;
    };window.renderMarket=renderMarket;
  }
  try{for(const s of (state?.slots||[]))syncBudget(s);localStorage.setItem(STATE_KEY,JSON.stringify(state));}catch{}
  window.OSM_DIRECTOR_HOTFIX_35={version:V,syncBudget};
})();

'use strict';
/* OSM AI Coach Pro — Director Hotfix 3.6.0: só decide com elenco validado 3.6 */
(function(){
  const V='3.6.0',TARGET={ATA:4,MEI:6,DEF:6,GOL:2};
  const esc=v=>String(v??'NI').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[m]));
  const num=v=>{const x=Number(v);return Number.isFinite(x)?x:null};
  function money(v){if(v===null||v===undefined||v==='')return null;if(typeof v==='number'&&Number.isFinite(v))return v;const m=String(v).toLowerCase().replace(/\s/g,'').replace(',','.').match(/([0-9]+(?:\.[0-9]+)?)([kmb])?/i);if(!m)return null;let x=Number(m[1]);if(m[2]==='k')x*=1e3;else if(m[2]==='m')x*=1e6;else if(m[2]==='b')x*=1e9;return Number.isFinite(x)?x:null;}
  function fmt(v){const x=money(v);if(x===null)return'NI';if(x>=1e9)return(x/1e9).toFixed(1).replace('.',',')+'B';if(x>=1e6)return(x/1e6).toFixed(x>=1e8?0:1).replace('.',',')+'M';if(x>=1e3)return Math.round(x/1e3)+'K';return String(Math.round(x));}
  function current(){try{return selectedSlot()}catch{return null}}
  function rows(s){return Array.isArray(s?.roster)?s.roster.filter(p=>p?.verifiedRoster&&['ATA','MEI','DEF','GOL'].includes(p?.position)&&num(p?.rating)!==null&&money(p?.value)!==null):[]}
  function build(s){
    const r=rows(s),by={ATA:[],MEI:[],DEF:[],GOL:[]};for(const p of r)by[p.position].push(p);for(const k in by)by[k].sort((a,b)=>a.rating-b.rating);
    const sell=[];for(const [k,t] of Object.entries(TARGET)){const list=by[k].filter(p=>!p.training);const excess=Math.max(0,list.length-t);list.slice(0,excess).forEach(p=>sell.push(p));}
    sell.sort((a,b)=>(a.forSale===true?-1:0)-(b.forSale===true?-1:0)||a.rating-b.rating);
    const cash=money(s?.myTeam?.cash),sq=money(s?.myTeam?.squadValue),saleRef=sell.slice(0,4).reduce((a,p)=>a+(money(p.value)||0),0);
    const weakest={};for(const k in by)weakest[k]=by[k][0]||null;
    return {r,by,sell:sell.slice(0,4),cash,sq,saleRef,projected:cash===null?null:cash+saleRef,weakest};
  }
  function html(s){
    const p=build(s),v=s?.rosterValidation36;
    if(!v?.ok)return `<div class="card director36"><span class="eyebrow">DIRETOR IA · v${V}</span><h3>Plano bloqueado até validar o elenco</h3><p class="small muted">Os dados atuais vieram de uma leitura incompleta/antiga. Reanalise o vídeo do elenco. Enquanto a validação não fechar, o Diretor não vai sugerir compras ou vendas com dados errados.</p><div class="actions"><button class="btn" onclick="showView('analyze');setAnalysisMode('market')">Analisar elenco</button></div></div>`;
    const counts={ATA:p.by.ATA.length,MEI:p.by.MEI.length,DEF:p.by.DEF.length,GOL:p.by.GOL.length};
    const sellHtml=p.sell.length?p.sell.map(x=>`<p>${x.forSale?'↗ Já está à venda · ':''}<b>${esc(x.name)}</b> · ${esc(x.position)} · força ${esc(x.rating)} · valor ${esc(x.value)}</p>`).join(''):'<p>Nenhuma venda é necessária para reduzir excesso de posição.</p>';
    const upgrades=Object.keys(TARGET).map(k=>{const w=p.weakest[k];return w?`${k}: menor força ${w.rating} (${esc(w.name)})`:`${k}: sem jogador validado`;}).join(' · ');
    return `<div class="card director36"><div class="section-head compact-head"><div><span class="eyebrow">DIRETOR IA · DADOS REAIS · v${V}</span><h3>Plano baseado no vídeo validado</h3></div></div>
      <div class="coach30-kpis"><div><span>Caixa atual</span><b>${fmt(p.cash)}</b></div><div><span>Valor do elenco</span><b>${fmt(p.sq)}</b></div><div><span>Elenco</span><b>${p.r.length}</b></div><div><span>ATA/MEI/DEF/GOL</span><b>${counts.ATA}/${counts.MEI}/${counts.DEF}/${counts.GOL}</b></div></div>
      <div class="reason-box"><b>Venda estrutural</b>${sellHtml}${p.sell.length?`<p class="small muted">Referência de caixa após essas vendas: ${fmt(p.projected)}. O preço real recebido depende da venda no OSM.</p>`:''}</div>
      <div class="reason-box"><b>Onde o elenco está mais fraco</b><p>${upgrades}</p><p class="small muted">Sem ler a lista de transferências, o app não inventa jogador disponível. O teto imediato é ${fmt(p.cash)}${p.sell.length?`; referência após vendas: ${fmt(p.projected)}`:''}.</p></div>
      <div class="actions"><button class="btn" onclick="showView('analyze');setAnalysisMode('market')">Atualizar elenco</button></div>
    </div>`;
  }
  if(typeof renderMarket==='function'){
    const base=renderMarket;renderMarket=function(){const out=base.apply(this,arguments),s=current(),root=document.getElementById('marketContent');if(!root||!s)return out;
      root.querySelectorAll('.director36,.coach34-director,.finance35').forEach(x=>x.remove());
      // remove blocos antigos que davam meta/compra com elenco contaminado
      [...root.querySelectorAll('.card')].forEach(c=>{const t=String(c.textContent||'').toLowerCase();if(t.includes('plano 72h')||t.includes('subir força rapidamente')||t.includes('capacidade de compra baseada no vídeo'))c.remove();});
      root.insertAdjacentHTML('afterbegin',html(s));return out;};window.renderMarket=renderMarket;
  }
  window.OSM_DIRECTOR_HOTFIX_36={version:V,build};
  try{console.info('[OSM] Director '+V+' ativo')}catch{}
})();

const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const arrow=path=>'<path class="play-arrow" d="'+path+'"/>';
export function renderTacticVisual(formation,style){
 const rows=String(formation||'').match(/^([3-5])-([1-5])-([1-5])(?:-([1-5]))?\b/);
 const counts=rows?rows.slice(1).filter(Boolean).map(Number):[];
 const players=counts.reduce((a,b)=>a+b,0)===10?[[80,193],...counts.flatMap((count,row)=>Array.from({length:count},(_,i)=>[20+(i+1)*120/(count+1),168-row*135/Math.max(1,counts.length-1)]))]:[];
 const paths={
  'Jogar pelas alas':['M30 151 L25 74 L34 42','M130 151 L135 74 L126 42','M34 42 Q59 39 78 27','M126 42 Q101 39 82 27'],
  'Jogo de passes':['M45 139 L81 117','M81 117 L111 94','M111 94 L56 74','M56 74 L82 48'],
  'Contra-ataque':['M51 171 L65 128 L52 86 L73 36','M112 161 L107 107 L93 52'],
  'Remate à vista':['M43 78 L76 15','M80 81 L80 15','M117 78 L84 15'],
  'Bola longa':['M64 178 Q18 86 73 34','M98 173 Q139 83 88 35']
 };
 return '<figure class="tactic-visual"><svg viewBox="0 0 160 216" role="img" aria-label="'+esc(formation+' · '+style)+'"><title>'+esc(formation+' · '+style)+'</title><defs><marker id="play-arrow-head" markerWidth="5" markerHeight="5" refX="4" refY="2.5" orient="auto"><path d="M0 0L5 2.5L0 5Z" fill="#fff1a7"/></marker></defs><rect x="1" y="1" width="158" height="214" rx="9" fill="#174d3f"/><g fill="none" stroke="#ffffff" stroke-opacity=".45"><rect x="14" y="12" width="132" height="192"/><path d="M14 108H146M50 12V44H110V12M50 204V172H110V204M66 12V24H94V12M66 204V192H94V204"/><circle cx="80" cy="108" r="23"/></g><g fill="none" stroke="#fff1a7" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round" marker-end="url(#play-arrow-head)">'+(paths[style]||[]).map(arrow).join('')+'</g><g fill="#b7e957" stroke="#16482e" stroke-width="2">'+players.map(([x,y])=>'<circle class="formation-player" cx="'+x+'" cy="'+y+'" r="5"/>').join('')+'</g></svg><figcaption>'+esc(style)+'</figcaption></figure>';
}

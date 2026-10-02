import {uid} from './utils.js';
import {scoreOutcome} from './domain.js';
export function recordResult(slot,result){
 const outcome=scoreOutcome(result.score);if(!outcome)throw Error('Informe o placar do seu time primeiro, por exemplo 2x1.');
 const rec={id:uid(),at:new Date().toISOString(),tactic:structuredClone(slot.tactics),context:structuredClone(slot.match),...result,outcome};
 slot.learning.matches.unshift(rec);slot.learning.matches=slot.learning.matches.slice(0,300);
 const key=rec.tactic?.formation||'NI';const w=slot.learning.weights[key]||{games:0,wins:0,draws:0,losses:0};
 w.games++;w[outcome==='V'?'wins':outcome==='E'?'draws':'losses']++;w.weight=Number(((w.wins-w.losses)/(w.games+4)).toFixed(3));slot.learning.weights[key]=w;
 const row=slot.calendar.find(r=>r.id===result.calendarId);if(row){row.result=outcome;row.score=result.score;}
 return rec;
}


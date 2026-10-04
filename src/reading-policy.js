import {known} from './domain.js';
import {coverage} from './extraction.js';

// Video inference is useful for missing essentials, not a second request to
// reconfirm every optional field or the end of an already legible list.
export function videoRequired(type,data){
 const quality=coverage(type,data);
 if(type==='match')return ['myName','rivalName','myStrength','rivalStrength','location','referee','rivalMarking'].some(field=>!(field==='rivalMarking'&&quality.hidden.includes('Marcação rival'))&&(!known(data.match?.[field])||field==='rivalMarking'&&(data._matchFieldSources?.rivalMarking?.rank||0)<2||(data.conflicts||[]).some(conflict=>conflict.field==='match.'+field&&!conflict.resolved)));
 return quality.count===0||(quality.filledFields/Math.max(1,quality.requiredFields)<.55);
}

// Read the small tactical controls early, while OCR still has a useful budget.
// Layout selects a screen to inspect; it never supplies the marking value.
export function prioritizeReadingFrames(frames,type,data){
 if(type!=='match'||!coverage(type,data).missing.some(label=>label.startsWith('Marcação rival'))&&!(data.conflicts||[]).some(conflict=>conflict.field==='match.rivalMarking'&&!conflict.resolved))return [...frames];
 const priority={'report-details':0,'roster-header':1,'comparison':2,'report-field':3,'report-cover':4};
 return frames.map((frame,index)=>({frame,index})).sort((a,b)=>(priority[a.frame.layout?.kind]??5)-(priority[b.frame.layout?.kind]??5)||a.index-b.index).map(item=>item.frame);
}
export function disableFailedProvider(disabled,failure){
 const f=failure;
 if(f.provider==='groq'&&(['NO_SUPPORTED_MODEL','GROQ_INVALID_JSON','GROQ_INPUT_SPLIT','GROQ_OUTPUT_SPLIT','GROQ_INPUT_BUDGET'].includes(f.code)||f.status===404||f.status===413||(f.status===400&&/json/i.test(f.error||''))))disabled.add(f.stage==='text'?'groq-text':'groq-visual');
 else if([401,403,404,429,503].includes(f.status)||['PROVIDER_BUSY','PROVIDER_COOLDOWN','PROVIDER_TIMEOUT'].includes(f.code))disabled.add(f.provider);
}

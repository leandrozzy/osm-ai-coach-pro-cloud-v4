import {rowTime,known,pending} from './domain.js';
const sent=new Set();
export async function requestNotifications(){if(!('Notification' in window))return 'unsupported';return Notification.requestPermission();}
export async function notify(title,body,tag){
 if(!('Notification' in window)||Notification.permission!=='granted')return;
 const registration=await navigator.serviceWorker?.getRegistration();
 if(registration)await registration.showNotification(title,{body,tag,icon:'/assets/icon-192.png',data:{url:'/'}});else new Notification(title,{body,tag});
}
export function pollNotifications(state){
 if(!state.settings.notifications)return;
 const slots=[...state.slots].sort((a,b)=>(b.competitionType==='Batalha')-(a.competitionType==='Batalha'));
 for(const slot of slots){
  for(const row of slot.calendar){
   if(known(row.result)||known(row.score))continue;
   const time=rowTime(row);if(time===null)continue;const left=(time-Date.now())/60000;
   for(const minutes of [20,10]){const key=slot.id+'|'+row.id+'|'+time+'|'+minutes;if(left>minutes-1&&left<=minutes&&!sent.has(key)){sent.add(key);notify('S'+slot.id+': jogo em '+minutes+' min',(row.opponent||'NI')+' • '+(slot.competitionType==='Batalha'?'Batalha • ':'')+pending(slot).map(p=>p.label).join(', '),key).catch(()=>{});}}
  }
 }
}


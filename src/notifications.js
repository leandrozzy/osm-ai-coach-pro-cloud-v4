export async function requestNotifications(){if(!('Notification'in window))return 'unsupported';return Notification.requestPermission();}
export function notify(title,body){if(Notification.permission==='granted')new Notification(title,{body,icon:'/assets/icon-192.png'});}
export function scheduleLocal(slot){const rows=slot.calendar||[];const now=Date.now();for(const r of rows){if(!r.date||!r.time)continue;const [d,m,y]=r.date.split('/').map(Number);const [hh,mm]=r.time.split(':').map(Number);const t=new Date(y,m-1,d,hh,mm).getTime();for(const mins of [20,10]){const delay=t-now-mins*60000;if(delay>0&&delay<2147483647)setTimeout(()=>notify(`S${slot.id}: jogo em ${mins} min`,`${r.opponent||'Adversário'} • ${r.home?'Casa':'Fora'}`),delay);}}
}

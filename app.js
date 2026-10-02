function showBootError(error){
 const root=document.querySelector('#app');root.replaceChildren();
 const main=document.createElement('main'),heading=document.createElement('h1'),message=document.createElement('p'),button=document.createElement('button');
 heading.textContent='Não foi possível abrir o app';message.textContent=String(error?.message||error);button.textContent='Exportar dados brutos para recuperação';
 button.onclick=()=>{const blob=new Blob([localStorage.getItem('osm-ai-coach-pro:v1')||'{}'],{type:'application/json'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='osm-recuperacao.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
 main.append(heading,message,button);root.append(main);
}
import('./src/ui.js').then(({initApp})=>initApp(document.querySelector('#app'))).catch(showBootError);
if('serviceWorker' in navigator)window.addEventListener('load',()=>navigator.serviceWorker.register('/sw.js').catch(()=>{}));


import { initApp } from './src/ui.js';

function showBootError(error) {
  console.error('Falha ao iniciar OSM AI Coach Pro', error);
  const root = document.querySelector('#app');
  if (root) root.innerHTML = `<main style="padding:20px;font-family:system-ui"><h2>Falha ao iniciar o app</h2><p>Atualize a página. Se continuar, limpe apenas o cache deste site e abra novamente.</p><pre style="white-space:pre-wrap">${String(error?.message || error)}</pre></main>`;
}

window.addEventListener('error', e => showBootError(e.error || e.message));
window.addEventListener('unhandledrejection', e => showBootError(e.reason));

try {
  initApp(document.querySelector('#app'));
  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js').catch(console.warn));
  }
} catch (error) {
  showBootError(error);
}

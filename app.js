import './styles.css';
import { initApp } from './src/ui.js';

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js').catch(console.warn));
}

initApp(document.querySelector('#app'));

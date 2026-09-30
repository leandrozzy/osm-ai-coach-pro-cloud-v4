
(() => {
  const CFG_KEY = 'osm_push_firebase_config_v1';
  const VAPID_KEY = 'osm_push_vapid_v1';
  const TOKEN_KEY = 'osm_push_fcm_token_v1';

  const $ = id => document.getElementById(id);
  const status = (msg, kind='warn') => {
    const el = $('status');
    el.textContent = msg;
    el.className = 'status ' + kind;
  };

  function parseFirebaseConfig(raw) {
    raw = (raw || '').trim();
    if (!raw) throw new Error('Cole a configuração firebaseConfig.');
    let objText = raw;

    const first = raw.indexOf('{');
    const last = raw.lastIndexOf('}');
    if (first >= 0 && last > first) objText = raw.slice(first, last + 1);

    try { return JSON.parse(objText); } catch (_) {}

    try {
      return Function('"use strict"; return (' + objText + ');')();
    } catch (e) {
      throw new Error('Não consegui ler o firebaseConfig. Cole o bloco mostrado pelo Firebase.');
    }
  }

  async function ensureSW() {
    if (!('serviceWorker' in navigator)) throw new Error('Service Worker não suportado neste navegador.');

    // Registra/atualiza o worker da raiz.
    let reg = await navigator.serviceWorker.register('/sw.js', { scope: '/' });
    try { await reg.update(); } catch (_) {}

    // Se houver uma versão aguardando, mande ativar imediatamente.
    if (reg.waiting) {
      try { reg.waiting.postMessage({ type: 'SKIP_WAITING' }); } catch (_) {}
    }

    // Aguarda existir um Service Worker realmente ATIVO.
    reg = await Promise.race([
      navigator.serviceWorker.ready,
      new Promise((_, reject) =>
        setTimeout(() => reject(new Error('O Service Worker não ficou ativo. Recarregue a página e tente novamente.')), 15000)
      )
    ]);

    if (!reg.active) {
      throw new Error('Service Worker registrado, mas ainda não está ativo. Recarregue a página e tente novamente.');
    }

    return reg;
  }

  function loadSaved() {
    $('firebaseConfig').value = localStorage.getItem(CFG_KEY) || '';
    $('vapid').value = localStorage.getItem(VAPID_KEY) || $('vapid').value;
    const token = localStorage.getItem(TOKEN_KEY);
    if (token) {
      $('copyTokenBtn').disabled = false;
      $('testBtn').disabled = false;
      $('testClosedBtn').disabled = false;
      status('✅ Este navegador já possui token FCM salvo.\nToque em "Ativar notificações Push" para validar/renovar.', 'ok');
    }
  }

  $('saveBtn').onclick = () => {
    try {
      const cfg = parseFirebaseConfig($('firebaseConfig').value);
      for (const required of ['apiKey','projectId','messagingSenderId','appId']) {
        if (!cfg[required]) throw new Error('firebaseConfig sem o campo ' + required);
      }
      localStorage.setItem(CFG_KEY, JSON.stringify(cfg));
      localStorage.setItem(VAPID_KEY, $('vapid').value.trim());
      $('firebaseConfig').value = JSON.stringify(cfg, null, 2);
      status('✅ Configuração Firebase salva neste aparelho.', 'ok');
    } catch (e) {
      status('❌ ' + e.message, 'bad');
    }
  };

  $('activateBtn').onclick = async () => {
    try {
      if (!('Notification' in window)) throw new Error('Notificações não são suportadas.');
      const cfg = parseFirebaseConfig(localStorage.getItem(CFG_KEY) || $('firebaseConfig').value);
      const vapid = (localStorage.getItem(VAPID_KEY) || $('vapid').value).trim();
      if (!vapid) throw new Error('VAPID vazia.');

      localStorage.setItem(CFG_KEY, JSON.stringify(cfg));
      localStorage.setItem(VAPID_KEY, vapid);

      const permission = await Notification.requestPermission();
      if (permission !== 'granted') throw new Error('Permissão de notificações não concedida.');

      const reg = await ensureSW();

      let app;
      if (!firebase.apps.length) app = firebase.initializeApp(cfg);
      else app = firebase.app();

      const messaging = firebase.messaging(app);
      const token = await messaging.getToken({
        vapidKey: vapid,
        serviceWorkerRegistration: reg
      });

      if (!token) throw new Error('O Firebase não retornou token FCM.');

      localStorage.setItem(TOKEN_KEY, token);
      $('copyTokenBtn').disabled = false;
      $('testBtn').disabled = false;
      $('testClosedBtn').disabled = false;
      status('✅ PUSH ATIVO NESTE DISPOSITIVO\n\nToken FCM criado com sucesso.\nO Service Worker está registrado e pode receber push em segundo plano.', 'ok');
    } catch (e) {
      console.error(e);
      status('❌ ' + (e?.message || e), 'bad');
    }
  };

  $('copyTokenBtn').onclick = async () => {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) return status('Token ainda não existe.', 'bad');
    await navigator.clipboard.writeText(token);
    status('✅ Token FCM copiado.', 'ok');
  };

  async function sendTest(delaySeconds) {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) return status('Ative o Push primeiro.', 'bad');

    status(delaySeconds ? `⏳ Push programado para daqui a ${delaySeconds}s. Feche esta aba agora.` : '⏳ Enviando push...', 'warn');

    try {
      const res = await fetch('/api/push-test', {
        method: 'POST',
        headers: {'Content-Type':'application/json'},
        body: JSON.stringify({ token, delaySeconds })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || `Erro HTTP ${res.status}`);
      status(delaySeconds ? `✅ Servidor aceitou o teste de ${delaySeconds}s.` : '✅ Push solicitado ao Firebase.', 'ok');
    } catch (e) {
      status('❌ ' + e.message + '\n\nSe aparecer FIREBASE_SERVICE_ACCOUNT_JSON, falta configurar a credencial no Vercel.', 'bad');
    }
  }

  $('testBtn').onclick = () => sendTest(0);
  $('testClosedBtn').onclick = () => sendTest(15);

  loadSaved();
})();

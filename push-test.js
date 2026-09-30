
const admin = require('firebase-admin');

function getAdminApp() {
  if (admin.apps.length) return admin.app();

  const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  if (!raw) {
    throw new Error('FIREBASE_SERVICE_ACCOUNT_JSON não configurado no Vercel.');
  }

  let serviceAccount;
  try {
    serviceAccount = JSON.parse(raw);
  } catch (_) {
    throw new Error('FIREBASE_SERVICE_ACCOUNT_JSON não contém JSON válido.');
  }

  return admin.initializeApp({
    credential: admin.credential.cert(serviceAccount)
  });
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

module.exports = async function handler(req, res) {
  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Use POST.' });
  }

  try {
    const { token, delaySeconds = 0 } = req.body || {};
    if (!token || typeof token !== 'string') {
      return res.status(400).json({ error: 'Token FCM ausente.' });
    }

    const delay = Math.max(0, Math.min(20, Number(delaySeconds) || 0));
    if (delay) await sleep(delay * 1000);

    getAdminApp();

    const message = {
      token,
      data: {
        title: '🤖 OSM AI Coach',
        body: delay ? 'Push com o app fechado funcionando.' : 'Push configurado corretamente.',
        url: '/',
        tag: 'osm-push-test'
      },
      android: {
        priority: 'high'
      },
      webpush: {
        headers: { Urgency: 'high' }
      }
    };

    const id = await admin.messaging().send(message);
    return res.status(200).json({ ok: true, id });
  } catch (e) {
    console.error('push-test', e);
    return res.status(500).json({ error: e?.message || 'Falha ao enviar push.' });
  }
};

module.exports.config = { maxDuration: 30 };

import {HeatingController} from '../lib/controller.mjs';
import {AppError} from '../lib/parsers.mjs';
import {UpstreamClient, safeUpstreamUrl} from '../lib/upstream.mjs';

const pending = new Map();
let nextRequest = 0;
const bridge = globalThis.window?.MobileBridge;
const MAX_RESPONSE = 5_000_000;

function aborted() {
  return new DOMException('Zahtjev je prekinut.', 'AbortError');
}

// Android performs HTTPS in its own process. The local screen receives text
// only; remote HTML is parsed by the same controller used on Windows.
export function nativeFetch(input, {method = 'GET', headers = {}, signal} = {}) {
  if (!bridge || typeof bridge.request !== 'function') return Promise.reject(new Error('Android veza nije dostupna.'));
  let url;
  try {
    url = safeUpstreamUrl(String(input));
    if (method !== 'GET') throw new Error('Nepodržana metoda zahtjeva.');
  } catch (error) { return Promise.reject(error); }
  if (signal?.aborted) return Promise.reject(aborted());
  return new Promise((resolve, reject) => {
    const id = String(++nextRequest);
    let timer;
    const finish = (error, response) => {
      if (!pending.has(id)) return;
      pending.delete(id);
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
      error ? reject(error) : resolve(response);
    };
    const cancelNative = () => {
      try { if (typeof bridge.cancel === 'function') bridge.cancel(id); } catch {}
    };
    const onAbort = () => { cancelNative(); finish(aborted()); };
    pending.set(id, finish);
    signal?.addEventListener('abort', onAbort, {once: true});
    timer = setTimeout(() => { cancelNative(); finish(new DOMException('Veza je istekla.', 'TimeoutError')); }, 25_000);
    const requestHeaders = {};
    for (const [name, value] of Object.entries(headers)) {
      if (!['accept', 'user-agent', 'cookie'].includes(name.toLowerCase()) || typeof value !== 'string' || /[\r\n]/.test(value)) {
        finish(new Error('Neispravno zaglavlje zahtjeva.'));
        return;
      }
      requestHeaders[name.toLowerCase()] = value;
    }
    try { bridge.request(JSON.stringify({id, url: url.href, headers: requestHeaders})); }
    catch { finish(new Error('Android nije uspio pokrenuti vezu sa sustavom.')); }
  });
}

function receiveNativeResponse(id, result) {
  if (typeof id !== 'string') return;
  const finish = pending.get(id);
  if (!finish) return;
  if (result?.error) {
    finish(new Error(typeof result.error === 'string' ? result.error.slice(0, 400) : 'Veza sa sustavom nije uspjela.'));
    return;
  }
  if (!result || !Number.isInteger(result.status) || result.status < 100 || result.status > 599 ||
      typeof result.body !== 'string' || result.body.length > MAX_RESPONSE || !Array.isArray(result.headers) ||
      !result.headers.every(pair => Array.isArray(pair) && pair.length === 2 && pair.every(value => typeof value === 'string'))) {
    finish(new Error('Android je vratio neispravan odgovor sustava.'));
    return;
  }
  const responseHeaders = result.headers.map(([name, value]) => [name.toLowerCase(), value]);
  finish(null, {
    status: result.status,
    ok: result.status >= 200 && result.status < 300,
    headers: {
      get(name) { return responseHeaders.find(([key]) => key === String(name).toLowerCase())?.[1] ?? null; },
      getSetCookie() { return responseHeaders.filter(([key]) => key === 'set-cookie').map(([, value]) => value); }
    },
    text: async () => result.body
  });
}

function allowedExternal(value) {
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password || url.port) return false;
    if (url.origin === 'https://automatiziraj.se') return url.pathname === '/centralno/php/novo.php' && !url.search;
    return url.origin === 'https://github.com' &&
      (url.pathname === '/leo25matko-boop/termo' || url.pathname.startsWith('/leo25matko-boop/termo/'));
  } catch { return false; }
}

function installAndroid() {
  if (!bridge || typeof bridge.request !== 'function') return false;
  document.documentElement.classList.add('android-app');
  window.__termodomNativeResponse = receiveNativeResponse;
  // Older Android System WebView releases lack AbortSignal.timeout.
  if (!AbortSignal.timeout) AbortSignal.timeout = duration => {
    const controller = new AbortController();
    setTimeout(() => controller.abort(), duration);
    return controller.signal;
  };
  const controller = new HeatingController({upstream: new UpstreamClient({fetchImpl: nativeFetch})});
  window.termodom = Object.freeze({
    native: true,
    platform: 'android',
    async request(requestPath, body) {
      try {
        if (typeof requestPath !== 'string' || !/^\/api\/[a-z]+(?:\/[a-z0-9-]+)?$/.test(requestPath)) {
          throw new AppError('Neispravna funkcija aplikacije.', 400);
        }
        const isWrite = body !== undefined;
        if (isWrite && (!body || typeof body !== 'object' || Array.isArray(body) || JSON.stringify(body).length > 16_384)) {
          throw new AppError('Neispravni ili preveliki podaci zahtjeva.', 400);
        }
        let data;
        if (!isWrite) {
          if (requestPath === '/api/health') data = {app: 'termodom', ok: true, native: true, platform: 'android'};
          else if (requestPath === '/api/session') data = {app: 'termodom', token: 'android'};
          else if (requestPath === '/api/status') data = await controller.status();
          else if (requestPath === '/api/settings') data = await controller.settings();
          else if (requestPath === '/api/catalog') data = controller.catalog();
          else if (/^\/api\/schedule\/(kat|prizemlje)$/.test(requestPath)) data = await controller.schedule(requestPath.split('/').at(-1));
          else if (/^\/api\/chart\/[a-z0-9-]+$/.test(requestPath)) data = await controller.chart(requestPath.split('/').at(-1));
          else throw new AppError('Funkcija aplikacije nije pronađena.', 404);
        } else {
          if (/^\/api\/schedule\/(kat|prizemlje)$/.test(requestPath)) data = await controller.saveSchedule(requestPath.split('/').at(-1), body.values);
          else if (requestPath === '/api/settings') data = await controller.saveSettings(body.values);
          else if (requestPath === '/api/logout') data = await controller.logout();
          else if (requestPath === '/api/shutdown') {
            data = {ok: true};
            setTimeout(() => bridge.closeApp(), 100);
          } else throw new AppError('Funkcija aplikacije nije pronađena.', 404);
        }
        return {status: 200, body: data};
      } catch (error) {
        return {
          status: error instanceof AppError ? error.status : 500,
          body: {error: error instanceof AppError ? error.message : 'Lokalna aplikacija naišla je na pogrešku.', ...(error instanceof AppError ? error.details : {})}
        };
      }
    }
  });
  document.addEventListener('click', event => {
    const anchor = event.target.closest?.('a[href]');
    // A hash link resolves to the HTTPS asset origin on Android. Let the app's
    // normal navigation handle it before considering external browser links.
    if (anchor?.getAttribute('href')?.startsWith('#')) return;
    if (!anchor || !/^https?:/i.test(anchor.href)) return;
    event.preventDefault();
    if (allowedExternal(anchor.href)) bridge.openExternal(anchor.href);
  });
  return true;
}

export const androidReady = installAndroid();

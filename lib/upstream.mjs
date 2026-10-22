import {AppError, detectLogin} from './parsers.mjs';

export const SOURCE = 'https://automatiziraj.se/centralno/php/novo.php';
export const BASE = new URL('.', SOURCE);
const READ_PATHS = [
  'novo.php', 'kat.php', 'prizemlje.php', 'podesavanje.php',
  'graf_kat_temp.php', 'graf_prizemlje_temp.php', 'graf_puffer_temp.php',
  'graf_sanitarna_temp.php', 'graf_pec_temp2.php', 'grafVANJ.php',
  'graf_tuya.php', 'graf_napon2.php', 'grafP1.php', 'grafp1-6.php'
];
const WRITE_PATHS = ['dodajPODESAVANJA.php', 'zapisi_postavke.php'];
const LOGOUT_PATH = '/centralno/scr/logout.scr.php';
const ALL_PATHS = new Set([...READ_PATHS, ...WRITE_PATHS].map(p => new URL(p, BASE).pathname).concat(LOGOUT_PATH));

export function safeUpstreamUrl(path) {
  const url = new URL(path, BASE);
  if (url.protocol !== 'https:' || url.origin !== BASE.origin || url.username || url.password || url.hash || !ALL_PATHS.has(url.pathname)) {
    throw new AppError('Izvorna adresa nije na popisu dopuštenih funkcija.', 400);
  }
  return url;
}

// A small cookie jar scoped to this one original origin. Nothing is saved to disk.
export class UpstreamClient {
  constructor({fetchImpl = globalThis.fetch, timeout = 20000} = {}) {
    this.fetchImpl = fetchImpl;
    this.timeout = timeout;
    this.cookies = new Map();
  }
  clearSession() { this.cookies.clear(); }
  absorbCookies(headers) {
    const cookies = headers.getSetCookie?.() ?? (headers.get('set-cookie') ? [headers.get('set-cookie')] : []);
    for (const cookie of cookies) {
      const [pair, ...attrs] = cookie.split(';');
      const equals = pair.indexOf('=');
      if (equals <= 0) continue;
      const name = pair.slice(0, equals).trim();
      const value = pair.slice(equals + 1).trim();
      const directives = new Map(attrs.map(part => {const [k, ...v] = part.trim().split('='); return [k.toLowerCase(), v.join('=')];}));
      const expires = directives.has('max-age') ? Date.now() + Number(directives.get('max-age')) * 1000 : directives.has('expires') ? Date.parse(directives.get('expires')) : Infinity;
      if (expires <= Date.now() || value === '') this.cookies.delete(name);
      else this.cookies.set(name, {value, expires: Number.isNaN(expires) ? Infinity : expires, path: directives.get('path') || '/'});
    }
  }
  cookieHeader(url) {
    const now = Date.now();
    return [...this.cookies].filter(([name, c]) => {
      if (c.expires <= now) { this.cookies.delete(name); return false; }
      return url.pathname.startsWith(c.path);
    }).map(([name, c]) => `${name}=${c.value}`).join('; ');
  }
  async request(path, {params, mutation = false, logout = false} = {}) {
    let url = safeUpstreamUrl(path);
    const allowed = mutation ? WRITE_PATHS.includes(url.pathname.split('/').at(-1)) : logout ? url.pathname === LOGOUT_PATH : READ_PATHS.includes(url.pathname.split('/').at(-1));
    if (!allowed) throw new AppError('Ova izvorna funkcija nije dopuštena za traženi način rada.', 400);
    if (params) for (const [key, value] of Object.entries(params)) url.searchParams.set(key, String(value));
    let response;
    for (let hop = 0; hop <= 4; hop++) {
      try {
        const cookie = this.cookieHeader(url);
        response = await this.fetchImpl(url, {
          method: 'GET', redirect: 'manual', signal: AbortSignal.timeout(this.timeout),
          headers: {accept: 'text/html', 'user-agent': 'TermoDom/1.0 (local heating controller)', ...(cookie ? {cookie} : {})}
        });
      } catch (error) {
        throw new AppError(`Veza s izvornim sustavom nije dostupna${mutation ? '; potvrda spremanja nije primljena, prije ponavljanja osvježite podatke' : ''}.`, 502, {mutationUnconfirmed: mutation, cause: error.name});
      }
      this.absorbCookies(response.headers);
      if ([301, 302, 303, 307, 308].includes(response.status)) {
        const location = response.headers.get('location');
        if (!location) throw new AppError('Izvorni sustav poslao je neispravno preusmjeravanje.');
        const next = new URL(location, url);
        if (logout) return {html: '', url: url.href, status: response.status};
        if (/login|prijava/i.test(next.pathname)) throw new AppError('Izvorni sustav traži prijavu.', 401, {requiresLogin: true});
        url = safeUpstreamUrl(next);
        // A write can only redirect to a read page. Never replay a write endpoint.
        if (!READ_PATHS.includes(url.pathname.split('/').at(-1))) throw new AppError('Neočekivano preusmjeravanje izvornog sustava.');
        continue;
      }
      if (!response.ok) throw new AppError(`Izvorni sustav vratio je HTTP ${response.status}.`, response.status === 401 || response.status === 403 ? 401 : 502, {requiresLogin: response.status === 401 || response.status === 403});
      let html;
      try { html = await response.text(); }
      catch (error) {
        throw new AppError(`Odgovor izvornog sustava nije pročitan do kraja${mutation ? '; potvrda spremanja nije primljena, prije ponavljanja osvježite podatke' : ''}.`, 502, {mutationUnconfirmed: mutation, cause: error.name});
      }
      if (html.length > 5_000_000) throw new AppError('Izvorni odgovor je prevelik.');
      if (!logout && detectLogin(html)) throw new AppError('Izvorni sustav traži prijavu.', 401, {requiresLogin: true});
      return {html, url: url.href, status: response.status};
    }
    throw new AppError('Previše preusmjeravanja izvornog sustava.');
  }
  async logout() {
    try { return await this.request(LOGOUT_PATH, {logout: true}); }
    finally { this.clearSession(); }
  }
}

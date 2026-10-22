import {AppError, parseStatus, parseSchedule, parseSettings, validateSchedule, validateSettings} from './parsers.mjs';
import {UpstreamClient, SOURCE, safeUpstreamUrl} from './upstream.mjs';
import {CHART_CATALOG, parseChart} from './charts.mjs';

const equal = (a, b) => Math.abs(a - b) < 0.00001;
export class HeatingController {
  constructor({upstream = new UpstreamClient(), now = () => new Date().toISOString()} = {}) { this.upstream = upstream; this.now = now; this.busy = false; }
  async status() {
    const {html} = await this.upstream.request('novo.php');
    return {fetchedAt: this.now(), source: SOURCE, ...parseStatus(html)};
  }
  async schedule(id) {
    if (!['kat', 'prizemlje'].includes(id)) throw new AppError('Nepoznata zona.', 404);
    const {html} = await this.upstream.request(`${id}.php`);
    return {...parseSchedule(html, id), fetchedAt: this.now()};
  }
  async settings() {
    const {html} = await this.upstream.request('podesavanje.php');
    return {...parseSettings(html), fetchedAt: this.now()};
  }
  catalog() { return CHART_CATALOG.map(c => ({...c})); }
  async chart(id) {
    const chart = CHART_CATALOG.find(c => c.id === id);
    if (!chart) throw new AppError('Nepoznat grafikon.', 404);
    const {html} = await this.upstream.request(chart.path);
    try { return {...parseChart(html, id), fetchedAt: this.now()}; }
    catch (error) { throw new AppError(error.message || 'Izvor nije vratio podatke grafa.'); }
  }
  async exclusive(action) {
    if (this.busy) throw new AppError('Drugo spremanje je u tijeku. Pričekajte potvrdu.', 409);
    this.busy = true;
    try { return await action(); } finally { this.busy = false; }
  }
  async saveSchedule(id, values) {
    return this.exclusive(async () => {
      const current = await this.schedule(id);
      validateSchedule(values, current);
      if (safeUpstreamUrl(current.action).pathname !== '/centralno/php/dodajPODESAVANJA.php' || current.method !== 'GET') throw new AppError('Izvorni način spremanja rasporeda promijenjen je. Spremanje je zaustavljeno.');
      const params = Object.fromEntries(current.fields.map((f, i) => [f.name, values[i]]));
      Object.assign(params, current.hidden);
      await this.upstream.request(current.action, {mutation: true, params});
      let saved;
      try { saved = await this.schedule(id); }
      catch { throw new AppError('Zahtjev je poslan, ali ponovna provjera rasporeda nije uspjela. Osvježite prije ponovnog spremanja.', 502, {mutationUnconfirmed: true}); }
      const verified = values.every((v, i) => equal(v, saved.fields[i].value));
      if (!verified) throw new AppError('Izvorna stranica nije potvrdila sve spremljene temperature. Osvježite raspored i provjerite vrijednosti.', 502, {mutationUnconfirmed: true, current: saved});
      return {...saved, ok: true, verified: true, message: 'Izvorna stranica potvrdila je raspored. Sinkronizaciju regulatora pratite na pregledu.'};
    });
  }
  async saveSettings(values) {
    return this.exclusive(async () => {
      validateSettings(values);
      const current = await this.settings();
      if (safeUpstreamUrl(current.action).pathname !== '/centralno/php/zapisi_postavke.php' || current.method !== 'GET') throw new AppError('Izvorni način spremanja postavki promijenjen je. Spremanje je zaustavljeno.');
      await this.upstream.request(current.action, {mutation: true, params: values});
      let saved;
      try { saved = await this.settings(); }
      catch { throw new AppError('Zahtjev je poslan, ali ponovna provjera postavki nije uspjela. Osvježite prije ponovnog spremanja.', 502, {mutationUnconfirmed: true}); }
      if (!saved.fields.every(f => equal(f.value, values[f.name]))) throw new AppError('Izvorna stranica nije potvrdila sve spremljene postavke. Osvježite i provjerite vrijednosti.', 502, {mutationUnconfirmed: true, current: saved});
      return {...saved, ok: true, verified: true, message: 'Izvorna stranica potvrdila je postavke. Primjena na regulatoru može uslijediti naknadno.'};
    });
  }
  async logout() { return this.exclusive(async () => { await this.upstream.logout(); return {ok: true}; }); }
}

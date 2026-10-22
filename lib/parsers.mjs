// The original controller renders HTML rather than offering a documented API.
// Keep parsing here so changes in the source can fail visibly instead of producing
// believable but invented temperatures or device states.
export class AppError extends Error {
  constructor(message, status = 502, details = {}) {
    super(message);
    this.name = 'AppError';
    this.status = status;
    this.details = details;
  }
}

export function decodeHtml(value = '') {
  return String(value).replace(/&(#x[\da-f]+|#\d+|amp|quot|apos|lt|gt|nbsp|deg);/gi, (whole, code) => {
    const names = {amp: '&', quot: '"', apos: "'", lt: '<', gt: '>', nbsp: ' ', deg: '°'};
    if (code[0] === '#') {
      const n = code[1].toLowerCase() === 'x' ? parseInt(code.slice(2), 16) : parseInt(code.slice(1), 10);
      return n > 0 && n <= 0x10ffff ? String.fromCodePoint(n) : whole;
    }
    return names[code.toLowerCase()] ?? whole;
  });
}

export function plainText(html = '') {
  return decodeHtml(String(html).replace(/<(script|style)\b[^>]*>[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<[^>]*>/g, ' ')).replace(/\s+/g, ' ').trim();
}

export function attributes(tag) {
  const attrs = Object.create(null);
  const source = tag.replace(/^<\/?[\w:-]+\b/, '').replace(/\/?\s*>$/, '');
  for (const match of source.matchAll(/([\w:-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g)) {
    attrs[match[1].toLowerCase()] = decodeHtml(match[2] ?? match[3] ?? match[4] ?? '');
  }
  return attrs;
}

const number = value => {
  const m = String(value ?? '').match(/-?\d+(?:[.,]\d+)?/);
  return m ? Number(m[0].replace(',', '.')) : null;
};
// Number('') is zero in JavaScript; an empty controller field is missing data.
const sourceNumber = value => {
  if (typeof value !== 'string' || !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(value.trim())) return NaN;
  return Number(value.trim());
};
const get = (source, regex, group = 1) => source.match(regex)?.[group] ?? null;
const indicator = source => {
  const m = source?.match(/<img\b[^>]*\bsrc\s*=\s*["'][^"']*\/(ok|no)\.(?:png|gif)["']/i);
  return m ? m[1].toLowerCase() === 'ok' : null;
};

export function detectLogin(html) {
  return /<input\b[^>]*\btype\s*=\s*["']?password\b/i.test(html) ||
    /<form\b[^>]*\baction\s*=\s*["'][^"']*(?:login|prijava)[^"']*["']/i.test(html);
}

export function parseStatus(html) {
  if (detectLogin(html)) throw new AppError('Izvorna stranica traži prijavu. Otvorite izvornu stranicu za provjeru pristupa.', 401, {requiresLogin: true});
  const body = html.slice(Math.max(0, html.search(/<body\b/i)));
  if (!/graf_kat_temp\.php/i.test(body) || !/graf_puffer_temp\.php/i.test(body)) {
    throw new AppError('Izvorna stranica nema očekivanu strukturu sustava grijanja. Podatci nisu zamijenjeni primjerima.');
  }
  const text = plainText(body);
  const zoneTemperature = path => number(get(body, new RegExp(`href=["']${path.replaceAll('.', '\\.')}["'][\\s\\S]*?<span\\b[^>]*>([^<]*)<\\/span>`, 'i')));
  const waterSections = [...body.matchAll(/Trenutno\s+je\s+(-?\d+(?:[.,]\d+)?)[\s\S]*?<span\b[^>]*id\s*=\s*["'](ztk|ztp)["'][^>]*>([\s\S]*?)<\/span>/gi)];
  const water = Object.fromEntries(waterSections.map(m => [m[2], {
    temperature: number(m[1]), planned: number(plainText(m[3])), running: indicator(m[0])
  }]));
  const bufferBlock = get(body, /<div\b[^>]*class\s*=\s*["'][^"']*\btekst-puffer\b[^"']*["'][^>]*>([\s\S]*?)<\/div>/i) ?? '';
  const bufferValue = label => number(get(bufferBlock, new RegExp(`<p[^>]*>\\s*${label}\\s*<\\/p>\\s*<h2[^>]*>([\\s\\S]*?)<\\/h2>`, 'i')));
  const furnaceBlock = get(body, /<div\b[^>]*class\s*=\s*["'][^"']*\btekst-pec\b[^"']*["'][^>]*>([\s\S]*?)<\/div>/i) ?? '';
  const furnaceTemps = [...furnaceBlock.matchAll(/<h2[^>]*>([\s\S]*?)<\/h2>/gi)].map(m => number(plainText(m[1])));
  const pumpBlock = get(body, /<div\b[^>]*class\s*=\s*["'][^"']*\bpump_pec\b[^"']*["'][^>]*>([\s\S]*?)<\/div>/i);
  const sanitaryBlock = get(body, /href\s*=\s*["']graf_sanitarna_temp\.php["']([\s\S]*?)href\s*=\s*["']graf_pec_temp2\.php["']/i);
  const fanBlock = get(body, /<div\b[^>]*class\s*=\s*["'][^"']*\bvent\b[^"']*["'][^>]*>([\s\S]*?)<\/div>/i);
  const paras = [...body.matchAll(/<p\b[^>]*>([\s\S]*?)<\/p>/gi)].map(m => plainText(m[1]));
  const sync = id => paras.find(p => /sync/i.test(p) && new RegExp(`\\b${id}\\b`, 'i').test(p)) ?? null;
  return {
    serverTime: get(text, /(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})/),
    systemTime: get(text, /vrijeme\s+sistema\s+(\d{1,2}:\d{2}(?::\d{2})?)/i),
    sync: {kat: sync('kat'), prizemlje: sync('prizemlje')},
    resets: number(get(text, /Resetirano\s*:\s*(\d+)/i)),
    outside: number(get(text, /Vanjska\s+temperatura\s+(-?\d+(?:[.,]\d+)?)/i)),
    percentage: number(get(text, /Posto\s+(-?\d+(?:[.,]\d+)?)\s*%/i)),
    zones: [
      {id: 'kat', name: 'Kat', temperature: zoneTemperature('graf_kat_temp.php'), waterTemperature: water.ztk?.temperature ?? null, plannedWaterTemperature: water.ztk?.planned ?? null, pumpRunning: water.ztk?.running ?? null},
      {id: 'prizemlje', name: 'Prizemlje', temperature: zoneTemperature('graf_prizemlje_temp.php'), waterTemperature: water.ztp?.temperature ?? null, plannedWaterTemperature: water.ztp?.planned ?? null, pumpRunning: water.ztp?.running ?? null}
    ],
    buffer: {top: bufferValue('vrh'), middle: bufferValue('sredina'), bottom: bufferValue('dno')},
    sanitary: {temperature: furnaceTemps[0] ?? null, pumpRunning: indicator(sanitaryBlock)},
    boiler: {temperature: furnaceTemps[1] ?? null, pumpRunning: indicator(pumpBlock), fanRunning: indicator(fanBlock)}
  };
}

function formWith(html, predicate) {
  for (const m of html.matchAll(/(<form\b[^>]*>)([\s\S]*?)<\/form>/gi)) {
    const inputs = [...m[2].matchAll(/<input\b[^>]*>/gi)].map(m => attributes(m[0]));
    if (predicate(inputs)) return {attrs: attributes(m[1]), inputs, source: m[2]};
  }
  throw new AppError('Izvorna stranica nema očekivani obrazac. Spremanje je zaustavljeno.');
}

export function parseSchedule(html, id) {
  if (!['kat', 'prizemlje'].includes(id)) throw new AppError('Nepoznata zona.', 404);
  if (detectLogin(html)) throw new AppError('Izvorna stranica traži prijavu.', 401, {requiresLogin: true});
  const form = formWith(html, inputs => inputs.some(i => i.type === 'range' && i.name === 'n23'));
  const fields = Array.from({length: 24}, (_, n) => {
    const field = form.inputs.find(i => i.name === `n${n}` && i.type === 'range');
    if (!field) throw new AppError('U izvornom rasporedu nedostaje jedan od 24 sata.');
    const values = {name: field.name, value: sourceNumber(field.value), min: sourceNumber(field.min), max: sourceNumber(field.max), step: field.step === undefined ? 1 : sourceNumber(field.step)};
    if (Object.values(values).slice(1).some(v => !Number.isFinite(v)) || values.step <= 0 || values.min > values.max) throw new AppError('Izvorni raspored ima neispravne granice.');
    return values;
  });
  const hidden = Object.fromEntries(form.inputs.filter(i => i.type === 'hidden' && i.name).map(i => [i.name, i.value]));
  if (hidden.kat !== (id === 'kat' ? '2' : '1')) throw new AppError('Izvorni obrazac ima neočekivanu oznaku zone.');
  const defaults = [...html.matchAll(/<div\b[^>]*class\s*=\s*["'][^"']*\bdefault_temp\b[^"']*["'][^>]*>([\s\S]*?)<\/div>/gi)].map(m => number(plainText(m[1])));
  return {id, fields, defaults, action: form.attrs.action, hidden, method: (form.attrs.method || 'get').toUpperCase()};
}

export function parseSettings(html) {
  if (detectLogin(html)) throw new AppError('Izvorna stranica traži prijavu.', 401, {requiresLogin: true});
  const form = formWith(html, inputs => inputs.some(i => i.name === 'n5'));
  const labels = [...form.source.matchAll(/<label\b[^>]*>([\s\S]*?)<\/label>/gi)].map(m => plainText(m[1]));
  const fields = Array.from({length: 6}, (_, n) => {
    const input = form.inputs.find(i => i.name === `n${n}`);
    if (!input || !Number.isFinite(sourceNumber(input.value))) throw new AppError('Izvorne postavke imaju nepotpun ili neispravan obrazac.');
    return {name: input.name, label: labels[n] || input.name, value: sourceNumber(input.value)};
  });
  return {fields, action: form.attrs.action, method: (form.attrs.method || 'get').toUpperCase()};
}

export function validateSchedule(values, schedule) {
  if (!Array.isArray(values) || values.length !== 24) throw new AppError('Raspored mora sadržavati točno 24 temperature.', 400);
  values.forEach((v, i) => {
    const f = schedule.fields[i];
    if (typeof v !== 'number' || !Number.isFinite(v) || v < f.min || v > f.max || Math.abs((v - f.min) / f.step - Math.round((v - f.min) / f.step)) > 1e-6) {
      throw new AppError(`Neispravna temperatura za ${i}:00. Dopušteno je ${f.min}–${f.max} °C, korak ${f.step} °C.`, 400);
    }
  });
}

export function validateSettings(values) {
  if (!values || typeof values !== 'object' || Array.isArray(values) || Object.keys(values).length !== 6) throw new AppError('Potrebno je svih šest postavki.', 400);
  for (let n = 0; n < 6; n++) if (typeof values[`n${n}`] !== 'number' || !Number.isFinite(values[`n${n}`])) throw new AppError(`Postavka n${n} mora biti broj.`, 400);
  if (![0, 1].includes(values.n5)) throw new AppError('Stanje klimatizacije mora biti 0 ili 1.', 400);
}

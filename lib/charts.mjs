/**
 * Read-only adapters for the original Chart.js pages. Remote scripts are treated
 * as text: this module never uses eval, Function, vm, or a browser execution API.
 */
export const CHART_CATALOG = Object.freeze([
  { id: 'kat', title: 'Kat', path: 'graf_kat_temp.php', unit: '°C', period: '24h' },
  { id: 'prizemlje', title: 'Prizemlje', path: 'graf_prizemlje_temp.php', unit: '°C', period: '24h' },
  { id: 'buffer', title: 'Puffer', path: 'graf_puffer_temp.php', unit: '°C', period: '24h' },
  { id: 'sanitary', title: 'Sanitarna voda', path: 'graf_sanitarna_temp.php', unit: '°C', period: '24h' },
  { id: 'boiler', title: 'Peć', path: 'graf_pec_temp2.php', unit: '°C', period: '24h' },
  { id: 'outside', title: 'Vanjska temperatura', path: 'grafVANJ.php', unit: '°C', period: '24h' },
  { id: 'tuya', title: 'Tuya senzori', path: 'graf_tuya.php', unit: '°C', period: '24h' },
  { id: 'voltage', title: 'Napon', path: 'graf_napon2.php', unit: 'V', period: '24h' },
  { id: 'tuya-1h', title: 'Tuya senzori · 1 sat', path: 'grafP1.php', unit: '°C', period: '1h' },
  { id: 'tuya-6h', title: 'Tuya senzori · 6 sati', path: 'grafp1-6.php', unit: '°C', period: '6h' },
].map(Object.freeze));

const MAX_ITEMS = 200_000;
const MAX_HTML = 4_000_000;
const UNAVAILABLE = Symbol('unavailable');

// A deliberately small lexer: it recognizes data literals without executing JS.
function tokenize(source) {
  const tokens = [];
  let i = 0;
  const push = (type, value) => {
    if (tokens.length >= MAX_ITEMS * 3) throw new Error('Graf sadrži previše podataka.');
    tokens.push({ type, value });
  };
  while (i < source.length) {
    const c = source[i];
    if (/\s/.test(c)) { i++; continue; }
    if (c === '/' && source[i + 1] === '/') {
      i += 2;
      while (i < source.length && source[i] !== '\n') i++;
      continue;
    }
    if (c === '/' && source[i + 1] === '*') {
      const end = source.indexOf('*/', i + 2);
      i = end === -1 ? source.length : end + 2;
      continue;
    }
    if (c === '"' || c === "'") {
      const quote = c;
      let value = '';
      let closed = false;
      i++;
      while (i < source.length) {
        const next = source[i++];
        if (next === quote) { closed = true; break; }
        if (next !== '\\') { value += next; continue; }
        if (i >= source.length) break;
        const escaped = source[i++];
        const simple = { n: '\n', r: '\r', t: '\t', b: '\b', f: '\f', v: '\v', '0': '\0' };
        if (Object.hasOwn(simple, escaped)) value += simple[escaped];
        else if (escaped === 'u' || escaped === 'x') {
          const count = escaped === 'u' ? 4 : 2;
          const digits = source.slice(i, i + count);
          if (!new RegExp(`^[0-9a-fA-F]{${count}}$`).test(digits)) throw new Error('Neispravan tekst u grafu.');
          value += String.fromCharCode(Number.parseInt(digits, 16));
          i += count;
        } else if (escaped === '\r') { if (source[i] === '\n') i++; }
        else if (escaped !== '\n') value += escaped;
      }
      if (!closed) throw new Error('Nedovršen tekst u grafu.');
      push('string', value);
      continue;
    }
    if (c === '`') {
      // Template literals are not accepted as chart data. Skip them as one token.
      const start = i++;
      while (i < source.length) {
        if (source[i] === '\\') { i += 2; continue; }
        if (source[i++] === '`') break;
      }
      push('unsupported', source.slice(start, i));
      continue;
    }
    const number = source.slice(i).match(/^(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?/i);
    if (number) {
      push('number', Number(number[0]));
      i += number[0].length;
      continue;
    }
    const identifier = source.slice(i).match(/^[a-zA-Z_$][\w$]*/);
    if (identifier) {
      push('identifier', identifier[0]);
      i += identifier[0].length;
      continue;
    }
    push('punctuation', c);
    i++;
  }
  return tokens;
}

const valueAt = (tokens, position) => tokens[position]?.value;

/** Only literal data, previous data bindings, arithmetic, .length, and Array().fill(). */
function readExpression(tokens, position, bindings) {
  let at = position;
  let steps = 0;
  const expect = (value) => {
    if (valueAt(tokens, at++) !== value) throw new Error('Nepodržan izraz u grafu.');
  };
  const scalarNumber = value => {
    if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error('Neispravan broj u grafu.');
    return value;
  };
  function primary() {
    if (++steps > MAX_ITEMS * 2) throw new Error('Graf sadrži previše podataka.');
    const token = tokens[at++];
    if (!token) throw new Error('Nedovršen izraz u grafu.');
    if (token.type === 'number' || token.type === 'string') return token.value;
    if (token.value === '+' || token.value === '-') {
      const n = scalarNumber(primary());
      return token.value === '-' ? -n : n;
    }
    if (token.value === '(') {
      const result = arithmetic(0);
      expect(')');
      return result;
    }
    if (token.value === '[') {
      const result = [];
      while (valueAt(tokens, at) !== ']') {
        if (result.length >= MAX_ITEMS) throw new Error('Graf sadrži previše podataka.');
        result.push(arithmetic(0));
        if (valueAt(tokens, at) !== ',') break;
        at++;
        if (valueAt(tokens, at) === ']') break;
      }
      expect(']');
      return result;
    }
    if (token.value === 'true') return true;
    if (token.value === 'false') return false;
    if (token.value === 'null') return null;
    if (token.value === 'new' && valueAt(tokens, at) === 'Array') at++;
    else if (token.value !== 'Array') {
      if (token.type !== 'identifier' || !bindings.has(token.value)) throw new Error('Nepoznata varijabla u grafu.');
      let result = bindings.get(token.value);
      if (valueAt(tokens, at) === '.' && valueAt(tokens, at + 1) === 'length' && Array.isArray(result)) {
        at += 2;
        result = result.length;
      }
      return result;
    }
    expect('(');
    const count = scalarNumber(arithmetic(0));
    expect(')'); expect('.'); expect('fill'); expect('(');
    const fill = arithmetic(0);
    expect(')');
    if (!Number.isInteger(count) || count < 0 || count > MAX_ITEMS || (fill !== null && !['string', 'number', 'boolean'].includes(typeof fill))) {
      throw new Error('Neispravan niz u grafu.');
    }
    return Array(count).fill(fill);
  }
  const precedence = { '+': 1, '-': 1, '*': 2, '/': 2, '%': 2 };
  function arithmetic(min) {
    let left = primary();
    while (Object.hasOwn(precedence, valueAt(tokens, at)) && precedence[valueAt(tokens, at)] >= min) {
      const op = valueAt(tokens, at++);
      const right = arithmetic(precedence[op] + 1);
      const a = scalarNumber(left); const b = scalarNumber(right);
      left = op === '+' ? a + b : op === '-' ? a - b : op === '*' ? a * b : op === '/' ? a / b : a % b;
      scalarNumber(left);
    }
    return left;
  }
  const value = arithmetic(0);
  return { value, end: at };
}

function closePosition(tokens, start) {
  const open = valueAt(tokens, start);
  const pairs = { '[': ']', '{': '}', '(': ')' };
  if (!Object.hasOwn(pairs, open)) throw new Error('Neispravna struktura grafa.');
  const stack = [pairs[open]];
  for (let i = start + 1; i < tokens.length; i++) {
    if (tokens[i].type !== 'punctuation') continue;
    const value = tokens[i].value;
    if (Object.hasOwn(pairs, value)) stack.push(pairs[value]);
    else if ([']', '}', ')'].includes(value)) {
      if (stack.pop() !== value) throw new Error('Neispravna struktura grafa.');
      if (!stack.length) return i;
    }
  }
  throw new Error('Nedovršen graf.');
}

// Return direct object properties; unrelated functions/options are skipped intact.
function objectProperties(tokens, start) {
  const result = new Map();
  const end = closePosition(tokens, start);
  let at = start + 1;
  while (at < end) {
    const key = tokens[at++];
    if (key?.value === ',') continue;
    if (!['identifier', 'string'].includes(key?.type) || valueAt(tokens, at++) !== ':') break;
    const valueStart = at;
    while (at < end && valueAt(tokens, at) !== ',') {
      if (['[', '{', '('].includes(valueAt(tokens, at)) && tokens[at].type === 'punctuation') at = closePosition(tokens, at) + 1;
      else at++;
    }
    result.set(key.value, { start: valueStart, end: at });
    if (valueAt(tokens, at) === ',') at++;
  }
  return result;
}

function propertyValue(tokens, property, bindings) {
  if (!property) return UNAVAILABLE;
  try {
    const parsed = readExpression(tokens, property.start, bindings);
    return parsed.end === property.end ? parsed.value : UNAVAILABLE;
  } catch { return UNAVAILABLE; }
}

function dataBindings(tokens) {
  const bindings = new Map();
  for (let at = 0; at < tokens.length - 3; at++) {
    if (!['var', 'let', 'const'].includes(valueAt(tokens, at)) || tokens[at + 1]?.type !== 'identifier' || valueAt(tokens, at + 2) !== '=') continue;
    try {
      const parsed = readExpression(tokens, at + 3, bindings);
      if ([';', ','].includes(valueAt(tokens, parsed.end))) bindings.set(valueAt(tokens, at + 1), parsed.value);
    } catch { /* DOM references and script functions intentionally stay unparsed. */ }
  }
  return bindings;
}

/** Parse original Chart.js data while retaining every source dataset and value. */
export function parseChart(html, id) {
  const meta = CHART_CATALOG.find(chart => chart.id === id);
  if (!meta) throw new Error('Nepoznat graf.');
  if (typeof html !== 'string' || html.length > MAX_HTML) throw new Error('Neispravan odgovor grafa.');
  const scripts = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script\s*>/gi)].map(match => match[1]).join('\n');
  const tokens = tokenize(scripts);
  const bindings = dataBindings(tokens);
  let properties;
  for (let at = 0; at < tokens.length - 3; at++) {
    if (valueAt(tokens, at) !== 'data' || valueAt(tokens, at + 1) !== ':' || valueAt(tokens, at + 2) !== '{') continue;
    const candidate = objectProperties(tokens, at + 2);
    if (candidate.has('labels') && candidate.has('datasets')) { properties = candidate; break; }
  }
  if (!properties) throw new Error('Izvor nije vratio podatke grafa.');
  const rawLabels = propertyValue(tokens, properties.get('labels'), bindings);
  if (!Array.isArray(rawLabels) || rawLabels.some(label => !['string', 'number'].includes(typeof label))) throw new Error('Neispravne oznake grafa.');
  const datasetRange = properties.get('datasets');
  if (valueAt(tokens, datasetRange.start) !== '[') throw new Error('Neispravni nizovi grafa.');
  const datasets = [];
  let at = datasetRange.start + 1;
  const palette = ['#ef694f', '#46b29c', '#7f96ec', '#b488dc', '#dfad59'];
  while (at < datasetRange.end - 1) {
    if (valueAt(tokens, at) === ',') { at++; continue; }
    if (valueAt(tokens, at) !== '{') throw new Error('Nepodržan niz grafa.');
    const props = objectProperties(tokens, at);
    const label = propertyValue(tokens, props.get('label'), bindings);
    const data = propertyValue(tokens, props.get('data'), bindings);
    const color = propertyValue(tokens, props.get('borderColor'), bindings);
    const stepped = propertyValue(tokens, props.get('steppedLine') ?? props.get('stepped'), bindings);
    const numeric = value => typeof value === 'string' && /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(value) ? Number(value) : value;
    if (typeof label !== 'string' || !Array.isArray(data) || data.some(value => value !== null && (typeof numeric(value) !== 'number' || !Number.isFinite(numeric(value))))) {
      throw new Error('Podaci grafa sadrže nepodržan izraz.');
    }
    datasets.push({ label, data: data.map(numeric), color: typeof color === 'string' ? color : palette[datasets.length % palette.length], stepped: stepped === true || ['before', 'after', 'middle'].includes(stepped) });
    at = closePosition(tokens, at) + 1;
  }
  if (!datasets.length) throw new Error('Izvor nije vratio nizove grafa.');
  return { id: meta.id, title: meta.title, labels: [...rawLabels], datasets, unit: meta.unit, period: meta.period };
}

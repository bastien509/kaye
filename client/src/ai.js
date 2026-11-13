// Kaye's hybrid AI engine (Firebase AI Logic).
// Gemini Nano on this device first; Gemini in the cloud only when needed and only with what it needs:
//  - notebook photos go to the cloud only after the vendor says yes,
//  - quick notes go with customer names and phones replaced by tokens,
//  - the monthly record is written from totals only.
import { getGenerativeModel, InferenceMode, InferenceSource, Schema } from 'firebase/ai';
import { ai, settings, track } from './firebase.js';
import { parseRules, hideNames, restoreNames } from './rules.js';

const KINDS = ['sale', 'expense', 'credit', 'payment'];
const AI_TIMEOUT_MS = 120000; // on-device image reading on a CPU can take ~45 s

const LINES_DEVICE = {
  type: 'object',
  properties: {
    readable: { type: 'boolean' },
    total_written: { type: 'number' },
    lines: {
      type: 'array',
      maxItems: 30,
      items: {
        type: 'object',
        properties: {
          kind: { type: 'string', enum: KINDS },
          item: { type: 'string' },
          qty: { type: 'number' },
          unit_price: { type: 'number' },
          amount: { type: 'number' },
          customer: { type: 'string' },
        },
        required: ['kind', 'item', 'amount'],
      },
    },
  },
  required: ['readable', 'lines'],
};
const LINES_CLOUD = Schema.object({
  properties: {
    readable: Schema.boolean(),
    total_written: Schema.number(),
    lines: Schema.array({
      items: Schema.object({
        properties: {
          kind: Schema.enumString({ enum: KINDS }),
          item: Schema.string(),
          qty: Schema.number(),
          unit_price: Schema.number(),
          amount: Schema.number(),
          customer: Schema.string(),
        },
        optionalProperties: ['qty', 'unit_price', 'customer'],
      }),
    }),
  },
  optionalProperties: ['total_written'],
});
const REPORT_DEVICE = {
  type: 'object',
  properties: { summary: { type: 'string' }, highlights: { type: 'array', maxItems: 4, items: { type: 'string' } } },
  required: ['summary', 'highlights'],
};
const REPORT_CLOUD = Schema.object({ properties: { summary: Schema.string(), highlights: Schema.array({ items: Schema.string() }) } });
const MSG_DEVICE = { type: 'object', properties: { message: { type: 'string' } }, required: ['message'] };
const MSG_CLOUD = Schema.object({ properties: { message: Schema.string() } });

const SHAPES = {
  lines: { device: LINES_DEVICE, cloud: LINES_CLOUD, tokens: 1200, image: true },
  report: { device: REPORT_DEVICE, cloud: REPORT_CLOUD, tokens: 700, image: false },
  message: { device: MSG_DEVICE, cloud: MSG_CLOUD, tokens: 300, image: false },
};

const LANG_NAME = { en: 'English', fr: 'French', ht: 'Haitian Creole (Kreyòl)', es: 'Spanish' };
const models = new Map();
const hasPromptApi = () => typeof globalThis.LanguageModel !== 'undefined';
const deviceLang = (lang) => (settings.deviceLanguages.includes(lang) ? lang : 'en');

function model(mode, shape, lang = 'en') {
  const key = `${mode}|${shape}|${lang}|${settings.cloudModel}`;
  if (!models.has(key)) {
    const s = SHAPES[shape];
    const out = deviceLang(lang);
    const inputs = [{ type: 'text', languages: [...new Set(['en', out])] }];
    if (s.image) inputs.push({ type: 'image' });
    models.set(key, getGenerativeModel(ai, {
      mode,
      inCloudParams: {
        model: settings.cloudModel,
        generationConfig: { responseMimeType: 'application/json', responseSchema: s.cloud, temperature: 0.1, maxOutputTokens: s.tokens },
      },
      onDeviceParams: {
        createOptions: { expectedInputs: inputs, expectedOutputs: [{ type: 'text', languages: [out] }] },
        promptOptions: { responseConstraint: s.device },
      },
    }));
  }
  return models.get(key);
}

/** 'available' | 'downloadable' | 'downloading' | 'unavailable' | 'unsupported' */
export async function deviceStatus(withImage = false) {
  if (!hasPromptApi()) return 'unsupported';
  try {
    const expectedInputs = [{ type: 'text', languages: ['en'] }];
    if (withImage) expectedInputs.push({ type: 'image' });
    return await globalThis.LanguageModel.availability({ expectedInputs, expectedOutputs: [{ type: 'text', languages: ['en'] }] });
  } catch {
    return 'unavailable';
  }
}

/** Starts the Gemini Nano download. Must run from a click. */
export async function prepareDevice(onProgress) {
  if (!hasPromptApi()) return false;
  try {
    await model(InferenceMode.PREFER_ON_DEVICE, 'lines').initializeDeviceModel(onProgress);
    return true;
  } catch {
    return false;
  }
}

function withTimeout(p, ms) {
  let t;
  return Promise.race([p.finally(() => clearTimeout(t)), new Promise((_, rej) => { t = setTimeout(() => rej(new Error('The AI took too long')), ms); })]);
}

function parseJson(text) {
  const t = String(text || '').trim().replace(/^```(?:json)?\s*|\s*```$/g, '');
  try {
    return JSON.parse(t);
  } catch {
    const a = t.indexOf('{');
    const b = t.lastIndexOf('}');
    if (a >= 0 && b > a) return JSON.parse(t.slice(a, b + 1));
    throw new Error('The AI answer was not readable');
  }
}

async function run(mode, shape, parts, lang, feature) {
  const t0 = performance.now();
  const res = await withTimeout(model(mode, shape, lang).generateContent(parts), AI_TIMEOUT_MS);
  const ms = Math.round(performance.now() - t0);
  const source = res.response.inferenceSource === InferenceSource.ON_DEVICE ? 'on_device' : 'in_cloud';
  track('ai_call', { feature, source, ms, mode: modeName(mode) });
  return { data: parseJson(res.response.text()), source, ms };
}

export const modeName = (m) => ({
  [InferenceMode.PREFER_ON_DEVICE]: 'PREFER_ON_DEVICE',
  [InferenceMode.ONLY_ON_DEVICE]: 'ONLY_ON_DEVICE',
  [InferenceMode.PREFER_IN_CLOUD]: 'PREFER_IN_CLOUD',
  [InferenceMode.ONLY_IN_CLOUD]: 'ONLY_IN_CLOUD',
}[m] || String(m));

// ---------- Lines: shared cleaning + arithmetic check ----------

// Small models sometimes list the page's "Total" row as a sale. A total is never a line: move it to total_written.
const TOTAL_RE = /^\s*(grand\s+)?(total|tot[aà]l|totalman|sous[- ]total|subtotal|sum|som|suma)\b/i;

function cleanLines(raw, max) {
  const list = Array.isArray(raw?.lines) ? raw.lines : [];
  const lines = list.slice(0, max).map((l) => ({
    kind: KINDS.includes(l.kind) ? l.kind : 'sale',
    // "Rice 3 cups x 250 = 750" → "Rice": the numbers already live in qty / unit_price / amount.
    item: String(l.item || '').replace(/\s*=\s*[\d.,]+\s*$/, '')
      .replace(/\s+\d+(?:[.,]\d+)?\s*(?:[\p{L}]+\s*)?[x×*]\s*[\d.,]+.*$/iu, '').trim().slice(0, 80) || 'Item',
    qty: num(l.qty),
    unit_price: num(l.unit_price),
    amount: num(l.amount) ?? 0,
    customer: l.customer ? String(l.customer).trim().slice(0, 60) : null,
  })).filter((l) => l.amount > 0);
  const totals = lines.filter((l) => TOTAL_RE.test(l.item));
  if (raw && totals.length && !num(raw.total_written)) raw.total_written = totals[totals.length - 1].amount;
  return lines.filter((l) => !TOTAL_RE.test(l.item));
}
/**
 * If the sales alone don't match the page total but every sale+cost line together does, the small model
 * mislabelled some lines: count them as sales (the page total is what the vendor wrote). Returns a note for the trace.
 */
function reconcileKinds(lines, totalWritten) {
  const t = num(totalWritten);
  if (!t) return '';
  const money = lines.filter((l) => l.kind === 'sale' || l.kind === 'expense');
  const sales = money.filter((l) => l.kind === 'sale');
  const sum = (a) => a.reduce((s, l) => s + l.amount, 0);
  const close = (a) => Math.abs(a - t) <= Math.max(1, settings.sumTolerance * t);
  if (sales.length && sales.length < money.length && sales.length * 2 >= money.length && !close(sum(sales)) && close(sum(money))) {
    const n = money.length - sales.length;
    money.forEach((l) => { l.kind = 'sale'; });
    return `${n} line(s) were read as costs, but all lines together match the page total, so they were counted as sales. Check before saving.`;
  }
  return '';
}

const num = (v) => {
  const n = typeof v === 'string' ? Number(v.replace(/[^\d.]/g, '')) : Number(v);
  return Number.isFinite(n) && n > 0 ? Math.round(n * 100) / 100 : null;
};

/** Do the numbers add up? Every line (qty × price) and the page total. */
export function checkLines(lines, totalWritten) {
  const tol = settings.sumTolerance;
  const issues = [];
  for (const l of lines) {
    if (l.qty && l.unit_price) {
      const expect = l.qty * l.unit_price;
      if (Math.abs(expect - l.amount) > Math.max(1, tol * l.amount)) {
        issues.push(`“${l.item}”: ${l.qty} × ${fmt(l.unit_price)} = ${fmt(expect)}, but the line says ${fmt(l.amount)}.`);
      }
    }
  }
  const sales = lines.filter((l) => l.kind === 'sale');
  const pool = sales.length ? sales : lines;
  const sum = pool.reduce((a, l) => a + l.amount, 0);
  const total = num(totalWritten);
  if (total && Math.abs(sum - total) > Math.max(1, tol * total)) {
    issues.push(`The lines add up to ${fmt(sum)}, but the page total says ${fmt(total)}.`);
  }
  if (!lines.length) issues.push('No money lines were found on this page.');
  return { ok: issues.length === 0, issues, sum, total };
}
const fmt = (n) => Number(n).toLocaleString('en-US', { maximumFractionDigits: 2 });

// ---------- 1. Read a notebook page or receipt (photo) ----------

const PAGE_PROMPT = (max) => `You read a photo of a market vendor's handwritten notebook page or a paper receipt.
List every money line on it, at most ${max}.
- kind: "sale" (money received for goods), "expense" (money spent: stock, transport, rent, fees), "credit" (a customer took goods and still owes money), "payment" (a customer paid back money they owed).
- Headings help: Lavant/Ventes/Sales = sale, Depans/Dépenses/Expenses/Achte = expense, Kredi/Crédit/Dette/Dwe = credit, Peye/Remboursé = payment.
- item: the item name as written (keep Kreyòl or French words). For credit and payment lines, item is what was taken, or "Credit"/"Payment".
- customer: only for credit and payment lines, the name as written.
- qty and unit_price only if they are written. amount is the line total, a plain number, no currency.
- total_written: the total written on the page, if there is one.
- readable: false if this is not a notebook page or receipt, or it is too blurry to read.
Do not invent lines. Copy numbers exactly as written, even if the arithmetic looks wrong. Answer with JSON only.`;

/**
 * @param {{data:string,mimeType:string}} image base64 JPEG/PNG
 * @param {{online:boolean, confirmCloud:(reason:string)=>Promise<boolean>}} opts
 */
export async function readPage(image, { online, confirmCloud }) {
  const t0 = performance.now();
  const trace = [];
  const parts = [PAGE_PROMPT(settings.maxLines), { inlineData: { mimeType: image.mimeType, data: image.data } }];
  const status = await deviceStatus(true);
  trace.push(`Gemini Nano on this device: ${status}. Network: ${online ? 'online' : 'offline'}.`);

  let result = null;
  let deviceError = '';
  if (status === 'available') {
    try {
      trace.push('Photos stay on the device first: ONLY_ON_DEVICE.');
      result = await run(InferenceMode.ONLY_ON_DEVICE, 'lines', parts, 'en', 'read_page');
    } catch (e) {
      deviceError = e?.message || 'failed';
      trace.push(`Gemini Nano could not read it (${deviceError}).`);
    }
  } else {
    trace.push('No on-device model here, so the photo cannot be read on this device.');
  }

  let lines = result ? cleanLines(result.data, settings.maxLines) : [];
  const fixNote = result ? reconcileKinds(lines, result.data.total_written) : '';
  if (fixNote) trace.push(fixNote);
  let check = result ? checkLines(lines, result.data.total_written) : { ok: false, issues: [] };
  const unreadable = result && result.data.readable === false;
  if (result && !check.ok) trace.push(`Arithmetic check failed: ${check.issues.join(' ')}`);
  if (result && check.ok) trace.push('Arithmetic check passed: every line and the page total add up.');

  // Escalate only with consent: a photo cannot be redacted.
  const needCloud = !result || unreadable || !check.ok;
  if (needCloud && online && settings.cloudPhotos) {
    const why = !result
      ? (status === 'available' ? 'Gemini Nano could not read this photo.' : 'This device has no on-device model for photos.')
      : unreadable ? 'Gemini Nano could not read this photo clearly.' : 'The numbers did not add up when read on this device.';
    const yes = await confirmCloud(why);
    if (yes) {
      trace.push(`You agreed to send this one photo to Gemini in the cloud (ONLY_IN_CLOUD, ${settings.cloudModel}).`);
      try {
        const cloud = await run(InferenceMode.ONLY_IN_CLOUD, 'lines', parts, 'en', 'read_page');
        const cl = cleanLines(cloud.data, settings.maxLines);
        const cfix = reconcileKinds(cl, cloud.data.total_written);
        if (cfix) trace.push(cfix);
        const cc = checkLines(cl, cloud.data.total_written);
        trace.push(cc.ok ? 'Cloud read: the numbers add up.' : `Cloud read: ${cc.issues.join(' ')} Check the lines before saving.`);
        result = cloud;
        lines = cl;
        check = cc;
      } catch (e) {
        trace.push(`The cloud could not read it either (${e?.message || 'error'}).`);
      }
    } else {
      trace.push('You kept the photo on this device. Fix the lines by hand.');
    }
  } else if (needCloud && !online) {
    trace.push('Offline: type the lines by hand; they will sync later.');
  }

  return {
    lines,
    check,
    totalWritten: num(result?.data?.total_written),
    source: result?.source || 'manual',
    ms: result?.ms ?? Math.round(performance.now() - t0),
    trace,
  };
}

// ---------- 2. Quick note (typed or spoken) ----------

const NOTE_PROMPT = (text, lang, max) => `A market vendor wrote this note about today's business, in ${LANG_NAME[lang] || 'English'}.
Turn it into notebook lines, at most ${max}. kind: "sale", "expense", "credit" (customer owes money), "payment" (customer paid back).
Customer names may appear as tokens like C1, C2: copy them exactly into "customer".
For credit and payment lines, item is what was taken, or "Credit" / "Payment".
qty and unit_price only if given ("each", "chak", "chacun", "cada" means unit price). amount is the line total, a plain number.
Do not invent lines. Answer with JSON only.

Note: """${text}"""`;

export async function readNote(text, { lang = 'en', online, knownNames = [] }) {
  const t0 = performance.now();
  const trace = [];
  const rules = parseRules(text);
  trace.push(`Offline rules found ${rules.lines.length} line(s) instantly.`);
  const status = await deviceStatus(false);
  const langOk = settings.deviceLanguages.includes(lang);
  trace.push(`Gemini Nano on this device: ${status}. Network: ${online ? 'online' : 'offline'}.`);

  let mode = null;
  let send = text;
  let map = null;
  let hidden = 0;
  if (status === 'available' && langOk) {
    mode = InferenceMode.ONLY_ON_DEVICE;
    trace.push(`${LANG_NAME[lang]} note and Gemini Nano is ready: ONLY_ON_DEVICE. Nothing leaves this device.`);
  } else if (online) {
    const h = hideNames(text, [...knownNames, ...rules.names]);
    send = h.text;
    map = h.map;
    hidden = h.count;
    mode = InferenceMode.ONLY_IN_CLOUD;
    trace.push(status === 'available'
      ? `Gemini Nano here is set up for ${settings.deviceLanguages.join(', ')}, not ${LANG_NAME[lang]}, so this note goes to the cloud.`
      : 'No on-device model here, so this note goes to the cloud.');
    trace.push(`Before sending, ${hidden} name(s) and phone number(s) were replaced on this device (e.g. “C1”).`);
  } else {
    trace.push('Offline and no on-device model for this language: the offline rules answer.');
  }

  if (mode !== null) {
    try {
      const r = await run(mode, 'lines', NOTE_PROMPT(send, lang, settings.maxLines), lang, 'read_note');
      let lines = cleanLines(r.data, settings.maxLines);
      if (map) lines = lines.map((l) => ({ ...l, customer: restoreNames(l.customer, map), item: restoreNames(l.item, map) }));
      if (lines.length) {
        if (map) trace.push('Names were put back on this device.');
        return { lines, source: r.source, ms: r.ms, trace, hidden };
      }
      trace.push('The AI found no lines; using the offline rules.');
    } catch (e) {
      trace.push(`The AI did not answer (${e?.message || 'error'}); using the offline rules.`);
    }
  }
  return { lines: rules.lines.map((l) => ({ qty: null, unit_price: null, customer: null, ...l })), source: 'rules', ms: Math.round(performance.now() - t0), trace, hidden };
}

// ---------- 3. Monthly record (totals only) ----------

export async function writeRecord(totals, { lang = 'en', online }) {
  const trace = ['Built from totals only: no customer names, no single entries.'];
  const prompt = `You help a market vendor show their monthly business record to a microfinance officer.
Write in ${LANG_NAME[lang] || 'English'}, plain and factual, 3 to 5 sentences: sales, costs, profit, the trend across the weeks, the best-selling items and money still owed by customers.
Then up to 4 short highlights. Do not give financial advice and do not recommend loans. Use only these numbers.
Totals (currency ${totals.currency}): ${JSON.stringify(totals)}
Answer with JSON only.`;
  const status = await deviceStatus(false);
  const langOk = settings.deviceLanguages.includes(lang);
  let mode;
  if (status === 'available' && langOk) {
    mode = InferenceMode.PREFER_ON_DEVICE;
    trace.push('Gemini Nano is ready: PREFER_ON_DEVICE.');
  } else if (online) {
    mode = InferenceMode.PREFER_IN_CLOUD;
    trace.push(`${status === 'available' ? `Gemini Nano here is not set up for ${LANG_NAME[lang]}` : 'No on-device model here'}: PREFER_IN_CLOUD with the totals only.`);
  } else {
    trace.push('Offline and no on-device model: a plain template summary.');
    return { summary: templateRecord(totals), highlights: [], source: 'rules', ms: 0, trace };
  }
  try {
    const r = await run(mode, 'report', prompt, lang, 'month_record');
    return {
      summary: String(r.data.summary || '').slice(0, 1800) || templateRecord(totals),
      highlights: (Array.isArray(r.data.highlights) ? r.data.highlights : []).slice(0, 4).map((h) => String(h).slice(0, 160)),
      source: r.source, ms: r.ms, trace,
    };
  } catch (e) {
    trace.push(`The AI did not answer (${e?.message || 'error'}): template summary.`);
    return { summary: templateRecord(totals), highlights: [], source: 'rules', ms: 0, trace };
  }
}

function templateRecord(t) {
  const c = t.currency;
  return `In ${t.month}, sales were ${fmt(t.sales)} ${c} and costs ${fmt(t.expenses)} ${c}, so profit was ${fmt(t.profit)} ${c}. `
    + `Sales were recorded on ${t.days_recorded} day(s). Customers still owe ${fmt(t.owed)} ${c}.`;
}

// ---------- 4. Polite reminder for a customer who owes money ----------

export async function writeReminder({ name, amount, currency, lang = 'ht', online }) {
  const trace = [];
  const prompt = `Write one short, warm, respectful message (max 40 words) from a market vendor to a regular customer called {NAME}, reminding them they owe ${fmt(amount)} ${currency}.
Write it in ${LANG_NAME[lang] || 'Haitian Creole'}. Keep the token {NAME} exactly as it is. No threats, no interest, no pressure. JSON only.`;
  const status = await deviceStatus(false);
  const langOk = settings.deviceLanguages.includes(lang);
  let mode = null;
  if (status === 'available' && langOk) {
    mode = InferenceMode.ONLY_ON_DEVICE;
    trace.push('Written on this device (ONLY_ON_DEVICE).');
  } else if (online) {
    mode = InferenceMode.ONLY_IN_CLOUD;
    trace.push('Written in the cloud. The customer’s name was never sent: the AI only saw “{NAME}”, filled in here.');
  }
  if (mode !== null) {
    try {
      const r = await run(mode, 'message', prompt, lang, 'reminder');
      const msg = String(r.data.message || '').replace(/\{NAME\}/g, name).slice(0, 400);
      if (msg) return { message: msg, source: r.source, ms: r.ms, trace };
    } catch (e) {
      trace.push(`The AI did not answer (${e?.message || 'error'}).`);
    }
  }
  trace.push('Template message used.');
  return { message: templateReminder(name, amount, currency, lang), source: 'rules', ms: 0, trace };
}

function templateReminder(name, amount, c, lang) {
  const a = `${fmt(amount)} ${c}`;
  return {
    ht: `Bonjou ${name}, se mwen menm nan mache a. Ti rapèl: ou gen ${a} ki rete pou peye. Lè ou kapab, pa gen pwoblèm. Mèsi anpil!`,
    fr: `Bonjour ${name}, petit rappel amical : il reste ${a} à régler. Quand vous pourrez. Merci beaucoup !`,
    es: `Hola ${name}, un recordatorio amable: quedan ${a} por pagar. Cuando pueda. ¡Muchas gracias!`,
  }[lang] || `Hello ${name}, a friendly reminder: ${a} is still open. Whenever you can. Thank you!`;
}

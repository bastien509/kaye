// Offline rules: turn a short note into notebook lines with no AI and no network,
// and hide customer names and phone numbers before any cloud call.
// Works in English, French, Haitian Kreyòl and Spanish.

const NUM = String.raw`(\d{1,3}(?:[ ,.]\d{3})+|\d+(?:[.,]\d{1,2})?)`;
const NAME = String.raw`((?:(?:Madan|Madam|Msye|Mme|Mr|Mrs|Ms|Doña|Don|Sr|Sra|Manmi|Papi|Tonton|Tant|Ti)\.?\s+)?[A-ZÀ-ÝÒ][\p{L}'-]+(?:\s+[A-ZÀ-ÝÒ][\p{L}'-]+)?)`;

const VERB = {
  owes: String.raw`owes|owe|dwe|doit|debe|took on credit|pran kredi|a pris à crédit`,
  paid: String.raw`paid back|paid|peye|remèt|a payé|a remboursé|pagó|pago|devolvió`,
  sold: String.raw`sold|sell|vann|vand|vendu|vends|vendí|vendi|vendió`,
  bought: String.raw`bought|buy|paid for|achte|peye pou|acheté|achat|compré|compre|pagué`,
};
const EACH = String.raw`each|apiece|chak|chacun|chacune|la pièce|cada|c\/u`;
const LINK = String.raw`at|for|a|à|pou|pour|por|=|:|x`;

export function toNumber(s) {
  if (s == null) return null;
  let t = String(s).trim().replace(/\s/g, '');
  // "1,500" / "1.500" as thousands; "12,50" as decimals.
  if (/^\d{1,3}([.,]\d{3})+$/.test(t)) t = t.replace(/[.,]/g, '');
  else t = t.replace(',', '.');
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
}

const cleanItem = (s) => String(s || '')
  .replace(/\b(of|de|d'|bags?|sak|mamit|marmites?|cups?|gode|lbs?|liv|kg|pieces?|pyès)\b/gi, (m) => m) // keep units, they are useful
  .replace(/\s{2,}/g, ' ')
  .replace(/^[\s,:-]+|[\s,:-]+$/g, '')
  .slice(0, 60);

function splitClauses(text) {
  return String(text)
    .replace(/\r/g, '')
    .split(/\n|;|\.(?=\s|$)|,\s*(?=[A-ZÀ-ÝÒ])|\s+(?:and|epi|ak|et|y)\s+(?=[A-ZÀ-ÝÒ]|(?:sold|vann|vendu|vendí|bought|achte|acheté|compré)\b)/u)
    .map((c) => c.trim())
    .filter(Boolean);
}

/** @returns {{lines: Array, names: string[]}} */
export function parseRules(text) {
  const lines = [];
  const names = new Set();
  for (const c of splitClauses(text)) {
    let m;
    // "Marie owes 500" / "Madan Pòl dwe 1 200"
    if ((m = c.match(new RegExp(`^${NAME}\\s+(?:${VERB.owes})\\s+(?:me\\s+|mwen\\s+|moi\\s+|m\\s+)?${NUM}`, 'iu')))) {
      names.add(m[1]);
      lines.push({ kind: 'credit', item: 'Credit', amount: toNumber(m[2]), customer: m[1] });
      continue;
    }
    // "Marie paid 200" / "Jean peye 300"
    if ((m = c.match(new RegExp(`^${NAME}\\s+(?:${VERB.paid})\\s+(?:me\\s+|mwen\\s+)?${NUM}`, 'iu')))) {
      names.add(m[1]);
      lines.push({ kind: 'payment', item: 'Payment', amount: toNumber(m[2]), customer: m[1] });
      continue;
    }
    // "Sold 3 bags of rice, 250 each" / "vann 2 pwa 300" / "sold rice 750"
    if ((m = c.match(new RegExp(`(?:${VERB.sold})\\s+(?:(\\d+)\\s+)?(.+?)[,\\s]+(?:(?:${LINK})\\s+)?${NUM}\\s*(?:goud|gourdes?|htg|g|\\$|usd|dollars?|pesos?)?\\s*(${EACH})?\\s*$`, 'iu')))) {
      lines.push(makeLine('sale', m[1], m[2], m[3], !!m[4]));
      continue;
    }
    // "Bought 2 sacks of flour for 3000" / "achte chabon 500"
    if ((m = c.match(new RegExp(`(?:${VERB.bought})\\s+(?:(\\d+)\\s+)?(.+?)[,\\s]+(?:(?:${LINK})\\s+)?${NUM}\\s*(?:goud|gourdes?|htg|g|\\$|usd|dollars?|pesos?)?\\s*(${EACH})?\\s*$`, 'iu')))) {
      lines.push(makeLine('expense', m[1], m[2], m[3], !!m[4]));
      continue;
    }
  }
  return { lines: lines.filter((l) => l.amount != null && l.amount > 0), names: [...names] };
}

function makeLine(kind, qtyS, itemS, numS, each) {
  const qty = qtyS ? Number(qtyS) : null;
  const n = toNumber(numS);
  const unitPrice = each ? n : null;
  const amount = each && qty ? qty * n : n;
  return { kind, item: cleanItem(itemS) || (kind === 'sale' ? 'Sale' : 'Expense'), qty, unit_price: unitPrice, amount };
}

/**
 * Replaces customer names and phone numbers with tokens before a cloud call.
 * @returns {{text: string, map: Record<string,string>, count: number}}
 */
export function hideNames(text, knownNames = []) {
  let out = String(text);
  let count = 0;
  const map = {};
  out = out.replace(/(?:\+?509[\s-]?)?\b\d{4}[\s-]?\d{4}\b|\+?\d[\d\s().-]{8,}\d/g, () => { count++; return '[phone]'; });
  const found = new Set([...knownNames.filter((n) => n && n.length > 1), ...parseRules(out).names]);
  const sorted = [...found].sort((a, b) => b.length - a.length);
  let i = 0;
  for (const name of sorted) {
    const re = new RegExp(`(^|[^\\p{L}])${escapeRe(name)}(?=$|[^\\p{L}])`, 'giu');
    if (!re.test(out)) continue;
    const token = `C${++i}`;
    map[token] = name;
    out = out.replace(new RegExp(`(^|[^\\p{L}])${escapeRe(name)}(?=$|[^\\p{L}])`, 'giu'), (_, pre) => { count++; return `${pre}${token}`; });
  }
  return { text: out, map, count };
}

export function restoreNames(value, map) {
  if (!value || !map) return value;
  return String(value).replace(/\bC(\d+)\b/g, (t) => map[t] || t);
}

const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export const SAMPLE_NOTES = [
  { lang: 'en', label: 'EN · market day', text: 'Sold 3 bags of rice, 250 each. Sold beans 600. Bought charcoal for 400. Marie owes 500.' },
  { lang: 'ht', label: 'HT · jounen mache', text: 'Vann 4 mamit diri 250 chak. Vann lwil 450. Achte chabon 300. Madan Pòl dwe 1200, Jean peye 300.' },
  { lang: 'fr', label: 'FR · journée', text: 'Vendu 2 sacs de farine 1500 chacun. Acheté transport 250. Rose doit 700.' },
  { lang: 'es', label: 'ES · día de venta', text: 'Vendí 5 plátanos 25 cada. Compré bolsas por 100. Ana pagó 200.' },
];

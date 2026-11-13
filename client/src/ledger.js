// Notebook math: totals, weeks, top items and who owes what. Pure functions, no network.

export const today = () => isoDate(new Date());
export function isoDate(d) {
  const z = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${z(d.getMonth() + 1)}-${z(d.getDate())}`;
}
export const monthOf = (date) => String(date).slice(0, 7);
export function monthRange(month) {
  const [y, m] = month.split('-').map(Number);
  const last = new Date(y, m, 0).getDate();
  return { start: `${month}-01`, end: `${month}-${String(last).padStart(2, '0')}`, days: last };
}
export function shiftMonth(month, delta) {
  const [y, m] = month.split('-').map(Number);
  const d = new Date(y, m - 1 + delta, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

/** Sales, costs, profit, weeks and top items for a list of entries (one month or one day). */
export function totals(entries, { month = '', currency = 'HTG', owed = 0, owingCount = 0 } = {}) {
  let sales = 0;
  let expenses = 0;
  let credit = 0;
  let payments = 0;
  const weeks = [0, 0, 0, 0, 0];
  const items = new Map();
  const days = new Set();
  const src = { on_device: 0, in_cloud: 0, rules: 0, manual: 0 };
  for (const e of entries) {
    const a = Number(e.amount) || 0;
    src[e.ai] = (src[e.ai] || 0) + 1;
    if (e.kind === 'sale') {
      sales += a;
      days.add(e.date);
      const w = Math.min(4, Math.floor((Number(String(e.date).slice(8, 10)) - 1) / 7));
      weeks[w] += a;
      const k = String(e.item || 'Item').toLowerCase();
      items.set(k, { item: e.item || 'Item', amount: (items.get(k)?.amount || 0) + a });
    } else if (e.kind === 'expense') expenses += a;
    else if (e.kind === 'credit') credit += a;
    else if (e.kind === 'payment') payments += a;
  }
  const top = [...items.values()].sort((x, y) => y.amount - x.amount).slice(0, 5);
  const aiCount = src.on_device + src.in_cloud;
  return {
    month, currency,
    sales: round(sales), expenses: round(expenses), profit: round(sales - expenses),
    credit_given: round(credit), payments_received: round(payments),
    owed: round(owed), customers_owing: owingCount,
    by_week: weeks.map(round), top_items: top.map((t) => ({ item: t.item, amount: round(t.amount) })),
    days_recorded: days.size, entries: entries.length,
    sources: src,
    on_device_share: aiCount ? Math.round((100 * src.on_device) / aiCount) : null,
  };
}

/** Who owes me: credit minus payments per customer. */
export function balances(creditEntries, customers) {
  const byId = new Map(customers.map((c) => [c.id, { ...c, owed: 0, last: '' }]));
  for (const e of creditEntries) {
    if (!e.customerId) continue;
    if (!byId.has(e.customerId)) byId.set(e.customerId, { id: e.customerId, name: '(deleted)', owed: 0, last: '' });
    const c = byId.get(e.customerId);
    c.owed += (e.kind === 'credit' ? 1 : -1) * (Number(e.amount) || 0);
    if (e.date > c.last) c.last = e.date;
  }
  return [...byId.values()].map((c) => ({ ...c, owed: round(c.owed) })).sort((a, b) => b.owed - a.owed);
}

const round = (n) => Math.round(n * 100) / 100;

export function money(n, currency = 'HTG') {
  const v = Number(n) || 0;
  const s = v.toLocaleString('en-US', { maximumFractionDigits: v % 1 ? 2 : 0 });
  return currency === 'USD' ? `$${s}` : `${s} ${currency === 'HTG' ? 'G' : currency}`;
}

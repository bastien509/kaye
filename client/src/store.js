// Firestore data layer. Path: users/{uid}/{entries|customers|reports}. Security rules allow only the owner.
// Firestore's offline cache means every write lands instantly on the device and syncs when there is signal.
import {
  collection, doc, setDoc, getDoc, getDocs, addDoc, deleteDoc, writeBatch, onSnapshot, query, where, orderBy,
  serverTimestamp, Timestamp,
} from 'firebase/firestore';
import { db, track } from './firebase.js';
import { monthRange, isoDate } from './ledger.js';

const col = (uid, name) => collection(db, 'users', uid, name);

export async function loadProfile(uid) {
  try {
    const s = await getDoc(doc(db, 'users', uid));
    return s.exists() ? s.data() : null;
  } catch {
    return null;
  }
}

export function saveProfile(uid, p) {
  const data = {
    name: str(p.name, 80), business: str(p.business, 80), currency: str(p.currency || 'HTG', 8),
    market: str(p.market, 80), lang: str(p.lang || 'en', 5), updatedAt: serverTimestamp(),
  };
  if (p.isNew) data.createdAt = serverTimestamp();
  return setDoc(doc(db, 'users', uid), data, { merge: true });
}

/** Live entries for one month (works offline from the local cache). */
export function watchMonth(uid, month, cb, onError) {
  const { start, end } = monthRange(month);
  const q = query(col(uid, 'entries'), where('date', '>=', start), where('date', '<=', end), orderBy('date', 'desc'));
  return onSnapshot(q, { includeMetadataChanges: true }, (snap) => {
    cb(snap.docs.map((d) => ({ id: d.id, ...d.data(), pending: d.metadata.hasPendingWrites })), snap.metadata);
  }, onError);
}

export function watchCredit(uid, cb, onError) {
  const q = query(col(uid, 'entries'), where('kind', 'in', ['credit', 'payment']));
  return onSnapshot(q, (snap) => cb(snap.docs.map((d) => ({ id: d.id, ...d.data() }))), onError);
}

export function watchCustomers(uid, cb, onError) {
  return onSnapshot(col(uid, 'customers'), (snap) => cb(snap.docs.map((d) => ({ id: d.id, ...d.data() }))), onError);
}

export async function getMonth(uid, month) {
  const { start, end } = monthRange(month);
  const snap = await getDocs(query(col(uid, 'entries'), where('date', '>=', start), where('date', '<=', end)));
  return snap.docs.map((d) => ({ id: d.id, ...d.data() }));
}

/** Finds or creates customers by name, then saves all lines in one batch. Returns the count. */
export async function saveLines(uid, lines, { date, method, ai, customers, pageId = null }) {
  const byName = new Map(customers.map((c) => [c.name.trim().toLowerCase(), c.id]));
  const batch = writeBatch(db);
  let n = 0;
  for (const l of lines) {
    let customerId = null;
    if ((l.kind === 'credit' || l.kind === 'payment') && l.customer) {
      const key = l.customer.trim().toLowerCase();
      customerId = byName.get(key);
      if (!customerId) {
        const ref = doc(col(uid, 'customers'));
        batch.set(ref, { name: str(l.customer, 80), createdAt: serverTimestamp() });
        customerId = ref.id;
        byName.set(key, customerId);
      }
    }
    const e = {
      kind: l.kind, item: str(l.item, 120) || null, amount: Math.round(Number(l.amount) * 100) / 100,
      qty: l.qty ?? null, unitPrice: l.unit_price ?? l.unitPrice ?? null,
      customerId, date: date || isoDate(new Date()), method, ai, note: l.note ? str(l.note, 300) : null,
      pageId, createdAt: serverTimestamp(),
    };
    batch.set(doc(col(uid, 'entries')), e);
    n++;
  }
  // Do not await the server: offline, the batch is committed locally and syncs later.
  batch.commit().catch(() => {});
  track('entries_saved', { count: n, method, ai });
  return n;
}

export function addCustomer(uid, name, phone = '') {
  const ref = doc(col(uid, 'customers'));
  setDoc(ref, { name: str(name, 80), phone: phone ? str(phone, 32) : null, createdAt: serverTimestamp() }).catch(() => {});
  return ref.id;
}

export function deleteEntry(uid, id) {
  return deleteDoc(doc(db, 'users', uid, 'entries', id)).catch(() => {});
}

export function saveReport(uid, month, r, totals) {
  const t = { ...totals };
  delete t.sources;
  return setDoc(doc(db, 'users', uid, 'reports', month), {
    month, summary: str(r.summary, 2000), highlights: (r.highlights || []).slice(0, 8).map((h) => str(h, 200)),
    ai: r.source === 'on_device' || r.source === 'in_cloud' ? r.source : 'rules', ms: r.ms || 0,
    totals: t, createdAt: serverTimestamp(),
  }).catch(() => {});
}

export async function loadReport(uid, month) {
  try {
    const s = await getDoc(doc(db, 'users', uid, 'reports', month));
    return s.exists() ? s.data() : null;
  } catch {
    return null;
  }
}

/** Deletes every entry, customer and report of this notebook (used by "Clear" and the demo reset). */
export async function clearNotebook(uid) {
  for (const name of ['entries', 'customers', 'reports']) {
    const snap = await getDocs(col(uid, name));
    let batch = writeBatch(db);
    let n = 0;
    for (const d of snap.docs) {
      batch.delete(d.ref);
      if (++n % 400 === 0) { await batch.commit(); batch = writeBatch(db); }
    }
    await batch.commit();
  }
}

/** Writes about six weeks of invented market entries so judges can explore without typing. */
export async function seedSample(uid) {
  const people = ['Marie Joseph', 'Madan Pòl', 'Jean Baptiste', 'Rose Michel', 'Ti Jak'];
  const goods = [['Diri (rice)', 250], ['Pwa (beans)', 300], ['Lwil (oil)', 450], ['Sik (sugar)', 75], ['Farin (flour)', 1500], ['Savon (soap)', 60]];
  const costs = [['Transport', 250], ['Chabon (charcoal)', 400], ['Stock: diri', 4500], ['Market fee', 100]];
  const batch = writeBatch(db);
  const custIds = people.map((name) => {
    const ref = doc(col(uid, 'customers'));
    batch.set(ref, { name, createdAt: serverTimestamp() });
    return ref.id;
  });
  let seed = 7;
  const rnd = () => { seed = (seed * 9301 + 49297) % 233280; return seed / 233280; };
  const now = new Date();
  const methods = ['photo', 'photo', 'text', 'manual'];
  const ais = ['on_device', 'on_device', 'on_device', 'in_cloud', 'rules'];
  for (let back = 40; back >= 0; back--) {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - back);
    if (d.getDay() === 0) continue; // closed on Sunday
    const date = isoDate(d);
    const ts = Timestamp.fromDate(new Date(d.getTime() + 9 * 3600e3));
    const method = methods[Math.floor(rnd() * methods.length)];
    const ai = method === 'manual' ? 'manual' : ais[Math.floor(rnd() * ais.length)];
    const n = 2 + Math.floor(rnd() * 3);
    for (let i = 0; i < n; i++) {
      const [item, price] = goods[Math.floor(rnd() * goods.length)];
      const qty = 1 + Math.floor(rnd() * 4);
      batch.set(doc(col(uid, 'entries')), { kind: 'sale', item, qty, unitPrice: price, amount: qty * price, customerId: null, date, method, ai, note: null, pageId: null, createdAt: ts });
    }
    if (rnd() < 0.5) {
      const [item, amount] = costs[Math.floor(rnd() * costs.length)];
      batch.set(doc(col(uid, 'entries')), { kind: 'expense', item, qty: null, unitPrice: null, amount, customerId: null, date, method, ai, note: null, pageId: null, createdAt: ts });
    }
    if (rnd() < 0.3) {
      const c = Math.floor(rnd() * people.length);
      const amount = [300, 500, 750, 1200][Math.floor(rnd() * 4)];
      batch.set(doc(col(uid, 'entries')), { kind: 'credit', item: 'Credit', qty: null, unitPrice: null, amount, customerId: custIds[c], date, method, ai, note: null, pageId: null, createdAt: ts });
    }
    if (rnd() < 0.2) {
      const c = Math.floor(rnd() * people.length);
      batch.set(doc(col(uid, 'entries')), { kind: 'payment', item: 'Payment', qty: null, unitPrice: null, amount: 200, customerId: custIds[c], date, method: 'manual', ai: 'manual', note: null, pageId: null, createdAt: ts });
    }
  }
  await batch.commit();
  track('sample_loaded');
}

function str(v, max) {
  if (v == null) return '';
  return String(v).trim().slice(0, max);
}

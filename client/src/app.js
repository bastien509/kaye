// Kaye web app: an offline-first shop notebook with hybrid on-device / cloud AI.
import {
  onAuthStateChanged, signInAnonymously, signInWithEmailAndPassword, createUserWithEmailAndPassword,
  EmailAuthProvider, linkWithCredential, signOut,
} from 'firebase/auth';
import { auth, settings, loadRemoteConfig, track } from './firebase.js';
import { deviceStatus, prepareDevice, readPage, readNote, writeRecord, writeReminder, checkLines } from './ai.js';
import { SAMPLE_NOTES } from './rules.js';
import { SAMPLE_PAGES, drawSample, toAiImage } from './samples.js';
import { today, monthOf, shiftMonth, totals, balances, money } from './ledger.js';
import * as store from './store.js';

const DEMO_EMAIL = 'demo@petiht.com';
const DEMO_PASS = 'KayeDemo509';
const LANGS = { en: 'English', ht: 'Kreyòl', fr: 'Français', es: 'Español' };
const KIND = { sale: 'Sale', expense: 'Cost', credit: 'Credit', payment: 'Paid back' };
const SRC = { on_device: 'On this device', in_cloud: 'Cloud', rules: 'Offline rules', manual: 'By hand' };

const S = {
  user: null, profile: null, month: monthOf(today()), entries: [], credit: [], customers: [],
  online: navigator.onLine, pending: false, fromCache: false, nano: 'unsupported', rc: 'defaults', unsub: [],
  draft: null, // { lines, check, source, ms, trace, method, image }
};

const $ = (sel, el = document) => el.querySelector(sel);
const view = () => $('#view');

function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (k === 'value') el.value = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat(Infinity)) {
    if (kid == null || kid === false) continue;
    el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return el;
}
const icon = (name) => {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('class', 'ic');
  s.setAttribute('aria-hidden', 'true');
  const u = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  u.setAttribute('href', `#i-${name}`);
  s.append(u);
  return s;
};
const cur = () => S.profile?.currency || 'HTG';
const m$ = (n) => money(n, cur());

function toast(msg, kind = '') {
  const t = $('#toast');
  t.textContent = msg;
  t.className = `toast show ${kind}`;
  clearTimeout(toast.t);
  toast.t = setTimeout(() => { t.className = 'toast'; }, 4200);
}

function srcBadge(source, ms) {
  return h('span', { class: `badge src-${source}` }, icon(source === 'on_device' ? 'chip' : source === 'in_cloud' ? 'cloud' : source === 'rules' ? 'list' : 'pen'),
    SRC[source] || source, ms ? h('small', null, ` · ${ms} ms`) : null);
}

// ---------------- boot ----------------

window.addEventListener('online', () => { S.online = true; renderStatus(); });
window.addEventListener('offline', () => { S.online = false; renderStatus(); });
window.addEventListener('hashchange', () => render());

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}

// ---------------- installed app: install button, shared photos, opened files ----------------

/** A photo handed to Kaye from outside (Android share sheet, "Open with" on desktop). Snap picks it up. */
function receivePhoto(blob, label) {
  if (!blob || !/^image\/(png|jpeg)$/.test(blob.type)) { toast('Kaye reads PNG or JPEG photos.', 'bad'); return; }
  S.incoming = { blob, label: label || 'Shared photo' };
  if (route() === 'snap') render(); else location.hash = '#snap';
}

// Android share sheet → the service worker stores the photo, then opens app.html?shared=1#snap.
if (new URLSearchParams(location.search).has('shared')) {
  history.replaceState(null, '', location.pathname + (location.hash || '#snap'));
  caches.open('kaye-share').then(async (c) => {
    const hit = await c.match('shared-photo');
    if (!hit) return;
    await c.delete('shared-photo');
    receivePhoto(await hit.blob(), 'Shared photo');
  }).catch(() => {});
}

// Desktop "Open with Kaye" (manifest file_handlers).
if ('launchQueue' in window) {
  window.launchQueue.setConsumer(async (params) => {
    const handle = params.files?.[0];
    if (handle) { const f = await handle.getFile(); receivePhoto(f, f.name); }
  });
}

// Paste a photo (Ctrl+V) while on Snap.
document.addEventListener('paste', (e) => {
  if (route() !== 'snap' || !S.snapTake) return;
  const item = [...(e.clipboardData?.items || [])].find((i) => i.kind === 'file');
  if (item) S.snapTake(item.getAsFile(), 'Pasted photo');
});

window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); S.installEvt = e; renderStatus(); });
window.addEventListener('appinstalled', () => { S.installEvt = null; renderStatus(); track('app_installed', {}); toast('Kaye is installed.', 'ok'); });
async function installApp() {
  const e = S.installEvt;
  if (!e) return;
  S.installEvt = null;
  e.prompt();
  await e.userChoice.catch(() => null);
  renderStatus();
}

loadRemoteConfig().then((r) => { S.rc = r; if (route() === 'settings') render(); });
refreshNano();

onAuthStateChanged(auth, async (user) => {
  S.unsub.forEach((u) => u());
  S.unsub = [];
  S.user = user;
  S.entries = []; S.credit = []; S.customers = []; S.profile = null;
  document.body.classList.remove('signed-in');
  $('#boot')?.remove();
  if (!user) { renderAuth(); return; }
  S.profile = await store.loadProfile(user.uid);
  if (!S.profile) { renderOnboarding(); return; }
  startWatching();
  render();
});

function startWatching() {
  const uid = S.user.uid;
  S.unsub.push(store.watchMonth(uid, S.month, (list, meta) => {
    S.entries = list;
    S.pending = meta.hasPendingWrites;
    S.fromCache = meta.fromCache;
    renderStatus();
    if (['home', 'month'].includes(route())) render();
  }, onDbError));
  S.unsub.push(store.watchCredit(uid, (list) => { S.credit = list; if (['home', 'owed'].includes(route())) render(); }, onDbError));
  S.unsub.push(store.watchCustomers(uid, (list) => { S.customers = list; if (['owed'].includes(route())) render(); }, onDbError));
}
function onDbError(e) { toast(`Could not read your notebook: ${e.code || e.message}`, 'bad'); }

async function refreshNano() {
  S.nano = await deviceStatus(true);
  if (S.nano === 'unavailable' || S.nano === 'unsupported') S.nano = await deviceStatus(false).then((s) => (s === 'available' ? 'text-only' : S.nano));
  renderStatus();
}

const route = () => (location.hash.replace('#', '') || 'home').split('/')[0];

// ---------------- chrome ----------------

function renderStatus() {
  const net = $('#net');
  if (!net) return;
  net.replaceChildren(
    h('span', { class: `chip ${S.online ? 'ok' : 'warn'}` }, icon(S.online ? 'wifi' : 'nowifi'), S.online ? 'Online' : 'No signal'),
    h('span', { class: `chip ${S.pending ? 'warn' : 'ok'}` }, icon(S.pending ? 'clock' : 'check'), S.pending ? 'Waiting to sync' : 'Synced'),
  );
  const nano = $('#nano');
  if (nano) {
    const map = {
      available: ['ok', 'Gemini Nano ready'], 'text-only': ['ok', 'Gemini Nano (text)'], downloadable: ['', 'Gemini Nano: download'],
      downloading: ['', 'Gemini Nano: downloading'], unavailable: ['muted', 'No on-device model'], unsupported: ['muted', 'No on-device model'],
    };
    const [cls, label] = map[S.nano] || map.unsupported;
    nano.replaceChildren(h('a', { class: `chip ${cls}`, href: '#settings' }, icon('chip'), label),
      ...(S.installEvt ? [h('button', { class: 'chip install', type: 'button', onclick: installApp }, icon('download'), 'Install Kaye')] : []));
  }
}

function render() {
  if (!S.user || !S.profile) return;
  document.body.classList.add('signed-in');
  const r = route();
  document.querySelectorAll('.tabs a').forEach((a) => a.classList.toggle('on', a.dataset.r === r));
  const v = view();
  v.replaceChildren();
  v.className = `view v-${r}`;
  const fn = { home: viewHome, snap: viewSnap, note: viewNote, owed: viewOwed, month: viewMonth, settings: viewSettings }[r] || viewHome;
  v.append(fn());
  renderStatus();
  track('screen_view', { screen_name: r });
}

// ---------------- auth ----------------

function renderAuth() {
  const v = view();
  v.className = 'view v-auth';
  let mode = 'in';
  const err = h('p', { class: 'err', role: 'alert' });
  const email = h('input', { type: 'email', autocomplete: 'username', placeholder: 'Email', required: true });
  const pass = h('input', { type: 'password', autocomplete: 'current-password', placeholder: 'Password (6+ characters)', required: true, minlength: '6' });
  const submit = h('button', { class: 'btn primary wide', type: 'submit' }, 'Sign in');
  const switcher = h('button', { class: 'link', type: 'button', onclick: () => {
    mode = mode === 'in' ? 'up' : 'in';
    submit.textContent = mode === 'in' ? 'Sign in' : 'Create my notebook';
    switcher.textContent = mode === 'in' ? 'New here? Create an account' : 'I already have an account';
    pass.autocomplete = mode === 'in' ? 'current-password' : 'new-password';
  } }, 'New here? Create an account');
  const form = h('form', { class: 'auth-form', onsubmit: async (e) => {
    e.preventDefault();
    err.textContent = '';
    submit.disabled = true;
    try {
      if (mode === 'in') await signInWithEmailAndPassword(auth, email.value.trim(), pass.value);
      else await createUserWithEmailAndPassword(auth, email.value.trim(), pass.value);
      track(mode === 'in' ? 'login' : 'sign_up', { method: 'password' });
    } catch (x) {
      err.textContent = authMessage(x);
    } finally {
      submit.disabled = false;
    }
  } }, email, pass, submit, err, switcher);

  v.replaceChildren(h('section', { class: 'auth' },
    h('div', { class: 'auth-brand' },
      h('img', { src: 'assets/logo.svg', alt: '', width: '64', height: '64' }),
      h('h1', null, 'Kaye'),
      h('p', { class: 'hand' }, 'Kaye ou, ki konn kalkile.'),
      h('p', null, 'Your shop notebook that reads your pages on your phone, even with no signal.')),
    h('div', { class: 'auth-card' },
      h('button', { class: 'btn primary wide big', type: 'button', onclick: async (e) => {
        const b = e.currentTarget;
        b.disabled = true;
        try { await signInAnonymously(auth); track('login', { method: 'anonymous' }); } catch (x) { err.textContent = authMessage(x); b.disabled = false; }
      } }, icon('spark'), 'Try it now, no account'),
      h('p', { class: 'muted small center' }, 'You get your own private notebook. Save it with an email later.'),
      h('div', { class: 'or' }, h('span', null, 'or')),
      form,
      h('div', { class: 'judges' },
        h('b', null, 'Judges'),
        h('p', null, 'Demo account: ', h('code', null, DEMO_EMAIL), ' / ', h('code', null, DEMO_PASS), '. Sample data is invented.'),
        h('button', { class: 'link', type: 'button', onclick: () => { email.value = DEMO_EMAIL; pass.value = DEMO_PASS; mode = 'up'; switcher.click(); } }, 'Fill in the demo account')))));
}

function authMessage(x) {
  const c = x?.code || '';
  if (c.includes('invalid-credential') || c.includes('wrong-password') || c.includes('user-not-found')) return 'Wrong email or password.';
  if (c.includes('email-already-in-use')) return 'That email already has a notebook. Sign in instead.';
  if (c.includes('weak-password')) return 'Use at least 6 characters.';
  if (c.includes('network')) return 'No connection. Sign in once with signal; after that Kaye works offline.';
  if (c.includes('too-many-requests')) return 'Too many tries. Wait a few minutes.';
  return 'Could not sign in. Try again.';
}

function renderOnboarding() {
  const v = view();
  v.className = 'view v-auth';
  const anon = S.user.isAnonymous;
  const name = h('input', { placeholder: 'Your name', maxlength: '80', value: anon ? '' : (S.user.email === DEMO_EMAIL ? 'Demo vendor' : '') });
  const business = h('input', { placeholder: 'Business (e.g. Rice & beans stall)', maxlength: '80' });
  const market = h('input', { placeholder: 'Market (e.g. Marché Cluny, Cap-Haïtien)', maxlength: '80' });
  const currency = select(['HTG', 'USD', 'DOP', 'EUR', 'XOF', 'MXN'], 'HTG');
  const lang = select(Object.keys(LANGS), 'ht', LANGS);
  const sample = h('input', { type: 'checkbox', checked: true });
  const btn = h('button', { class: 'btn primary wide big', type: 'submit' }, 'Open my notebook');
  v.replaceChildren(h('section', { class: 'auth' },
    h('div', { class: 'auth-brand' }, h('img', { src: 'assets/logo.svg', alt: '', width: '56', height: '56' }), h('h1', null, 'Welcome'), h('p', null, 'Three quick things, then your notebook is ready.')),
    h('form', { class: 'auth-card', onsubmit: async (e) => {
      e.preventDefault();
      btn.disabled = true;
      const p = { name: name.value, business: business.value || 'My stall', market: market.value, currency: currency.value, lang: lang.value, isNew: true };
      store.saveProfile(S.user.uid, p).catch(() => {});
      S.profile = { ...p };
      if (sample.checked) { try { await store.seedSample(S.user.uid); } catch { /* offline: still fine */ } }
      startWatching();
      location.hash = '#home';
      render();
    } },
    field('Name', name), field('Business', business), field('Market', market),
    h('div', { class: 'row2' }, field('Currency', currency), field('Language', lang)),
    h('label', { class: 'check' }, sample, ' Load a sample notebook (6 weeks of invented sales) so you can explore'),
    btn)));
}

function select(options, value, labels) {
  const s = h('select', null, options.map((o) => h('option', { value: o }, labels ? labels[o] : o)));
  s.value = value;
  return s;
}
const field = (label, input) => h('label', { class: 'field' }, h('span', null, label), input);

// ---------------- home ----------------

function viewHome() {
  const d = today();
  const todays = S.entries.filter((e) => e.date === d);
  const t = totals(todays);
  const bal = balances(S.credit, S.customers);
  const owed = bal.reduce((a, c) => a + Math.max(0, c.owed), 0);
  const m = totals(S.entries, { month: S.month });
  const name = (S.profile.name || '').split(' ')[0];
  return h('div', null,
    h('header', { class: 'hello' },
      h('div', null, h('p', { class: 'hand' }, `Bonjou${name ? `, ${name}` : ''}!`), h('h1', null, S.profile.business || 'My notebook'),
        h('p', { class: 'muted' }, [S.profile.market, new Date().toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' })].filter(Boolean).join(' · ')))),
    h('div', { class: 'kpis' },
      kpi('Sales today', m$(t.sales), 'up'), kpi('Costs today', m$(t.expenses), 'down'),
      kpi('Profit today', m$(t.profit), t.profit >= 0 ? 'up' : 'down'), kpi('Customers owe you', m$(owed), 'owes', '#owed')),
    h('div', { class: 'month-strip' },
      h('span', null, h('b', null, 'This month: '), `${m$(m.sales)} sales · ${m$(m.profit)} profit`),
      m.on_device_share != null ? h('span', { class: 'chip ok' }, icon('chip'), `${m.on_device_share}% of AI reads stayed on the device`) : null),
    h('div', { class: 'actions' },
      action('#snap', 'camera', 'Snap a page', 'Photo of your notebook or a receipt'),
      action('#note', 'pen', 'Quick note', '“Sold 3 rice, 250 each…”'),
      action('#owed', 'people', 'Who owes me', `${bal.filter((c) => c.owed > 0).length} customer(s)`),
      action('#month', 'book', 'Month record', 'For you or a lender')),
    h('section', { class: 'card' }, h('h2', null, 'Latest lines'), entryList(S.entries.slice(0, 14))));
}

const kpi = (label, value, tone, href) => h(href ? 'a' : 'div', { class: `kpi ${tone}`, href }, h('span', null, label), h('b', null, value));
const action = (href, ic, title, sub) => h('a', { class: 'action', href }, h('span', { class: 'aicon' }, icon(ic)), h('b', null, title), h('small', null, sub));

function entryList(list) {
  if (!list.length) return h('p', { class: 'empty' }, 'No lines yet. Snap a page or write a quick note.');
  const byId = new Map(S.customers.map((c) => [c.id, c.name]));
  let lastDate = '';
  const rows = [];
  for (const e of list) {
    if (e.date !== lastDate) {
      lastDate = e.date;
      rows.push(h('li', { class: 'day' }, new Date(`${e.date}T12:00`).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })));
    }
    const sign = e.kind === 'sale' || e.kind === 'payment' ? '+' : e.kind === 'expense' ? '−' : '';
    rows.push(h('li', { class: `entry k-${e.kind}` },
      h('span', { class: 'dot' }),
      h('div', { class: 'what' },
        h('b', null, e.kind === 'credit' || e.kind === 'payment' ? `${KIND[e.kind]} · ${byId.get(e.customerId) || 'customer'}` : (e.item || KIND[e.kind])),
        h('small', null, [e.qty && e.unitPrice ? `${e.qty} × ${m$(e.unitPrice)}` : KIND[e.kind], SRC[e.ai]].filter(Boolean).join(' · '),
          e.pending ? h('span', { class: 'pend', title: 'Saved on this device, waiting to sync' }, icon('clock')) : null)),
      h('b', { class: 'amt' }, `${sign}${m$(e.amount)}`),
      h('button', { class: 'x', title: 'Delete line', 'aria-label': 'Delete line', onclick: () => { if (confirm('Delete this line?')) store.deleteEntry(S.user.uid, e.id); } }, '×')));
  }
  return h('ul', { class: 'entries' }, rows);
}

// ---------------- snap a page ----------------

function viewSnap() {
  const wrap = h('div', null, h('header', { class: 'vh' }, h('h1', null, 'Snap a page'), h('p', { class: 'muted' }, 'Gemini Nano reads the photo on this device. It only goes to the cloud if you say yes.')));
  const preview = h('div', { class: 'preview' });
  const out = h('div', { class: 'result' });
  let photo = null;
  const showPhoto = (blob, url, label) => {
    photo = blob;
    preview.replaceChildren(h('img', { src: url, alt: label || 'Notebook page' }));
    readBtn.disabled = false;
    out.replaceChildren();
    S.draft = null;
  };
  const file = h('input', { type: 'file', accept: 'image/png,image/jpeg', capture: 'environment', class: 'sr', id: 'photo', onchange: () => {
    const f = file.files?.[0];
    if (!f) return;
    if (f.size > 12 * 1024 * 1024) { toast('That photo is over 12 MB.', 'bad'); return; }
    showPhoto(f, URL.createObjectURL(f), f.name);
  } });
  const readBtn = h('button', { class: 'btn primary big wide', disabled: true, onclick: async () => {
    readBtn.disabled = true;
    readBtn.replaceChildren(h('span', { class: 'spin' }), 'Reading the page…');
    try {
      const img = await toAiImage(photo);
      const r = await readPage(img, { online: S.online, confirmCloud: askCloud });
      S.draft = { ...r, method: 'photo' };
      out.replaceChildren(draftView(S.draft, () => { out.replaceChildren(); preview.replaceChildren(); photo = null; }));
    } catch (e) {
      toast(e.message || 'Could not read the page.', 'bad');
    } finally {
      readBtn.disabled = false;
      readBtn.replaceChildren(icon('spark'), 'Read this page');
    }
  } }, icon('spark'), 'Read this page');

  const takeFile = (f, label) => {
    if (!f || !/^image\/(png|jpeg)$/.test(f.type)) { toast('Kaye reads PNG or JPEG photos.', 'bad'); return; }
    if (f.size > 12 * 1024 * 1024) { toast('That photo is over 12 MB.', 'bad'); return; }
    showPhoto(f, URL.createObjectURL(f), label || f.name);
  };
  // Desktop Chrome: drop a photo on the box, or paste one (Ctrl+V).
  const drop = h('label', { class: 'drop', for: 'photo',
    ondragover: (e) => { e.preventDefault(); drop.classList.add('over'); },
    ondragleave: () => drop.classList.remove('over'),
    ondrop: (e) => { e.preventDefault(); drop.classList.remove('over'); takeFile(e.dataTransfer?.files?.[0]); },
  }, icon('camera'), h('b', null, 'Take a photo or choose one'), h('small', null, 'A notebook page or a receipt, PNG or JPEG. You can also drop or paste one here.'));
  S.snapTake = takeFile;
  if (S.incoming) { const { blob, label } = S.incoming; S.incoming = null; setTimeout(() => takeFile(blob, label)); }

  wrap.append(
    h('section', { class: 'card snap' },
      drop, file,
      h('div', { class: 'samples' }, h('span', { class: 'muted small' }, 'No notebook here? Try a sample page:'),
        SAMPLE_PAGES.map((p) => h('button', { class: 'chipbtn', title: p.note || '', onclick: async () => { const s = await drawSample(p.id); showPhoto(s.blob, s.url, p.label); } }, p.label))),
      preview, readBtn),
    out);
  return wrap;
}

/** Consent dialog before a photo may leave the device. */
function askCloud(reason) {
  return new Promise((resolve) => {
    const dlg = $('#consent');
    $('#consent-reason').textContent = reason;
    const done = (v) => { dlg.close(); resolve(v); };
    $('#consent-yes').onclick = () => done(true);
    $('#consent-no').onclick = () => done(false);
    dlg.oncancel = () => resolve(false);
    dlg.showModal();
  });
}

// ---------------- quick note ----------------

function viewNote() {
  let lang = S.profile.lang || 'en';
  const out = h('div', { class: 'result' });
  const ta = h('textarea', { rows: '4', maxlength: '1200', placeholder: 'Sold 3 bags of rice, 250 each. Bought charcoal for 400. Marie owes 500.' });
  const langs = h('div', { class: 'seg' }, Object.entries(LANGS).map(([k, l]) => h('button', { class: k === lang ? 'on' : '', type: 'button', onclick: (e) => {
    lang = k;
    langs.querySelectorAll('button').forEach((b) => b.classList.remove('on'));
    e.currentTarget.classList.add('on');
  } }, l)));
  const btn = h('button', { class: 'btn primary big wide', onclick: async () => {
    const text = ta.value.trim();
    if (!text) { toast('Write a short note first.'); return; }
    btn.disabled = true;
    btn.replaceChildren(h('span', { class: 'spin' }), 'Understanding…');
    try {
      const r = await readNote(text, { lang, online: S.online, knownNames: S.customers.map((c) => c.name) });
      S.draft = { ...r, check: checkLines(r.lines, null), method: 'text' };
      out.replaceChildren(draftView(S.draft, () => { out.replaceChildren(); ta.value = ''; }));
    } finally {
      btn.disabled = false;
      btn.replaceChildren(icon('spark'), 'Turn into lines');
    }
  } }, icon('spark'), 'Turn into lines');
  return h('div', null,
    h('header', { class: 'vh' }, h('h1', null, 'Quick note'), h('p', { class: 'muted' }, 'Write it the way you say it. Names are hidden before anything goes to the cloud.')),
    h('section', { class: 'card' }, langs, ta,
      h('div', { class: 'samples' }, SAMPLE_NOTES.map((s) => h('button', { class: 'chipbtn', onclick: () => {
        ta.value = s.text;
        langs.querySelectorAll('button').forEach((b, i) => b.classList.toggle('on', Object.keys(LANGS)[i] === s.lang));
        lang = s.lang;
      } }, s.label))), btn),
    out);
}

// ---------------- draft editor (shared by photo + note) ----------------

function draftView(d, onSaved) {
  const box = h('section', { class: 'card draft' });
  const date = h('input', { type: 'date', value: today(), max: today() });
  const banner = h('div');
  const tbody = h('div', { class: 'lines' });
  const saveBtn = h('button', { class: 'btn red big wide' });

  const refresh = () => {
    const c = checkLines(d.lines, d.totalWritten);
    d.check = c;
    banner.replaceChildren(...[d.method === 'photo'
      ? h('div', { class: `banner ${c.ok ? 'ok' : 'warn'}` }, icon(c.ok ? 'check' : 'alert'),
        h('div', null, h('b', null, c.ok ? 'The numbers add up.' : 'Check these numbers'), c.ok ? h('small', null, `Lines total ${m$(c.sum)}${c.total ? `, page total ${m$(c.total)}` : ''}.`) : h('ul', null, c.issues.map((i) => h('li', null, i)))))
      : null].filter(Boolean)); // replaceChildren(null) would print "null"
    saveBtn.replaceChildren(icon('check'), `Save ${d.lines.length} line${d.lines.length === 1 ? '' : 's'}`);
    saveBtn.disabled = !d.lines.length;
  };
  const row = (l) => {
    const kind = select(Object.keys(KIND), l.kind, KIND);
    const item = h('input', { value: l.item || '', maxlength: '80', 'aria-label': 'Item' });
    const qty = h('input', { type: 'number', min: '0', step: 'any', value: l.qty ?? '', placeholder: 'qty', 'aria-label': 'Quantity' });
    const price = h('input', { type: 'number', min: '0', step: 'any', value: l.unit_price ?? '', placeholder: 'price', 'aria-label': 'Unit price' });
    const amount = h('input', { type: 'number', min: '0', step: 'any', value: l.amount ?? '', 'aria-label': 'Amount', class: 'amt-in' });
    const cust = h('input', { value: l.customer || '', placeholder: 'customer', maxlength: '60', 'aria-label': 'Customer', list: 'custlist' });
    const showCust = () => cust.classList.toggle('hidden', !(kind.value === 'credit' || kind.value === 'payment'));
    const sync = () => {
      l.kind = kind.value; l.item = item.value; l.qty = num(qty.value); l.unit_price = num(price.value); l.amount = num(amount.value) || 0; l.customer = cust.value.trim() || null;
      showCust(); refresh();
    };
    [kind, item, qty, price, amount, cust].forEach((x) => x.addEventListener('input', sync));
    showCust();
    const r = h('div', { class: `line k-${l.kind}` }, kind, item, qty, price, amount, cust,
      h('button', { class: 'x', type: 'button', 'aria-label': 'Remove line', onclick: () => { d.lines.splice(d.lines.indexOf(l), 1); r.remove(); refresh(); } }, '×'));
    kind.addEventListener('input', () => { r.className = `line k-${kind.value}`; });
    return r;
  };
  d.lines.forEach((l) => tbody.append(row(l)));
  saveBtn.onclick = async () => {
    const bad = d.lines.find((l) => (l.kind === 'credit' || l.kind === 'payment') && !l.customer);
    if (bad) { toast('Credit and paid-back lines need a customer name.', 'bad'); return; }
    const ai = d.source === 'on_device' || d.source === 'in_cloud' || d.source === 'rules' ? d.source : 'manual';
    const n = await store.saveLines(S.user.uid, d.lines.filter((l) => l.amount > 0), { date: date.value || today(), method: d.method, ai, customers: S.customers });
    toast(S.online ? `${n} line(s) saved.` : `${n} line(s) saved on this device. They will sync when there is signal.`, 'ok');
    S.draft = null;
    onSaved();
    location.hash = '#home';
  };

  box.append(
    h('div', { class: 'draft-head' }, h('h2', null, `${d.lines.length} line(s) found`), srcBadge(d.source, d.ms), d.hidden ? h('span', { class: 'badge' }, icon('shield'), `${d.hidden} name(s) hidden`) : null),
    banner,
    h('div', { class: 'lines-head' }, h('span', null, 'Type'), h('span', null, 'Item'), h('span', null, 'Qty'), h('span', null, 'Price'), h('span', null, 'Amount'), h('span', null, '')),
    tbody,
    h('datalist', { id: 'custlist' }, S.customers.map((c) => h('option', { value: c.name }))),
    h('button', { class: 'link', type: 'button', onclick: () => { const l = { kind: 'sale', item: '', qty: null, unit_price: null, amount: 0, customer: null }; d.lines.push(l); tbody.append(row(l)); refresh(); } }, '+ Add a line'),
    h('div', { class: 'row2 end' }, field('Date', date), saveBtn),
    trace(d.trace));
  refresh();
  return box;
}
const num = (v) => (v === '' || v == null || !Number.isFinite(Number(v)) ? null : Number(v));

function trace(lines) {
  return h('details', { class: 'trace', open: true }, h('summary', null, icon('route'), 'Where the AI ran, and why'),
    h('ol', null, (lines || []).map((t) => h('li', null, t))));
}

// ---------------- who owes me ----------------

function viewOwed() {
  const bal = balances(S.credit, S.customers);
  const owing = bal.filter((c) => c.owed > 0);
  const total = owing.reduce((a, c) => a + c.owed, 0);
  const name = h('input', { placeholder: 'Customer name', maxlength: '80', list: 'custlist2' });
  const amount = h('input', { type: 'number', min: '1', step: 'any', placeholder: 'Amount' });
  const kind = select(['credit', 'payment'], 'credit', { credit: 'took on credit', payment: 'paid back' });
  return h('div', null,
    h('header', { class: 'vh' }, h('h1', null, 'Who owes me'), h('p', { class: 'muted' }, 'Names stay in your private notebook. The AI never sees them.')),
    h('div', { class: 'kpis two' }, kpi('Customers owe you', m$(total), 'owes'), kpi('Customers', String(owing.length), '')),
    h('section', { class: 'card' },
      h('h2', null, 'Add credit or a payment'),
      h('form', { class: 'inline-form', onsubmit: async (e) => {
        e.preventDefault();
        const a = num(amount.value);
        if (!name.value.trim() || !a) { toast('Add a name and an amount.'); return; }
        await store.saveLines(S.user.uid, [{ kind: kind.value, item: kind.value === 'credit' ? 'Credit' : 'Payment', amount: a, customer: name.value.trim() }], { date: today(), method: 'manual', ai: 'manual', customers: S.customers });
        toast('Saved.', 'ok');
        name.value = ''; amount.value = '';
      } }, name, kind, amount, h('button', { class: 'btn primary', type: 'submit' }, 'Save')),
      h('datalist', { id: 'custlist2' }, S.customers.map((c) => h('option', { value: c.name })))),
    h('section', { class: 'card' },
      owing.length ? h('ul', { class: 'owed' }, owing.map((c) => owedRow(c))) : h('p', { class: 'empty' }, 'Nobody owes you money. 🎉')));
}

function owedRow(c) {
  const out = h('div', { class: 'remind' });
  return h('li', null,
    h('div', { class: 'who' }, h('span', { class: 'avatar' }, initials(c.name)), h('div', null, h('b', null, c.name), h('small', null, c.last ? `Last: ${c.last}` : ''))),
    h('b', { class: 'amt' }, m$(c.owed)),
    h('div', { class: 'btns' },
      h('button', { class: 'btn ghost', onclick: async () => {
        const v = prompt(`How much did ${c.name} pay back?`, String(c.owed));
        const a = num(v);
        if (!a) return;
        await store.saveLines(S.user.uid, [{ kind: 'payment', item: 'Payment', amount: a, customer: c.name }], { date: today(), method: 'manual', ai: 'manual', customers: S.customers });
        toast('Payment saved.', 'ok');
      } }, 'Paid back'),
      h('button', { class: 'btn ghost', onclick: async (e) => {
        const b = e.currentTarget;
        b.disabled = true;
        const r = await writeReminder({ name: c.name, amount: c.owed, currency: cur(), lang: S.profile.lang || 'ht', online: S.online });
        b.disabled = false;
        const msg = h('textarea', { rows: '3', value: r.message });
        out.replaceChildren(msg,
          h('div', { class: 'row-btns' }, srcBadge(r.source, r.ms),
            h('button', { class: 'btn ghost', onclick: () => { navigator.clipboard?.writeText(msg.value); toast('Copied.'); } }, 'Copy'),
            navigator.share ? h('button', { class: 'btn ghost', onclick: () => navigator.share({ text: msg.value }).catch(() => {}) }, 'Share') : null),
          trace(r.trace));
      } }, icon('spark'), 'Polite reminder')),
    out);
}
const initials = (n) => String(n).split(/\s+/).map((p) => p[0]).join('').slice(0, 2).toUpperCase();

// ---------------- month record ----------------

function viewMonth() {
  const wrap = h('div');
  const month = S.month;
  const label = new Date(`${month}-15T12:00`).toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
  const bal = balances(S.credit, S.customers);
  const owing = bal.filter((c) => c.owed > 0);
  const t = totals(S.entries, { month, currency: cur(), owed: owing.reduce((a, c) => a + c.owed, 0), owingCount: owing.length });
  const max = Math.max(1, ...t.by_week);
  const recordBox = h('div', { class: 'record' });
  const setMonth = (delta) => {
    S.month = shiftMonth(S.month, delta);
    S.unsub.shift()?.();
    S.unsub.unshift(store.watchMonth(S.user.uid, S.month, (list, meta) => { S.entries = list; S.pending = meta.hasPendingWrites; renderStatus(); if (['home', 'month'].includes(route())) render(); }, onDbError));
    render();
  };
  store.loadReport(S.user.uid, month).then((r) => { if (r) recordBox.replaceChildren(recordView(r, r.ai, r.ms, null)); });

  const src = t.sources;
  const srcTotal = Object.values(src).reduce((a, b) => a + b, 0) || 1;
  const bar = h('div', { class: 'srcbar' }, ['on_device', 'in_cloud', 'rules', 'manual'].map((k) => {
    const seg = h('span', { class: `s-${k}`, title: `${SRC[k]}: ${src[k] || 0}` });
    seg.style.setProperty('--w', `${(100 * (src[k] || 0)) / srcTotal}%`);
    return seg;
  }));

  wrap.append(
    h('header', { class: 'vh month-head' },
      h('button', { class: 'btn ghost', 'aria-label': 'Previous month', onclick: () => setMonth(-1) }, '‹'),
      h('div', null, h('h1', null, label), h('p', { class: 'muted' }, `${S.profile.business || ''}${S.profile.market ? ` · ${S.profile.market}` : ''}`)),
      h('button', { class: 'btn ghost', 'aria-label': 'Next month', disabled: month >= monthOf(today()), onclick: () => setMonth(1) }, '›')),
    h('div', { class: 'kpis' }, kpi('Sales', m$(t.sales), 'up'), kpi('Costs', m$(t.expenses), 'down'), kpi('Profit', m$(t.profit), t.profit >= 0 ? 'up' : 'down'), kpi('Owed to you', m$(t.owed), 'owes')),
    h('div', { class: 'grid2' },
      h('section', { class: 'card' }, h('h2', null, 'Sales by week'),
        h('div', { class: 'weeks' }, t.by_week.map((w, i) => {
          const b = h('i');
          b.style.setProperty('--h', `${Math.round((100 * w) / max)}%`);
          return h('div', { class: 'wk' }, h('small', null, w ? m$(w) : ''), h('div', { class: 'col' }, b), h('span', null, `W${i + 1}`));
        }))),
      h('section', { class: 'card' }, h('h2', null, 'Best sellers'),
        t.top_items.length ? h('ol', { class: 'top' }, t.top_items.map((x) => h('li', null, h('span', null, x.item), h('b', null, m$(x.amount))))) : h('p', { class: 'empty' }, 'No sales this month yet.'))),
    h('section', { class: 'card' }, h('h2', null, 'Where the AI ran this month'), bar,
      h('p', { class: 'legend' }, ['on_device', 'in_cloud', 'rules', 'manual'].map((k) => h('span', { class: `lg s-${k}` }, `${SRC[k]} ${src[k] || 0}`))),
      t.on_device_share != null ? h('p', { class: 'muted small' }, `${t.on_device_share}% of AI reads ran on the device: free, private and offline.`) : null),
    h('section', { class: 'card printable' },
      h('div', { class: 'draft-head' }, h('h2', null, 'Monthly record'), h('span', { class: 'badge' }, icon('shield'), 'From totals only')),
      h('p', { class: 'muted small' }, 'A short, factual summary you can show a microfinance officer. Kaye does not give financial advice.'),
      recordBox,
      h('div', { class: 'row-btns' },
        h('button', { class: 'btn primary', disabled: !t.entries, onclick: async (e) => {
          const b = e.currentTarget;
          b.disabled = true;
          recordBox.replaceChildren(h('p', { class: 'muted' }, h('span', { class: 'spin' }), 'Writing the record…'));
          const sendTotals = { ...t };
          delete sendTotals.sources;
          delete sendTotals.on_device_share;
          const r = await writeRecord(sendTotals, { lang: S.profile.lang || 'en', online: S.online });
          store.saveReport(S.user.uid, month, r, sendTotals);
          recordBox.replaceChildren(recordView(r, r.source, r.ms, r.trace));
          b.disabled = false;
        } }, icon('spark'), 'Write the record'),
        h('button', { class: 'btn ghost', onclick: () => window.print() }, icon('print'), 'Print or save as PDF'))));
  return wrap;
}

function recordView(r, source, ms, tr) {
  return h('div', null,
    h('p', { class: 'summary' }, r.summary),
    r.highlights?.length ? h('ul', { class: 'hl' }, r.highlights.map((x) => h('li', null, x))) : null,
    h('div', { class: 'row-btns' }, srcBadge(source, ms)),
    tr ? trace(tr) : null);
}

// ---------------- settings ----------------

const NANO_LABEL = {
  available: ['ok', 'Ready'], 'text-only': ['ok', 'Text only'], downloadable: ['warn', 'Can download'],
  downloading: ['warn', 'Downloading'], unavailable: ['muted', 'Unavailable'], unsupported: ['muted', 'Not supported'],
};

function viewSettings() {
  const p = S.profile;
  const name = h('input', { value: p.name || '', maxlength: '80', autocomplete: 'name' });
  const business = h('input', { value: p.business || '', maxlength: '80', autocomplete: 'organization' });
  const market = h('input', { value: p.market || '', maxlength: '80' });
  const currency = select(['HTG', 'USD', 'DOP', 'EUR', 'XOF', 'MXN'], p.currency || 'HTG');
  const lang = select(Object.keys(LANGS), p.lang || 'en', LANGS);
  const prog = h('progress', { max: '1', value: '0', class: 'hidden' });
  const isDemo = S.user.email === DEMO_EMAIL;
  const anon = S.user.isAnonymous;
  const [nanoTone, nanoText] = NANO_LABEL[S.nano] || NANO_LABEL.unsupported;
  const sync = !S.online ? ['warn', 'No signal'] : S.pending ? ['warn', 'Waiting'] : ['ok', 'Synced'];

  const cardHead = (ic, title, sub) => h('div', { class: 'set-head' },
    h('span', { class: 'set-ic' }, icon(ic)), h('div', null, h('h2', null, title), sub ? h('p', { class: 'muted small' }, sub) : null));
  const wide = (el, cls) => { el.classList.add(cls); return el; };
  const goTo = (id) => () => document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  const tile = (label, value, tone, ic, target) => h('button', { class: `set-tile ${tone}`, type: 'button', onclick: goTo(target) },
    h('span', { class: 'set-tile-ic' }, icon(ic)), h('span', { class: 'set-tile-t' }, h('small', null, label), h('b', null, value)));

  const accountCard = anon
    ? (() => {
      const em = h('input', { type: 'email', placeholder: 'you@example.com', autocomplete: 'username', required: true });
      const pw = h('input', { type: 'password', placeholder: '6 characters or more', autocomplete: 'new-password', minlength: '6', required: true });
      return h('section', { class: 'card set-card set-save', id: 'set-account' },
        cardHead('shield', 'Save your notebook', 'You are using Kaye without an account. Add an email to keep this notebook on any device.'),
        h('form', { class: 'set-form', onsubmit: async (e) => {
          e.preventDefault();
          try {
            await linkWithCredential(S.user, EmailAuthProvider.credential(em.value.trim(), pw.value));
            track('sign_up', { method: 'link_anonymous' });
            toast('Your notebook is saved to this email.', 'ok');
            render();
          } catch (x) { toast(authMessage(x), 'bad'); }
        } }, field('Email', em), field('Password', pw),
        h('div', { class: 'set-actions' }, h('button', { class: 'btn red', type: 'submit' }, 'Save my notebook'),
          h('button', { class: 'btn ghost', type: 'button', onclick: () => signOut(auth) }, 'Sign out'))));
    })()
    : h('section', { class: 'card set-card', id: 'set-account' },
      cardHead('people', 'Account'),
      h('div', { class: 'set-who' }, h('span', { class: 'set-avatar' }, (S.user.email || '?')[0].toUpperCase()),
        h('div', null, h('small', { class: 'muted' }, 'Signed in as'), h('b', null, S.user.email))),
      h('div', { class: 'set-actions' }, h('button', { class: 'btn ghost', onclick: () => signOut(auth) }, 'Sign out')));

  const businessCard = h('section', { class: 'card set-card', id: 'set-business' },
    cardHead('home', 'Your business', 'Shown on your month record.'),
    h('form', { class: 'set-form', onsubmit: (e) => {
      e.preventDefault();
      const np = { name: name.value, business: business.value, market: market.value, currency: currency.value, lang: lang.value };
      store.saveProfile(S.user.uid, np).catch(() => {});
      S.profile = { ...S.profile, ...np };
      toast('Saved.', 'ok');
    } },
    h('div', { class: 'set-fields' }, wide(field('Name', name), 'f-name'), wide(field('Business', business), 'f-name'), wide(field('Market', market), 'f-wide'), field('Currency', currency), field('Language', lang)),
    h('div', { class: 'set-actions' }, h('button', { class: 'btn primary', type: 'submit' }, 'Save'))));

  const rc = [
    ['Cloud model', settings.cloudModel],
    ['On-device languages', settings.deviceLanguages.join(', ')],
    ['Total check tolerance', `${Math.round(settings.sumTolerance * 100)}%`],
    ['Cloud photos', settings.cloudPhotos ? 'Allowed with your OK' : 'Off'],
  ];
  const aiCard = h('section', { class: 'card set-card', id: 'set-ai' },
    cardHead('chip', 'AI on this device', 'Gemini Nano runs inside Chrome. Photos and notes read here never leave it.'),
    h('div', { class: 'set-status' }, h('span', null, 'Gemini Nano'), h('span', { class: `set-pill ${nanoTone}` }, nanoText)),
    h('p', { class: 'muted small' }, 'Needs Chrome 138+ on desktop with the Prompt API; photos need multimodal input enabled.'),
    h('div', { class: 'set-actions' },
      h('button', { class: 'btn primary', disabled: S.nano === 'available', onclick: async (e) => {
        e.currentTarget.disabled = true; // re-rendered below
        prog.classList.remove('hidden');
        const ok = await prepareDevice((ev) => { prog.value = ev.loaded ?? 0; });
        prog.classList.add('hidden');
        await refreshNano();
        toast(ok ? 'Gemini Nano is ready on this device.' : 'This browser cannot run Gemini Nano. Kaye will use the cloud with your OK.', ok ? 'ok' : '');
        render();
      } }, icon('chip'), S.nano === 'available' ? 'Ready' : 'Get Gemini Nano')), prog,
    h('h3', { class: 'set-sub' }, icon('cloud'), S.rc === 'remote' ? 'From Firebase Remote Config' : 'App defaults (Remote Config not loaded)'),
    h('dl', { class: 'set-dl' }, rc.flatMap(([k, v]) => [h('dt', null, k), h('dd', null, v)])));

  const notebookCard = h('section', { class: 'card set-card', id: 'set-notebook' },
    cardHead('book', 'Notebook', 'Stored in your private Firebase account and on this device, so it works with no signal. Only you can read it.'),
    h('div', { class: 'set-actions' },
      h('button', { class: 'btn ghost', onclick: async () => { await store.seedSample(S.user.uid); toast('Sample notebook loaded.', 'ok'); } }, 'Load sample notebook'),
      h('button', { class: 'btn ghost danger', onclick: async () => {
        if (!confirm(isDemo ? 'Reset the demo notebook to the sample data?' : 'Delete every line, customer and record in this notebook? This cannot be undone.')) return;
        await store.clearNotebook(S.user.uid);
        if (isDemo) await store.seedSample(S.user.uid);
        toast(isDemo ? 'Demo notebook reset.' : 'Notebook cleared.', 'ok');
      } }, isDemo ? 'Reset demo notebook' : 'Clear my notebook')));

  const privacyCard = h('section', { class: 'card set-card set-wide privacy', id: 'set-privacy' },
    cardHead('shield', 'Privacy, in plain words'),
    h('ul', { class: 'set-promises' },
      h('li', null, 'Photos are read by Gemini Nano on this device. A photo goes to the cloud only after you tap “Send this photo”.'),
      h('li', null, 'Quick notes go to the cloud only when no on-device model can read them, and customer names and phone numbers are replaced first.'),
      h('li', null, 'The monthly record is written from totals only.'),
      h('li', null, 'Analytics counts where the AI ran and how long it took. Never your lines, names or amounts.')));

  return h('div', { class: 'settings' },
    h('header', { class: 'vh' }, h('h1', null, 'Settings')),
    h('div', { class: 'set-strip' },
      tile('Account', anon ? 'Not saved' : 'Saved', anon ? 'warn' : 'ok', anon ? 'alert' : 'shield', 'set-account'),
      tile('Gemini Nano', nanoText, nanoTone, 'chip', 'set-ai'),
      tile('Sync', sync[1], sync[0], S.online ? (S.pending ? 'clock' : 'check') : 'nowifi', 'set-notebook'),
      tile('Config', S.rc === 'remote' ? 'Remote' : 'Defaults', S.rc === 'remote' ? 'ok' : 'muted', 'cloud', 'set-ai')),
    h('div', { class: 'set-grid' },
      ...(anon ? [accountCard, businessCard, aiCard, notebookCard] : [businessCard, aiCard, accountCard, notebookCard]),
      privacyCard));
}

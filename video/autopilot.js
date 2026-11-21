// Demo autopilot for recording the Kaye video. Loaded only by video/app-demo.html on localhost — never deployed.
// It drives the real app (real Firebase, real Gemini Nano) with a visible cursor, one scene per ?scene=... URL.
(() => {
  const params = new URLSearchParams(location.search);
  const scene = params.get('scene') || '';
  // A fresh demo user = a fresh origin (another localhost port), never deleting databases.
  const log = (window.__demoLog = []);
  const t0 = performance.now();
  const mark = (name) => { log.push([name, Math.round(performance.now() - t0)]); document.title = `Kaye demo ${name}`; };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const $ = (s) => document.querySelector(s);
  const byText = (sel, text) => [...document.querySelectorAll(sel)].find((e) => e.textContent.trim().includes(text));
  async function waitFor(fn, timeout = 150000) {
    const end = performance.now() + timeout;
    while (performance.now() < end) { const v = fn(); if (v) return v; await sleep(200); }
    throw new Error('timeout');
  }

  // --- visible cursor + click ripple ---
  const css = document.createElement('style');
  css.textContent = `
    #demo-cursor{position:fixed;left:0;top:0;width:30px;height:30px;z-index:99999;pointer-events:none;transition:transform .7s cubic-bezier(.3,.8,.3,1);filter:drop-shadow(0 3px 6px rgba(0,0,0,.45))}
    .demo-ripple{position:fixed;width:16px;height:16px;margin:-8px 0 0 -8px;border-radius:50%;border:3px solid #F5C518;z-index:99998;pointer-events:none;animation:dr .6s ease-out forwards}
    @keyframes dr{to{transform:scale(4);opacity:0}}
    html{scroll-behavior:smooth}`;
  document.head.append(css);
  const cur = document.createElement('div');
  cur.id = 'demo-cursor';
  cur.innerHTML = '<svg viewBox="0 0 24 24" width="30" height="30"><path d="M4 2l16 9-7 1.6L9.6 20z" fill="#fff" stroke="#0b1533" stroke-width="1.6" stroke-linejoin="round"/></svg>';
  let cx = innerWidth * 0.62, cy = innerHeight * 0.7;
  cur.style.transform = `translate(${cx}px,${cy}px)`;
  document.addEventListener('DOMContentLoaded', () => document.body.append(cur));

  async function moveTo(el) {
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    await sleep(650);
    const r = el.getBoundingClientRect();
    cx = r.left + Math.min(r.width * 0.5, 60); cy = r.top + r.height * 0.55;
    cur.style.transform = `translate(${cx}px,${cy}px)`;
    await sleep(800);
  }
  async function click(el, pause = 500) {
    await moveTo(el);
    const rp = document.createElement('div');
    rp.className = 'demo-ripple'; rp.style.left = `${cx + 4}px`; rp.style.top = `${cy + 4}px`;
    document.body.append(rp); setTimeout(() => rp.remove(), 700);
    el.click();
    await sleep(pause);
  }
  async function type(el, text) {
    await moveTo(el); el.focus(); el.value = '';
    for (const ch of text) { el.value += ch; el.dispatchEvent(new Event('input', { bubbles: true })); await sleep(55); }
  }
  async function scrollToEl(el, block = 'start', wait = 1400) { el.scrollIntoView({ block, behavior: 'smooth' }); await sleep(wait); }
  const go = async (hash) => { location.hash = hash; await sleep(900); };
  const btn = (text) => waitFor(() => byText('button', text), 30000);

  const scenes = {
    // Landing page: hero, the problem band, how it works, hybrid routes.
    async landing() {
      await sleep(6500); await scrollToEl($('.band'), 'center', 4500);
      await scrollToEl($('#how'), 'start', 5500); await scrollToEl($('#hybrid'), 'start', 9000);
    },
    async stack() {
      await scrollToEl([...document.querySelectorAll('.sec h2')].find((h) => h.textContent.includes('Six Firebase')), 'center', 6000);
      await scrollToEl($('#judges'), 'center', 5000);
    },
    // Sign-in without an account, onboarding, sample notebook, home.
    async start() {
      const btn = await waitFor(() => byText('button', 'Try it now'));
      await sleep(1500); await click(btn, 1500);
      const name = await waitFor(() => $('input[placeholder="Your name"]'));
      await type(name, 'Mirlande');
      await type($('input[placeholder^="Business"]'), 'Diri ak pwa');
      await type($('input[placeholder^="Market"]'), 'Marché Cluny, Cap-Haïtien');
      const lang = [...document.querySelectorAll('select')].find((s) => [...s.options].some((o) => o.value === 'ht'));
      if (lang) { await moveTo(lang); lang.value = 'en'; lang.dispatchEvent(new Event('change', { bubbles: true })); await sleep(600); }
      await click(await btn('Open my notebook'), 2500);
      mark('home');
      await sleep(3500); await scrollToEl($('.actions'), 'center', 3000);
    },
    // A sales page read entirely by Gemini Nano in Chrome.
    async snap() {
      await go('#snap'); await sleep(1200);
      await click(await btn('Sales page (English)'), 1200);
      await click(await btn('Read this page'), 300);
      mark('read_start');
      await waitFor(() => $('.draft'));
      mark('read_done');
      await scrollToEl($('.draft'), 'start', 4500);
      await scrollToEl($('.trace'), 'center', 5000);
    },
    // A page with a wrong total: check fails, Kaye asks before using the cloud.
    async mistake() {
      await go('#snap'); await sleep(1200);
      await click(await btn('Page with a mistake'), 1200);
      await click(await btn('Read this page'), 300);
      mark('read_start');
      await waitFor(() => $('#consent').open);
      mark('consent');
      await sleep(5500);
      await click(await waitFor(() => $('#consent-no')), 1500);
      await scrollToEl($('.draft'), 'start', 5500);
    },
    // A Kreyòl note: names hidden before the cloud, put back after.
    async note() {
      await go('#note'); await sleep(1200);
      await click(await btn('HT · jounen mache'), 1500);
      await click(await btn('Turn into lines'), 300);
      mark('read_start');
      await waitFor(() => $('.draft'));
      mark('read_done');
      await scrollToEl($('.draft'), 'start', 5000);
      await scrollToEl($('.trace'), 'center', 5500);
    },
    async owed() {
      await go('#owed'); await sleep(2500);
      await click(await btn('Polite reminder'), 300);
      await waitFor(() => $('.remind textarea'));
      mark('reminder');
      await scrollToEl($('.remind'), 'center', 5000);
    },
    async month() {
      await go('#month'); await sleep(3000);
      await scrollToEl($('.weeks'), 'center', 3000);
      await click(await btn('Write the record'), 300);
      await waitFor(() => $('.record .summary'));
      mark('record');
      await scrollToEl($('.record'), 'center', 6000);
    },
  };

  window.addEventListener('load', async () => {
    const fn = scenes[scene];
    if (!fn) return;
    mark('begin');
    try { await fn(); mark('end'); } catch (e) { mark(`error:${e.message}`); }
  });
})();

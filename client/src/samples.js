// Sample notebook pages, drawn on a canvas in a handwriting font, so anyone can try "Snap a page"
// without a real notebook. Names and numbers are invented.

export const SAMPLE_PAGES = [
  {
    id: 'en', label: 'Sales page (English)', title: 'Tuesday — Sales',
    lines: ['Rice  3 cups x 250 = 750', 'Beans  2 x 300 = 600', 'Oil  1 bottle = 450', 'Sugar  4 x 75 = 300', '', 'Total   2100'],
  },
  {
    id: 'ht', label: 'Lavant + kredi (Kreyòl)', title: 'Madi 11 — Lavant',
    lines: ['Diri  4 mamit x 250 = 1000', 'Pwa  2 x 300 = 600', 'Savon  5 x 60 = 300', 'Total  1900', '', 'Kredi: Madan Pòl  500', 'Jean peye  200'],
  },
  {
    id: 'err', label: 'Page with a mistake', title: 'Wednesday — Sales',
    lines: ['Flour  2 sacks x 1500 = 3000', 'Soap  6 x 60 = 360', 'Charcoal  1 = 400', '', 'Total   3860'],
    note: 'The total is wrong on purpose (the lines add up to 3,760), to show the arithmetic check.',
  },
];

/** @returns {Promise<{blob: Blob, url: string}>} */
export async function drawSample(id) {
  const page = SAMPLE_PAGES.find((p) => p.id === id) || SAMPLE_PAGES[0];
  try { await document.fonts.load('44px Caveat'); } catch { /* fallback font */ }
  const W = 900;
  const H = 1150;
  const c = document.createElement('canvas');
  c.width = W;
  c.height = H;
  const g = c.getContext('2d');
  // paper
  const grad = g.createLinearGradient(0, 0, W, H);
  grad.addColorStop(0, '#fbfaf5');
  grad.addColorStop(1, '#f1efe6');
  g.fillStyle = grad;
  g.fillRect(0, 0, W, H);
  // ruled lines + red margin (a Haitian school notebook)
  g.strokeStyle = 'rgba(0, 32, 159, 0.28)';
  g.lineWidth = 2;
  for (let y = 170; y < H - 30; y += 70) { g.beginPath(); g.moveTo(0, y); g.lineTo(W, y); g.stroke(); }
  g.strokeStyle = 'rgba(210, 16, 52, 0.55)';
  g.lineWidth = 3;
  g.beginPath(); g.moveTo(110, 0); g.lineTo(110, H); g.stroke();
  // handwriting
  let seed = page.id.charCodeAt(0);
  const jit = () => { seed = (seed * 9301 + 49297) % 233280; return seed / 233280 - 0.5; };
  g.fillStyle = '#16245e';
  g.font = '600 58px Caveat, "Segoe Print", cursive';
  draw(g, page.title, 140, 140, jit() * 0.02);
  g.font = '50px Caveat, "Segoe Print", cursive';
  page.lines.forEach((text, i) => {
    if (!text) return;
    const y = 225 + i * 70 + jit() * 6;
    if (/^total/i.test(text)) {
      g.font = '700 54px Caveat, "Segoe Print", cursive';
      draw(g, text, 150 + jit() * 10, y, jit() * 0.02);
      g.strokeStyle = '#16245e';
      g.lineWidth = 2.5;
      g.beginPath(); g.moveTo(140, y - 52); g.lineTo(560, y - 56); g.stroke();
      g.font = '50px Caveat, "Segoe Print", cursive';
    } else {
      draw(g, text, 140 + jit() * 12, y, jit() * 0.025);
    }
  });
  // light shadow at the edges, like a photo
  const v = g.createRadialGradient(W / 2, H / 2, H * 0.35, W / 2, H / 2, H * 0.75);
  v.addColorStop(0, 'rgba(0,0,0,0)');
  v.addColorStop(1, 'rgba(0,0,0,0.12)');
  g.fillStyle = v;
  g.fillRect(0, 0, W, H);
  const blob = await new Promise((res) => c.toBlob(res, 'image/jpeg', 0.88));
  return { blob, url: URL.createObjectURL(blob) };
}

function draw(g, text, x, y, rot) {
  g.save();
  g.translate(x, y);
  g.rotate(rot);
  g.fillText(text, 0, 0);
  g.restore();
}

/** Downscales a photo to at most 1280 px and returns base64 JPEG for the AI. */
export async function toAiImage(blob) {
  const bmp = await createImageBitmap(blob);
  const scale = Math.min(1, 1280 / Math.max(bmp.width, bmp.height));
  const c = document.createElement('canvas');
  c.width = Math.round(bmp.width * scale);
  c.height = Math.round(bmp.height * scale);
  c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
  const out = await new Promise((res) => c.toBlob(res, 'image/jpeg', 0.85));
  const data = await new Promise((res, rej) => {
    const r = new FileReader();
    r.onload = () => res(String(r.result).split(',')[1]);
    r.onerror = rej;
    r.readAsDataURL(out);
  });
  return { data, mimeType: 'image/jpeg', bytes: out.size };
}

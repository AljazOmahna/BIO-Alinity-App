/*
 * Zajem zaslonov aplikacije BIO Alinity za navodila.
 * Naloži bio_alinity.html v glavo brez glave (Chrome/Edge/Chromium), vstavi vzorčne
 * podatke (zaloga + QC vnosi) in shrani posnetek vsakega zaslona v navodila/screens/*.png.
 *
 * Zagon:  node capture_screens.js
 * V CI:   nastavi PUPPETEER_EXECUTABLE_PATH na nameščen Chromium.
 *
 * Posnetki se uporabijo v generate_navodila.py za sestavo Word datoteke.
 */
const fs = require('fs');
const path = require('path');
const http = require('http');
const puppeteer = require('puppeteer-core');

const ASSETS = path.resolve(__dirname, '..', 'app', 'src', 'main', 'assets');
const OUT = path.resolve(__dirname, 'screens');
const PORT = 8801;

// Najdi Chromium/Chrome/Edge
function findBrowser() {
  if (process.env.PUPPETEER_EXECUTABLE_PATH) return process.env.PUPPETEER_EXECUTABLE_PATH;
  const cands = [
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
    '/usr/bin/google-chrome', '/usr/bin/chromium-browser', '/usr/bin/chromium',
  ];
  for (const c of cands) { if (fs.existsSync(c)) return c; }
  throw new Error('Ni najden Chromium/Chrome/Edge. Nastavi PUPPETEER_EXECUTABLE_PATH.');
}

const MIME = { '.html':'text/html', '.js':'application/javascript', '.css':'text/css',
  '.png':'image/png', '.json':'application/json', '.svg':'image/svg+xml', '.ico':'image/x-icon' };

function serve() {
  return new Promise((resolve) => {
    const srv = http.createServer((req, res) => {
      let p = decodeURIComponent(req.url.split('?')[0]);
      if (p === '/') p = '/bio_alinity.html';
      const fp = path.join(ASSETS, p);
      if (!fp.startsWith(ASSETS) || !fs.existsSync(fp)) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(fp)] || 'application/octet-stream' });
      fs.createReadStream(fp).pipe(res);
    });
    srv.listen(PORT, () => resolve(srv));
  });
}

// Vzorčna zaloga (pregledBK_db) — različni roki za prikaz barvnih oznak
function sampleInventory() {
  const today = new Date();
  const fmt = (o) => { const d = new Date(today); d.setDate(d.getDate() + o); return d.toISOString().slice(0, 10); };
  const inv = [
    { gtin:'07613336100011', name:'Glukoza Reagent',        type:'reagent-bio',   lot:'G1182', expiry:fmt(-5),  qty:4 },
    { gtin:'07613336100028', name:'Kreatinin Reagent',      type:'reagent-bio',   lot:'K3320', expiry:fmt(10),  qty:6 },
    { gtin:'07613336100035', name:'ALT (GPT) Reagent',      type:'reagent-bio',   lot:'A2401', expiry:fmt(24),  qty:8 },
    { gtin:'07613336100042', name:'Troponin Reagent',       type:'reagent-imuno', lot:'T9901', expiry:fmt(120), qty:12 },
    { gtin:'07613336100059', name:'TSH Reagent',            type:'reagent-imuno', lot:'T5510', expiry:fmt(6),   qty:3 },
    { gtin:'07613336100066', name:'Multichem S Plus L1',    type:'control',       lot:'C5501', expiry:fmt(45),  qty:5 },
    { gtin:'07613336100073', name:'Multichem IA L2',        type:'control',       lot:'C5502', expiry:fmt(60),  qty:4 },
    { gtin:'07613336100080', name:'Kalibrator CK',          type:'calibrator',    lot:'KAL77', expiry:fmt(200), qty:2 },
    { gtin:'07613336100097', name:'Kivete / reakcijske',    type:'consumable',    lot:'KV2024', expiry:fmt(330), qty:20 },
  ];
  return {
    gtins: inv.map(i => ({ gtin:i.gtin, name:i.name, type:i.type })),
    inventory: inv,
    received: inv.map((i, n) => ({ gtin:i.gtin, name:i.name, type:i.type, lot:i.lot, expiry:i.expiry,
      receivedAt: new Date(today.getTime() - n*86400000).toISOString() })),
    lots: inv.map(i => ({ gtin:i.gtin, lot:i.lot, name:i.name, type:i.type, expiry:i.expiry })),
    sessions: [], consumed: [], lastUsed: [],
  };
}

// Vzorčni QC vnosi (qclab_v2.entries) — oblika kot vnos čarovnika:
// en vnos = en QC zagon (datetime, qcName, levels[], results[{assay,level,sd,...}]).
function sampleQc() {
  const today = new Date();
  const iso = (o, h) => { const x = new Date(today); x.setDate(x.getDate() + o); x.setHours(h||7, 30, 0, 0); return x.toISOString(); };
  const entries = [];
  const bioAssays = ['Glukoza', 'Kalij', 'Natrij'];
  // AL01 C1, Multichem S Plus, nivoja L1+L2, zadnjih ~12 dni
  for (let i = 12; i >= 0; i--) {
    const results = [];
    bioAssays.forEach(a => {
      // večinoma OK; Glukoza L1 ima en izpad (1₃ₛ) in nekaj 2SD opozoril
      let sd1 = 'ok', sd2 = 'ok';
      if (a === 'Glukoza') {
        if (i === 4) sd1 = '+3sd';
        else if (i === 7 || i === 8) sd1 = '+2sd';
      }
      results.push({ assay:a, level:'L1', sd:sd1, value:'', note: sd1==='+3sd'?'A':'' });
      results.push({ assay:a, level:'L2', sd:sd2, value:'', note:'' });
    });
    entries.push({ id:'e_c1_'+i, datetime: iso(-i), instrId:'AL01 C1', operatorId:'27289',
      qcName:'Multichem S Plus', levels:['L1','L2'], results });
  }
  // AL01 I2, Multichem IA, Troponin — krajša serija z enim 2SD opozorilom
  for (let i = 8; i >= 0; i--) {
    const sd = (i === 2) ? '+2sd' : 'ok';
    entries.push({ id:'e_i2_'+i, datetime: iso(-i, 9), instrId:'AL01 I2', operatorId:'27289',
      qcName:'Multichem IA', levels:['L1','L2'],
      results:[{ assay:'Troponin', level:'L1', sd, value:'', note:'' },
               { assay:'Troponin', level:'L2', sd:'ok', value:'', note:'' }] });
  }
  return entries;
}

// Vzorčni dnevnik vzdrževanja (qclab_v2.maintenanceLog) — oblika kot zapis aplikacije
function sampleMaint() {
  const today = new Date();
  const iso = (o) => { const x = new Date(today); x.setDate(x.getDate() + o); return x.toISOString(); };
  const rec = (id, instrId, integration, type, n, off) => ({
    id: id, datetime: iso(off), instrId: instrId, integration: integration, type: type,
    steps: [], doneCount: n, totalSteps: n, note: '', operatorId: '27289',
  });
  return [
    rec('m_d1', 'AL01 C1', 'AL01', 'daily', 3, 0),
    rec('m_d2', 'AL01 C1', 'AL01', 'daily', 3, -1),
    rec('m_d3', 'AL01 C1', 'AL01', 'daily', 3, -2),
    rec('m_w1', 'AL01 C1', 'AL01', 'weekly', 2, -3),
  ];
}

const SCREENS = [
  { nav:()=>navMain('zaloga'),                          file:'01_zaloga.png' },
  { nav:()=>navMain('roki'),                            file:'02_roki.png' },
  { nav:()=>navMain('loti'),                            file:'03_loti.png' },
  { nav:()=>{navMain('narocila');navOrd('prepare');},   file:'04_narocila.png' },
  { nav:()=>{navMain('servis');navSrv('entry');},       file:'05_servis.png' },
  { nav:()=>{navMain('qc');navQc('dashboard');},        file:'06_qc_nadzor.png' },
  { nav:()=>{navMain('qc');navQc('entry');},            file:'07_qc_vnos.png' },
  { nav:()=>{navMain('qc');navQc('lj');},               file:'08_qc_lj.png' },
  { nav:()=>{navMain('qc');navQc('maintenance');},      file:'09_vzdrzevanje.png' },
  { nav:()=>navMain('vec'),                             file:'10_vec.png' },
];

(async () => {
  if (!fs.existsSync(OUT)) fs.mkdirSync(OUT, { recursive: true });
  const srv = await serve();
  const userDataDir = fs.mkdtempSync(path.join(require('os').tmpdir(), 'bio-cap-'));
  const browser = await puppeteer.launch({
    executablePath: findBrowser(), headless: 'new', userDataDir,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--no-first-run', '--no-default-browser-check'],
  });
  const page = await browser.newPage();
  // Tablica v ležeči usmeritvi
  await page.setViewport({ width: 1280, height: 800, deviceScaleFactor: 1.5 });
  await page.goto(`http://127.0.0.1:${PORT}/bio_alinity.html`, { waitUntil: 'networkidle0' });

  // Vstavi vzorčne podatke in osveži
  await page.evaluate((data) => {
    localStorage.setItem('pregledBK_db', JSON.stringify(data.inv));
    localStorage.setItem('qclab_v2', JSON.stringify({ entries: data.qc, maintenanceLog: data.maint }));
  }, { inv: sampleInventory(), qc: sampleQc(), maint: sampleMaint() });
  await page.reload({ waitUntil: 'networkidle0' });

  for (const s of SCREENS) {
    await page.evaluate(s.nav);
    await new Promise(r => setTimeout(r, 700));
    await page.screenshot({ path: path.join(OUT, s.file) });
    console.log('zajeto:', s.file);
  }

  // Posnetek mesečnega poročila (modal) — best-effort
  try {
    await page.evaluate(() => { navMain('vec'); if (typeof openReportModal === 'function') openReportModal(); });
    await new Promise(r => setTimeout(r, 700));
    await page.screenshot({ path: path.join(OUT, '11_porocila.png') });
    console.log('zajeto:', '11_porocila.png');
  } catch (e) { console.log('porocila modal preskocen:', e.message); }

  await browser.close();
  srv.close();
  console.log('Končano. Posnetki v', OUT);
})().catch((e) => { console.error(e); process.exit(1); });

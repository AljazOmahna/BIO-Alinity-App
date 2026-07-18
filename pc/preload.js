/* BIO Alinity PC — preload.
 *
 * Definira globalni `window.AndroidBridge` z ISTO API povrsino kot
 * MainActivity.AndroidBridge na tablici, a podprt z lokalnimi datotekami v
 * OneDrive mapi DigiLab. HTML (bio_alinity.html) ne loci, ali tece na tablici
 * ali na PC — klice iste metode in iste povratne klice (onMs*, onReport* ...).
 */
const fs = require('fs');
const path = require('path');
const { ipcRenderer } = require('electron');

const CFG = ipcRenderer.sendSync('bridge:config');   // { digiLab, kcljDigiLab, appBuild, appVersion, platform, model }
const DIGILAB = CFG.digiLab || '';
const KCLJ_DIGILAB = CFG.kcljDigiLab || '';
let LAST_SYNC = '';

function dlPath(rel) { return path.join(DIGILAB, rel); }
function exists(p) { try { return fs.existsSync(p); } catch (e) { return false; } }
// Nekatera orodja (npr. PowerShell) pišejo UTF-8 z BOM — odstrani, sicer JSON.parse pade.
function readText(p) { return fs.readFileSync(p, 'utf8').replace(/^﻿/, ''); }

// Povratne klice zaganjamo asinhrono (kot na tablici), da se notify/UABI tok ujema.
function cb(code) { setTimeout(() => { try { window.eval(code); } catch (e) {} }, 0); }
function call(fn, ...args) {
  setTimeout(() => {
    try { if (typeof window[fn] === 'function') window[fn](...args); } catch (e) {}
  }, 0);
}

const Bridge = {
  // ---------- info / status ----------
  getDeviceInfo() {
    return JSON.stringify({
      model: CFG.model || 'PC', manufacturer: 'Windows', platform: 'pc',
      appVersion: CFG.appVersion || '', appBuild: CFG.appBuild || 0
    });
  },
  showToast(msg) { /* na PC ni sistemskega toasta — app uporablja notify() */ },

  // ---------- "prijava" = ali je DigiLab mapa dostopna ----------
  msIsSignedIn() { return !!DIGILAB && exists(DIGILAB); },
  msGetAccount() { return DIGILAB ? ('OneDrive (' + path.basename(path.dirname(DIGILAB)) + ')') : ''; },
  msGetLastSync() { return LAST_SYNC; },
  msSignIn() {
    if (this.msIsSignedIn()) { call('onMsSignedIn', this.msGetAccount()); }
    else { ipcRenderer.invoke('bridge:pickFolder').then(() => call('onMsSignedIn', this.msGetAccount())); }
  },
  msCancelSignIn() {},
  msSignOut() { call('onMsSignedOut'); },

  // ---------- sync JSON kljucev: DigiLab/<key>.json ----------
  msUpload(key, json) {
    try {
      fs.writeFileSync(dlPath(key + '.json'), json != null ? json : '', 'utf8');
      LAST_SYNC = new Date().toLocaleString('sl-SI');
      call('onMsUploadDone', true, 'Shranjeno v DigiLab/' + key + '.json');
    } catch (e) { call('onMsUploadDone', false, 'Napaka shranjevanja: ' + e.message); }
  },
  msDownload(key) {
    try {
      const p = dlPath(key + '.json');
      if (!exists(p)) { call('onMsDownloadDone', key, null, 'Ni datoteke v DigiLab'); return; }
      const txt = readText(p);
      LAST_SYNC = new Date().toLocaleString('sl-SI');
      call('onMsDownloadDone', key, txt, 'ok');
    } catch (e) { call('onMsDownloadDone', key, null, 'Napaka branja: ' + e.message); }
  },

  // ---------- seznam mape (Salus dobavnice) — Graph oblika {value:[{name}]} ----------
  msListFolder(folder) {
    try {
      const dir = dlPath(folder);
      const names = exists(dir) ? fs.readdirSync(dir) : [];
      const value = names.map(n => ({ name: n }));
      call('onMsFolderListed', JSON.stringify({ value }), 'ok');
    } catch (e) { call('onMsFolderListed', null, e.message); }
  },
  msDownloadText(rel) {
    try {
      const p = dlPath(rel);
      if (!exists(p)) { call('onMsTextFile', null, 'Ni datoteke'); return; }
      call('onMsTextFile', readText(p), 'ok');
    } catch (e) { call('onMsTextFile', null, e.message); }
  },

  // ---------- binarni prenos (DD Excel .xls): DigiLab/<rel> -> base64 ----------
  msDownloadBinary(rel) {
    try {
      const p = dlPath(rel);
      if (!exists(p)) { call('onMsBinaryFile', rel, null, 'Ni datoteke'); return; }
      call('onMsBinaryFile', rel, fs.readFileSync(p).toString('base64'), 'ok');
    } catch (e) { call('onMsBinaryFile', rel, null, e.message); }
  },

  // ---------- surovi zapis (CSV obvestila): DigiLab/<relPath> ----------
  msUploadRaw(relPath, content, mimeType) {
    try {
      const p = dlPath(relPath);
      fs.mkdirSync(path.dirname(p), { recursive: true });
      fs.writeFileSync(p, content != null ? content : '', 'utf8');
      call('onMsUploadRawDone', true, 'ok');
    } catch (e) { call('onMsUploadRawDone', false, e.message); }
  },

  // ---------- PDF poročilo (prek glavnega procesa) ----------
  generateAndUploadReport(subfolder, filename, html) {
    ipcRenderer.invoke('bridge:report', { subfolder, filename, html })
      .then(r => call('onReportGenerated', filename, r.ok, r.msg, r.localPath || ''))
      .catch(e => call('onReportGenerated', filename, false, e.message, ''));
  },
  openReportFile(p) {
    ipcRenderer.invoke('bridge:openPath', p).then(r => {
      if (!r.ok) call('onReportPickError', 'Ni mogoce odpreti: ' + (r.msg || ''));
    });
  },

  // ---------- QC vrednosti: poisci XML lota v DigiLab/QC_vrednosti/<lot> ----------
  qcFetchXmlForLot(lot) {
    try {
      const base = dlPath('QC_vrednosti');
      const dirs = [path.join(base, lot), base];
      for (const d of dirs) {
        if (!exists(d)) continue;
        const xml = fs.readdirSync(d).find(n => /\.xml$/i.test(n) && n.includes(lot));
        if (xml) { call('onQcXmlFetched', lot, fs.readFileSync(path.join(d, xml), 'utf8'), 'ok'); return; }
      }
      call('onQcXmlFetched', lot, null, 'Ni XML za lot ' + lot);
    } catch (e) { call('onQcXmlFetched', lot, null, e.message); }
  },
  saveQcXmlToDownloads(filename, xml, lot) {
    try {
      const lotKey = lot || filename.replace(/\.[^.]+$/, '');
      const dir = dlPath(path.join('QC_vrednosti', lotKey));
      fs.mkdirSync(dir, { recursive: true });
      const p = path.join(dir, filename);
      fs.writeFileSync(p, xml != null ? xml : '', 'utf8');
      call('onQcXmlSaved', p, true, 'ok');
    } catch (e) { call('onQcXmlSaved', null, false, e.message); }
  },
  // ---------- Abbott servisna porocila: odpri PDF iz KCLJ OneDrive ----------
  servAbottOpenPdf(filename) {
    try {
      if (!KCLJ_DIGILAB) { call('onServAbottError', 'KCLJ DigiLab mapa ni nastavljena'); return; }
      const p = path.join(KCLJ_DIGILAB, 'Servis_Abbott', filename);
      if (!exists(p)) { call('onServAbottError', 'Datoteka ne obstaja: ' + filename); return; }
      ipcRenderer.invoke('bridge:openPath', p);
    } catch (e) { call('onServAbottError', e.message); }
  }
  // Opomba: pickReportFile / pickQcValuesFile / quickShare namenoma NISO definirani —
  // HTML jih klice pogojno (if(AndroidBridge.pickReportFile)) in se brez njih elegantno izogne.
};

window.AndroidBridge = Bridge;

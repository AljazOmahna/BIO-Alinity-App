/* BIO Alinity PC — preload.
 *
 * Definira globalni `window.AndroidBridge` z ISTO API povrsino kot
 * MainActivity.AndroidBridge na tablici. Glavna sinhronizacija (prijava,
 * qclab_v2/operators/servis_abbott/pregledBK_db, Salus/DD datoteke) gre prek
 * Microsoft Graph (device code prijava — glej pc/graphSync.js), enako kot na
 * tablici/Zebri. Poročila (PDF) in nekaj lokalnih pomozhnih funkcij se se
 * vedno naslanjajo na lokalno DigiLab mapo (glej main.js DIGILAB) — te niso
 * del prijave in delujejo neodvisno.
 */
const fs = require('fs');
const path = require('path');
const { ipcRenderer } = require('electron');

const CFG = ipcRenderer.sendSync('bridge:config');   // { digiLab, kcljDigiLab, appBuild, appVersion, platform, model }
let DIGILAB = CFG.digiLab || '';   // let: posodobi se ob msChangeAccount() brez restarta
const KCLJ_DIGILAB = CFG.kcljDigiLab || '';

function dlPath(rel) { return path.join(DIGILAB, rel); }
function exists(p) { try { return fs.existsSync(p); } catch (e) { return false; } }
// Nekatera orodja (npr. PowerShell) pišejo UTF-8 z BOM — odstrani, sicer JSON.parse pade.
function readText(p) { return fs.readFileSync(p, 'utf8').replace(/^﻿/, ''); }

// Povratne klice zaganjamo asinhrono (kot na tablici), da se notify/UABI tok ujema.
function call(fn, ...args) {
  setTimeout(() => {
    try { if (typeof window[fn] === 'function') window[fn](...args); } catch (e) {}
  }, 0);
}

// Dogodki iz Graph device-code prijave (main.js -> graphSync.js -> mainWin.webContents.send).
ipcRenderer.on('graph-event', (event, payload) => {
  if (!payload) return;
  if (payload.type === 'deviceCode') call('onMsDeviceCode', payload.userCode, payload.verUri, payload.message || '');
  else if (payload.type === 'signedIn') call('onMsSignedIn', payload.account);
  else if (payload.type === 'error') call('onMsError', payload.msg);
});

const Bridge = {
  // ---------- info / status ----------
  getDeviceInfo() {
    return JSON.stringify({
      model: CFG.model || 'PC', manufacturer: 'Windows', platform: 'pc',
      appVersion: CFG.appVersion || '', appBuild: CFG.appBuild || 0
    });
  },
  showToast(msg) { /* na PC ni sistemskega toasta — app uporablja notify() */ },

  // ---------- prijava = Microsoft Graph (device code) ----------
  msIsSignedIn() { try { return ipcRenderer.sendSync('bridge:graph:isSignedIn'); } catch (e) { return false; } },
  msGetAccount() { try { return ipcRenderer.sendSync('bridge:graph:getAccount') || ''; } catch (e) { return ''; } },
  msGetLastSync() { try { return ipcRenderer.sendSync('bridge:graph:getLastSync') || ''; } catch (e) { return ''; } },
  msSignIn() { try { ipcRenderer.sendSync('bridge:graph:signIn'); } catch (e) { call('onMsError', 'Napaka prijave'); } },
  msCancelSignIn() { try { ipcRenderer.sendSync('bridge:graph:cancelSignIn'); } catch (e) {} },
  msSignOut() { try { ipcRenderer.sendSync('bridge:graph:signOut'); } catch (e) {} call('onMsSignedOut'); },
  // "Spremeni racun" = odjava trenutnega + takoj nova device-code prijava (nov uporabnik/racun).
  msChangeAccount() {
    try { ipcRenderer.sendSync('bridge:graph:signOut'); } catch (e) {}
    try { ipcRenderer.sendSync('bridge:graph:signIn'); } catch (e) { call('onMsError', 'Napaka prijave'); }
  },
  // Rocno preveri/ustvari mapno strukturo v OneDrive (BIO/, PBK/ ...) — koristno, ce je
  // uporabnik ze prijavljen izpred te funkcionalnosti (samodejno se sprozi le ob novi prijavi).
  msEnsureFolders(extraFolders) {
    ipcRenderer.invoke('bridge:graph:ensureFolders', extraFolders).then(r => call('onMsFoldersReady', r.ok, r.msg))
      .catch(e => call('onMsFoldersReady', false, e.message));
  },

  // ---------- sync JSON kljucev prek Graph: DigiLab/BIO|PBK/<kljuc>.json ----------
  msUpload(key, json) {
    ipcRenderer.invoke('bridge:graph:upload', key, json).then(r => call('onMsUploadDone', r.ok, r.msg))
      .catch(e => call('onMsUploadDone', false, 'Napaka nalaganja: ' + e.message));
  },
  msDownload(key) {
    ipcRenderer.invoke('bridge:graph:download', key).then(r => call('onMsDownloadDone', key, r.json, r.msg))
      .catch(e => call('onMsDownloadDone', key, null, 'Napaka prenosa: ' + e.message));
  },

  // ---------- seznam mape (Salus dobavnice) prek Graph — oblika {value:[{name}]} ----------
  msListFolder(folder) {
    ipcRenderer.invoke('bridge:graph:listFolder', folder).then(r => call('onMsFolderListed', r.json, r.msg))
      .catch(e => call('onMsFolderListed', null, e.message));
  },
  msDownloadText(rel) {
    ipcRenderer.invoke('bridge:graph:downloadText', rel).then(r => call('onMsTextFile', r.text, r.msg))
      .catch(e => call('onMsTextFile', null, e.message));
  },

  // ---------- binarni prenos (DD Excel .xls) prek Graph -> base64 ----------
  msDownloadBinary(rel) {
    ipcRenderer.invoke('bridge:graph:downloadBinary', rel).then(r => call('onMsBinaryFile', rel, r.base64, r.msg))
      .catch(e => call('onMsBinaryFile', rel, null, e.message));
  },

  // ---------- PDF -> golo besedilo, vse v oblaku (Salus dobavnice brez sidecar JSON) ----------
  msExtractPdfText(rel) {
    ipcRenderer.invoke('bridge:graph:extractPdfText', rel).then(r => call('onMsPdfTextExtracted', rel, r.text, r.msg))
      .catch(e => call('onMsPdfTextExtracted', rel, null, e.message));
  },

  // ---------- surovi zapis (CSV obvestila) prek Graph: DigiLab/<relPath> ----------
  msUploadRaw(relPath, content, mimeType) {
    ipcRenderer.invoke('bridge:graph:uploadRaw', relPath, content, mimeType).then(r => call('onMsUploadRawDone', r.ok, r.msg))
      .catch(e => call('onMsUploadRawDone', false, e.message));
  },

  // ---------- PDF poročilo (prek glavnega procesa, se vedno lokalna DigiLab mapa) ----------
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

  // ---------- Tiskanje (predogled -> sistemsko okno Windows, Wi-Fi/Bluetooth tiskalnik) ----------
  printHtml(html /*, title */) {
    ipcRenderer.invoke('bridge:printHtml', html)
      .then(r => call('onPrintDone', null))
      .catch(e => { call('onPrintDone', null); });
  },

  // ---------- QC vrednosti: poisci XML lota (se vedno lokalna DigiLab mapa, ce nastavljena) ----------
  qcFetchXmlForLot(lot) {
    try {
      if (!DIGILAB) { call('onQcXmlFetched', lot, null, 'Lokalna DigiLab mapa ni nastavljena'); return; }
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
      if (!DIGILAB) { call('onQcXmlSaved', null, false, 'Lokalna DigiLab mapa ni nastavljena'); return; }
      const lotKey = lot || filename.replace(/\.[^.]+$/, '');
      const dir = dlPath(path.join('QC_vrednosti', lotKey));
      fs.mkdirSync(dir, { recursive: true });
      const p = path.join(dir, filename);
      fs.writeFileSync(p, xml != null ? xml : '', 'utf8');
      call('onQcXmlSaved', p, true, 'ok');
    } catch (e) { call('onQcXmlSaved', null, false, e.message); }
  },
  // ---------- Abbott servisna porocila: odpri PDF iz KCLJ OneDrive (lokalno) ----------
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

/* BIO Alinity PC — Electron glavni proces.
 *
 * Naloga: prikazati ISTI bio_alinity.html kot tablicna aplikacija in mu
 * priskrbeti `window.AndroidBridge` (preko preload.js), ki dela prek lokalne
 * OneDrive mape DigiLab namesto prek MS Graph. Tablica in PC tako delita iste
 * datoteke (pregledBK_db.json, qclab_v2.json, operators.json ...) prek OneDrive.
 */
const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');

let CONFIG_PATH = '';      // pc-config.json v userData
let DIGILAB = '';          // absolutna pot do mape DigiLab
let mainWin = null;

// ---- verzija / build (uskladi z Android buildom prek build-info.json, ki ga zapise CI) ----
function buildInfo() {
  let info = { appBuild: 0, appVersion: app.getVersion() };
  try {
    const p = path.join(__dirname, 'build-info.json');
    if (fs.existsSync(p)) Object.assign(info, JSON.parse(fs.readFileSync(p, 'utf8')));
  } catch (e) {}
  return info;
}

// ---- pot do deljenega HTML (razvoj: repo; zapakirano: extraResources) ----
function assetsDir() {
  if (app.isPackaged) return path.join(process.resourcesPath, 'app-assets');
  return path.join(__dirname, '..', 'app', 'src', 'main', 'assets');
}

// ---- DigiLab: nalozi/poisci/vprasaj ----
function loadConfig() {
  CONFIG_PATH = path.join(app.getPath('userData'), 'pc-config.json');
  try {
    if (fs.existsSync(CONFIG_PATH)) {
      const c = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
      if (c.digiLab) return c.digiLab;
    }
  } catch (e) {}
  return '';
}
function saveConfig() {
  try { fs.writeFileSync(CONFIG_PATH, JSON.stringify({ digiLab: DIGILAB }, null, 2)); } catch (e) {}
}
function autoDetectDigiLab() {
  if (process.env.BIOALINITY_DIGILAB) return process.env.BIOALINITY_DIGILAB;
  const home = os.homedir();
  const candidates = [];
  // Osebni OneDrive (DigiLab je v osebnem OneDriveju — glej onedrive-sync-findings)
  candidates.push(path.join(home, 'OneDrive', 'DigiLab'));
  // Sluzbeni / poimenovani OneDrive imeniki (OneDrive - Xxx)
  try {
    for (const d of fs.readdirSync(home)) {
      if (/^OneDrive/i.test(d)) candidates.push(path.join(home, d, 'DigiLab'));
    }
  } catch (e) {}
  for (const c of candidates) {
    try { if (fs.statSync(c).isDirectory()) return c; } catch (e) {}
  }
  return '';
}
async function ensureDigiLab() {
  DIGILAB = loadConfig();
  if (DIGILAB && fs.existsSync(DIGILAB)) return;
  const auto = autoDetectDigiLab();
  if (auto) { DIGILAB = auto; saveConfig(); return; }
  // Vprasaj uporabnika
  const res = await dialog.showOpenDialog({
    title: 'Izberite mapo DigiLab (OneDrive)',
    message: 'Najdite mapo "DigiLab" v vasem OneDrive — tam so pregledBK_db.json, qclab_v2.json ...',
    properties: ['openDirectory']
  });
  if (!res.canceled && res.filePaths[0]) { DIGILAB = res.filePaths[0]; saveConfig(); }
}

function createWindow() {
  mainWin = new BrowserWindow({
    width: 1280,
    height: 832,
    title: 'BIO Alinity PC',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: false,   // HTML pricakuje globalni AndroidBridge
      nodeIntegration: false,
      sandbox: false             // preload rabi require('fs')
    }
  });
  mainWin.loadFile(path.join(assetsDir(), 'bio_alinity.html'));
  // Ob vsakem zagonu samodejno povleci zadnje podatke iz DigiLab (ista sync pot
  // kot gumb "Sync" na tablici — onMsDownloadDone zdruzi v localStorage).
  mainWin.webContents.on('did-finish-load', () => {
    mainWin.webContents.executeJavaScript(
      "try{if(window.msDownload){msDownload('pregledBK_db');msDownload('qclab_v2');}}catch(e){}"
    ).catch(() => {});
    setTimeout(() => {
      mainWin.webContents.executeJavaScript(
        "({db:(localStorage.getItem('pregledBK_db')||'').length,qc:(localStorage.getItem('qclab_v2')||'').length})"
      ).then(r => console.log('[sync] localStorage napolnjen — pregledBK_db:', r.db, 'B, qclab_v2:', r.qc, 'B')).catch(() => {});
    }, 1500);
  });
  // Zunanje povezave v sistemskem brskalniku
  mainWin.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) { shell.openExternal(url); return { action: 'deny' }; }
    return { action: 'allow' };
  });
}

// ===================== IPC: most do datotek / sistema =====================

ipcMain.on('bridge:config', (e) => {
  e.returnValue = { digiLab: DIGILAB, ...buildInfo(), platform: 'pc', model: os.hostname() };
});

// Sestavi PDF iz HTML in shrani lokalno (Poročila/<leto>) + v DigiLab/<subfolder>
ipcMain.handle('bridge:report', async (e, { subfolder, filename, html }) => {
  let win;
  try {
    win = new BrowserWindow({ show: false, webPreferences: { offscreen: true } });
    await win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html));
    const pdf = await win.webContents.printToPDF({ printBackground: true, pageSize: 'A4', landscape: true });
    const year = (filename.match(/(20\d\d)/) || [, new Date().getFullYear()])[1];
    // lokalni arhiv poleg OneDrive
    const localDir = path.join(path.dirname(DIGILAB), 'Poročila', String(year));
    fs.mkdirSync(localDir, { recursive: true });
    const localPath = path.join(localDir, filename);
    fs.writeFileSync(localPath, pdf);
    // v OneDrive DigiLab/<subfolder>/<filename>
    if (DIGILAB && subfolder) {
      const cloudDir = path.join(DIGILAB, subfolder);
      fs.mkdirSync(cloudDir, { recursive: true });
      fs.writeFileSync(path.join(cloudDir, filename), pdf);
    }
    return { ok: true, msg: 'PDF shranjen: ' + filename, localPath };
  } catch (err) {
    return { ok: false, msg: 'Napaka PDF: ' + err.message, localPath: '' };
  } finally {
    if (win) win.destroy();
  }
});

ipcMain.handle('bridge:openPath', async (e, p) => {
  try { const r = await shell.openPath(p); return r === '' ? { ok: true } : { ok: false, msg: r }; }
  catch (err) { return { ok: false, msg: err.message }; }
});

ipcMain.handle('bridge:pickFolder', async () => {
  const res = await dialog.showOpenDialog(mainWin, { title: 'Mapa DigiLab', properties: ['openDirectory'] });
  if (!res.canceled && res.filePaths[0]) { DIGILAB = res.filePaths[0]; saveConfig(); }
  return DIGILAB;
});

// ===================== zagon =====================
app.whenReady().then(async () => {
  await ensureDigiLab();
  createWindow();
  // Samodejna posodobitev (le zapakirano) — preveri GitHub Releases
  if (app.isPackaged) {
    try {
      const { autoUpdater } = require('electron-updater');
      autoUpdater.autoDownload = true;
      autoUpdater.on('update-downloaded', () => {
        dialog.showMessageBox(mainWin, {
          type: 'info', buttons: ['Posodobi zdaj', 'Pozneje'], defaultId: 0,
          title: 'Posodobitev', message: 'Na voljo je nova razlicica BIO Alinity PC.'
        }).then(r => { if (r.response === 0) autoUpdater.quitAndInstall(); });
      });
      autoUpdater.checkForUpdates();
    } catch (e) { /* posodobitev ni kriticna */ }
  }
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });

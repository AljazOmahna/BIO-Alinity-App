/* BIO Alinity PC — Microsoft Graph sinhronizacija prek OAuth2 Device Code Flow.
 * Enak tok kot na Androidu/Pregled BK (glej pregled-bk-android GraphSync.java):
 *   1) signIn() -> devicecode endpoint -> cb.deviceCode(userCode, verificationUri)
 *                  -> polling token endpoint -> shrani refresh_token -> cb.signedIn(account)
 *   2) upload()/download() uporabita access_token (osvezen iz refresh_token po potrebi)
 * Refresh token je shranjen sifrirano (Electron safeStorage) v userData mapi.
 */
const fs = require('fs');
const path = require('path');
const { safeStorage, shell } = require('electron');
let _pdfParse = null;
function pdfParse(buf) {
  if (!_pdfParse) _pdfParse = require('pdf-parse');
  return _pdfParse(buf);
}

const TENANT = 'consumers'; // osebni Microsoft racuni (Hotmail/Outlook)
const CLIENT_ID = 'd80dd868-33d3-40aa-b7c3-10f494509030'; // DigiLab BIO Alinity app registracija
const SCOPE = 'offline_access Files.ReadWrite User.Read';
const MS_FOLDER = 'DigiLab';

// Preslika logicni kljuc stanja v pot znotraj DigiLab (shema B: BIO/ predpona) — usklajeno z
// mapStateKey() v Android MainActivity.java, da PC in tablica delita iste datoteke.
function mapStateKey(key) {
  switch (key) {
    case 'qclab_v2': return 'BIO/qclab_v2';
    case 'operators': return 'BIO/operaterji';
    case 'servis_abbott': return 'BIO/servis_abbott';
    case 'pregledBK_db': return 'PBK/pbk_sync';
    default: return 'BIO/' + key;
  }
}

class GraphSync {
  constructor(userDataPath) {
    this.tokenPath = path.join(userDataPath, 'ms-auth.enc');
    this.metaPath = path.join(userDataPath, 'ms-auth.json'); // {account, lastSync} — ni obcutljivo
    this._cancelLogin = false;
    this._meta = this._loadMeta();
  }
  _loadMeta() {
    try { return JSON.parse(fs.readFileSync(this.metaPath, 'utf8')); } catch (e) { return {}; }
  }
  _saveMeta() { try { fs.writeFileSync(this.metaPath, JSON.stringify(this._meta)); } catch (e) {} }
  _saveRefreshToken(rt) {
    if (!rt) return;
    try {
      const enc = safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(rt) : Buffer.from(rt, 'utf8');
      fs.writeFileSync(this.tokenPath, enc);
    } catch (e) {}
  }
  _loadRefreshToken() {
    try {
      const buf = fs.readFileSync(this.tokenPath);
      return safeStorage.isEncryptionAvailable() ? safeStorage.decryptString(buf) : buf.toString('utf8');
    } catch (e) { return null; }
  }
  isSignedIn() { return !!this._loadRefreshToken(); }
  getAccount() { return this._meta.account || ''; }
  getLastSync() { return this._meta.lastSync || ''; }
  signOut() {
    try { fs.unlinkSync(this.tokenPath); } catch (e) {}
    this._meta = {};
    this._saveMeta();
  }
  cancelSignIn() { this._cancelLogin = true; }

  // cb: { deviceCode(userCode,verUri,message), signedIn(account), error(msg) }
  async signIn(cb) {
    this._cancelLogin = false;
    try {
      const dcUrl = `https://login.microsoftonline.com/${TENANT}/oauth2/v2.0/devicecode`;
      const tokenUrl = `https://login.microsoftonline.com/${TENANT}/oauth2/v2.0/token`;
      const dcRes = await fetch(dcUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ client_id: CLIENT_ID, scope: SCOPE })
      });
      const dc = await dcRes.json();
      if (!dc.device_code) { cb.error(dc.error_description || 'Napaka prijave'); return; }
      const verUri = dc.verification_uri || 'https://microsoft.com/link';
      cb.deviceCode(dc.user_code, verUri, dc.message || '');
      try { shell.openExternal(verUri); } catch (e) {}

      let interval = dc.interval || 5;
      const deadline = Date.now() + (dc.expires_in || 900) * 1000;
      while (Date.now() < deadline) {
        if (this._cancelLogin) { cb.error('Prijava preklicana'); return; }
        await new Promise(r => setTimeout(r, Math.max(2, interval) * 1000));
        if (this._cancelLogin) { cb.error('Prijava preklicana'); return; }

        const pollRes = await fetch(tokenUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({
            grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
            client_id: CLIENT_ID,
            device_code: dc.device_code
          })
        });
        const pj = await pollRes.json();
        if (pollRes.status === 200) {
          this._saveRefreshToken(pj.refresh_token || '');
          const account = await this._fetchAccountName(pj.access_token);
          this._meta.account = account;
          this._saveMeta();
          // Ob prvi prijavi na (nov) racun samodejno pripravi mapno strukturo v OneDrive
          // (ce ze obstaja, ustvarjanje tiho spregleda — brez skode za obstojece podatke).
          try { await this.ensureFolderStructure(); } catch (e) {}
          cb.signedIn(account);
          return;
        }
        const err = pj.error || '';
        if (err === 'authorization_pending') continue;
        if (err === 'slow_down') { interval += 5; continue; }
        if (err === 'expired_token' || err === 'code_expired') { cb.error('Koda je potekla — poskusi znova'); return; }
        if (err === 'authorization_declined') { cb.error('Prijava zavrnjena'); return; }
        cb.error(pj.error_description || err || 'Napaka prijave');
        return;
      }
      cb.error('Koda je potekla — poskusi znova');
    } catch (e) {
      cb.error('Napaka prijave: ' + e.message);
    }
  }

  async _fetchAccountName(accessToken) {
    try {
      const r = await fetch('https://graph.microsoft.com/v1.0/me', { headers: { Authorization: 'Bearer ' + accessToken } });
      const j = await r.json();
      const name = j.displayName || '';
      const upn = j.userPrincipalName || j.mail || '';
      if (name && upn) return `${name} (${upn})`;
      return upn || name || 'OneDrive racun';
    } catch (e) { return 'OneDrive racun'; }
  }

  async _freshAccessToken() {
    const rt = this._loadRefreshToken();
    if (!rt) return null;
    const tokenUrl = `https://login.microsoftonline.com/${TENANT}/oauth2/v2.0/token`;
    const r = await fetch(tokenUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'refresh_token', client_id: CLIENT_ID, refresh_token: rt, scope: SCOPE })
    });
    const j = await r.json();
    if (r.status === 200) {
      this._saveRefreshToken(j.refresh_token || rt);
      return j.access_token;
    }
    this.signOut();
    throw new Error('Seja je potekla — prijavi se znova');
  }

  // Ustvari eno mapo (relativno na DigiLab), ce se ne obstaja. relPath je lahko
  // vecnivojska pot npr. "BIO/Arhiv/QC" — ustvari vsak nivo posebej po vrsti.
  async ensureFolder(relPath) {
    const segments = (relPath || '').split('/').filter(Boolean);
    let cur = MS_FOLDER;
    for (const seg of segments) {
      const at = await this._freshAccessToken();
      if (!at) return { ok: false, msg: 'Niste prijavljeni' };
      const url = `https://graph.microsoft.com/v1.0/me/drive/root:/${cur}:/children`;
      const r = await fetch(url, {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + at, 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: seg, folder: {}, '@microsoft.graph.conflictBehavior': 'fail' })
      });
      // 201 = ustvarjeno, 409 = ze obstaja (obakrat v redu, nadaljuj globlje)
      if (r.status !== 201 && r.status !== 409) {
        let detail = '';
        try { detail = (await r.json()).error?.message || ''; } catch (e) {}
        return { ok: false, msg: `Napaka pri ustvarjanju mape ${cur}/${seg} (${r.status}) ${detail}` };
      }
      cur = cur + '/' + seg;
    }
    return { ok: true };
  }

  // Osnovna mapna struktura ob prvi prijavi na (nov) racun — shema B (BIO/, PBK/ predpone).
  // Instrument-specificne podmape (npr. BIO/Arhiv/QC/AL01/AL01 C1/2026/07) se ustvarijo
  // samodejno ob prvem poroci lu/prenosu (Graph PUT :/content ustvari manjkajoce nadrejene mape).
  // extraFolders: dodatne poti (npr. trenutno konfigurirane S.ddCategories[].folder
  // iz renderer-ja) — omogoca da se ob "Pripravi mape" ustvarijo tudi uporabnikove
  // DD kategorije/podmape, ne le fiksni osnovni seznam spodaj.
  async ensureFolderStructure(extraFolders) {
    const folders = [
      'BIO', 'BIO/Arhiv', 'BIO/Arhiv/Vzdrzevanje', 'BIO/Arhiv/QC',
      'BIO/DD_Kontrole', 'BIO/DD_Kontrole/DD_CSV', 'BIO/Obvestila', 'BIO/QC_vrednosti',
      'BIO/Reagencni_Listi', 'BIO/Varnostni_Listi',
      'PBK', 'PBK/Arhiv',
      'Salus_Dobavnice',
      'Abbott_Servis', 'Abbott_Servis/_cache'
    ].concat(Array.isArray(extraFolders) ? extraFolders.filter(Boolean) : []);
    for (const f of folders) {
      const r = await this.ensureFolder(f);
      if (!r.ok) return r;
    }
    return { ok: true };
  }

  async upload(key, json) {
    const at = await this._freshAccessToken();
    if (!at) return { ok: false, msg: 'Niste prijavljeni' };
    const url = `https://graph.microsoft.com/v1.0/me/drive/root:/${MS_FOLDER}/${mapStateKey(key)}.json:/content`;
    const r = await fetch(url, { method: 'PUT', headers: { Authorization: 'Bearer ' + at, 'Content-Type': 'application/json' }, body: json });
    if (r.status === 200 || r.status === 201) {
      this._meta.lastSync = new Date().toLocaleString('sl-SI'); this._saveMeta();
      return { ok: true, msg: 'Naloženo v ' + MS_FOLDER + '/' + mapStateKey(key) + '.json' };
    }
    return { ok: false, msg: 'Napaka nalaganja (' + r.status + ')' };
  }

  async download(key) {
    const at = await this._freshAccessToken();
    if (!at) return { ok: false, json: null, msg: 'Niste prijavljeni' };
    const url = `https://graph.microsoft.com/v1.0/me/drive/root:/${MS_FOLDER}/${mapStateKey(key)}.json:/content`;
    const r = await fetch(url, { headers: { Authorization: 'Bearer ' + at } });
    if (r.status === 200) {
      this._meta.lastSync = new Date().toLocaleString('sl-SI'); this._saveMeta();
      return { ok: true, json: await r.text(), msg: 'ok' };
    }
    if (r.status === 404) return { ok: true, json: null, msg: 'V OneDrive še ni shranjenih podatkov' };
    return { ok: false, json: null, msg: 'Napaka prenosa (' + r.status + ')' };
  }

  async listFolder(relPath) {
    const at = await this._freshAccessToken();
    if (!at) return { ok: false, json: null, msg: 'Niste prijavljeni' };
    const rp = (relPath || '').replace(/^\/+/, '');
    // Ce je rp prazen (uporabnik je z "nazaj" prisel do korena DigiLab), pot NE
    // sme imeti odvecnega "/" pred zakljucnim ":" — Graph tak path zavrne kot
    // neveljaven (404), tudi ce mapa DigiLab dejansko obstaja.
    const path = rp ? `${MS_FOLDER}/${rp}` : MS_FOLDER;
    const url = `https://graph.microsoft.com/v1.0/me/drive/root:/${path}:/children?$select=name,size,lastModifiedDateTime,folder,file,webUrl&$top=500`;
    const r = await fetch(url, { headers: { Authorization: 'Bearer ' + at } });
    if (r.status === 200) return { ok: true, json: await r.text(), msg: 'ok' };
    if (r.status === 404) return { ok: true, json: null, msg: 'Mape ni v OneDrive' };
    return { ok: false, json: null, msg: 'Napaka listanja (' + r.status + ')' };
  }

  async downloadText(relPath) {
    const at = await this._freshAccessToken();
    if (!at) return { ok: false, text: null, msg: 'Niste prijavljeni' };
    const rp = (relPath || '').replace(/^\/+/, '');
    const url = `https://graph.microsoft.com/v1.0/me/drive/root:/${MS_FOLDER}/${rp}:/content`;
    const r = await fetch(url, { headers: { Authorization: 'Bearer ' + at } });
    if (r.status === 200) return { ok: true, text: await r.text(), msg: 'ok' };
    if (r.status === 404) return { ok: true, text: null, msg: 'Datoteke ni v OneDrive' };
    return { ok: false, text: null, msg: 'Napaka prenosa (' + r.status + ')' };
  }

  async downloadBinary(relPath) {
    const at = await this._freshAccessToken();
    if (!at) return { ok: false, base64: null, msg: 'Niste prijavljeni' };
    const rp = (relPath || '').replace(/^\/+/, '');
    const url = `https://graph.microsoft.com/v1.0/me/drive/root:/${MS_FOLDER}/${rp}:/content`;
    const r = await fetch(url, { headers: { Authorization: 'Bearer ' + at } });
    if (r.status === 200) return { ok: true, base64: Buffer.from(await r.arrayBuffer()).toString('base64'), msg: 'ok' };
    if (r.status === 404) return { ok: true, base64: null, msg: 'Datoteke ni v OneDrive' };
    return { ok: false, base64: null, msg: 'Napaka prenosa (' + r.status + ')' };
  }

  // Prenese PDF iz OneDrive (Graph) in izlusci golo besedilo — vse v oblaku, brez lokalne
  // odvisnosti. Uporablja se za Salus dobavnice, kjer ni (se) naloen .salus.json sidecar.
  async extractPdfText(relPath) {
    const at = await this._freshAccessToken();
    if (!at) return { ok: false, text: null, msg: 'Niste prijavljeni' };
    const rp = (relPath || '').replace(/^\/+/, '');
    const url = `https://graph.microsoft.com/v1.0/me/drive/root:/${MS_FOLDER}/${rp}:/content`;
    const r = await fetch(url, { headers: { Authorization: 'Bearer ' + at } });
    if (r.status === 404) return { ok: true, text: null, msg: 'Datoteke ni v OneDrive' };
    if (r.status !== 200) return { ok: false, text: null, msg: 'Napaka prenosa (' + r.status + ')' };
    try {
      const buf = Buffer.from(await r.arrayBuffer());
      const parsed = await pdfParse(buf);
      return { ok: true, text: parsed.text || '', msg: 'ok' };
    } catch (e) {
      return { ok: false, text: null, msg: 'Napaka branja PDF: ' + e.message };
    }
  }

  async uploadRaw(relPath, content, mimeType) {
    const at = await this._freshAccessToken();
    if (!at) return { ok: false, msg: 'Niste prijavljeni' };
    const rp = (relPath || '').replace(/^\/+/, '');
    const url = `https://graph.microsoft.com/v1.0/me/drive/root:/${MS_FOLDER}/${rp}:/content`;
    const r = await fetch(url, { method: 'PUT', headers: { Authorization: 'Bearer ' + at, 'Content-Type': mimeType || 'application/octet-stream' }, body: content });
    if (r.status === 200 || r.status === 201) return { ok: true, msg: 'ok' };
    return { ok: false, msg: 'Napaka nalaganja (' + r.status + ')' };
  }

  async uploadBytes(relPath, buffer, mimeType) {
    const at = await this._freshAccessToken();
    if (!at) return { ok: false, msg: 'Niste prijavljeni' };
    const rp = (relPath || '').replace(/^\/+/, '');
    const url = `https://graph.microsoft.com/v1.0/me/drive/root:/${MS_FOLDER}/${rp}:/content`;
    const r = await fetch(url, { method: 'PUT', headers: { Authorization: 'Bearer ' + at, 'Content-Type': mimeType || 'application/octet-stream' }, body: buffer });
    if (r.status === 200 || r.status === 201) return { ok: true, msg: 'ok' };
    return { ok: false, msg: 'Napaka nalaganja (' + r.status + ')' };
  }
}

module.exports = { GraphSync, mapStateKey, MS_FOLDER };

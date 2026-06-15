# Sinhronizacija Zebra ↔ tablica prek OneDrive (Microsoft Graph)

Načrt sinhronizacije zaloge med čitalniki **Zebra TC20 (Pregled BK)** in **tablico (BIO Alinity)**.
OneDrive/Graph je samo **prenosna pot**; pravilnost zagotavlja pametno združevanje (merge), ki je
že vgrajeno in preverjeno v obeh aplikacijah.

## Model: ena skupna datoteka + »download → merge → upload«

Skupna datoteka na OneDrive (mapa IT UKCL), npr. `/Aplikacije/PregledBK/pregledBK_db.json`.

Vsaka naprava ob sinhronizaciji:
1. **Download** trenutne skupne datoteke (blob).
2. **Merge** v lokalno bazo (`mergeDB` na Zebri / `mergePregledDB` na tablici).
3. **Upload** združene lokalne baze nazaj v skupno datoteko.

Ta vzorec je **konvergenten in idempotenten**: ne glede na vrstni red naprav vse pridejo do iste
popolne unije; ponovni sync ničesar ne podvoji.

QC podatki (`qclab_v2`) se sinhronizirajo po istem vzorcu prek ločene datoteke
`/Aplikacije/PregledBK/qclab_v2.json` (tablica) — Zebra je ne uporablja.

## Zakaj je merge varen (preverjeno)

- **Enota = EPC** (pravi unikat RFID; če EPC manjka, SN). Isti fizični kos se nikoli ne podvoji;
  dva različna kosa z istim SN, a različnim EPC, ostaneta ločena.
- `serials` / `rfidEpcs` / `qty` se **izpeljejo iz združenih enot** → vedno konsistentno.
- Združujejo se tudi `gtins`, `received`, `sessions` (z items[]), `lots` (z ovrednotenjem),
  `consumed` (poraba → uskladi zalogo). Brez izgub, brez dvojnikov.
- Preverjeno na pravih bazah: Zebra A(209) + B(31) → 240 enot, 0 izgub, 0 dvojnikov;
  Zebra → tablica enako (240), idempotentno. Zebra Pregled BK build 113, BIO Alinity parity.

## Kaj je že pripravljeno v kodi (placeholder)

Obe aplikaciji imata vgrajene **odložišča (stubs)** za Graph, ki čakajo le na Azure `client_id`:

- `MainActivity.java` (obe): `msSignIn()`, `msSignOut()`, `msIsSignedIn()`, `msUpload(key,json)`,
  `msDownload(key)` — trenutno vračajo »OneDrive sync ni aktiven«.
- JS callbacki: `onMsSignedIn`, `onMsSignedOut`, `onMsError`, `onMsUploadDone(ok,msg)`,
  `onMsDownloadDone(key/json,msg)`. Pri Zebri `onMsDownloadDone` že kliče `onSharedImport`
  (download → merge), torej je sprejemna pot dokončana.
- UI: Več → »OneDrive prenesi / naloži« gumbi že obstajajo.

## Kaj je potrebno, ko pride dostop (ta teden)

1. **Azure app registration** (IT UKCL): javni odjemalec (mobile/desktop), redirect URI
   `msauth://si.kclj.pregledbk/<hash>` in `msauth://si.kclj.bioalinity/<hash>`.
2. **Dovoljenja Graph (delegated)**: `Files.ReadWrite` (ali `Files.ReadWrite.AppFolder` za izolacijo),
   `User.Read`, `offline_access`. Skrbniško soglasje za @kclj.si najemnika.
3. **MSAL Android** v obe aplikaciji: OAuth2 **device-code** ali interaktivni tok (PKCE);
   shrani refresh token (`offline_access`) za tiho osveževanje.
4. Implementiraj `msUpload`/`msDownload` prek Graph REST:
   - Download: `GET /me/drive/root:/Aplikacije/PregledBK/pregledBK_db.json:/content`
   - Upload (<4 MB): `PUT /me/drive/root:/Aplikacije/PregledBK/pregledBK_db.json:/content`
   - (ETag/`@microsoft.graph.conflictBehavior=replace`; pri veliki rasti uporabi upload session.)
5. **Vrstni red ob uploadu**: VEDNO najprej download+merge, šele nato upload (prepreči povozenje
   tujih sprememb). Opcijsko ETag preverjanje za zaznavo sočasne spremembe → ponovi cikel.

## Test brez Azure (opravljeno)

Sprejemna+merge pot je preverjena brez Grapha: realni JSON ene naprave podan drugi prek
`onSharedImport`/`mergeDB` (Quick Share danes; OneDrive jutri — ista koda). Rezultat: popolna
konvergenca, 0 izgub, 0 dvojnikov, idempotentno.

# BIO Alinity App

Android WebView aplikacija za tablico **Samsung Galaxy Tab S6 Lite (SM-P620)**, namenjena
laboratoriju BK za pregled zaloge in upravljanje notranje kontrole kakovosti (QC) za
Abbott Alinity analizatorje.

## Namen

Tablična aplikacija združuje dve funkcionalnosti:

1. **Pregled zaloge** (samo za branje, vir: *Pregled BK* na Zebra TC26)
   - Zavihek **Zaloga** — vsi artikli z roki, loti, EPC
   - Zavihek **Roki** — razvrščeno po rokih z barvnim opozorilom
   - Zavihek **Loti** — loti kalibratorjev/kontrol/reagentov

2. **QC Lab** (polna funkcionalnost)
   - Vnos QC z **Westgard pravili** (1₂ₛ, 1₃ₛ, 2₂ₛ, R₄ₛ, 4₁ₛ, 10ₓ)
   - **Levey-Jennings grafi** (Canvas)
   - 300+ operaterjev s PIN avtentikacijo (5-mestna matična številka)
   - Materiali, preiskave, vzdrževanje analizatorjev

Na tablici **ni skeniranja** (brez RFID, brez DataWedge, brez Locate Tag).

## Tehnične podrobnosti

| | |
|---|---|
| Package | `si.kclj.bioalinity` |
| minSdk / targetSdk / compileSdk | 26 / 34 / 34 |
| WebView entry | `assets/bio_alinity.html` |
| JS bridge | `AndroidBridge` |
| localStorage ključi | `pregledBK_db` (zaloga), `qclab_v2` (QC) |
| Signing | `keystore/kclj_debug.jks` (alias androiddebugkey) |

## Build

GitHub Actions samodejno zgradi debug APK ob vsakem `push` na `main`.
APK je dostopen kot artifact v zavihku **Actions**.

Lokalno:
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## OneDrive sync

OneDrive sinhronizacija (Microsoft Graph API) je **pripravljena kot placeholder**.
Aktivira se, ko IT oddelek UKCL odobri Azure app registracijo.
Datoteki: `pregledBK_db.json` + `qclab_v2.json`.

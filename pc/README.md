# BIO Alinity PC

Namizna (Windows) razlicica aplikacije **BIO Alinity**. Prikaze **isti**
`app/src/main/assets/bio_alinity.html` kot tablicna (Android) aplikacija — torej
se UI in logika spreminjata **hkrati** s tablico. Edina razlika je tanka lupina
(Electron) in most do datotek.

## Kako deluje

- `main.js` — Electron glavni proces: poisce mapo **DigiLab** v OneDrive,
  odpre okno in nalozi deljeni `bio_alinity.html`. Sestavlja tudi PDF poročila
  (`printToPDF`) in skrbi za samodejno posodobitev (electron-updater).
- `preload.js` — definira `window.AndroidBridge` z isto API povrsino kot
  `MainActivity.AndroidBridge` na tablici, a podprt z lokalnimi datotekami.
  Namesto MS Graph kar bere/pise v lokalno sinhronizirano OneDrive mapo DigiLab.

### Sinhronizacija
Na PC je osebni OneDrive ze sinhroniziran kot navadna mapa
(`C:\Users\<ime>\OneDrive\DigiLab`). Tablica pise iste datoteke prek MS Graph v
isto OneDrive mapo. Tako sta PC in tablica usklajena brez dodatne kode:

| kljuc | datoteka |
|-------|----------|
| `pregledBK_db` | `DigiLab/pregledBK_db.json` |
| `qclab_v2` | `DigiLab/qclab_v2.json` |
| `operators` | `DigiLab/operators.json` |
| obvestila CSV | `DigiLab/Obvestila/YYYY-MM.csv` |
| Salus dobavnice | `DigiLab/Salus_Dobavnice/*.salus.json` |
| QC vrednosti | `DigiLab/QC_vrednosti/<lot>/*.xml` |

Mapo DigiLab ob prvem zagonu poskusi najti samodejno; ce ne uspe, te vprasa.
Lahko jo nastavis tudi z okoljsko spremenljivko `BIOALINITY_DIGILAB`.

## Razvoj

```sh
cd pc
npm install
npm start
```

### Tezava: "Electron failed to install correctly"
Ker rep zivi v OneDrive mapi, lahko OneDrive prekine razsiritev Electron binarke
med `npm install`. Popravek (brez ponovnega prenosa — zip je ze v predpomnilniku):

```powershell
$dist = "node_modules\electron\dist"
$zip  = "$env:LOCALAPPDATA\electron\Cache\*\electron-*-win32-x64.zip"
Remove-Item -Recurse -Force $dist; New-Item -ItemType Directory $dist | Out-Null
Expand-Archive (Resolve-Path $zip) $dist -Force
"electron.exe" | Out-File node_modules\electron\path.txt -Encoding ascii -NoNewline
```

V CI (cist windows-latest runner, brez OneDrive) te tezave ni.

## Gradnja namestilnika (.exe)

```sh
npm run dist        # ustvari dist/BIO-Alinity-PC-Setup-<verzija>.exe
```

V CI (GitHub Actions) se zgradi ob istem pushu kot APK; glej
`.github/workflows/pc-build.yml`. `build-info.json` napolni `appBuild` iz
`github.run_number`, da je verzija usklajena s tablicno.

## Samodejna posodobitev
Zapakirana aplikacija ob zagonu preveri GitHub Releases (`electron-updater`).
Ko CI objavi nov release, odjemalci dobijo posodobitev samodejno.

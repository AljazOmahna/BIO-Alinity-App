# Navodila BIO Alinity — sistem za samodejno posodabljanje

Navodila se sestavijo iz besedilnega vira in posnetkov zaslonov, zajetih
neposredno iz aplikacije, zato ostanejo usklajena z vsako novo različico.

## Datoteke

| Datoteka | Namen |
|---|---|
| `NAVODILA.md` | Vir besedila navodil (urejaj tukaj). |
| `screens/*.png` | Posnetki zaslonov, zajeti iz aplikacije. |
| `capture_screens.js` | Zajme zaslone iz `bio_alinity.html` (puppeteer-core). |
| `generate_navodila.py` | Sestavi `Navodila_BioAlinity.docx` iz vira + posnetkov. |
| `Navodila_BioAlinity.docx` | Končni Word dokument. |

## Samodejno posodabljanje (GitHub Actions)

Ob vsakem `push` v `main`, ki spremeni `navodila/**` ali
`app/src/main/assets/bio_alinity.html`, se sproži `.github/workflows/navodila.yml`:
zajame posnetke, zgradi `.docx` in ga naloži kot artifact **Navodila_BioAlinity**.
Različica aplikacije (build) se prebere iz `github.run_number` (okoljska
spremenljivka `APP_BUILD`), enako kot `versionName` v `app/build.gradle`.

## Ročna izdelava (lokalno)

```bash
cd navodila
npm install puppeteer-core      # enkratno
node capture_screens.js         # osveži posnetke (uporabi nameščen Chrome/Edge)
python generate_navodila.py     # zgradi Navodila_BioAlinity.docx
```

Za zajem brez sistemskega brskalnika nastavi `PUPPETEER_EXECUTABLE_PATH`.
Različico v podnaslovu nastaviš ročno z `APP_BUILD=<št>` pred zagonom generatorja.

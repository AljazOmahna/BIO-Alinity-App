<#
.SYNOPSIS
  Generira servis_abbott.json indeks iz imen PDF datotek v Servis_Abbott mapi,
  vkljucno s polnim izvlecenim besedilom vsakega porocila (pdftotext).
  Shrani v Hotmail OneDrive DigiLab za sync na tablico in Zebro.
.USAGE
  .\generate_servis_index.ps1          # doda/osvezi text le za spremenjene PDF-je
  .\generate_servis_index.ps1 -Force   # ponovno izvleci besedilo za vse
#>
param([switch]$Force)

$kclj  = "C:\Users\$env:USERNAME\OneDrive - Univerzitetni Klinicni center Ljubljana\DigiLab\Servis_Abbott"
$hotml = "C:\Users\$env:USERNAME\OneDrive\DigiLab"
$outPath = Join-Path $hotml "servis_abbott.json"

$pdfToTextExe = "C:\Program Files\Git\mingw64\bin\pdftotext.exe"
if (-not (Test-Path $pdfToTextExe)) {
    $cmd = Get-Command pdftotext -ErrorAction SilentlyContinue
    $pdfToTextExe = if ($cmd) { $cmd.Source } else { $null }
}
if (-not $pdfToTextExe) { Write-Error "pdftotext ni najden. Namesti Git for Windows."; exit 1 }

if (-not (Test-Path $kclj))  { Write-Error "Vir ni najden: $kclj"; exit 1 }
if (-not (Test-Path $hotml)) { New-Item -ItemType Directory -Force $hotml | Out-Null }

# Predhodni indeks — za ponovno rabo ze izvlecenega besedila (hitrost)
$prevById = @{}
if ((Test-Path $outPath) -and -not $Force) {
    try {
        $prev = Get-Content $outPath -Raw | ConvertFrom-Json
        foreach ($r in $prev.records) { if ($r.file) { $prevById[$r.file] = $r } }
    } catch {}
}

$pdfs = Get-ChildItem $kclj -Recurse -Filter "*.pdf" | Sort-Object Name
Write-Host "Najdenih $($pdfs.Count) servisnih porocil v $kclj"

function Extract-InstDesc($text) {
    # Razdeli na instrument + opis. Locilo: " - " ali "- " (z morebitnim presledkom)
    $parts = [regex]::Split($text, '\s*-+\s+', 2)
    if ($parts.Count -ge 2) {
        $cand = $parts[0].Trim()
        # Instrument: zacne z veliko/24h, max 30 znakov, max 3 besede
        $wordCnt = ($cand -split '\s+').Count
        if (($cand -cmatch '^[A-Z0-9]' -or $cand -match '^24h') -and $cand.Length -le 30 -and $wordCnt -le 3) {
            return @{ instrument=$cand; desc=$parts[1].Trim() }
        }
        # Kandidat predolg — celoten tekst = opis
        return @{ instrument=''; desc=$text }
    }
    # Ni locila: poskusi vodeci instrument (en token brez presledka, zacne z veliko)
    if ($text -cmatch '^(24h|[A-Z][a-zA-Z0-9]*(?:[-][A-Za-z0-9]+)*)\s+(.+)$') {
        return @{ instrument=$Matches[1]; desc=$Matches[2] }
    }
    if ($text -cmatch '^(24h|[A-Z][a-zA-Z0-9]*(?:[-][A-Za-z0-9]+)*)$') {
        return @{ instrument=$text; desc='' }
    }
    return @{ instrument=''; desc=$text }
}

function Parse-ServName($name) {
    $base = [System.IO.Path]::GetFileNameWithoutExtension($name)

    # Izvleci stevilko in leto: "NNN-YYYY ..."
    if ($base -notmatch '^(\d+)-(\d{4})\s+(.+)$') {
        return @{ id=$base; num=0; year=0; instrument=''; desc=$base; file=$name }
    }
    $num  = [int]$Matches[1]
    $year = [int]$Matches[2]
    $rest = $Matches[3].Trim()

    # Odstrani "UKC Lj. - " ali "UKC Ljubljana - " ali "UKC 24h " ali "UKC "
    $rest = $rest -replace '^UKC\s+(Lj\.|Ljubljana)\s*-\s*', ''
    $rest = $rest -replace '^UKC\s+(Lj\.|Ljubljana)\s+', ''
    $rest = $rest -replace '^UKC\s+', ''

    $id = Extract-InstDesc $rest
    return @{
        id         = "$($num.ToString('D3'))-$year"
        num        = $num
        year       = $year
        instrument = $id.instrument
        desc       = $id.desc
        file       = $name
    }
}

$records = @()
$noInst = 0
$extracted = 0
$reused = 0
$i = 0
foreach ($pdf in $pdfs) {
    $i++
    $r = Parse-ServName $pdf.Name

    # Besedilo: ponovno uporabi, ce datoteka ni bila spremenjena od zadnjega izvoza
    $prev = $prevById[$pdf.Name]
    $needExtract = $Force -or (-not $prev) -or (-not $prev.text) -or
                   (-not $prev.mtime) -or ($prev.mtime -ne $pdf.LastWriteTime.ToString('o'))
    if ($needExtract) {
        try {
            $txt = & $pdfToTextExe -enc UTF-8 -layout $pdf.FullName "-" 2>$null
            $text = ($txt -join "`n").Trim()
        } catch { $text = '' }
        $extracted++
    } else {
        $text = $prev.text
        $reused++
    }
    if (-not $r.instrument) { $noInst++ }

    $records += [ordered]@{
        id         = $r.id
        num        = $r.num
        year       = $r.year
        instrument = $r.instrument
        desc       = $r.desc
        file       = $r.file
        mtime      = $pdf.LastWriteTime.ToString('o')
        text       = $text
    }
    if ($i % 50 -eq 0) { Write-Host "  ... $i / $($pdfs.Count)" }
}

$obj = [ordered]@{
    generated = (Get-Date).ToString("yyyy-MM-ddTHH:mm:ss")
    records   = $records
}
$json = $obj | ConvertTo-Json -Depth 4 -Compress
[System.IO.File]::WriteAllText($outPath, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Shranjen indeks z $($records.Count) zapisi ($noInst brez prepoznane naprave, $extracted izvlecenih, $reused ponovno uporabljenih): $outPath"

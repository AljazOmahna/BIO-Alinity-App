<#
.SYNOPSIS
  Generira servis_abbott.json indeks iz imen PDF datotek v Servis_Abbott mapi.
  Shrani v Hotmail OneDrive DigiLab za sync na tablico in Zebro.
#>

$kclj  = "C:\Users\$env:USERNAME\OneDrive - Univerzitetni Klinicni center Ljubljana\DigiLab\Servis_Abbott"
$hotml = "C:\Users\$env:USERNAME\OneDrive\DigiLab"
$outPath = Join-Path $hotml "servis_abbott.json"

if (-not (Test-Path $kclj))  { Write-Error "Vir ni najden: $kclj"; exit 1 }
if (-not (Test-Path $hotml)) { New-Item -ItemType Directory -Force $hotml | Out-Null }

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
foreach ($pdf in $pdfs) {
    $r = Parse-ServName $pdf.Name
    if (-not $r.instrument) { $noInst++ }
    $records += [ordered]@{
        id         = $r.id
        num        = $r.num
        year       = $r.year
        instrument = $r.instrument
        desc       = $r.desc
        file       = $r.file
    }
}

$obj = [ordered]@{
    generated = (Get-Date).ToString("yyyy-MM-ddTHH:mm:ss")
    records   = $records
}
$json = $obj | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText($outPath, $json, [System.Text.Encoding]::UTF8)

Write-Host "Shranjen indeks z $($records.Count) zapisi ($noInst brez prepoznane naprave): $outPath"

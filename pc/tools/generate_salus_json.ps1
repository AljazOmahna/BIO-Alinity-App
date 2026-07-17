<#
.SYNOPSIS
  Generira .salus.json sidecar datoteke za vsak Salus dobavnico PDF.
  Bere iz KCLJ OneDrive Salus_Dobavnice, pise v Hotmail OneDrive DigiLab.

.USAGE
  cd pc\tools
  .\generate_salus_json.ps1
  .\generate_salus_json.ps1 -Force   # Prepiši obstoječe
#>
param([switch]$Force)

$pdfToText = "C:\Program Files\Git\mingw64\bin\pdftotext.exe"
if (-not (Test-Path $pdfToText)) {
    $cmd = Get-Command pdftotext -ErrorAction SilentlyContinue
    $pdfToText = if ($cmd) { $cmd.Source } else { $null }
    if (-not $pdfToText) { Write-Error "pdftotext ni najden. Namesti Git for Windows."; exit 1 }
}

$kclj  = "C:\Users\$env:USERNAME\OneDrive - Univerzitetni Klinicni center Ljubljana\DigiLab\Salus_Dobavnice"
$hotml = "C:\Users\$env:USERNAME\OneDrive\DigiLab\Salus_Dobavnice"

if (-not (Test-Path $kclj))  { Write-Error "Vir ni najden: $kclj";  exit 1 }
if (-not (Test-Path $hotml)) { New-Item -ItemType Directory -Force $hotml | Out-Null }

$pdfs = Get-ChildItem $kclj -Filter "*.pdf" | Sort-Object Name
Write-Host "Najdenih $($pdfs.Count) PDF datotek v $kclj"

$ok = 0; $skip = 0; $err = 0
foreach ($pdf in $pdfs) {
    $outName = $pdf.BaseName + ".salus.json"
    $outPath = Join-Path $hotml $outName

    if ((Test-Path $outPath) -and -not $Force) { $skip++; continue }

    try {
        $txt = & $pdfToText -enc UTF-8 $pdf.FullName "-" 2>$null
        $pages = @($txt -join "`n")
        $obj = [ordered]@{
            supplier  = "Salus"
            file      = $pdf.Name
            fetchedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:ss")
            pages     = $pages
        }
        $json = $obj | ConvertTo-Json -Compress -Depth 3
        [System.IO.File]::WriteAllText($outPath, $json, [System.Text.Encoding]::UTF8)
        $ok++
        Write-Host "  OK  $($pdf.Name)"
    } catch {
        $err++
        Write-Warning "  ERR $($pdf.Name): $_"
    }
}

Write-Host ""
Write-Host "Konec: $ok ustvarjenih, $skip preskočenih (že obstajajo), $err napak."
Write-Host "Datoteke so v: $hotml"

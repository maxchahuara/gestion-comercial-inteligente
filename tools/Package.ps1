param([string]$Version='0.4.2')
$ErrorActionPreference='Stop'
if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { throw 'Versión inválida.' }
$projectRoot=Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    $apk=Join-Path $projectRoot 'app/build/outputs/apk/release/app-release.apk'
    if (-not (Test-Path -LiteralPath $apk)) { throw 'Primero genera un APK release firmado.' }
    $distribution=Join-Path $projectRoot 'dist'
    New-Item -ItemType Directory -Path $distribution -Force | Out-Null
    $artifactName='Gestión Comercial Inteligente - {ℂℎ}'
    $apkTarget=Join-Path $distribution ($artifactName+'-'+$Version+'.apk')
    Copy-Item -LiteralPath $apk -Destination $apkTarget
    $sourceFiles=@(& git -c core.quotepath=false ls-files --cached)
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo inventariar el código fuente.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $sourceTarget=Join-Path $distribution ($artifactName+'-'+$Version+'-fuentes.zip')
    if (Test-Path -LiteralPath $sourceTarget) { Remove-Item -LiteralPath $sourceTarget }
    $archive=[IO.Compression.ZipFile]::Open($sourceTarget,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($relative in $sourceFiles) {
            if ($relative -match '(^|/)(\.git|\.gradle|\.kotlin|build|dist)/|(^|/)(local\.properties|signing\.properties|\.env)$|\.(p12|jks|keystore|log)$') { continue }
            $absolute=[IO.Path]::GetFullPath((Join-Path $projectRoot $relative))
            if (-not $absolute.StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Archivo fuera del proyecto.' }
            if (Test-Path -LiteralPath $absolute -PathType Leaf) {
                [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$absolute,$relative.Replace('\','/'),[IO.Compression.CompressionLevel]::Optimal) | Out-Null
            }
        }
    } finally { $archive.Dispose() }
    $inventory=Join-Path $projectRoot 'app/build/reports/dependencies/runtime.json'
    $outputs=@($apkTarget,$sourceTarget)
    if (Test-Path -LiteralPath $inventory) {
        $inventoryTarget=Join-Path $distribution ('dependencias-'+$Version+'.json')
        Copy-Item -LiteralPath $inventory -Destination $inventoryTarget
        $outputs+=$inventoryTarget
    }
    $checksums=$outputs | ForEach-Object { (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()+'  '+[IO.Path]::GetFileName($_) }
    [IO.File]::WriteAllLines((Join-Path $distribution 'SHA256SUMS.txt'),$checksums,[Text.UTF8Encoding]::new($false))
    Write-Output ('Distribución preparada: '+$distribution)
} finally { Pop-Location }

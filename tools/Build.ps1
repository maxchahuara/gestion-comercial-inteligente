param([switch]$Release)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$buildDrive = $null
Push-Location $projectRoot
try {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Se necesita OpenJDK 17 para compilar.' }
    if (-not $env:ANDROID_HOME -and -not (Test-Path -LiteralPath 'local.properties')) { throw 'Configura ANDROID_HOME con el SDK existente.' }
    $tasks = @(':app:assembleDebug',':app:testDebugUnitTest',':app:lintDebug',':app:runtimeInventory')
    if ($Release) { $tasks += ':app:assembleRelease' }

    # Java 17 can lose Unicode in the classpath of Gradle's Windows test worker.
    if ($env:OS -eq 'Windows_NT' -and $projectRoot -match '[^\x00-\x7F]') {
        $drive = 90..68 | ForEach-Object { [string][char]$_ } |
            Where-Object { -not (Get-PSDrive -Name $_ -ErrorAction SilentlyContinue) } |
            Select-Object -First 1
        if (-not $drive) { throw 'Se necesita una letra de unidad libre para compilar desde esta ruta.' }
        & subst.exe "${drive}:" .
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear la unidad temporal para Gradle.' }
        $buildDrive = "${drive}:"
        Set-Location -LiteralPath ($buildDrive + '\')
    }
    & '.\gradlew.bat' @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'La compilación o las verificaciones fallaron.' }
} finally {
    Pop-Location
    if ($buildDrive) { & subst.exe $buildDrive /D }
}

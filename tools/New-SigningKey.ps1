$ErrorActionPreference = 'Stop'
$signingRoot = Join-Path $env:USERPROFILE '.campana'
$propertiesFile = Join-Path $signingRoot 'signing.properties'
if (Test-Path -LiteralPath $propertiesFile) {
    Write-Output 'Ya existe una identidad de firma local. Se reutilizará.'
    exit 0
}
$keytool = Get-Command keytool -ErrorAction Stop
New-Item -ItemType Directory -Path $signingRoot -Force | Out-Null
$keyFile = Join-Path $signingRoot 'release.p12'
if (Test-Path -LiteralPath $keyFile) { throw 'Existe un almacén sin configuración. Revísalo antes de crear otro.' }
$randomBytes = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
$env:CAMPANA_KEY_PASSWORD = [Convert]::ToBase64String($randomBytes)
try {
    $keyArguments = @('-genkeypair','-keystore',$keyFile,'-storetype','PKCS12','-storepass:env','CAMPANA_KEY_PASSWORD','-keypass:env','CAMPANA_KEY_PASSWORD','-alias','campana','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Campana Open Source,OU=Android,O=Campana,C=PE','-noprompt')
    & $keytool.Source @keyArguments
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear la identidad de firma.' }
    $portableKeyPath = $keyFile.Replace('\','/')
    $values = @(
        ('storeFile=' + $portableKeyPath)
        ('storePassword=' + $env:CAMPANA_KEY_PASSWORD)
        'keyAlias=campana'
        ('keyPassword=' + $env:CAMPANA_KEY_PASSWORD)
    )
    [IO.File]::WriteAllLines($propertiesFile,$values,[Text.UTF8Encoding]::new($false))
    Write-Output 'Identidad de firma guardada en el perfil local, fuera del repositorio. Conserva una copia privada para futuras actualizaciones.'
} finally {
    Remove-Item Env:\CAMPANA_KEY_PASSWORD -ErrorAction SilentlyContinue
    [Array]::Clear($randomBytes,0,$randomBytes.Length)
}

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$appName = 'ASCENDERSMC-UNIVERSAL-INSTALLER'
$buildDir = Join-Path $PSScriptRoot '.build'
$releaseDir = Join-Path $PSScriptRoot 'release'

if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    $jar = Join-Path $env:JAVA_HOME 'bin\jar.exe'
} else {
    $javac = 'javac'
    $jar = 'jar'
}

Remove-Item $buildDir -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $releaseDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path (Join-Path $buildDir 'classes') -Force | Out-Null
New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null

$sources = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' -File
if (-not $sources) { throw 'No se encontraron fuentes Java en src.' }

$sourceFile = Join-Path $buildDir 'sources.txt'
$sources | ForEach-Object { '"' + ($_.FullName -replace '\\','/') + '"' } | Set-Content -LiteralPath $sourceFile -Encoding ASCII

Write-Host '[BUILD] Compilando con Java 21...'
& $javac '--release' '21' '-encoding' 'UTF-8' '-d' (Join-Path $buildDir 'classes') "@$sourceFile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Copy-Item 'installer.properties' (Join-Path $buildDir 'classes\installer.properties') -Force
if (Test-Path 'assets') { Copy-Item 'assets' (Join-Path $buildDir 'classes\assets') -Recurse -Force }

$jarPath = Join-Path $releaseDir "$appName.jar"
Write-Host '[BUILD] Empaquetando JAR...'
& $jar '--create' '--file' $jarPath '--main-class' 'com.ascendersmc.installer.App' '-C' (Join-Path $buildDir 'classes') '.'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jarPath).Hash.ToLowerInvariant()
"$hash  $appName.jar" | Set-Content -LiteralPath (Join-Path $releaseDir 'SHA256SUMS.txt') -Encoding ASCII
Remove-Item $buildDir -Recurse -Force
Write-Host "[OK] JAR: release\$appName.jar"

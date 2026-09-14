$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$appName = 'ASCENDERSMC-UNIVERSAL-INSTALLER'
$buildDir = Join-Path $PSScriptRoot '.build'
$releaseDir = Join-Path $PSScriptRoot 'release'
$classesDir = Join-Path $buildDir 'classes'
$bootstrap = Join-Path $PSScriptRoot 'src\com\ascendersmc\installer\Bootstrap.java'

if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    $jar = Join-Path $env:JAVA_HOME 'bin\jar.exe'
} else {
    $javac = 'javac'
    $jar = 'jar'
}

Remove-Item $buildDir -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $releaseDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null

if (-not (Test-Path $bootstrap)) { throw 'No se encontró Bootstrap.java' }

Write-Host '[BUILD] Compilando bootstrap compatible con Java 8...'
& $javac '--release' '8' '-encoding' 'UTF-8' '-d' $classesDir $bootstrap
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$sources = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' -File |
    Where-Object { $_.FullName -ne $bootstrap }
if (-not $sources) { throw 'No se encontraron fuentes Java de la aplicación en src.' }

$sourceFile = Join-Path $buildDir 'sources.txt'
$sources | ForEach-Object { '"' + ($_.FullName -replace '\\','/') + '"' } | Set-Content -LiteralPath $sourceFile -Encoding ASCII

Write-Host '[BUILD] Compilando aplicación con Java 21...'
& $javac '--release' '21' '-encoding' 'UTF-8' '-cp' $classesDir '-d' $classesDir "@$sourceFile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Copy-Item 'installer.properties' (Join-Path $classesDir 'installer.properties') -Force
if (Test-Path 'assets') { Copy-Item 'assets' (Join-Path $classesDir 'assets') -Recurse -Force }

$jarPath = Join-Path $releaseDir "$appName.jar"
Write-Host '[BUILD] Empaquetando JAR...'
& $jar '--create' '--file' $jarPath '--main-class' 'com.ascendersmc.installer.Bootstrap' '-C' $classesDir '.'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jarPath).Hash.ToLowerInvariant()
"$hash  $appName.jar" | Set-Content -LiteralPath (Join-Path $releaseDir 'SHA256SUMS.txt') -Encoding ASCII
Remove-Item $buildDir -Recurse -Force
Write-Host "[OK] JAR: release\$appName.jar"

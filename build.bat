@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "APP_NAME=ASCENDERSMC-UNIVERSAL-INSTALLER"
set "BUILD_DIR=.build"
set "RELEASE_DIR=release"
set "SOURCES_FILE=%BUILD_DIR%\sources.txt"

rem ============================================================
rem Java 21: prefer JAVA_HOME, otherwise use PATH.
rem ============================================================
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" (
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "JAR=%JAVA_HOME%\bin\jar.exe"
)

if not defined JAVAC (
    where javac >nul 2>nul || (
        echo [ERROR] No se encontro javac. Configura JAVA_HOME con un JDK 21 o agregalo al PATH.
        exit /b 1
    )
    where jar >nul 2>nul || (
        echo [ERROR] No se encontro jar. Configura JAVA_HOME con un JDK 21 o agregalo al PATH.
        exit /b 1
    )
    set "JAVAC=javac"
    set "JAR=jar"
)

rem ============================================================
rem Clean build. All distributable output goes to release\
rem ============================================================
if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
if exist "out" rmdir /s /q "out"
if exist "%RELEASE_DIR%" rmdir /s /q "%RELEASE_DIR%"
mkdir "%BUILD_DIR%\classes" || exit /b 1
mkdir "%RELEASE_DIR%" || exit /b 1

rem ============================================================
rem Generate javac argfile without PowerShell. Every path is quoted
rem and converted to forward slashes, so paths containing spaces work.
rem ============================================================
>"%SOURCES_FILE%" (
    for /r "src" %%F in (*.java) do (
        set "SRC=%%~fF"
        set "SRC=!SRC:\=/!"
        echo "!SRC!"
    )
)

for %%A in ("%SOURCES_FILE%") do if %%~zA EQU 0 (
    echo [ERROR] No se encontraron archivos .java dentro de src\
    rmdir /s /q "%BUILD_DIR%" 2>nul
    exit /b 1
)

echo [BUILD] Compilando con Java 21...
"%JAVAC%" --release 21 -encoding UTF-8 -d "%BUILD_DIR%\classes" @"%SOURCES_FILE%"
if errorlevel 1 (
    echo [ERROR] La compilacion fallo.
    exit /b 1
)

copy /y "installer.properties" "%BUILD_DIR%\classes\installer.properties" >nul
if exist "assets" xcopy /e /i /y "assets" "%BUILD_DIR%\classes\assets" >nul

echo [BUILD] Empaquetando JAR...
"%JAR%" --create --file "%RELEASE_DIR%\%APP_NAME%.jar" --main-class com.ascendersmc.installer.App -C "%BUILD_DIR%\classes" .
if errorlevel 1 (
    echo [ERROR] No se pudo crear el JAR.
    exit /b 1
)

rem Generate SHA-256. This PowerShell command is a single line and does
rem not use CMD line-continuation characters.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$f=Get-FileHash -Algorithm SHA256 -LiteralPath '%RELEASE_DIR%\%APP_NAME%.jar'; ($f.Hash.ToLower() + '  %APP_NAME%.jar') | Set-Content -LiteralPath '%RELEASE_DIR%\SHA256SUMS.txt' -Encoding ASCII"
if errorlevel 1 echo [WARN] El JAR se creo, pero no se pudo generar SHA256SUMS.txt.

rmdir /s /q "%BUILD_DIR%" 2>nul

echo.
echo [OK] Compilacion completada.
echo [OK] JAR: %RELEASE_DIR%\%APP_NAME%.jar
if exist "%RELEASE_DIR%\SHA256SUMS.txt" echo [OK] SHA-256: %RELEASE_DIR%\SHA256SUMS.txt
exit /b 0

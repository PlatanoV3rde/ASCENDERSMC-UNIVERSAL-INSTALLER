@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "JAR_PATH=release\ASCENDERSMC-UNIVERSAL-INSTALLER.jar"

if not exist "%JAR_PATH%" call build.bat
if errorlevel 1 exit /b 1

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    "%JAVA_HOME%\bin\java.exe" -jar "%JAR_PATH%"
) else (
    java -jar "%JAR_PATH%"
)

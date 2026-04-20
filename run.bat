@echo off
setlocal enabledelayedexpansion

set "ROOT=%~dp0"
cd /d "%ROOT%"

if not exist out mkdir out

set "JAVA_SOURCES="
for /r src %%f in (*.java) do (
  set "JAVA_SOURCES=!JAVA_SOURCES! "%%f""
)

if "%JAVA_SOURCES%"=="" (
  echo No Java sources found under src
  exit /b 1
)

javac -d out %JAVA_SOURCES%
if errorlevel 1 exit /b 1

java -cp out app.App

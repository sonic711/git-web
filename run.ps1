$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if (-not (Test-Path "out")) {
    New-Item -ItemType Directory -Path "out" | Out-Null
}

$sources = Get-ChildItem -Path "src" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
if (-not $sources -or $sources.Count -eq 0) {
    throw "No Java sources found under src"
}

javac -d out $sources
java -cp out app.App

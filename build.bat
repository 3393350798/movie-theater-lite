@echo off
cd /d "%~dp0"
if exist out rmdir /s /q out
if exist sources.txt del sources.txt
mkdir out
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-ChildItem -LiteralPath 'src' -Recurse -Filter '*.java' | ForEach-Object { $_.FullName.Substring((Resolve-Path 'src').Path.Length + 1) } | Set-Content -LiteralPath 'sources.txt' -Encoding ASCII"
pushd src
javac -encoding UTF-8 -d ..\out @..\sources.txt
if errorlevel 1 (
  popd
  del sources.txt
  exit /b 1
)
popd
del sources.txt
echo Build completed.

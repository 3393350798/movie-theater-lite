@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist out (
  call build.bat
)
java "-Dfile.encoding=UTF-8" -cp "out;..\lib\mysql-connector-j-9.4.0.jar" movietheater.server.MovieTheaterServer
pause

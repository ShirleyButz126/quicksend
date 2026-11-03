@echo off
setlocal EnableExtensions
set PY=python
where %PY% >nul 2>nul || set PY="C:\Users\Administrator\software\python38\python.exe"
%PY% "%~dp0quicksend\app.py"
endlocal

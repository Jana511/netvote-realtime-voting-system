@echo off
REM NetVote DESKTOP CLIENT (Windows). Start the server first (run.bat), then double-click this - as many times as you like.
cd /d "%~dp0"
where javac >nul 2>nul
if errorlevel 1 (
  echo Java JDK was not found. Install JDK 11 or newer and try again.
  pause
  exit /b 1
)
if not exist out\client\ClientGUI.class (
  if not exist out mkdir out
  echo Compiling...
  javac -encoding UTF-8 -d out src\common\*.java src\backend\*.java src\network\*.java src\client\*.java
  if errorlevel 1 (
    pause
    exit /b 1
  )
)
start "NetVote client" javaw -cp out client.ClientGUI

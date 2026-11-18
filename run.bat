@echo off
REM NetVote SERVER - compile and start (Windows). Double-click, or:  run.bat [httpPort] [adminPassword] [tcpPort]
cd /d "%~dp0"
where javac >nul 2>nul
if errorlevel 1 (
  echo Java JDK was not found. Install JDK 11 or newer ^(for example Eclipse Temurin 21^) and try again.
  pause
  exit /b 1
)
if not exist out mkdir out
echo Compiling...
javac -encoding UTF-8 -d out src\common\*.java src\backend\*.java src\network\*.java src\client\*.java
if errorlevel 1 (
  echo.
  echo Compilation failed - see the errors above.
  pause
  exit /b 1
)
set PORT=8080
if not "%~1"=="" set PORT=%~1
echo Starting the NetVote server. Your browser will open http://localhost:%PORT%
start "" cmd /c "timeout /t 2 >nul & start http://localhost:%PORT%"
java -cp out backend.Main %*
pause

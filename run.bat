@echo off
REM NetVote - compile and start the backend (Windows). Double-click this file.
cd /d "%~dp0"
where javac >nul 2>nul
if errorlevel 1 (
  echo Java JDK was not found. Install JDK 11 or newer ^(for example Eclipse Temurin 21^) and try again.
  pause
  exit /b 1
)
if not exist out mkdir out
echo Compiling...
javac -encoding UTF-8 -d out src\backend\*.java
if errorlevel 1 (
  echo.
  echo Compilation failed - see the errors above.
  pause
  exit /b 1
)
echo Starting NetVote. Your browser will open http://localhost:8080
start "" cmd /c "timeout /t 2 >nul & start http://localhost:8080"
java -cp out backend.Main %*
pause

@echo off
setlocal
set GRADLE_VERSION=9.6.0
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
set CACHE_ROOT=%USERPROFILE%\.gradle\floortrace-bootstrap
set DIST_DIR=%CACHE_ROOT%\gradle-%GRADLE_VERSION%
set ZIP=%CACHE_ROOT%\gradle-%GRADLE_VERSION%-bin.zip
if not exist "%DIST_DIR%\bin\gradle.bat" (
  if not exist "%CACHE_ROOT%" mkdir "%CACHE_ROOT%"
  if not exist "%ZIP%" powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%'"
  if errorlevel 1 exit /b 2
  powershell -NoProfile -ExecutionPolicy Bypass -Command "if ((Get-FileHash -Algorithm SHA256 '%ZIP%').Hash -ne 'bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01') { throw 'Gradle checksum mismatch' }"
  if errorlevel 1 exit /b 2
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP%' '%CACHE_ROOT%'"
  if errorlevel 1 exit /b 2
)
call "%DIST_DIR%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%

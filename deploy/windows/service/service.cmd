@echo off
rem Windows service mode (optional): service.cmd install | uninstall | start | stop | status
rem Needs an administrator command prompt for everything but status. Runs WinSW (toolkit.exe) with toolkit.xml.
setlocal
set "SVC=%~dp0toolkit.exe"
set "CMD=%~1"
if "%CMD%"=="" goto usage
if /i "%CMD%"=="status" goto run
if /i "%CMD%"=="install" goto admin
if /i "%CMD%"=="uninstall" goto admin
if /i "%CMD%"=="start" goto admin
if /i "%CMD%"=="stop" goto admin
goto usage

:admin
net session >nul 2>&1
if errorlevel 1 (
  echo service.cmd %CMD%: needs an administrator command prompt ^(right-click Command Prompt, Run as administrator^).
  exit /b 5
)
if /i "%CMD%"=="install" goto install
if /i "%CMD%"=="uninstall" goto uninstall
if /i "%CMD%"=="stop" (
  "%SVC%" stopwait
  exit /b %ERRORLEVEL%
)
goto run

:install
if not exist "%~dp0..\data\pg\PG_VERSION" (
  echo service.cmd install: run toolkit.cmd setup first.
  exit /b 2
)
if exist "%~dp0..\data\toolkit.pid" (
  echo service.cmd install: the toolkit may be running from start.cmd; run stop.cmd first.
)
"%SVC%" install
if errorlevel 1 exit /b %ERRORLEVEL%
echo Installed the service "toolkit" (starts automatically with Windows). Start it now with: service.cmd start
exit /b 0

:uninstall
"%SVC%" stopwait >nul 2>&1
"%SVC%" uninstall
exit /b %ERRORLEVEL%

:run
"%SVC%" %CMD%
exit /b %ERRORLEVEL%

:usage
echo usage: service.cmd install ^| uninstall ^| start ^| stop ^| status
echo   install    register the Windows service "toolkit" (automatic start); run toolkit.cmd setup first
echo   start      start it (the database, then the server on https://%COMPUTERNAME%:8443/)
echo   stop       stop it and wait until the server and the database have stopped
echo   status     NonExistent, Stopped or Started
echo   uninstall  stop and remove the service; the install folder and its data stay
echo All but status need an administrator command prompt.
exit /b 2

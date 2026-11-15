@echo off
rem The toolkit from this folder. TOOLKIT_HOME gets the folder plus "." because a backslash before a closing quote would escape the quote.
setlocal
"%~dp0runtime\jre\bin\java.exe" -DTOOLKIT_HOME="%~dp0." -jar "%~dp0lib\toolkit.jar" stop %*
exit /b %ERRORLEVEL%

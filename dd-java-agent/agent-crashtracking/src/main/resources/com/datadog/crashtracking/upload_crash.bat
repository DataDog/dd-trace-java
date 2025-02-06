@echo off
setlocal enabledelayedexpansion

REM ========================================================
REM Function: ensureJava
REM Usage: call :ensureJava path\to\hs_err_file.txt
REM If 'java' is not found in PATH, extract JAVA_HOME from the
REM hs_err file and update PATH accordingly.
REM ========================================================
:ensureJava
    REM Check if java is available
    where java >nul 2>&1
    if %ERRORLEVEL%==0 (
        REM Java found; nothing to do.
        goto :EOF
    )

    REM Java not found; try to extract JAVA_HOME from the hs_err file passed as parameter.
    if "%~1"=="" (
        echo Error: No hs_err file provided.
        exit /b 1
    )

    REM Use findstr to locate the line with JAVA_HOME.
    for /f "tokens=2 delims==" %%A in ('findstr "JAVA_HOME" "%~1"') do (
        set "JAVA_HOME=%%A"
    )

    REM Check if JAVA_HOME was found
    if not defined JAVA_HOME (
        echo Error: Java executable not found. Cannot upload error file.
        exit /b 1
    )

    REM Optionally, remove any surrounding quotes or spaces:
    set "JAVA_HOME=%JAVA_HOME:"=%"
    for /f "tokens=* delims= " %%A in ("%JAVA_HOME%") do set "JAVA_HOME=%%A"

    REM Prepend JAVA_HOME\bin to PATH
    set "PATH=%JAVA_HOME%\bin;%PATH%"
    goto :EOF


:: Check if PID is provided
if "%1"=="" (
    echo "Error: No PID provided. Running in legacy mode."
    call :ensureJava "!JAVA_ERROR_FILE!"
    java -jar "!AGENT_JAR!" uploadCrash "!JAVA_ERROR_FILE!"
    if %ERRORLEVEL% EQU 0 (
        echo "Uploaded error file \"!JAVA_ERROR_FILE!\""
    ) else (
        echo "Error: Failed to upload error file \"!JAVA_ERROR_FILE!\""
        exit /b %ERRORLEVEL%
    )
    exit /b 0
)
set PID=%1

:: Get the directory of the script
set scriptDir=%~dp0
:: Get the base name of the script
set scriptName=%~n0
set configFile=%scriptDir%%scriptName%_pid%PID%.cfg

:: Check if the configuration file exists
if not exist "%configFile%" (
    echo Error: Configuration file "%configFile%" not found
    exit /b 1
)

:: Read the configuration file
:: The expected contents are
:: - agent: Path to the dd-java-agent.jar
:: - hs_err: Path to the hs_err log file
for /f "tokens=1,2 delims=: " %%a in (%configFile%.cfg) do (
    set %%a=%%b
)

:: Debug: Print the loaded values (Optional)
echo Agent Jar: %agent%
echo Error Log: %hs_err%
echo JAVA_HOME: %java_home%
echo PID: %PID%

:: Execute the Java command with the loaded values
%java_home%\bin\java -jar "%agent%" uploadCrash "%hs_err%"
set RC=%ERRORLEVEL%
del "%configFile%" :: Clean up the configuration file
if %RC% EQU 0 (
    echo "Error file %hs_err% was uploaded successfully"
) else (
    echo "Error: Failed to upload error file %hs_err%"
    exit /b %RC%
)

@echo off
setlocal enabledelayedexpansion

:: Check if PID is provided
if "%1"=="" (
    echo "Error: No PID provided. Running in legacy mode."
    call :ensureJava "!JAVA_ERROR_FILE!"
    "!JAVA_HOME!\bin\java" -jar "!AGENT_JAR!" uploadCrash "!JAVA_ERROR_FILE!"
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
echo Config file: %configFile%
echo Agent Jar: %agent%
echo Error Log: %hs_err%
echo JAVA_HOME: %java_home%
echo PID: %PID%

:: Execute the Java command with the loaded values
"%java_home%\bin\java" -jar "%agent%" uploadCrash -c "%configFile%" %hs_err%"
set RC=%ERRORLEVEL%
del "%configFile%" :: Clean up the configuration file
if %RC% EQU 0 (
    echo "Error file %hs_err% was uploaded successfully"
) else (
    echo "Error: Failed to upload error file %hs_err%"
    exit /b %RC%
)

@echo off
setlocal enabledelayedexpansion

:: Check if PID is provided
if "%1"=="" (
    echo "Error: No PID provided"
    exit /b 1
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
:: - tags: Comma-separated list of tags to be sent with the OOME event; key:value pairs are supported
for /f "tokens=1,2 delims=: " %%a in (%configFile%.cfg) do (
    set %%a=%%b
)

:: Debug: Print the loaded values (Optional)
echo Agent Jar: %agent%
echo Tags: %tags%
echo JAVA_HOME: %java_home%
echo PID: %PID%

:: Execute the Java command with the loaded values
"%java_home%\bin\java" -Ddd.dogstatsd.start-delay=0 -jar "%agent%" sendOomeEvent "%tags%"
set RC=%ERRORLEVEL%
del "%configFile%" :: Clean up the configuration file
if %RC% EQU 0 (
    echo "OOME Event generated successfully"
) else (
    echo "Error: Failed to generate OOME event"
    exit /b %RC%
)

@echo off
setlocal enabledelayedexpansion

:: Check if PID is provided
if "%1"=="" (
    echo "Error: No PID provided. Running in legacy mode."
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

:: Initialize config values
set config_agent=
set config_hs_err=
set config_java_home=
set config_javacore_path=

:: Read the configuration file
:: The expected contents are
:: - agent: Path to the dd-java-agent.jar
:: - hs_err: Path to the hs_err log file (optional for J9)
:: - java_home: Path to Java installation
:: - javacore_path: Path/pattern for J9 javacore files (optional)
for /f "tokens=1,* delims==" %%a in (%configFile%) do (
    if "%%a"=="agent" set config_agent=%%b
    if "%%a"=="hs_err" set config_hs_err=%%b
    if "%%a"=="java_home" set config_java_home=%%b
    if "%%a"=="javacore_path" set config_javacore_path=%%b
)

:: Exiting early if agent or java_home is missing
if not defined config_agent (
    echo Error: Missing configuration - agent
    exit /b 1
)
if not defined config_java_home (
    echo Error: Missing configuration - java_home
    exit /b 1
)

:: Find crash file - support both HotSpot (hs_err) and J9 (javacore) formats
set crash_file=

:: Check HotSpot error file first
if defined config_hs_err (
    if exist "%config_hs_err%" (
        set crash_file=%config_hs_err%
    )
)

:: Check default HotSpot error file location
if not defined crash_file (
    if exist "hs_err_pid%PID%.log" (
        set crash_file=hs_err_pid%PID%.log
    )
)

:: Look for J9 javacore files if no HotSpot error file found
if not defined crash_file (
    :: Check custom javacore path if configured
    if defined config_javacore_path (
        :: Check if it's an existing directory
        if exist "%config_javacore_path%\*" (
            :: Search for javacore files in the directory - strict pattern with dots around PID
            for /f "delims=" %%f in ('dir /b /o-d "%config_javacore_path%\javacore.*.%PID%.*.txt" 2^>nul') do (
                if not defined crash_file set crash_file=%config_javacore_path%\%%f
            )
        ) else if exist "%config_javacore_path%" (
            :: It's an existing file - use it directly
            set crash_file=%config_javacore_path%
        ) else (
            :: Try pattern substitution with %%pid (J9 uses %pid token)
            set "substituted_path=!config_javacore_path:%%pid=%PID%!"
            if exist "!substituted_path!" (
                set crash_file=!substituted_path!
            ) else (
                :: Try the directory containing the pattern
                for %%d in ("!config_javacore_path!") do set pattern_dir=%%~dpd
                if exist "!pattern_dir!" (
                    for /f "delims=" %%f in ('dir /b /o-d "!pattern_dir!javacore.*.%PID%.*.txt" 2^>nul') do (
                        if not defined crash_file set crash_file=!pattern_dir!%%f
                    )
                )
            )
        )
    )

    :: Fallback: search in current directory if no custom path or file not found
    if not defined crash_file (
        for /f "delims=" %%f in ('dir /b /o-d javacore.*.%PID%.*.txt 2^>nul') do (
            if not defined crash_file set crash_file=%%f
        )
    )
)

if not defined crash_file (
    echo Error: No crash file found for PID %PID%
    if defined config_javacore_path (
        echo   Searched custom path: %config_javacore_path%
    )
    exit /b 1
)

:: Use the found crash file
set config_hs_err=%crash_file%

:: Debug: Print the loaded values (Optional)
echo Agent Jar: %config_agent%
echo Error Log: %config_hs_err%
echo JAVA_HOME: %config_java_home%
echo PID: %PID%

:: Execute the Java command with the loaded values
"%config_java_home%\bin\java" -jar "%config_agent%" uploadCrash -c "%configFile%" "%config_hs_err%"
set RC=%ERRORLEVEL%

:: Clean up the configuration file
del "%configFile%"

if %RC% EQU 0 (
    echo Error file %config_hs_err% was uploaded successfully
) else (
    echo Error: Failed to upload error file %config_hs_err%
    exit /b %RC%
)

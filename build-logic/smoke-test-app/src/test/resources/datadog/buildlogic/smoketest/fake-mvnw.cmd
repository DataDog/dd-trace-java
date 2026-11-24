@echo off
setlocal enabledelayedexpansion

set "target="
:parse
if "%~1"=="" goto run
set "arg=%~1"
if "!arg:~0,13!"=="-Dtarget.dir=" set "target=!arg:~13!"
shift
goto parse

:run
if "%target%"=="" exit /b 2
mkdir "%target%" 2>nul
echo jar>"%target%\sample.jar"
(
  echo MAVEN_OPTS=%MAVEN_OPTS%
  echo MVNW_REPOURL=%MVNW_REPOURL%
) > "%target%\maven-env.txt"

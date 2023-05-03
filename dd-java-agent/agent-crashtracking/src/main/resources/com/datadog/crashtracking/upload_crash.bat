@echo off

java -jar "!AGENT_JAR!" uploadCrash "!JAVA_ERROR_FILE!"
if %ERRORLEVEL% EQU 0 echo "Uploaded error file \"!JAVA_ERROR_FILE!\""

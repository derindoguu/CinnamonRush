@echo off
rem Builds and starts Cinnamon Rush, then open http://localhost:8080
cd /d "%~dp0"
if not exist out mkdir out
javac -d out src\bakery\*.java || exit /b 1
java -cp out bakery.Main 8080 web

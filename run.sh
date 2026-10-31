#!/bin/sh
# Builds and starts Cinnamon Rush, then open http://localhost:8080
cd "$(dirname "$0")" || exit 1
mkdir -p out
javac -d out src/bakery/*.java || exit 1
exec java -cp out bakery.Main 8080 web

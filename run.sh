#!/bin/sh
# NetVote - compile and start the backend (macOS / Linux):  ./run.sh   [port] [adminPassword]
cd "$(dirname "$0")" || exit 1
if ! command -v javac >/dev/null 2>&1; then
  echo "Java JDK was not found. Install JDK 11 or newer and try again."
  exit 1
fi
mkdir -p out
echo "Compiling..."
javac -encoding UTF-8 -d out src/backend/*.java || exit 1
echo "Starting NetVote..."
exec java -cp out backend.Main "$@"

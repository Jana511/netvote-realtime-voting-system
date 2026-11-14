#!/bin/sh
# NetVote SERVER (macOS / Linux):  ./run.sh   [httpPort] [adminPassword] [tcpPort]
cd "$(dirname "$0")" || exit 1
if ! command -v javac >/dev/null 2>&1; then
  echo "Java JDK was not found. Install JDK 11 or newer and try again."
  exit 1
fi
mkdir -p out
echo "Compiling..."
javac -encoding UTF-8 -d out src/common/*.java src/backend/*.java src/network/*.java src/client/*.java || exit 1
echo "Starting the NetVote server..."
exec java -cp out backend.Main "$@"

#!/bin/sh
# NetVote DESKTOP CLIENT (macOS / Linux). Start the server first (./run.sh).
cd "$(dirname "$0")" || exit 1
if [ ! -f out/client/ClientGUI.class ]; then
  mkdir -p out
  javac -encoding UTF-8 -d out src/common/*.java src/backend/*.java src/network/*.java src/client/*.java || exit 1
fi
exec java -cp out client.ClientGUI

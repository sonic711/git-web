#!/bin/zsh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

PORT="${PORT:-8080}"

mkdir -p out
javac -d out $(find src -name '*.java' | sort)
java -cp out app.App "$PORT"

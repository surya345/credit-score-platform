#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
docker compose up --build -d
printf '\nOpen http://localhost:4200 after startup. Logs: docker compose logs -f\n'

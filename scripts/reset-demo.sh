#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="${SCRIPT_DIR}/reset-demo.sql"

if [ "$(docker inspect -f '{{.State.Running}}' fortivance-mysql 2>/dev/null)" != "true" ]; then
    echo "Demo reset FAILED: container fortivance-mysql is not running. Start it with docker compose up -d" >&2
    exit 1
fi

echo "Resetting Fortivance demo state in fortivance-mysql container..."

docker exec -i fortivance-mysql sh -c 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' < "${SQL_FILE}"

echo "Demo reset complete."

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="${SCRIPT_DIR}/reset-demo.sql"

echo "Resetting Fortivance demo state in fortivance-mysql container..."

docker exec -i fortivance-mysql sh -c 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' < "${SQL_FILE}"

echo "Demo reset complete."

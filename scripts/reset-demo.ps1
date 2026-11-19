# Reset Fortivance demo state in Docker container
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sqlFile = Join-Path $scriptDir "reset-demo.sql"

Write-Host "Resetting Fortivance demo state in fortivance-mysql container..."

Get-Content $sqlFile -Raw | docker exec -i fortivance-mysql sh -c 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"'

Write-Host "Demo reset complete."

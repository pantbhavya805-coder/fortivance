# Reset Fortivance demo state in Docker container
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sqlFile = Join-Path $scriptDir "reset-demo.sql"
$container = "fortivance-mysql"

# Native commands do not throw on failure, so every docker call checks $LASTEXITCODE.
$running = docker inspect -f "{{.State.Running}}" $container 2>$null
if ($LASTEXITCODE -ne 0 -or $running -ne "true") {
    Write-Host "Demo reset FAILED: container $container is not running. Start it with: docker compose up -d" -ForegroundColor Red
    exit 1
}

Write-Host "Resetting Fortivance demo state in $container container..."

Get-Content $sqlFile -Raw | docker exec -i $container sh -c 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"'
if ($LASTEXITCODE -ne 0) {
    Write-Host "Demo reset FAILED: the SQL script returned exit code $LASTEXITCODE. Nothing above was confirmed." -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "Demo reset complete."

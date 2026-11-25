# Loads the synthetic register into the BENCHMARK stack through the real import API, in dependency order.
# Each file is checked first (apply=false); it is imported only if the check reports no errors.
# Run only against the fortivance-bench stack, never against your normal local data or the VPS.
param([string]$Base = "http://localhost:8080", [string]$User = "approver:Fortivance@2026")

$data = Join-Path $PSScriptRoot "data"
$steps = @(
    @{ field = "vendors";  file = "vendors_01.csv" },
    @{ field = "vendors";  file = "vendors_02.csv" },
    @{ field = "vendors";  file = "vendors_03.csv" },
    @{ field = "systems";  file = "systems_01.csv" },
    @{ field = "services"; file = "services_01.csv" }
)

foreach ($s in $steps) {
    $path = Join-Path $data $s.file
    Write-Host "== $($s.file)"
    $check = curl.exe -s -u $User -F "$($s.field)=@$path" "$Base/api/register-import?apply=false" | ConvertFrom-Json
    if (-not $check -or -not $check.ok) {
        Write-Host "   check failed:"; $check.errors | Select-Object -First 5 | Format-Table | Out-String | Write-Host; exit 1
    }
    Write-Host "   check: ok, $(@($check.warnings).Count) warning(s)"
    $t = Measure-Command { $script:res = curl.exe -s -u $User -F "$($s.field)=@$path" "$Base/api/register-import?apply=true" | ConvertFrom-Json }
    if (-not $res.applied) { Write-Host "   import NOT applied"; exit 1 }
    Write-Host ("   imported in {0:N1} s: {1}" -f $t.TotalSeconds, ($res.summary | ConvertTo-Json -Compress))
}

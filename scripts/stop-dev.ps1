$ErrorActionPreference = "Continue"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
Set-Location $repoRoot

$dockerBin = "C:\Program Files\Docker\Docker\resources\bin"
if (Test-Path $dockerBin) {
  $env:PATH = "$dockerBin;$env:PATH"
}

$dockerConfig = Join-Path $repoRoot ".codex-tools\docker-config"
if (Test-Path $dockerConfig) {
  $env:DOCKER_CONFIG = $dockerConfig
}

foreach ($port in 8080, 5173) {
  Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique |
    ForEach-Object {
      Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
    }
}

docker compose -p interview-guide-master -f docker-compose.dev.yml stop postgres rustfs redis

Write-Host "Stopped local frontend/backend processes and Docker dev services."

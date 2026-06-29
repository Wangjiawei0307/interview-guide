param(
  [switch]$WithRedis
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
Set-Location $repoRoot

$dockerBin = "C:\Program Files\Docker\Docker\resources\bin"
$dockerDesktop = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
function Resolve-Java21Home {
  $candidates = New-Object System.Collections.Generic.List[string]

  $vscodeExtensions = Join-Path $env:USERPROFILE ".vscode\extensions"
  if (Test-Path $vscodeExtensions) {
    Get-ChildItem -Path $vscodeExtensions -Directory -Filter "redhat.java-*-win32-x64" -ErrorAction SilentlyContinue |
      Sort-Object LastWriteTime -Descending |
      ForEach-Object {
        $jreRoot = Join-Path $_.FullName "jre"
        if (Test-Path $jreRoot) {
          Get-ChildItem -Path $jreRoot -Directory -Filter "21.*" -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add($_.FullName) }
        }
      }
  }

  $candidates.Add("C:\Program Files\JetBrains\PyCharm 2025.1.2\jbr")
  $candidates.Add("C:\Program Files\JetBrains\IntelliJ IDEA 2025.1.2\jbr")
  $candidates.Add("C:\Program Files\Java\jdk-21")

  foreach ($candidate in $candidates) {
    $javaExe = Join-Path $candidate "bin\java.exe"
    if (Test-Path $javaExe) {
      return $candidate
    }
  }

  return $null
}

$java21Home = Resolve-Java21Home

if (Test-Path $dockerBin) {
  $env:PATH = "$dockerBin;$env:PATH"
}

$dockerConfig = Join-Path $repoRoot ".codex-tools\docker-config"
New-Item -ItemType Directory -Force -Path $dockerConfig | Out-Null
$dockerConfigFile = Join-Path $dockerConfig "config.json"
if (-not (Test-Path $dockerConfigFile)) {
  Set-Content -Path $dockerConfigFile -Value "{}" -Encoding ASCII
}
$env:DOCKER_CONFIG = $dockerConfig

if ($java21Home -and (Test-Path (Join-Path $java21Home "bin\java.exe"))) {
  $env:JAVA_HOME = $java21Home
  $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
  Write-Host "Using JAVA_HOME=$env:JAVA_HOME"
} else {
  Write-Warning "JDK 21 was not found. Install JDK 21 or update JAVA_HOME before starting the backend."
}

function Test-Port {
  param([int]$Port)
  return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-Port {
  param(
    [int]$Port,
    [int]$TimeoutSeconds = 90
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    if (Test-Port $Port) {
      return
    }
    Start-Sleep -Seconds 2
  }
  throw "Timed out waiting for port $Port"
}

function Invoke-Native {
  param(
    [string]$FilePath,
    [string[]]$Arguments
  )

  & $FilePath @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "Command failed ($LASTEXITCODE): $FilePath $($Arguments -join ' ')"
  }
}

function Test-DockerReady {
  docker version --format "{{.Server.Version}}" *> $null
  return $LASTEXITCODE -eq 0
}

function Wait-Docker {
  $deadline = (Get-Date).AddMinutes(3)
  while ((Get-Date) -lt $deadline) {
    if (Test-DockerReady) {
      return
    }
    Start-Sleep -Seconds 3
  }
  throw "Docker engine is not ready. Start Docker Desktop manually, then rerun this script."
}

if (-not (Test-DockerReady)) {
  if (Test-Path $dockerDesktop) {
    Start-Process -FilePath $dockerDesktop -WindowStyle Hidden
  }
  Wait-Docker
}

$redisPort = 6379
$envFile = Join-Path $repoRoot ".env"
if (Test-Path $envFile) {
  $redisPortLine = Get-Content $envFile |
    Where-Object { $_ -match '^\s*REDIS_PORT\s*=' } |
    Select-Object -First 1
  if ($redisPortLine -match '^\s*REDIS_PORT\s*=\s*(\d+)') {
    $redisPort = [int]$Matches[1]
  }
}
Write-Host "Using REDIS_PORT=$redisPort"

$services = @("postgres", "rustfs")
if ($WithRedis -or -not (Test-Port $redisPort)) {
  $services += "redis"
}

$composeArgs = @(
  "compose",
  "-p",
  "interview-guide-master",
  "-f",
  "docker-compose.dev.yml",
  "up",
  "-d"
) + $services
Invoke-Native "docker" $composeArgs

$mcArgs = @(
  "run",
  "--rm",
  "--entrypoint",
  "sh",
  "--network",
  "interview-guide-master_default",
  "minio/mc:latest",
  "-c",
  "mc alias set rustfs http://rustfs:9000 rustfsadmin rustfsadmin >/dev/null && mc mb -p rustfs/interview-guide >/dev/null 2>&1 || true"
)
Invoke-Native "docker" $mcArgs

New-Item -ItemType Directory -Force -Path tmp | Out-Null

if (-not (Test-Port 8080)) {
  Start-Process -FilePath ".\gradlew.bat" `
    -ArgumentList @(":app:bootRun") `
    -WorkingDirectory $repoRoot `
    -RedirectStandardOutput (Join-Path $repoRoot "tmp\backend-full.out.log") `
    -RedirectStandardError (Join-Path $repoRoot "tmp\backend-full.err.log") `
    -WindowStyle Hidden
  Wait-Port 8080 120
}

if (-not (Test-Port 5173)) {
  if (-not (Test-Path "frontend\node_modules")) {
    Push-Location frontend
    Invoke-Native "pnpm.cmd" @("install")
    Pop-Location
  }

  Start-Process -FilePath "pnpm.cmd" `
    -ArgumentList @("exec", "vite", "--host", "127.0.0.1") `
    -WorkingDirectory (Join-Path $repoRoot "frontend") `
    -RedirectStandardOutput (Join-Path $repoRoot "tmp\frontend-vite.out.log") `
    -RedirectStandardError (Join-Path $repoRoot "tmp\frontend-vite.err.log") `
    -WindowStyle Hidden
  Wait-Port 5173 60
}

Write-Host "Frontend: http://127.0.0.1:5173/"
Write-Host "Backend:  http://127.0.0.1:8080/"
Write-Host "API docs: http://127.0.0.1:8080/v3/api-docs"

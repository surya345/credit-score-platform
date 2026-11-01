$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
docker compose up --build -d
if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed. Ensure Docker Desktop is running." }
Write-Host "Open http://localhost:4200 after the services start."

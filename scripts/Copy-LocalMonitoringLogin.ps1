$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$kubectl = (Get-Command kubectl -ErrorAction SilentlyContinue).Source
if (-not $kubectl) { $kubectl = 'C:\Program Files\Docker\Docker\resources\bin\kubectl.exe' }
$secretJson = & $kubectl --kubeconfig (Join-Path $projectRoot '.local\kubeconfig.yaml') --context k3d-knowledge-brain -n brain-observability get secret brain-grafana-admin -o json
if ($LASTEXITCODE -ne 0) { throw 'Cannot read the local operator credential.' }
$secret = ($secretJson -join "`n") | ConvertFrom-Json
$password = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($secret.data.password))
Set-Clipboard -Value $password
Write-Output 'Grafana password copied. Username: brain-admin. Password contents were not printed.'

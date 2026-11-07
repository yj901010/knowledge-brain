$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$kubeconfig = Join-Path $projectRoot '.local\kubeconfig.yaml'
$kubectl = (Get-Command kubectl -ErrorAction SilentlyContinue).Source
if (-not $kubectl) { $kubectl = 'C:\Program Files\Docker\Docker\resources\bin\kubectl.exe' }
$clusterArgs = @('--kubeconfig', $kubeconfig, '--context', 'k3d-knowledge-brain')
& $kubectl @clusterArgs -n brain-observability rollout status deployment/brain-grafana --timeout=120s
if ($LASTEXITCODE -ne 0) { throw 'Grafana is not Ready.' }
Write-Output 'Monitoring: https://localhost:8446/d/brain-platform — operator brain-admin; use Copy-LocalMonitoringLogin.ps1 for the password.'
& $kubectl @clusterArgs -n brain-observability port-forward --address=127.0.0.1 service/brain-grafana 8446:3000
if ($LASTEXITCODE -ne 0) { throw 'Grafana forwarding ended. Check local8446 before restarting.' }

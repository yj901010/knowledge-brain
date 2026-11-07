$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$kubeconfigPath = Join-Path $projectRoot '.local\kubeconfig.yaml'
$kubectlPath = (Get-Command kubectl -ErrorAction SilentlyContinue).Source
if (-not $kubectlPath) { $kubectlPath = 'C:\Program Files\Docker\Docker\resources\bin\kubectl.exe' }
$clusterArgs = @('--kubeconfig', $kubeconfigPath, '--server=https://127.0.0.1:6550')
$currentContext = & $kubectlPath @clusterArgs config current-context
if ($LASTEXITCODE -ne 0 -or $currentContext.Trim() -ne 'k3d-knowledge-brain') { throw 'Local knowledge-brain context required.' }
& $kubectlPath @clusterArgs -n brain-apps rollout status deployment/brain-login --timeout=60s
if ($LASTEXITCODE -ne 0) { throw 'Login HTTPS entry is not Ready.' }
Write-Host 'Keep Start-LocalGateway.ps1 running in another terminal. Login entry: https://localhost:8445'
Write-Host 'Uses the same .local/guest-api-ca.crt as the Graph UI. Ctrl+C stops this forward.'
& $kubectlPath @clusterArgs -n brain-apps port-forward --address=127.0.0.1 service/brain-login 8445:8443
if ($LASTEXITCODE -ne 0) { throw 'Login port-forward failed. Check whether another forward occupies 8445.' }

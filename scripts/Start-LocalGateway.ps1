$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$kubeconfigPath = Join-Path $projectRoot '.local\kubeconfig.yaml'
$kubectlPath = (Get-Command kubectl -ErrorAction SilentlyContinue).Source
if (-not $kubectlPath) { $kubectlPath = 'C:\Program Files\Docker\Docker\resources\bin\kubectl.exe' }
if (-not (Test-Path -LiteralPath $kubeconfigPath)) { throw 'Local kubeconfig is missing.' }
$clusterArgs = @('--kubeconfig', $kubeconfigPath, '--server=https://127.0.0.1:6550')
$currentContext = & $kubectlPath @clusterArgs config current-context
if ($LASTEXITCODE -ne 0 -or $currentContext.Trim() -ne 'k3d-knowledge-brain') { throw 'Local knowledge-brain context required.' }
& $kubectlPath @clusterArgs -n brain-apps rollout status deployment/gateway-service --timeout=60s
if ($LASTEXITCODE -ne 0) { throw 'Gateway is not Ready.' }
Write-Host 'Graph View: https://localhost:8443/graph'
Write-Host 'Gateway API: https://localhost:8443 (use .local/guest-api-ca.crt). Ctrl+C stops the forward.'
& $kubectlPath @clusterArgs -n brain-apps port-forward --address=127.0.0.1 service/gateway-service 8443:8080
if ($LASTEXITCODE -ne 0) { throw 'Gateway port-forward failed. Check whether another forward occupies 8443.' }

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$kubeconfigPath = Join-Path $projectRoot '.local\kubeconfig.yaml'
$kubectlPath = (Get-Command kubectl -ErrorAction SilentlyContinue).Source
if (-not $kubectlPath) { $kubectlPath = 'C:\Program Files\Docker\Docker\resources\bin\kubectl.exe' }
if (-not (Test-Path -LiteralPath $kubeconfigPath)) { throw 'Local kubeconfig is missing.' }
$clusterArgs = @('--kubeconfig', $kubeconfigPath, '--server=https://127.0.0.1:6550')
$currentContext = & $kubectlPath @clusterArgs config current-context
if ($LASTEXITCODE -ne 0 -or $currentContext.Trim() -ne 'k3d-knowledge-brain') { throw 'Local knowledge-brain context required.' }
& $kubectlPath @clusterArgs -n brain-data rollout status deployment/brain-minio --timeout=60s
if ($LASTEXITCODE -ne 0) { throw 'MinIO is not Ready.' }
Write-Host 'Media object storage: https://localhost:9443 (use .local/guest-api-ca.crt). Ctrl+C stops the forward.'
& $kubectlPath @clusterArgs -n brain-data port-forward --address=127.0.0.1 service/brain-minio 9443:9000
if ($LASTEXITCODE -ne 0) { throw 'Media port-forward failed. Check whether another forward occupies 9443.' }

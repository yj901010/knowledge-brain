$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$kubeconfigPath = Join-Path $projectRoot '.local\kubeconfig.yaml'
$kubectlPath = (Get-Command kubectl -ErrorAction SilentlyContinue).Source
if (-not $kubectlPath) { $kubectlPath = 'C:\Program Files\Docker\Docker\resources\bin\kubectl.exe' }
if (-not (Test-Path -LiteralPath $kubeconfigPath)) { throw 'Local kubeconfig is missing.' }
$clusterArgs = @('--kubeconfig', $kubeconfigPath, '--server=https://127.0.0.1:6550')
$currentContext = & $kubectlPath @clusterArgs config current-context
if ($LASTEXITCODE -ne 0 -or $currentContext.Trim() -ne 'k3d-knowledge-brain') { throw 'Local knowledge-brain context required.' }
$loginToken = & $kubectlPath @clusterArgs -n brain-ops create token brain-headlamp-viewer --duration=1h
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($loginToken)) { throw 'Could not issue read-only Headlamp token.' }
# Explicit user-run fallback: only this command intentionally prints the short-lived login credential.
Write-Output $loginToken.Trim()
Remove-Variable loginToken

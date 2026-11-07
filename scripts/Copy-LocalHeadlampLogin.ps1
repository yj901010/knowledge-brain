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
$copied = $false
for ($attempt = 0; $attempt -lt 8; $attempt++) {
 try { Set-Clipboard -Value $loginToken.Trim() -ErrorAction Stop; $copied = $true; break } catch { Start-Sleep -Milliseconds 150 }
}
Remove-Variable loginToken
if (-not $copied) { throw 'Clipboard is unavailable in this session. Run Get-LocalHeadlampLogin.ps1 in your own terminal and copy its output.' }
Write-Host 'One-hour read-only login token copied to clipboard. Paste into Headlamp at https://localhost:8444.'

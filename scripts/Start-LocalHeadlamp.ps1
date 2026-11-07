param(
    [ValidateRange(1024, 65535)] [int] $Port = 8444,
    [ValidateRange(0, 10)] [int] $MaximumReconnectAttempts = 5
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$kubeconfigPath = Join-Path $projectRoot '.local\kubeconfig.yaml'
$kubectlPath = (Get-Command kubectl -ErrorAction SilentlyContinue).Source
if (-not $kubectlPath) { $kubectlPath = 'C:\Program Files\Docker\Docker\resources\bin\kubectl.exe' }
if (-not (Test-Path -LiteralPath $kubeconfigPath)) { throw 'Local kubeconfig is missing.' }
$clusterArgs = @('--kubeconfig', $kubeconfigPath, '--server=https://127.0.0.1:6550')
$currentContext = & $kubectlPath @clusterArgs config current-context
if ($LASTEXITCODE -ne 0 -or $currentContext.Trim() -ne 'k3d-knowledge-brain') { throw 'Local knowledge-brain context required.' }
& $kubectlPath @clusterArgs -n brain-ops rollout status deployment/brain-headlamp --timeout=60s
if ($LASTEXITCODE -ne 0) { throw 'Headlamp is not Ready.' }
Write-Host "Headlamp UI: https://localhost:$Port (HTTPS only; trust .local/guest-api-ca.crt). Ctrl+C stops the forward."
Write-Host 'Do not open http:// on this port: a plain HTTP request can reset the TLS connection and terminate kubectl port-forward.'
for ($attempt = 0; $attempt -le $MaximumReconnectAttempts; $attempt++) {
    $forwardOutput = @()
    $savedErrorPreference = $ErrorActionPreference
    try {
        # Windows PowerShell wraps native stderr in ErrorRecord; retain diagnostics without terminating the retry loop.
        $ErrorActionPreference = 'Continue'
        & $kubectlPath @clusterArgs -n brain-ops port-forward --address=127.0.0.1 service/brain-headlamp "${Port}:80" 2>&1 |
            Tee-Object -Variable forwardOutput | ForEach-Object { Write-Host $_.ToString() }
        $forwardExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorPreference
    }
    if ($forwardExitCode -in @(0, 130, -1073741510)) { break }
    $diagnostic = ($forwardOutput | ForEach-Object { $_.ToString() }) -join "`n"
    if ($diagnostic -match 'unable to listen on any of the requested ports|Only one usage of each socket address|address already in use') {
        throw "Could not bind local port $Port. Stop the existing forward or pass -Port with a free local port."
    }
    if ($attempt -eq $MaximumReconnectAttempts) {
        throw "Headlamp forwarding stopped after $MaximumReconnectAttempts reconnect attempts. Review the kubectl errors above; verify the Pod and use https://localhost:$Port."
    }
    Write-Warning "Headlamp forwarding disconnected. Reconnecting in 2 seconds ($($attempt + 1)/$MaximumReconnectAttempts). Use https://localhost:$Port."
    Start-Sleep -Seconds 2
    & $kubectlPath @clusterArgs -n brain-ops rollout status deployment/brain-headlamp --timeout=30s
    if ($LASTEXITCODE -ne 0) { throw 'Headlamp did not become Ready for reconnection. Review the Pod status and events.' }
}

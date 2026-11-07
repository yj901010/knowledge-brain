param(
    [string]$K3d = 'k3d',
    [string]$Kubectl = 'kubectl',
    [ValidateRange(1, 120)]
    [int]$DockerInfoTimeoutSeconds = 30
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localDirectory = Join-Path $projectRoot '.local'
New-Item -ItemType Directory -Force -Path $localDirectory | Out-Null
# Keep project credentials out of the user's global kubeconfig.
$configPath = Join-Path $localDirectory 'kubeconfig.yaml'
# A running Desktop GUI does not establish that its WSL engine is responsive.
# Bound the read-only preflight before k3d can create or start any resources.
$dockerProcess = [Diagnostics.Process]::new()
try {
    $dockerCommand = Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1
    $dockerProcess.StartInfo.FileName = $dockerCommand.Source
    $dockerProcess.StartInfo.Arguments = 'info --format "{{.OSType}}|{{.CgroupVersion}}"'
    $dockerProcess.StartInfo.UseShellExecute = $false
    $dockerProcess.StartInfo.CreateNoWindow = $true
    $dockerProcess.StartInfo.RedirectStandardOutput = $true
    $dockerProcess.StartInfo.RedirectStandardError = $true
    if (-not $dockerProcess.Start()) { throw 'Cannot start the Docker runtime preflight.' }
    $dockerOutput = $dockerProcess.StandardOutput.ReadToEndAsync()
    $dockerError = $dockerProcess.StandardError.ReadToEndAsync()
    if (-not $dockerProcess.WaitForExit($DockerInfoTimeoutSeconds * 1000)) {
        # Stop only this script's read-only CLI process, never the Docker engine.
        $dockerProcess.Kill()
        $dockerProcess.WaitForExit()
        throw "Docker info timed out after $DockerInfoTimeoutSeconds seconds. Docker Desktop/WSL is not responding; recover the host before retrying. No cluster action was started."
    }
    $runtime = $dockerOutput.GetAwaiter().GetResult().Trim()
    if ($dockerProcess.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($runtime)) {
        throw 'Docker engine is unavailable. Start Docker Desktop with Linux containers before retrying. No cluster action was started.'
    }
} finally {
    $dockerProcess.Dispose()
}
$runtimeFields = $runtime -split '\|'
if ($runtimeFields.Count -ne 2) { throw 'Unexpected Docker runtime response. No cluster action was started.' }
if ([string]::IsNullOrWhiteSpace($runtimeFields[0]) -or [string]::IsNullOrWhiteSpace($runtimeFields[1])) {
    throw 'Docker engine is unavailable. Start Docker Desktop with Linux containers before retrying. No cluster action was started.'
}
if ($runtimeFields[0] -ne 'linux') { throw 'Linux containers are required for k3d.' }
if ($runtimeFields[1] -ne '2') { throw 'K3s 1.35 requires cgroup v2 with the current kubelet configuration. Update Docker Desktop/WSL and verify docker info before starting the cluster.' }
$clusters = & $K3d cluster list -o json | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Cannot list k3d clusters.' }
if (@($clusters | Where-Object { $_.name -eq 'knowledge-brain' }).Count -eq 0) {
    & $K3d cluster create --config (Join-Path $projectRoot 'infra\k3d\cluster.yaml')
    if ($LASTEXITCODE -ne 0) { throw 'Cluster creation failed.' }
}
# Resolve an existing stopped cluster explicitly; never delete it as a retry.
& $K3d cluster start knowledge-brain
if ($LASTEXITCODE -ne 0) { throw 'Cluster start failed.' }
$kubeconfig = & $K3d kubeconfig get knowledge-brain
if ($LASTEXITCODE -ne 0) { throw 'Kubeconfig retrieval failed.' }
[IO.File]::WriteAllText($configPath, (($kubeconfig -join "`n") -replace 'https://localhost:6550', 'https://127.0.0.1:6550'), [Text.UTF8Encoding]::new($false))
& $Kubectl --kubeconfig $configPath --context k3d-knowledge-brain wait --for=condition=Ready nodes --all --timeout=120s
if ($LASTEXITCODE -ne 0) { throw 'Nodes did not become Ready.' }
& $Kubectl --kubeconfig $configPath --context k3d-knowledge-brain apply -f (Join-Path $projectRoot 'infra\kubernetes\namespaces.yaml')
if ($LASTEXITCODE -ne 0) { throw 'Namespace creation failed.' }
Write-Output "Cluster ready. Project kubeconfig: $configPath"


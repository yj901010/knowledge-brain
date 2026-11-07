param([string]$Helm = 'helm')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$chart = Join-Path $projectRoot 'infra\charts\brain-service'
$localValues = Join-Path $projectRoot 'infra\environments\local\service-values.yaml'
$services = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'services') -Directory
if ($services.Count -ne 13) { throw 'Expected 13 separate service modules.' }
foreach ($service in $services) {
    foreach ($environment in @('base', 'local')) {
        $helmArguments = @('lint', $chart, '--set', "image.repository=knowledge-brain/$($service.Name)", '--set', 'image.tag=validation-only')
        if ($environment -eq 'local') { $helmArguments += @('-f', $localValues) }
        & $Helm @helmArguments
        if ($LASTEXITCODE -ne 0) { throw "Chart lint failed: $($service.Name) $environment" }
        $renderArguments = @('template', $service.Name, $chart, '--namespace', 'brain-apps', '--set', "image.repository=knowledge-brain/$($service.Name)", '--set', 'image.tag=validation-only')
        if ($environment -eq 'local') { $renderArguments += @('-f', $localValues) }
        & $Helm @renderArguments | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Chart render failed: $($service.Name) $environment" }
    }
}
Write-Output 'All 13 services linted and rendered with base and local values. No resources deployed.'

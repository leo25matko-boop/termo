param(
    [Parameter(Mandatory = $true)][string]$Destination,
    [string]$SourceDirectory = (Join-Path ([System.IO.Path]::GetTempPath()) ('termodom-camera-source-' + [guid]::NewGuid().ToString('N'))),
    [string]$Archive
)
$ErrorActionPreference = 'Stop'
$repositoryDirectory = Split-Path -Parent $PSScriptRoot
$helper = Join-Path $PSScriptRoot 'prepare-camera-engine.py'
$arguments = @($helper, '--destination', $SourceDirectory)
if ($Archive) { $arguments += @('--archive', $Archive) }
& python @arguments
if ($LASTEXITCODE -ne 0) { throw 'Pinned camera source preparation failed.' }
$destinationDirectory = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null
$previousCgo = $env:CGO_ENABLED
$previousGoos = $env:GOOS
$previousGoarch = $env:GOARCH
try {
    $env:CGO_ENABLED = '0'
    $env:GOOS = 'windows'
    $env:GOARCH = 'amd64'
    Push-Location -LiteralPath $SourceDirectory
    try {
        & go test -mod=readonly . ./internal/api ./internal/app ./internal/xiaomi ./pkg/xiaomi/... ./pkg/tutk/...
        if ($LASTEXITCODE -ne 0) { throw 'Pinned camera protocol unit tests failed.' }
        & go build -mod=readonly -trimpath -ldflags '-s -w' -o (Join-Path $destinationDirectory 'termodom-camera.exe') .
        if ($LASTEXITCODE -ne 0) { throw 'Pinned native camera engine build failed.' }
    } finally { Pop-Location }
} finally {
    $env:CGO_ENABLED = $previousCgo
    $env:GOOS = $previousGoos
    $env:GOARCH = $previousGoarch
}
Copy-Item -LiteralPath (Join-Path $SourceDirectory 'LICENSE') -Destination (Join-Path $destinationDirectory 'LICENSE-go2rtc.txt')
Copy-Item -LiteralPath (Join-Path $SourceDirectory 'LICENSE-homebridge-xiaomi-go2rtc.txt') -Destination $destinationDirectory
Copy-Item -LiteralPath (Join-Path $SourceDirectory 'TERMODOM-PATCHES.txt') -Destination $destinationDirectory
$digest = (Get-FileHash -LiteralPath (Join-Path $destinationDirectory 'termodom-camera.exe') -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText((Join-Path $destinationDirectory 'SHA256SUMS.txt'), "$digest  termodom-camera.exe`n")
Write-Output "CAMERA_ENGINE_BUILT: source b5948cfb25404cc5cb37b166ecaa2dca20b11d4b; sha256=$digest"

param([switch]$PrepareOnly)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$expectedHash = '884f85ce9995cadd17d92b41ed90db7dc6c228aeca474f44bb3e7e3745bed1ca'
$expectedSize = 561528533
$package = Join-Path $PSScriptRoot 'paket'
$installer = Join-Path $PSScriptRoot 'Instaliraj-L-Design-0.3.29.exe'
$temporary = Join-Path $PSScriptRoot ('ldesign-' + [Guid]::NewGuid().ToString('N') + '.tmp')
$parts = @(
    @{ Name = 'dio-001.bin'; Size = 50331648; Hash = 'a277c4ffac8294624e6505d2efda1347f42dd576a355c35ee7a5370f53e65744' },
    @{ Name = 'dio-002.bin'; Size = 50331648; Hash = 'a9c25c7527b35e0f737b05a2b6c2ec00e67b6dde646338ae08aeb53f603fe534' },
    @{ Name = 'dio-003.bin'; Size = 50331648; Hash = '363943b4477f0266294c860d7900de2f0855b47c2b7c59d8e7669cbef67d2681' },
    @{ Name = 'dio-004.bin'; Size = 50331648; Hash = 'b0c9846c6cf70980f0ac89511d763a8016671ffafe6c4213e4c73895260bd50d' },
    @{ Name = 'dio-005.bin'; Size = 50331648; Hash = 'd41880dbff15f217a98370987f238ae5c8d73ea27d85019ce7a978bba24584dd' },
    @{ Name = 'dio-006.bin'; Size = 50331648; Hash = '42b718930684f88cbb9f0d0855c969abb1ba76c9633389255f34c2bd8cd4827f' },
    @{ Name = 'dio-007.bin'; Size = 50331648; Hash = '4f0153556e97f2a5b8159d1b11d39be5f8b8c298072c0efbb7549671c0fdda2d' },
    @{ Name = 'dio-008.bin'; Size = 50331648; Hash = '4d7cb7d71d0709e2be59369bc52f9b13f0fdf705938aeac2a55e954a4d684523' },
    @{ Name = 'dio-009.bin'; Size = 50331648; Hash = 'f3208e99788e6df80c35edc61aa90e801b73cf6d5124d14f7d608d0045e82200' },
    @{ Name = 'dio-010.bin'; Size = 50331648; Hash = '5876f26168be199a998c62c1b8c64cd42a292170241f009a2fc838ea09fe3743' },
    @{ Name = 'dio-011.bin'; Size = 50331648; Hash = '18c278d0f8e89fbb1083146b1e3911928cc95af4b21ae424f68e3b63469a66b1' },
    @{ Name = 'dio-012.bin'; Size = 7880405; Hash = '9b462d2d0a657d72079becfacf5e4fc70de422d9a434bb1d44bd299a62ac463a' }
)
try {
    $ready = (Test-Path -LiteralPath $installer -PathType Leaf) -and ((Get-Item -LiteralPath $installer).Length -eq $expectedSize)
    if ($ready) { $ready = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash -eq $expectedHash }
    if (-not $ready) {
        Write-Host 'Priprema L-Design instalacije. Pricekaj zavrsetak...'
        $target = [System.IO.File]::Open($temporary, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try {
            foreach ($part in $parts) {
                $path = Join-Path $package $part.Name
                if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw ('Nedostaje ' + $part.Name + '. Prvo raspakiraj cijeli ZIP opcijom Izdvoji sve.') }
                if ((Get-Item -LiteralPath $path).Length -ne $part.Size) { throw ('Datoteka je nepotpuna: ' + $part.Name + '. Ponovi preuzimanje i raspakiravanje ZIP-a.') }
                if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $part.Hash) { throw ('Provjera datoteke nije uspjela: ' + $part.Name + '. Ponovi preuzimanje ZIP-a.') }
                $inputFile = [System.IO.File]::OpenRead($path)
                try { $inputFile.CopyTo($target) } finally { $inputFile.Dispose() }
            }
        } finally { $target.Dispose() }
        if ((Get-Item -LiteralPath $temporary).Length -ne $expectedSize -or (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $expectedHash) { throw 'Instalacija nije potpuna. Ponovi preuzimanje ZIP-a.' }
        Move-Item -LiteralPath $temporary -Destination $installer -Force
    }
    Write-Host 'L-Design instalacija je provjerena i spremna.'
    if (-not $PrepareOnly) {
        Write-Host 'Zatvori postojeci L-Design pa dovrsi instalaciju u otvorenom prozoru.'
        $process = Start-Process -FilePath $installer -PassThru -Wait
        if ($process.ExitCode -ne 0) { throw ('Instalacija je zavrsila kodom ' + $process.ExitCode) }
    }
    exit 0
} catch {
    Write-Host ('GRESKA: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
}

param([switch]$PrepareOnly)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$expectedHash = '815df9330c14c0f65af21bba8a5e1f3f5fa3c4f7f4740be6b4b86d50b9a1a823'
$expectedSize = 561218370
$package = Join-Path $PSScriptRoot 'paket'
$installer = Join-Path $PSScriptRoot 'Instaliraj-L-Design-0.3.24.exe'
$temporary = Join-Path $PSScriptRoot ('ldesign-' + [Guid]::NewGuid().ToString('N') + '.tmp')
$parts = @(
    @{ Name = 'dio-001.bin'; Size = 50331648; Hash = '56fab4d989a514efd4f1d76ee88a998d0bcd6753546a6ac874e7820944ece34e' },
    @{ Name = 'dio-002.bin'; Size = 50331648; Hash = '7de121c4904bccde4263e2338d529dc7ac2bf117b01f0f41c146046347d711c3' },
    @{ Name = 'dio-003.bin'; Size = 50331648; Hash = '3d21e8cedbc489f3f0d094eee62b438048bf4abb6823c09e957d3c37475b3d22' },
    @{ Name = 'dio-004.bin'; Size = 50331648; Hash = '18707ef7ea6d5796e975bafea14b5386e1c8e149ad0c9342e6e0668caa7c26d8' },
    @{ Name = 'dio-005.bin'; Size = 50331648; Hash = 'edd10b757feba01a49a077b78cbfebd2870f70be5bcf925a1f91736073ef3ae1' },
    @{ Name = 'dio-006.bin'; Size = 50331648; Hash = '3337b581b7eb630610ed0971acdd89b08ae2a0ace44ffb94d57606b36f400f66' },
    @{ Name = 'dio-007.bin'; Size = 50331648; Hash = '02502fa7cb17299c9b996cf7667396eaa46b2e2115f7536cf9ca241f06a5d06c' },
    @{ Name = 'dio-008.bin'; Size = 50331648; Hash = '8ac2a351b1e562620ba1228a3f4e04d590c2e625dfe63b229492beb688731704' },
    @{ Name = 'dio-009.bin'; Size = 50331648; Hash = '662af33f5681ee4c224be017bd414bd9ad55251c54dfffc8a190bb35381a3403' },
    @{ Name = 'dio-010.bin'; Size = 50331648; Hash = 'f73f9d815ad0af33754addeb98d68674b0b1099bea78ea75b2df1ddde17b5898' },
    @{ Name = 'dio-011.bin'; Size = 50331648; Hash = '0c59cd3814e0b5f3ab61473eed107ab707ae220a0dc94892bbe405807ca45b28' },
    @{ Name = 'dio-012.bin'; Size = 7570242; Hash = 'b67ef3bd34686979e50dc78b043b1edf409210e2d01fd33219096b8ad87a644d' }
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

param([switch]$PrepareOnly)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$expectedHash = '5465a4ece0440d35f33c94073853db145b05b50ffcb33b2ed8c95df9b8781727'
$expectedSize = 561232821
$package = Join-Path $PSScriptRoot 'paket'
$installer = Join-Path $PSScriptRoot 'Instaliraj-L-Design-0.3.23.exe'
$temporary = Join-Path $PSScriptRoot ('ldesign-' + [Guid]::NewGuid().ToString('N') + '.tmp')
$parts = @(
    @{ Name = 'dio-001.bin'; Size = 50331648; Hash = '77691cf925d5a643e63427847a2f21d3c0be35418ae069a4fc3f1775f91e3b86' },
    @{ Name = 'dio-002.bin'; Size = 50331648; Hash = '29bee9d8b5149a0428ed3927d1558f1f9554139a7f06c77e2b654cf8958d822e' },
    @{ Name = 'dio-003.bin'; Size = 50331648; Hash = '7a16351b364db7017cbf12c0d0fbb3e371314246c5f256a905fce6b74df662f5' },
    @{ Name = 'dio-004.bin'; Size = 50331648; Hash = 'c04a100a0353c9fb1ae558be21e072604eec744423e90c22b653cb468478715d' },
    @{ Name = 'dio-005.bin'; Size = 50331648; Hash = '405aefd50827b27251455472676b2508fd3dc5b7a714fd3d35c383e00f578d2f' },
    @{ Name = 'dio-006.bin'; Size = 50331648; Hash = '914ec608ad9be38565e737168ebe308f8f0c9879153094dd33938681a413dd45' },
    @{ Name = 'dio-007.bin'; Size = 50331648; Hash = '7fdfea1acec5258e451002cb9af69840e96a7e33432648baa4d0c95451bdfc03' },
    @{ Name = 'dio-008.bin'; Size = 50331648; Hash = '9787a052b12979cb0d8825d96885b762462664bcf65972b01a75f41e49494799' },
    @{ Name = 'dio-009.bin'; Size = 50331648; Hash = '655cd0c677c3e163918d6996ba78892ef99ff24ab6ff577ff64de95248c3243c' },
    @{ Name = 'dio-010.bin'; Size = 50331648; Hash = '7e3ad1aebf0afe912fa1e46cf4b8978347992d58bb02439f4634326bb79fc164' },
    @{ Name = 'dio-011.bin'; Size = 50331648; Hash = '56d6ead36ca2e7dc42f3c1945e5830beb7034c4465fdd448952dee004e732537' },
    @{ Name = 'dio-012.bin'; Size = 7584693; Hash = '5ef383ca0517bc7d476967269dc9164588f1bb4c55bc530bb176917fbcb397be' }
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

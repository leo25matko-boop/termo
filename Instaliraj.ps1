param([switch]$PrepareOnly)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$expectedHash = 'f58936d9230a65a79def24bf04ecb8977732a92eb21b8f7754aae9165100a516'
$expectedSize = 561224730
$package = Join-Path $PSScriptRoot 'paket'
$installer = Join-Path $PSScriptRoot 'Instaliraj-L-Design-0.3.25.exe'
$temporary = Join-Path $PSScriptRoot ('ldesign-' + [Guid]::NewGuid().ToString('N') + '.tmp')
$parts = @(
    @{ Name = 'dio-001.bin'; Size = 50331648; Hash = '4c4d04ac51fe22c2baba1d714a0bcd7383225f7f4530d4ea226892f7227d9ce4' },
    @{ Name = 'dio-002.bin'; Size = 50331648; Hash = 'de579d80bda008a1841072ba8d2f002c88a30419f87af661dab3d2e0a389d416' },
    @{ Name = 'dio-003.bin'; Size = 50331648; Hash = 'b575a5a5efa188f7d885d824913439e92e6767569f4e73f9be261ee86472d84a' },
    @{ Name = 'dio-004.bin'; Size = 50331648; Hash = 'ca254ac0043957bf63477c151d2bdf7c80745b8ccc6343d05c6ac767719461af' },
    @{ Name = 'dio-005.bin'; Size = 50331648; Hash = 'db135f9972373062f3cf4d56f3dc6aa2fb37c59c0c146238b66ee75230358b56' },
    @{ Name = 'dio-006.bin'; Size = 50331648; Hash = 'b5bf66438143bfae0a52149cb259cbdfd46c3f87920bce9656fef8016df9edf0' },
    @{ Name = 'dio-007.bin'; Size = 50331648; Hash = '973c935b812971f40c628671956e4b85d06cff301c10ed8c33a47f2c8e116c76' },
    @{ Name = 'dio-008.bin'; Size = 50331648; Hash = '5f9895943abe9609b248fb3941e17f9a5e0ed4a4bb616d403a36dd4936338c2b' },
    @{ Name = 'dio-009.bin'; Size = 50331648; Hash = '6f6c12b7d694c4ec81e7137aa1324fe4dc12f3611d063899143c2c4b858bcdc8' },
    @{ Name = 'dio-010.bin'; Size = 50331648; Hash = '3a2eb3e6010076625bf53e3c55dbcaa2e9a553ed182eef72430a1d034aa4e717' },
    @{ Name = 'dio-011.bin'; Size = 50331648; Hash = '4f99045f1afe0c89081d002218d22c4696ca5e6a9ecabe7bad33eb28848bb1c5' },
    @{ Name = 'dio-012.bin'; Size = 7576602; Hash = 'e88058c36d315bc9700dbc4bfcc8dbfbc64b8fbcc03bf48f06b9005e7244a8ed' }
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

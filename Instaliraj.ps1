param([switch]$PrepareOnly)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$expectedHash = 'f762247ad8a921cd9433d931a73c4e14d32d79acab6b793a323dff7c5c927c13'
$expectedSize = 561522366
$package = Join-Path $PSScriptRoot 'paket'
$installer = Join-Path $PSScriptRoot 'Instaliraj-L-Design-0.3.28.exe'
$temporary = Join-Path $PSScriptRoot ('ldesign-' + [Guid]::NewGuid().ToString('N') + '.tmp')
$parts = @(
    @{ Name = 'dio-001.bin'; Size = 50331648; Hash = 'ac12872bce24a676b8967a2db90cf466124096ba5ba7a0834885319d881cbaf6' },
    @{ Name = 'dio-002.bin'; Size = 50331648; Hash = '31c305ffd77810acc8aca3078632b3b3892896baea266d908f4be8c977399e75' },
    @{ Name = 'dio-003.bin'; Size = 50331648; Hash = '7bc632737f28425cad464477713039ea8b00aa194bbb65a0a8af7e279b48ea71' },
    @{ Name = 'dio-004.bin'; Size = 50331648; Hash = '883b0f232046ad179f6b7bafc5b8671376f8e97f2140955ae1c74fe440d34db2' },
    @{ Name = 'dio-005.bin'; Size = 50331648; Hash = 'f5bcf8c4ce84ab319bd7dca12b6a73548de36c2489a41372a26751064edbaaeb' },
    @{ Name = 'dio-006.bin'; Size = 50331648; Hash = 'f2349d7f71934b4d027e3516cf849f084f34992b6e819ece3e8514054f89e5dd' },
    @{ Name = 'dio-007.bin'; Size = 50331648; Hash = '1925e3b98886376eb27f20fa555fa5790ecec111c38e64395f54c75316f935e2' },
    @{ Name = 'dio-008.bin'; Size = 50331648; Hash = '7b3f363c29b54f1b8c74ed7f93582e05969154e19db98092555357e8c3a48ba7' },
    @{ Name = 'dio-009.bin'; Size = 50331648; Hash = '6e578ef41430d45b89b47da9bd35c3136dbed362d2c4c27ca73be085adce21d9' },
    @{ Name = 'dio-010.bin'; Size = 50331648; Hash = '67079001fc0734f8809d8fc9e8fb76e928b70f2d372f7290a468111b21dc4320' },
    @{ Name = 'dio-011.bin'; Size = 50331648; Hash = 'bac24676513a868af95ba266f6a848c1ec5d44caf9daac9254e467d3b568b1d9' },
    @{ Name = 'dio-012.bin'; Size = 7874238; Hash = '1549f230eb83298c2dc80f0eac17499b473e70d2ba70253cad01d4710979bc4f' }
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

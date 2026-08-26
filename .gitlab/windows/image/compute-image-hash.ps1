$ErrorActionPreference = "Stop"

try {
    $root = (Resolve-Path $PSScriptRoot).Path.TrimEnd("\")
    $files = Get-ChildItem -Path $root -File -Recurse -Force | Sort-Object -Property FullName
    $entries = $files | ForEach-Object {
        $relativePath = $_.FullName.Substring($root.Length + 1).Replace("\", "/")
        $fileHash = (Get-FileHash -Path $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$relativePath`:$fileHash"
    }
    $combined = $entries -join "`n"
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($combined)
    $stream = [System.IO.MemoryStream]::new($bytes)
    try {
        $digest = (Get-FileHash -InputStream $stream -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    finally {
        $stream.Dispose()
    }

    Write-Output $digest.Substring(0, 16)
    exit 0
}
catch {
    [Console]::Error.WriteLine("compute-image-hash.ps1: $_")
    exit 1
}

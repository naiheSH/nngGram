param([string]$FfmpegDirectory)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $FfmpegDirectory) {
    $command = Get-Command ffmpeg.exe -ErrorAction Stop
    $FfmpegDirectory = Split-Path -Parent $command.Source
}
$source = (Resolve-Path -LiteralPath $FfmpegDirectory).Path
$executable = Join-Path $source 'ffmpeg.exe'
$executables = @('ffmpeg.exe', 'ffprobe.exe') | ForEach-Object {
    $candidate = Join-Path $source $_
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "$_ is missing." }
    Get-Item -LiteralPath $candidate
}
$distribution = Split-Path -Parent $source
$license = @('LICENSE', 'LICENSE.txt') | ForEach-Object { Join-Path $distribution $_ } |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (-not $license) { throw 'Keep the FFmpeg distribution LICENSE or LICENSE.txt beside its bin directory.' }
$destination = [IO.Path]::GetFullPath((Join-Path $repository 'native/windows-x64/media'))
if (-not $destination.StartsWith($repository + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid destination.' }
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$binaries = @($executables) + @(Get-ChildItem -LiteralPath $source -Filter '*.dll' -File)
foreach ($binary in $binaries) {
    Copy-Item -LiteralPath $binary.FullName -Destination (Join-Path $destination $binary.Name) -Force
}
Copy-Item -LiteralPath $license -Destination (Join-Path $destination 'FFmpeg-LICENSE.txt') -Force
$version = (& $executable -version 2>&1 | Select-Object -First 1).ToString()
$provenance = @($version, 'Prepared locally for media decoding, video inspection and playback. Binaries and their license are kept together.')
foreach ($binary in $binaries) {
    $hash = (Get-FileHash -LiteralPath $binary.FullName -Algorithm SHA256).Hash
    $provenance += "$($binary.Name) SHA256 $hash"
}
$provenance | Set-Content -LiteralPath (Join-Path $destination 'VERSION.txt') -Encoding utf8
Write-Output "Prepared media tools in $destination"

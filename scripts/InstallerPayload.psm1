Set-StrictMode -Version Latest

function Get-NngGramPayload {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$AppDirectory)
    $root = (Resolve-Path -LiteralPath $AppDirectory -ErrorAction Stop).Path.TrimEnd('\', '/')
    $rootItem = Get-Item -LiteralPath $root
    if (!$rootItem.PSIsContainer -or ($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Payload must be an ordinary directory.' }
    $files = [Collections.Generic.List[object]]::new()
    $pending = [Collections.Generic.Queue[string]]::new()
    $pending.Enqueue($root)
    [long]$total = 0
    while ($pending.Count -gt 0) {
        $directory = $pending.Dequeue()
        foreach ($entry in Get-ChildItem -LiteralPath $directory -Force -ErrorAction Stop) {
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Payload must not contain reparse points.' }
            $relative = $entry.FullName.Substring($root.Length + 1).Replace('\', '/')
            if ($relative -match '(^|/)(tdata|tdlib-data|database|downloads|user-[0-9]+)(/|$)' -or
                $entry.Name -match '^(tdlib\.properties|ui-(folders|settings)\.properties|td\.binlog)$' -or
                $entry.Name -match '\.(sqlite([-.].*)?|log)$') { throw 'Payload contains a user configuration, data or log entry.' }
            if ($entry.PSIsContainer) { $pending.Enqueue($entry.FullName); continue }
            if ($entry.Length -le 0) { throw "Payload contains an empty file: $relative" }
            $total += $entry.Length
            if ($files.Count -ge 20000 -or $total -gt 1800000000) { throw 'Payload exceeds the bounded single-cabinet installer limit.' }
            $files.Add([pscustomobject]@{ Relative = $relative; FullName = $entry.FullName; Length = $entry.Length;
                Sha256 = (Get-FileHash -LiteralPath $entry.FullName -Algorithm SHA256).Hash.ToLowerInvariant() })
        }
    }
    $lookup = @{}
    foreach ($file in $files) { $lookup[$file.Relative] = $file }
    $required = @('nngGram.exe', 'tdlib.jar', 'tdjni.dll', 'manifest.properties', 'TDLib-LICENSE.txt',
        'bin/skiko-windows-x64.dll', 'bin/nucleus_tao.dll', 'bin/libEGL.dll', 'bin/libGLESv2.dll', 'bin/jnidispatch.dll',
        'media/ffmpeg.exe', 'media/ffprobe.exe', 'media/FFmpeg-LICENSE.txt')
    foreach ($relative in $required) { if (!$lookup.ContainsKey($relative)) { throw "Incomplete Native Image directory: $relative" } }
    if (!($files | Where-Object { $_.Relative.StartsWith('licenses/', [StringComparison]::OrdinalIgnoreCase) })) { throw 'TDLib dependency licenses are missing.' }
    $manifest = @{}
    foreach ($line in Get-Content -LiteralPath $lookup['manifest.properties'].FullName) {
        if ($line -match '^([^#=]+)=(.*)$') { $manifest[$Matches[1].Trim()] = $Matches[2].Trim() }
    }
    $pin = ConvertFrom-StringData (Get-Content -LiteralPath (Join-Path $PSScriptRoot '../native/tdlib-source.properties') -Raw)
    if ($manifest['source.commit'] -ne $pin.commit -or
        $manifest['architecture'] -ne 'x64' -or $manifest['binding'] -ne 'official-java-jni') { throw 'The payload does not match the pinned official Windows x64 TDLib bundle.' }
    if (!$manifest.ContainsKey('load.order') -or !$manifest.ContainsKey('sha256.tdlib.jar') -or
        'tdjni.dll' -notin $manifest['load.order'].Split(',')) { throw 'The TDLib manifest is incomplete.' }
    $nativeFiles = @('tdlib.jar') + $manifest['load.order'].Split(',')
    foreach ($relative in $nativeFiles) {
        if ($relative -notmatch '^[a-zA-Z0-9_.-]+$' -or !$lookup.ContainsKey($relative) -or
            !$manifest.ContainsKey("sha256.$relative") -or $lookup[$relative].Sha256 -ne $manifest["sha256.$relative"]) { throw 'A pinned TDLib manifest file is absent or does not match its SHA-256.' }
    }
    foreach ($file in $files) {
        if ($file.Relative -match '\.(exe|dll)$') { Assert-NngGramX64Pe $file.FullName }
    }
    [pscustomobject]@{ Root = $root; Files = @($files | Sort-Object Relative); TotalBytes = $total }
}

function Assert-NngGramX64Pe {
    param([Parameter(Mandatory)][string]$Path)
    $stream = [IO.File]::OpenRead($Path)
    $reader = [IO.BinaryReader]::new($stream)
    try {
        if ($stream.Length -lt 88 -or $reader.ReadUInt16() -ne 0x5a4d) { throw 'An executable or DLL is not a Windows PE image.' }
        $stream.Position = 0x3c
        $offset = $reader.ReadInt32()
        if ($offset -lt 64 -or $offset -gt $stream.Length - 26) { throw 'A Windows PE header is invalid.' }
        $stream.Position = $offset
        if ($reader.ReadUInt32() -ne 0x00004550 -or $reader.ReadUInt16() -ne 0x8664) { throw 'All packaged executable images must be Windows x64.' }
        $stream.Position = $offset + 24
        if ($reader.ReadUInt16() -ne 0x20b) { throw 'All packaged executable images must use PE32+.' }
    } finally { $reader.Dispose(); $stream.Dispose() }
}

function New-NngGramPayloadSource {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Payload, [Parameter(Mandatory)][string]$OutputFile)
    $document = [xml]'<?xml version="1.0" encoding="utf-8"?><Wix xmlns="http://schemas.microsoft.com/wix/2006/wi"><Fragment><DirectoryRef Id="INSTALLFOLDER" /></Fragment><Fragment><ComponentGroup Id="ApplicationPayload" /></Fragment></Wix>'
    $namespace = $document.DocumentElement.NamespaceURI
    $rootDirectory = $document.DocumentElement.FirstChild.FirstChild
    $group = $document.DocumentElement.LastChild.FirstChild
    $directories = @{ '' = $rootDirectory }
    function Add-Element($parent, [string]$name, [hashtable]$attributes) {
        $node = $document.CreateElement($name, $namespace)
        foreach ($key in $attributes.Keys) { $node.SetAttribute($key, [string]$attributes[$key]) }
        [void]$parent.AppendChild($node)
        return $node
    }
    function Stable-Hash([string]$value) {
        $algorithm = [Security.Cryptography.SHA256]::Create()
        try { return ([BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes('nngGram/payload/v1/' + $value.ToLowerInvariant())))).Replace('-', '') }
        finally { $algorithm.Dispose() }
    }
    $directoryOwners = @{}
    foreach ($file in $Payload.Files) {
        $parentPath = ''
        $parentNode = $rootDirectory
        $parts = $file.Relative.Split('/')
        for ($index = 0; $index -lt $parts.Length - 1; $index++) {
            $next = if ($parentPath) { $parentPath + '/' + $parts[$index] } else { $parts[$index] }
            if (!$directories.ContainsKey($next)) { $directories[$next] = Add-Element $parentNode 'Directory' @{ Id = 'D' + (Stable-Hash $next).Substring(0, 32); Name = $parts[$index] } }
            $parentPath = $next
            $parentNode = $directories[$next]
        }
        $hash = Stable-Hash $file.Relative
        $componentId = 'C' + $hash.Substring(0, 32)
        $guid = [Guid]::new($hash.Substring(0, 32)).ToString('B').ToUpperInvariant()
        $component = Add-Element $parentNode 'Component' @{ Id = $componentId; Guid = $guid; Win64 = 'yes' }
        $fileId = if ($file.Relative -eq 'nngGram.exe') { 'MainExecutable' } else { 'F' + $hash.Substring(0, 32) }
        [void](Add-Element $component 'File' @{ Id = $fileId; Source = $file.FullName; Name = $parts[-1] })
        [void](Add-Element $component 'RegistryValue' @{ Root = 'HKCU'; Key = 'Software\nngGram\Installer\Files';
            Name = $hash.Substring(0, 32); Type = 'integer'; Value = '1'; KeyPath = 'yes' })
        if (!$directoryOwners.ContainsKey($parentPath)) {
            $directoryOwners[$parentPath] = $true
            [void](Add-Element $component 'RemoveFolder' @{ Id = 'R' + $hash.Substring(0, 32); On = 'uninstall' })
        }
        [void](Add-Element $group 'ComponentRef' @{ Id = $componentId })
    }
    $settings = [Xml.XmlWriterSettings]::new()
    $settings.Indent = $true
    $settings.Encoding = [Text.UTF8Encoding]::new($false)
    $writer = [Xml.XmlWriter]::Create([IO.Path]::GetFullPath($OutputFile), $settings)
    try { $document.Save($writer) } finally { $writer.Dispose() }
}

Export-ModuleMember -Function Get-NngGramPayload, New-NngGramPayloadSource

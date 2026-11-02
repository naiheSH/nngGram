param([switch]$CompileSyntheticMsi, [switch]$SkipMsiValidation, [switch]$TestReparsePoint)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'InstallerPayload.psm1') -Force
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$caseName = 'installer-contracts-' + [Guid]::NewGuid().ToString('N')
$caseRoot = Join-Path $workspace ('build/' + $caseName)
New-Item -ItemType Directory -Path $caseRoot | Out-Null
$passed = [Collections.Generic.List[string]]::new()

function Assert-True([bool]$value, [string]$message) { if (!$value) { throw $message } }
function Assert-Rejected([scriptblock]$action, [string]$message) {
    $rejected = $false
    try { & $action | Out-Null } catch { $rejected = $true }
    Assert-True $rejected $message
}
function New-SyntheticPayload([string]$name) {
    $root = Join-Path $caseRoot $name
    New-Item -ItemType Directory -Path $root | Out-Null
    $files = @('nngGram.exe', 'tdjni.dll', 'bin/skiko-windows-x64.dll', 'bin/nucleus_tao.dll',
        'bin/libEGL.dll', 'bin/libGLESv2.dll', 'bin/jnidispatch.dll', 'media/ffmpeg.exe', 'media/ffprobe.exe',
        'tdlib.jar', 'TDLib-LICENSE.txt', 'media/FFmpeg-LICENSE.txt', 'licenses/synthetic/NOTICE.txt', 'extra/a & b.txt')
    foreach ($relative in $files) {
        $path = Join-Path $root $relative
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
        if ($relative -match '\.(dll|exe)$') {
            # A non-executable synthetic header only. The fixture is never started or installed.
            $bytes = [byte[]]::new(128)
            $bytes[0] = 0x4d; $bytes[1] = 0x5a; $bytes[0x3c] = 64
            $bytes[64] = 0x50; $bytes[65] = 0x45; $bytes[68] = 0x64; $bytes[69] = 0x86
            $bytes[88] = 0x0b; $bytes[89] = 0x02
            [IO.File]::WriteAllBytes($path, $bytes)
        } else { [IO.File]::WriteAllText($path, 'Synthetic installer contract; contains no application or account data.') }
    }
    $jarHash = (Get-FileHash -LiteralPath (Join-Path $root 'tdlib.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
    $jniHash = (Get-FileHash -LiteralPath (Join-Path $root 'tdjni.dll') -Algorithm SHA256).Hash.ToLowerInvariant()
    $pin = ConvertFrom-StringData (Get-Content -LiteralPath (Join-Path $workspace 'native/tdlib-source.properties') -Raw)
    [IO.File]::WriteAllLines((Join-Path $root 'manifest.properties'), @('format=1',
        "source.commit=$($pin.commit)", 'architecture=x64', 'binding=official-java-jni',
        'load.order=tdjni.dll', "sha256.tdlib.jar=$jarHash", "sha256.tdjni.dll=$jniHash"))
    return $root
}

$validRoot = New-SyntheticPayload 'valid'
$payload = Get-NngGramPayload $validRoot
Assert-True ($payload.Files.Count -eq 15) 'The full Native Image tree, including extra nested assets, must be retained.'
$sourceOne = Join-Path $caseRoot 'first.wxs'
$sourceTwo = Join-Path $caseRoot 'second.wxs'
New-NngGramPayloadSource $payload $sourceOne
New-NngGramPayloadSource $payload $sourceTwo
Assert-True ((Get-FileHash $sourceOne).Hash -eq (Get-FileHash $sourceTwo).Hash) 'Payload component identities and XML must be deterministic.'
[xml]$xml = Get-Content -LiteralPath $sourceOne -Raw
$namespace = [Xml.XmlNamespaceManager]::new($xml.NameTable)
$namespace.AddNamespace('w', 'http://schemas.microsoft.com/wix/2006/wi')
Assert-True ($xml.SelectNodes('//w:File', $namespace).Count -eq $payload.Files.Count) 'Every actual payload file must be represented exactly once.'
Assert-True ($xml.SelectNodes('//w:File[@Id="MainExecutable"]', $namespace).Count -eq 1) 'The explicit launch action must bind the real root main executable.'
Assert-True ($xml.SelectNodes('//w:RemoveFile', $namespace).Count -eq 0) 'Generated uninstall actions must not use wildcard file deletion.'
$passed.Add('complete-and-deterministic-payload')

$invalidRoot = New-SyntheticPayload 'missing-main'
Rename-Item -LiteralPath (Join-Path $invalidRoot 'nngGram.exe') -NewName 'renamed.exe'
Assert-Rejected { Get-NngGramPayload $invalidRoot } 'A folder without the actual main EXE must be rejected.'
$passed.Add('missing-executable-rejected')

$invalidRoot = New-SyntheticPayload 'mismatched-binding'
[IO.File]::AppendAllText((Join-Path $invalidRoot 'tdlib.jar'), 'changed')
Assert-Rejected { Get-NngGramPayload $invalidRoot } 'A mismatched pinned JNI binding must be rejected before any compiler runs.'
$passed.Add('binding-hash-mismatch-rejected')

$invalidRoot = New-SyntheticPayload 'x86-library'
$wrong = [IO.File]::ReadAllBytes((Join-Path $invalidRoot 'bin/libEGL.dll'))
$wrong[68] = 0x4c; $wrong[69] = 0x01
[IO.File]::WriteAllBytes((Join-Path $invalidRoot 'bin/libEGL.dll'), $wrong)
Assert-Rejected { Get-NngGramPayload $invalidRoot } 'A single foreign-architecture runtime library must be rejected.'
$passed.Add('mixed-architecture-rejected')

$invalidRoot = New-SyntheticPayload 'user-configuration'
[IO.File]::WriteAllText((Join-Path $invalidRoot 'tdlib.properties'), 'synthetic configuration must never ship')
Assert-Rejected { Get-NngGramPayload $invalidRoot } 'User configuration must not enter an installer payload.'
$passed.Add('user-configuration-rejected')

$invalidRoot = New-SyntheticPayload 'missing-license'
Rename-Item -LiteralPath (Join-Path $invalidRoot 'media/FFmpeg-LICENSE.txt') -NewName 'missing.txt'
Assert-Rejected { Get-NngGramPayload $invalidRoot } 'A runtime tool without its adjacent license must be rejected.'
$passed.Add('missing-runtime-license-rejected')

if ($TestReparsePoint) {
    $invalidRoot = New-SyntheticPayload 'reparse-child'
    New-Item -ItemType Junction -Path (Join-Path $invalidRoot 'linked-runtime') -Target $validRoot | Out-Null
    Assert-Rejected { Get-NngGramPayload $invalidRoot } 'A reparse child must be rejected before traversing its target.'
    $passed.Add('reparse-child-rejected-before-traversal')
}

[xml]$product = Get-Content -LiteralPath (Join-Path $workspace 'installer/windows/Product.wxs') -Raw
$productNamespace = [Xml.XmlNamespaceManager]::new($product.NameTable)
$productNamespace.AddNamespace('w', 'http://schemas.microsoft.com/wix/2006/wi')
Assert-True ($product.SelectSingleNode('/w:Wix/w:Product/w:Package', $productNamespace).InstallScope -eq 'perUser') 'Installation must remain per-user.'
Assert-True ($product.SelectSingleNode('/w:Wix/w:Product/w:MajorUpgrade', $productNamespace).Schedule -eq 'afterInstallInitialize') 'Major upgrades must support rollback and remove old program files before installing new ones.'
Assert-True ($product.SelectNodes('//w:RemoveFile', $productNamespace).Count -eq 0) 'Product uninstall must never recursively delete account data.'
Assert-True ($product.SelectNodes('//w:CustomAction', $productNamespace).Count -eq 2) 'Only the initial configuration helper and user-selected finished-page launch belong in this installer.'
Assert-True ($product.SelectSingleNode('//w:CustomAction[@Id="ConfigureInitialDataDirectory"]', $productNamespace).ExeCommand -eq '--configure-installation "[DATAFOLDER]." "[INSTALLFOLDER]."') 'The helper must receive exactly the selected unified data directory and install directory, with safe trailing-directory quoting.'
Assert-True ($product.SelectSingleNode('//w:CustomAction[@Id="ConfigureInitialDataDirectory"]', $productNamespace).Impersonate -eq 'yes') 'The helper must configure the installing user without elevation.'
Assert-True ($product.SelectSingleNode('//w:InstallExecuteSequence/w:Custom[@Action="ConfigureInitialDataDirectory"]', $productNamespace).InnerText.Contains('NOT NNGGRAM_EXISTING_CONFIG')) 'An upgrade must preserve existing local configuration.'
Assert-True ($product.SelectNodes('//w:Directory[@Id="DATAFOLDER"]//w:Component', $productNamespace).Count -eq 0) 'Data directory contents must never be owned by MSI uninstall.'
Assert-True ($product.SelectSingleNode('//w:Property[@Id="MSIRESTARTMANAGERCONTROL"]', $productNamespace).Value -eq 'Disable') 'An installer must not force shutdown of an active TDLib client.'
Assert-True ($product.SelectSingleNode('//w:Property[@Id="REBOOT"]', $productNamespace).Value -eq 'ReallySuppress') 'An installer must not automatically reboot the user machine.'
$passed.Add('owned-install-and-uninstall-boundaries')

$validationBuildName = $caseName + '-validate'
$validation = & (Join-Path $PSScriptRoot 'build-windows-installer.ps1') -AppDirectory $validRoot -BuildName $validationBuildName -ValidateOnly
Assert-True ($validation.Status -eq 'PAYLOAD_VALIDATED') 'Validation-only must finish without invoking an installer or compiler.'
Assert-True (!(Get-ChildItem -LiteralPath (Join-Path $workspace ('build/' + $validationBuildName)) -Filter '*.msi')) 'Validation-only must not generate an MSI.'
$passed.Add('validation-only-has-no-installer-side-effect')

# Test actual ZIP contents and source immutability with a complete synthetic PE/TDLib tree.
$packageRoot = New-SyntheticPayload 'release-source'
Copy-Item -LiteralPath (Join-Path $packageRoot 'nngGram.exe') -Destination (Join-Path $packageRoot 'media/ffplay.exe')
[IO.File]::WriteAllText((Join-Path $packageRoot 'nngGram.pdb'), 'Synthetic external debugging symbols.')
[IO.File]::WriteAllText((Join-Path $packageRoot 'bin/decoder.debug'), 'Synthetic external debugging symbols.')
[IO.File]::WriteAllText((Join-Path $packageRoot 'extra/unknown.lib'), 'Unknown files are retained; no unsafe whitelist shrinking.')
[IO.File]::WriteAllText((Join-Path $packageRoot 'manifest-owned.debug'), 'An explicitly pinned file may never be omitted.')
$protectedHash = (Get-FileHash -LiteralPath (Join-Path $packageRoot 'manifest-owned.debug')).Hash.ToLowerInvariant()
[IO.File]::AppendAllText((Join-Path $packageRoot 'manifest.properties'), "`nsha256.manifest-owned.debug=$protectedHash`n")
$packageBefore = Get-NngGramPayload $packageRoot
$packageName = $caseName + '-package'
$packaged = & (Join-Path $PSScriptRoot 'package-desktop.ps1') -AppDirectory $packageRoot -BuildName $packageName -Version '0.0.1' -SkipInstaller
$packageAfter = Get-NngGramPayload $packageRoot
$beforeKeys = @($packageBefore.Files | ForEach-Object { $_.Relative + '|' + $_.Sha256 })
$afterKeys = @($packageAfter.Files | ForEach-Object { $_.Relative + '|' + $_.Sha256 })
Assert-True (!(Compare-Object $beforeKeys $afterKeys)) 'Packaging must not mutate original Native Image inputs.'
$info = Get-Content -LiteralPath $packaged.BuildInfo -Raw | ConvertFrom-Json
Assert-True ($info.removedFiles.Count -eq 3) 'Only ffplay and external debug symbols may be removed.'
Assert-True ($info.sizes.sourceBytes - $info.sizes.stagedBytes -eq $info.sizes.removedBytes) 'Size savings must be measured in bytes from actual files.'
Assert-True ($info.artifacts.zip.sha256 -eq (Get-FileHash -LiteralPath $packaged.Zip).Hash.ToLowerInvariant()) 'Build information must report the actual ZIP SHA-256.'
Assert-True ($info.artifacts.zip.bytes -eq (Get-Item -LiteralPath $packaged.Zip).Length) 'Build information must report the actual compressed ZIP length.'
$extractRoot = Join-Path $caseRoot 'extracted'
[IO.Compression.ZipFile]::ExtractToDirectory($packaged.Zip, $extractRoot)
$extracted = Get-NngGramPayload (Join-Path $extractRoot 'nngGram')
$staged = Get-NngGramPayload (Join-Path ([IO.Path]::GetDirectoryName($packaged.BuildInfo)) $info.stagedAppDirectory)
Assert-True ($info.artifacts.zip.file -eq [IO.Path]::GetFileName($packaged.Zip)) 'Published artifact identities must be portable basenames, never runner-local absolute paths.'
Assert-True ($extracted.Files.Count -eq $staged.Files.Count) 'The ZIP must retain every staged runtime asset.'
foreach ($file in $extracted.Files) {
    $expected = $staged.Files | Where-Object Relative -EQ $file.Relative
    Assert-True ($file.Sha256 -eq $expected.Sha256) 'Every archived file must match the staged SHA-256.'
}
Assert-True (Test-Path -LiteralPath (Join-Path $extracted.Root 'extra/unknown.lib')) 'Unknown build-like extensions must be retained unless proven unnecessary.'
Assert-True (Test-Path -LiteralPath (Join-Path $extracted.Root 'manifest-owned.debug')) 'Every TDLib manifest-owned file must survive even if its extension matches debug symbols.'
Assert-True (!(Test-Path -LiteralPath (Join-Path $extracted.Root 'media/ffplay.exe'))) 'Unused ffplay must not enter the release archive.'
Assert-Rejected { & (Join-Path $PSScriptRoot 'package-desktop.ps1') -AppDirectory $packageRoot -BuildName $packageName -SkipInstaller } 'A release output directory must never be overwritten.'
$passed.Add('staged-only-size-reduction-zip-hashes-and-source-immutability')

$invalidRoot = New-SyntheticPayload 'empty-executable'
[IO.File]::WriteAllBytes((Join-Path $invalidRoot 'nngGram.exe'), [byte[]]::new(0))
Assert-Rejected { Get-NngGramPayload $invalidRoot } 'An empty application must never become an installer or archive.'
$passed.Add('empty-executable-rejected')

if ($CompileSyntheticMsi) {
    $buildName = $caseName + '-synthetic-msi'
    $compiled = & (Join-Path $PSScriptRoot 'build-windows-installer.ps1') -AppDirectory $validRoot -BuildName $buildName -ProductVersion '0.0.1' -SkipMsiValidation:$SkipMsiValidation
    Assert-True ($compiled.Status -eq 'MSI_COMPILED_NOT_INSTALLED') 'Synthetic package compilation must report its actual scope.'
    # Open only this synthetic MSI database read-only; never invoke an installation session.
    Add-Type -Path (Join-Path $workspace 'build/wix311/Microsoft.Deployment.WindowsInstaller.dll')
    $database = [Microsoft.Deployment.WindowsInstaller.Database]::new($compiled.Msi,
        [Microsoft.Deployment.WindowsInstaller.DatabaseOpenMode]::ReadOnly)
    try {
        $view = $database.OpenView('SELECT `Dialog` FROM `Dialog`')
        $dialogs = @{}
        try {
            $view.Execute()
            while ($record = $view.Fetch()) { try { $dialogs[$record.GetString(1)] = $true } finally { $record.Dispose() } }
        } finally { $view.Dispose() }
        foreach ($dialog in @('WelcomeDlg', 'InstallDirDlg', 'DataDirectoryDlg', 'ShortcutOptionsDlg', 'VerifyReadyDlg', 'ExitDialog', 'MaintenanceTypeDlg')) {
            Assert-True ($dialogs.ContainsKey($dialog)) "The compiled database must contain its actual $dialog UI."
        }
        $view = $database.OpenView('SELECT `Dialog_`, `Control` FROM `Control`')
        $controls = @{}
        try {
            $view.Execute()
            while ($record = $view.Fetch()) {
                try { $controls[$record.GetString(1) + '/' + $record.GetString(2)] = $true } finally { $record.Dispose() }
            }
        } finally { $view.Dispose() }
        $view = $database.OpenView('SELECT `Dialog_`, `Control_` FROM `ControlEvent`')
        try {
            $view.Execute()
            while ($record = $view.Fetch()) {
                try { Assert-True ($controls.ContainsKey($record.GetString(1) + '/' + $record.GetString(2))) 'Every compiled navigation event must reference an actual control.' }
                finally { $record.Dispose() }
            }
        } finally { $view.Dispose() }
        $view = $database.OpenView('SELECT `Action`, `Sequence` FROM `InstallExecuteSequence`')
        $sequence = @{}
        try {
            $view.Execute()
            while ($record = $view.Fetch()) { try { $sequence[$record.GetString(1)] = $record.GetInteger(2) } finally { $record.Dispose() } }
        } finally { $view.Dispose() }
        Assert-True ($sequence['RemoveExistingProducts'] -gt $sequence['InstallInitialize'] -and
            $sequence['RemoveExistingProducts'] -lt $sequence['ProcessComponents']) 'The actual compiled upgrade removal must stay inside the rollback transaction before installing new components.'
    } finally { $database.Dispose() }
    $passed.Add($(if ($SkipMsiValidation) { 'synthetic-wix-compilation-ice-skipped-not-installed' } else { 'synthetic-wix-compilation-ice-passed-not-installed' }))
}
$result = [pscustomobject]@{ Status = 'INSTALLER_CONTRACTS_PASSED'; Count = $passed.Count; Cases = @($passed);
    SyntheticOnly = $true; RealInstallerStarted = $false; IceValidationSkipped = [bool]$SkipMsiValidation;
    ReparsePointFixtureRequested = [bool]$TestReparsePoint; Root = $caseRoot }
$result | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $caseRoot 'result.json') -Encoding utf8
$result

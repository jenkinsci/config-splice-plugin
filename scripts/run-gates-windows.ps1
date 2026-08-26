#Requires -Version 5.1
<#
.SYNOPSIS
    Runs the full test suite and collects decision-gate evidence on Windows.

.DESCRIPTION
    The counterpart to run-gates-linux.sh. CONTRIBUTING.md asks contributors who touch file handling
    to build on both platforms, because the two genuinely differ: Files.isSymbolicLink() returns
    false for a directory junction, and an open handle blocks replacement on Windows but not on
    Linux. Both differences were found by running on both, and either would otherwise have shipped.

    Nothing here needs administrator rights. See docs/adr/ADR-002 and ADR-004 for what the two
    platforms are expected to disagree about.

.EXAMPLE
    .\scripts\run-gates-windows.ps1

.EXAMPLE
    $env:CONFIG_SPLICE_MAVEN_GOAL = 'clean test'; .\scripts\run-gates-windows.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is an order of magnitude slower without this.

$RepoRoot     = Split-Path -Parent $PSScriptRoot
$ToolsDir     = Join-Path $RepoRoot '.tools'
$MavenVersion = '3.9.16'                   # Keep in step with run-gates-linux.sh.
$MavenHome    = Join-Path $ToolsDir "apache-maven-$MavenVersion"

function Write-Step { param([string]$Message) Write-Host "`n==> $Message" -ForegroundColor White }
function Write-Warn { param([string]$Message) Write-Host "`nWARNING: $Message" -ForegroundColor Yellow }
function Stop-WithError {
    param([string]$Message)
    Write-Host "`nERROR: $Message" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
# Java
#
# The build targets Java 17 because Jenkins 2.541.x is the newest LTS line that still supports it
# (2.555+ requires Java 21). Building on a newer JDK is fine and the release target stays 17, but
# stay on a JDK that CI also exercises -- ci.jenkins.io runs 21 and 25, so a defect that only shows
# up on, say, 23 would be yours alone to discover.
# ---------------------------------------------------------------------------
Write-Step 'Checking Java'

function Get-JdkMajor {
    param([string]$JdkHome)
    # Prefer the release file: reading it avoids `java -version`, which writes to stderr and which
    # PowerShell 5.1 then wraps in ErrorRecords that trip $ErrorActionPreference.
    $releaseFile = Join-Path $JdkHome 'release'
    if (Test-Path $releaseFile) {
        $match = Select-String -Path $releaseFile -Pattern '^JAVA_VERSION="([0-9]+)' -ErrorAction SilentlyContinue
        if ($match) { return [int]$match.Matches[0].Groups[1].Value }
    }
    return 0
}

# Collect every usable JDK rather than taking the first hit. A developer machine commonly has
# several, and JAVA_HOME often points at whichever was installed last -- which is exactly the JDK
# least likely to match CI.
$searchPatterns = @(
    'C:\Program Files\Java\jdk-*'
    'C:\Program Files\Eclipse Adoptium\jdk-*'
    'C:\Program Files\Microsoft\jdk-*'
    'C:\Program Files\Amazon Corretto\jdk*'
    "$env:USERPROFILE\.jdks\*"
)

$discovered = @()
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $discovered += $env:JAVA_HOME
}
foreach ($pattern in $searchPatterns) {
    Get-Item -Path $pattern -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
        ForEach-Object { $discovered += $_.FullName }
}

$usable = @()
foreach ($jdkPath in ($discovered | Select-Object -Unique)) {
    $major = Get-JdkMajor $jdkPath
    if ($major -ge 17) {
        $usable += [pscustomobject]@{ Home = $jdkPath; Major = $major }
    }
}

if ($usable.Count -eq 0) {
    Stop-WithError @'
No JDK 17 or newer found. Install one, for example:
    winget install EclipseAdoptium.Temurin.17.JDK
Then re-run, or set JAVA_HOME explicitly.
'@
}

# Prefer a JDK that ci.jenkins.io also runs, 17 first, so that a failure here means something. On a
# JDK outside the matrix an unexplained failure could as easily be the JDK as the plugin, which is
# the least useful position to debug from.
$javaHome = $null
$javaMajor = 0
foreach ($preferred in @(17, 21, 25)) {
    $hit = $usable | Where-Object { $_.Major -eq $preferred } | Select-Object -First 1
    if ($hit) { $javaHome = $hit.Home; $javaMajor = $hit.Major; break }
}
if (-not $javaHome) {
    $javaHome = $usable[0].Home
    $javaMajor = $usable[0].Major
    Write-Warn "Java $javaMajor is outside the ci.jenkins.io matrix (17, 21, 25) and no matrix JDK was
         found. The build may well succeed, but a failure is not necessarily a defect in the plugin."
}

if ($env:JAVA_HOME -and ($env:JAVA_HOME -ne $javaHome)) {
    Write-Host "Overriding JAVA_HOME for this build only (was $env:JAVA_HOME)."
}
$env:JAVA_HOME = $javaHome
Write-Host "JAVA_HOME=$javaHome (Java $javaMajor)"

# ---------------------------------------------------------------------------
# Maven 3.9.6+
#
# Older Maven fails this build outright with "Unknown packaging: hpi". A suitable copy is fetched
# into .tools/ rather than installed, so running this script changes nothing outside the checkout.
# ---------------------------------------------------------------------------
Write-Step 'Checking Maven'
$mvn = $null

$systemMvn = Get-Command mvn -ErrorAction SilentlyContinue
if ($systemMvn) {
    # `mvn -v` writes to stdout, so this needs no stderr redirection.
    $versionText = (& $systemMvn.Source '-v' | Out-String)
    if ($versionText -match 'Apache Maven ((\d+)\.(\d+)[^\s]*)') {
        $full  = $Matches[1]
        $major = [int]$Matches[2]
        $minor = [int]$Matches[3]
        if (($major -gt 3) -or ($major -eq 3 -and $minor -ge 9)) {
            $mvn = $systemMvn.Source
            Write-Host "Using system Maven $full"
        } else {
            Write-Host "System Maven $full is too old (need 3.9.6+); fetching a local copy."
        }
    }
}

if (-not $mvn) {
    $localMvn = Join-Path $MavenHome 'bin\mvn.cmd'
    if (-not (Test-Path $localMvn)) {
        New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
        $zipPath = Join-Path $ToolsDir 'maven.zip'
        # dlcdn carries only current releases; archive keeps every version, so it is the fallback.
        $urls = @(
            "https://dlcdn.apache.org/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
            "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
        )
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $downloaded = $false
        foreach ($url in $urls) {
            Write-Host "Downloading $url"
            try {
                Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
                $downloaded = $true
                break
            } catch {
                Write-Warn "Download failed: $($_.Exception.Message)"
            }
        }
        if (-not $downloaded) { Stop-WithError 'Could not download Maven. Check network or proxy settings.' }

        Expand-Archive -Path $zipPath -DestinationPath $ToolsDir -Force
        Remove-Item $zipPath -Force
    }
    $mvn = $localMvn
}
& $mvn '-v' | Select-Object -First 1

# ---------------------------------------------------------------------------
# Windows-specific conditions that change what the evidence means
# ---------------------------------------------------------------------------
Write-Step 'Checking Windows conditions'

$isElevated = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)

$devMode = $false
try {
    $unlock = Get-ItemProperty -Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\AppModelUnlock' -ErrorAction Stop
    $devMode = ($unlock.AllowDevelopmentWithoutDevLicense -eq 1)
} catch { }

if ($isElevated -or $devMode) {
    Write-Host 'Symbolic links can be created: gate 3 symlink probes should be conclusive.'
} else {
    Write-Warn @'
Not elevated and Developer Mode is off, so this session cannot create symbolic links. The gate 3
         symlink probes will report INCONCLUSIVE. That is a recorded outcome rather than a failure --
         the junction probes, which are the ones Windows actually needs, still run. Enable Developer
         Mode (Settings > System > For developers) to close the gap.
'@
}

try {
    $longPaths = (Get-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem' -ErrorAction Stop).LongPathsEnabled
    if ($longPaths -ne 1) {
        Write-Warn 'LongPathsEnabled is off. The Jenkins test harness creates deep temporary paths;
         if the build fails with unexplained "path too long" or "file not found" errors, this is why.'
    }
} catch { }

# A JVM left over from an interrupted run keeps a handle on target\, and maven-clean then fails to
# delete it. The failure names the file rather than the cause, so it is worth flagging up front.
if ((Test-Path (Join-Path $RepoRoot 'target')) -and (Get-Process java -ErrorAction SilentlyContinue)) {
    Write-Warn 'java.exe is running and target\ exists. If this build fails during clean, an orphaned
         test JVM is probably holding a file open -- close it and re-run.'
}

# ---------------------------------------------------------------------------
# Build
#
# The first run downloads the Jenkins parent POM, the plugin BOM and the test harness (including a
# Jenkins war for the gate 2 agent test), so it needs network access and takes a few minutes.
#
# 'clean' is deliberate: copying sources between machines often preserves timestamps, which makes
# Maven decide "nothing to compile" and silently run stale classes -- a green build for code you no
# longer have. 'verify' rather than 'test' because SpotBugs, the parent's static checks and HPI
# packaging all bind to verify, and each has caught something 'mvn test' reported as green.
# ---------------------------------------------------------------------------
Write-Step 'Running the build'
Set-Location $RepoRoot

$goal = $env:CONFIG_SPLICE_MAVEN_GOAL
if (-not $goal) { $goal = 'clean verify' }
Write-Host "Running: mvn $goal"

$global:LASTEXITCODE = 0   # Set-StrictMode makes an unset $LASTEXITCODE a hard error to read.
& $mvn '-B' '-ntp' ([string[]]($goal -split '\s+'))
$buildStatus = $LASTEXITCODE

# ---------------------------------------------------------------------------
# Evidence
# ---------------------------------------------------------------------------
Write-Step 'Decision-gate evidence'
$evidenceDir = Join-Path $RepoRoot 'target\gate-evidence'
if (Test-Path $evidenceDir) {
    Get-ChildItem -Path $evidenceDir -Filter '*.txt' | ForEach-Object {
        Write-Host ''
        Get-Content $_.FullName | Write-Host
    }
    Write-Host ''
    Write-Host "Evidence files are in $evidenceDir"
    Write-Host 'Compare these against the Linux results from scripts/run-gates-linux.sh.'
} else {
    Write-Host 'No evidence produced - the build likely failed before the gate tests ran.'
}

Write-Step 'What to look for on Windows'
Write-Host @'
  Gate 3 (workspace confinement)
    - junction probes should be CONCLUSIVE; junctions need no elevation, unlike symbolic links
    - "JDK view of a junction" should record isSymbolicLink = NO. That is the whole reason the guard
      resolves the real path instead of trusting file attributes
    - symlink probes may be INCONCLUSIVE without Developer Mode; that is recorded, not a failure
    - every escape attempt must still be refused with WORKSPACE_ESCAPE

  Gate 4 (atomic replacement)
    - "POSIX permission preservation" should say "not applicable on this platform (no posix view)"
    - "permissions explicitly transferred" should say no: the replacement inherits the directory ACL
    - the open-handle probe should report the replacement BLOCKED, with the original bytes intact,
      and the retry should engage. This is the case Linux cannot show you: POSIX rename() over an
      open file simply succeeds
    - the retry budget must stay bounded whatever the outcome
'@

exit $buildStatus

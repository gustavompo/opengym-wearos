<#
.SYNOPSIS
  Drive the Wear OS emulator dev loop from the command line.

.DESCRIPTION
  Wraps the environment fixes this machine needs and the adb primitives that
  actually work for inspecting and interacting with a running Wear app:
  uiautomator text dumps (ground truth), touch injection, rotary injection,
  screenshots via screencap, and instrumented Compose tests.

.EXAMPLE
  .\scripts\wear.ps1 -Command boot
  .\scripts\wear.ps1 -Command loop          # build + install + launch + dump
  .\scripts\wear.ps1 -Command dump
  .\scripts\wear.ps1 -Command tap -X 227 -Y 256
  .\scripts\wear.ps1 -Command rotary -Rotary -1
  .\scripts\wear.ps1 -Command test

  # Two wear emulators booted at once (two agents, one each). Without -Serial the script picks the
  # first serial adb lists and may drive the other agent's device.
  .\scripts\wear.ps1 -Serial emulator-5556 -Command dump
  .\scripts\wear.ps1 -Serial emulator-5556 -Command launch -StartArgs '--es transport fake'
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('boot', 'build', 'install', 'launch', 'loop', 'dump', 'tap', 'rotary', 'swipe', 'key', 'shot', 'test', 'logs', 'wait')]
  [string]$Command,

  [string]$Avd = 'WearHello_API34',
  # The device to drive. Empty = the first `emulator-\d+` adb lists, which is ambiguous whenever a
  # second wear AVD is booted. Set it explicitly and every adb call below targets that serial and
  # nothing else.
  [string]$Serial = '',
  [string]$Package = 'ch.duartesantos.opengym.test',   # debug and benchmark carry the .test suffix; release does not
  # namespace != applicationId here, so the component name is not the package name + ".MainActivity"
  [string]$Activity = 'ch.duartesantos.opengym.wear.MainActivity',
  [int]$X = 0,
  [int]$Y = 0,
  [int]$X2 = 0,
  [int]$Y2 = 0,
  [int]$Rotary = 1,
  [string]$Key = 'KEYCODE_BACK',
  [string]$Out = 'build/weartest',
  [string]$Match = 'Exercise page placeholder',
  # Extra `am start` arguments for -Command launch/-Command loop, e.g. '--es transport fake'.
  [string]$StartArgs = '',
  [int]$TimeoutSec = 90
)

$ErrorActionPreference = 'Stop'

# --- environment fixes -------------------------------------------------------
# AGP 9 aborts if it sees two preference locations; Android Studio sets both.
if (Test-Path Env:ANDROID_PREFS_ROOT) { Remove-Item Env:ANDROID_PREFS_ROOT }
if (-not $env:JAVA_HOME) {
  $jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path "$($_.FullName)\bin\java.exe" } | Select-Object -First 1
  if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$emu = "$sdk\emulator\emulator.exe"
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

function Invoke-Adb { & $adb @args 2>&1 }

# The device this script is allowed to touch: -Serial when given, else the only running emulator.
# Every adb call goes through Invoke-AdbOnDevice so the target is decided exactly once.
function Get-Device {
  if ($Serial) {
    $known = (& $adb devices) | Select-String "^$([regex]::Escape($Serial))\s+device"
    if (-not $known) { throw "Serial '$Serial' is not connected. See: & $adb devices" }
    return $Serial
  }
  $serial = (& $adb devices) | Select-String '^emulator-\d+\s+device' | ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1
  if (-not $serial) { throw "No running emulator. Run: $PSCommandPath -Command boot" }
  return $serial
}

function Invoke-AdbOnDevice { & $adb '-s' (Get-Device) @args 2>&1 }

function Get-StartArgs {
  if (-not $StartArgs) { return @() }
  return $StartArgs.Split(' ', [StringSplitOptions]::RemoveEmptyEntries)
}

function Wait-Boot {
  Write-Host "waiting for device $(Get-Device)..."
  & $adb '-s' (Get-Device) wait-for-device | Out-Null
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    if ((Invoke-AdbOnDevice shell getprop sys.boot_completed) -match '1') {
      Write-Host 'boot_completed=1'
      return
    }
    Start-Sleep -Seconds 2
  }
  throw "Timed out after ${TimeoutSec}s waiting for boot_completed"
}

# Raw text dump: ground truth for labels + exact element bounds.
function Get-UiDump {
  Invoke-AdbOnDevice shell uiautomator dump /sdcard/ui.xml | Out-Null
  $xml = (Invoke-AdbOnDevice shell cat /sdcard/ui.xml) -join "`n"
  return $xml
}

function Show-UiText {
  $xml = Get-UiDump
  $texts = [regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
  if (-not $texts) { Write-Host '(no text nodes)' ; return }
  Write-Host ($texts -join '  |  ')
}

function Show-UiNodes {
  $xml = Get-UiDump
  foreach ($m in [regex]::Matches($xml, '<node([^>]*?)/?>')) {
    $a = $m.Groups[1].Value
    $get = { param($k) if ($a -match "$k=`"([^`"]*)`"") { $Matches[1] } else { '' } }
    $t = & $get 'text'; $c = & $get 'content-desc'; $b = & $get 'bounds'
    $cl = & $get 'class'; $ck = & $get 'clickable'
    if ($t -or $c -or $ck -eq 'true') {
      '{0,-44} text={1,-22} desc={2,-18} clickable={3,-5} {4}' -f $cl, "'$t'", "'$c'", $ck, $b
    }
  }
}

function Wait-Content {
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    $xml = Get-UiDump
    if ($xml -match [regex]::Escape($Match)) { Write-Host "content matched '$Match'"; return }
    Start-Sleep -Seconds 1
  }
  throw "Timed out waiting for content matching '$Match'"
}

switch ($Command) {
  'boot' {
    $pattern = if ($Serial) { "^$([regex]::Escape($Serial))\s+device" } else { '^emulator-\d+\s+device' }
    $running = (& $adb devices) | Select-String $pattern
    if ($running) { Write-Host "already running: $($running.Line.Trim())" ; break }
    if ($Serial) { throw "Serial '$Serial' is not running; start that AVD yourself or use -Serial only for a device that is up." }
    Start-Process -FilePath $emu -ArgumentList '-avd', $Avd, '-no-boot-anim' | Out-Null
    Wait-Boot
    Write-Host "window: $(Get-Process qemu-system-x86_64 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty MainWindowTitle)"
  }
  'build'   { & .\gradlew.bat :app:assembleDebug --console=plain }
  'install' { & .\gradlew.bat :app:installDebug --console=plain }
  'launch'  {
    Invoke-AdbOnDevice shell am start -n "$Package/$Activity" @(Get-StartArgs) | Out-Null
    Start-Sleep -Seconds 2
  }
  'loop' {
    & .\gradlew.bat :app:installDebug --console=plain | Select-String 'BUILD|Installed|error'
    Invoke-AdbOnDevice shell am force-stop $Package | Out-Null
    Invoke-AdbOnDevice shell am start -n "$Package/$Activity" @(Get-StartArgs) | Out-Null
    Wait-Content
    Show-UiText
  }
  'dump'   { Show-UiText; Write-Host '--- nodes ---'; Show-UiNodes }
  'tap'    { Invoke-AdbOnDevice shell input tap $X $Y | Out-Null; Start-Sleep -Milliseconds 500; Show-UiText }
  'swipe'  { Invoke-AdbOnDevice shell input swipe $X $Y $X2 $Y2 300 | Out-Null; Start-Sleep -Milliseconds 800; Show-UiText }
  # Wear rotary crown. Negative steps down the list, positive steps back up.
  'rotary' { Invoke-AdbOnDevice shell input rotaryencoder scroll --axis SCROLL,$Rotary | Out-Null; Start-Sleep -Milliseconds 800; Show-UiText }
  'key'    { Invoke-AdbOnDevice shell input keyevent $Key | Out-Null; Start-Sleep -Seconds 1; Show-UiText }
  'shot'   {
    New-Item -ItemType Directory -Force -Path $Out | Out-Null
    $file = Join-Path $Out "shot_$((Get-Date).ToString('HHmmss')).png"
    & $adb '-s' (Get-Device) exec-out screencap -p > $file
    Write-Host "wrote $file  (delegate to the 'vision' subagent to read it)"
  }
  'test' {
    & .\gradlew.bat :app:connectedDebugAndroidTest --console=plain |
      Select-String 'Starting \d+ tests|Finished|BUILD|FAILED|error:'
    $xml = Get-ChildItem app/build/outputs/androidTest-results -Recurse -Filter '*.xml' -ErrorAction SilentlyContinue |
      Sort-Object LastWriteTime | Select-Object -Last 1
    if ($xml) { (Select-String -Path $xml.FullName -Pattern '<testsuite [^>]*').Matches.Value }
  }
  'logs'   { Invoke-AdbOnDevice logcat -d -t 200 }
  'wait'   { Wait-Content }
}

# Find the wireless-debugging *connect* port of a device on the LAN when only the
# pairing port is known. The pairing dialog's port is ephemeral and stops listening
# once paired; the connect port is a different ephemeral port in the same range.
#
#   pwsh -File android/scripts/scan-adb-port.ps1 -TargetHost 192.168.86.21
#   pwsh -File android/scripts/scan-adb-port.ps1 -TargetHost 192.168.86.21 -First 45500 -Last 45600
#
# Linux (and therefore Android) picks ephemeral ports from 32768-60999, so that is the
# default search window. A full scan is ~45 s; a narrow window is seconds. Exits 0 with
# the port(s) if one was found, 1 if not.
#
# ---------------------------------------------------------------------------
# 2026-10-11 - two defects fixed. Both made this script look like "the device is not
# reachable", which is exactly the wrong conclusion on this rig: the watch's port had
# simply re-rolled. Do not reintroduce either.
#
#   1. The hit test was `$task.IsCompletedSuccessfully`. That property exists on
#      Task in .NET 5+ only, and **not** in Windows PowerShell 5.1 - which is what
#      `powershell.exe`, the default shell on this box, runs. PowerShell answers a
#      missing property with $null instead of raising, so every open port was silently
#      dropped and the script always printed "no open ports found".
#      Measured, not guessed: against a KNOWN-OPEN port (192.168.86.21:45553, while
#      adb was connected to it) this script found nothing, a bare
#      `[Net.Sockets.TcpClient]::new().ConnectAsync(...)` to that same port reported
#      Connected=True, and under 5.1 `[Task].GetProperty('IsCompletedSuccessfully')`
#      was $null. The hit test is now `$client.Connected`, which behaves the same on
#      both hosts.
#
#   2. It waited on each task in the chunk **one at a time** - `$task.Wait($TimeoutMs)`
#      per port - so a chunk of 400 "concurrent" connects cost up to 400 x 600 ms
#      instead of 600 ms, i.e. roughly two hours for the ephemeral range. It now waits
#      on the whole chunk once, which is the entire point of the concurrency.
#
#   (And one trap of its own, met while fixing the above: PowerShell variable names are
#   case-INSENSITIVE, so a chunked loop written with `$first`/`$last` silently rewrites the
#   `$First`/`$Last` parameters it is bounded by - the scan then reported
#   "no open ports found in 33168-33167" after covering one chunk. The loop variables are
#   therefore named `$chunkStart`/`$chunkEnd`, and the window is reported from copies made
#   before the loop.)
# ---------------------------------------------------------------------------
param(
  [Parameter(Mandatory = $true)][string]$TargetHost,
  [int]$First = 32768,
  [int]$Last = 60999,
  [int]$Concurrency = 400,
  [int]$TimeoutMs = 600,
  [switch]$Quiet
)

$ErrorActionPreference = 'Continue'
$hits = [System.Collections.Generic.List[int]]::new()
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$windowFirst = $First      # PowerShell is case-insensitive; never loop on $first/$last here
$windowLast = $Last

for ($chunkStart = $windowFirst; $chunkStart -le $windowLast; $chunkStart += $Concurrency) {
  $chunkEnd = [Math]::Min($chunkStart + $Concurrency - 1, $windowLast)

  $clients = [System.Collections.Generic.List[System.Net.Sockets.TcpClient]]::new()
  $tasks   = [System.Collections.Generic.List[System.Threading.Tasks.Task]]::new()
  foreach ($port in $chunkStart..$chunkEnd) {
    $client = [System.Net.Sockets.TcpClient]::new()
    $tasks.Add($client.ConnectAsync($TargetHost, $port))
    $clients.Add($client)
  }

  # Waits for the whole chunk (or the timeout, whichever comes first) and throws if any
  # connect was refused - which is the normal case and is not an error here. The result
  # of each connect is read from $client.Connected, never from a task property.
  try { [void][System.Threading.Tasks.Task]::WaitAll($tasks.ToArray(), $TimeoutMs) } catch { }

  $port = $chunkStart
  foreach ($client in $clients) {
    if ($client.Connected) { $hits.Add($port) }
    $client.Dispose()
    $port++
  }

  if (-not $Quiet) {
    Write-Progress -Activity "scanning $TargetHost" -Status "through port $chunkEnd" `
      -PercentComplete (100.0 * ($chunkEnd - $windowFirst) / [Math]::Max(1, $windowLast - $windowFirst))
  }
}
$sw.Stop()

if (-not $Quiet) { Write-Host "scanned $windowFirst-$windowLast in $([int]$sw.Elapsed.TotalSeconds)s" }
if ($hits.Count -eq 0) {
  Write-Host "no open ports found on $TargetHost in $windowFirst-$windowLast"
  exit 1
}
$hits | Sort-Object | ForEach-Object {
  Write-Host "OPEN $TargetHost`:$_"
  Write-Host "     adb connect $TargetHost`:$_"
}
exit 0

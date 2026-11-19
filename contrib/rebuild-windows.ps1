# Install the local voice gateway and restart its installed Windows scheduled task.
#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($args.Count -ne 0) { throw 'This script takes no arguments.' }

$installDir = Join-Path $env:LOCALAPPDATA 'voice-gateway'
$exe = Join-Path $installDir 'voice-gateway.exe'
$logFile = Join-Path $installDir 'voice-gateway.log'
$taskName = 'GoModeVoiceGateway'
$task = Get-ScheduledTask -TaskName $taskName -TaskPath '\'
if (@($task.Actions).Count -ne 1 -or $task.Actions[0].Execute -ne $exe) {
    throw "Scheduled task '$taskName' does not run '$exe'. Install the gateway first."
}

$goBin = & go env GOBIN
if ($LASTEXITCODE -ne 0) { throw 'Could not determine GOBIN.' }
if (-not $goBin) {
    $goPath = & go env GOPATH
    if ($LASTEXITCODE -ne 0) { throw 'Could not determine GOPATH.' }
    $goBin = Join-Path ($goPath -split ';')[0] 'bin'
}
$installedExe = Join-Path $goBin 'voice-gateway.exe'

Disable-ScheduledTask -TaskName $taskName -TaskPath '\' | Out-Null
try {
    $gateway = Get-Process -Name 'voice-gateway' -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe } | Select-Object -First 1
    if ($null -ne $gateway) {
        $null = $gateway.Handle
        $stop = $null
        if ([Threading.EventWaitHandle]::TryOpenExisting("Local\GoModeVoiceGateway-$($gateway.Id)", [ref]$stop)) {
            $null = $stop.Set()
            $stop.Dispose()
        }
        if (-not $gateway.WaitForExit(10000)) {
            Stop-ScheduledTask -TaskName $taskName -TaskPath '\'
            if (-not $gateway.HasExited) { Stop-Process -InputObject $gateway -Force }
        }
        if (-not $gateway.WaitForExit(30000)) { throw "Timed out stopping '$exe' (PID $($gateway.Id))." }
    }
    & go -C (Join-Path $PSScriptRoot '..') install -ldflags='-H=windowsgui' ./cmd/voice-gateway
    if ($LASTEXITCODE -ne 0) { throw "Go install failed with exit code $LASTEXITCODE." }
    if ($installedExe -ne $exe) {
        # Windows cannot overwrite an image that is still mapped, for example by
        # a scanner after the gateway exits, but can rename it. The gateway's
        # autoupdate startup removes this reserved sibling; see
        # autoupdate/platform_windows.go.
        $old = "$exe.autoupdate-old"
        $staged = Join-Path $installDir ('.voice-gateway-' + [Guid]::NewGuid().ToString('N') + '.exe')
        Copy-Item -LiteralPath $installedExe -Destination $staged
        try {
            if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Force }
            Move-Item -LiteralPath $exe -Destination $old
            try {
                Move-Item -LiteralPath $staged -Destination $exe
            } catch {
                Move-Item -LiteralPath $old -Destination $exe
                throw
            }
        } finally {
            if (Test-Path -LiteralPath $staged) { Remove-Item -LiteralPath $staged -Force }
        }
    }
} finally {
    # Restart the task even when stopping, building, or copying fails, so a
    # failed rebuild keeps the previous executable running instead of leaving
    # the task disabled.
    Enable-ScheduledTask -TaskName $taskName -TaskPath '\' | Out-Null
    Start-ScheduledTask -TaskName $taskName -TaskPath '\'
}
Write-Host "Installed $exe and requested start of scheduled task $taskName."
Write-Host "Investigate logs: Get-Content -LiteralPath '$($logFile.Replace("'", "''"))' -Tail 100 -Wait"

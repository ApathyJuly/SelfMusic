[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'restart', 'status')]
    [string]$Action = 'start',
    [switch]$KeepEmulator
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = $PSScriptRoot
$SdkRoot = Join-Path $ProjectRoot '.android-sdk'
$Adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
$Emulator = Join-Path $SdkRoot 'emulator\emulator.exe'
$Gradle = Join-Path $ProjectRoot 'gradlew.bat'
$AvdName = 'SelfMusic_API_35'
$PackageName = 'com.selfmusic'
$MainActivity = "$PackageName/.MainActivity"

function Set-AndroidEnvironment {
    $env:ANDROID_HOME = $SdkRoot
    $env:ANDROID_SDK_ROOT = $SdkRoot
    $env:ANDROID_AVD_HOME = Join-Path $ProjectRoot '.android-avd'
}

function Assert-ProjectTools {
    foreach ($Path in @($Adb, $Emulator, $Gradle)) {
        if (-not (Test-Path -LiteralPath $Path)) {
            throw "Required tool is missing: $Path"
        }
    }
}

function Invoke-Adb {
    & $Adb @args
    if ($LASTEXITCODE -ne 0) {
        throw "ADB command failed: adb $($args -join ' ')"
    }
}

function Get-EmulatorSerial {
    $deviceLines = & $Adb devices 2>$null
    foreach ($line in $deviceLines) {
        if ($line -match '^(emulator-\d+)\s+device$') {
            return $Matches[1]
        }
    }
    return $null
}

function Wait-ForEmulator {
    param([int]$TimeoutSeconds = 180)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    Write-Host 'Waiting for the Android emulator' -NoNewline
    while ((Get-Date) -lt $deadline) {
        $serial = Get-EmulatorSerial
        if ($serial) {
            $booted = (& $Adb -s $serial shell getprop sys.boot_completed 2>$null).Trim()
            if ($booted -eq '1') {
                Write-Host ' ready'
                return $serial
            }
        }
        Write-Host '.' -NoNewline
        Start-Sleep -Seconds 2
    }
    Write-Host
    throw "The emulator did not finish booting within $TimeoutSeconds seconds."
}

function Start-SelfMusic {
    $serial = Get-EmulatorSerial
    if (-not $serial) {
        Write-Host "Starting Android emulator $AvdName ..."
        Start-Process -FilePath $Emulator -ArgumentList @('-avd', $AvdName) -WorkingDirectory $ProjectRoot | Out-Null
        $serial = Wait-ForEmulator
    } else {
        Write-Host "Using the running emulator $serial"
    }

    Write-Host 'Building and installing the Debug app...'
    & $Gradle --no-daemon installDebug
    if ($LASTEXITCODE -ne 0) {
        throw 'Gradle build or installation failed.'
    }

    Write-Host 'Starting SelfMusic...'
    Invoke-Adb -s $serial shell am force-stop $PackageName
    Invoke-Adb -s $serial shell am start -n $MainActivity
    Write-Host "SelfMusic is running on $serial." -ForegroundColor Green
}

function Stop-SelfMusic {
    $serial = Get-EmulatorSerial
    if (-not $serial) {
        Write-Host 'The SelfMusic emulator is not running.'
        return
    }

    Invoke-Adb -s $serial shell am force-stop $PackageName
    Write-Host 'SelfMusic has stopped.'
    if (-not $KeepEmulator) {
        Invoke-Adb -s $serial emu kill
        Write-Host 'The Android emulator has stopped.' -ForegroundColor Green
    }
}

function Show-Status {
    $serial = Get-EmulatorSerial
    if (-not $serial) {
        Write-Host 'Status: emulator and SelfMusic are stopped.'
        return
    }

    $pidText = (& $Adb -s $serial shell pidof $PackageName 2>$null).Trim()
    if ($pidText) {
        Write-Host "Status: SelfMusic is running on ${serial}; PID: $pidText." -ForegroundColor Green
    } else {
        Write-Host "Status: emulator $serial is running; SelfMusic is stopped."
    }
}

Set-Location -LiteralPath $ProjectRoot
Set-AndroidEnvironment
Assert-ProjectTools

switch ($Action) {
    'start'   { Start-SelfMusic }
    'stop'    { Stop-SelfMusic }
    'restart' { $KeepEmulator = $true; Stop-SelfMusic; Start-SelfMusic }
    'status'  { Show-Status }
}

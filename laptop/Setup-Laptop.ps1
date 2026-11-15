# Downloads Google's portable adb (platform-tools) into this folder and checks that the Thor is visible. Nothing is installed system-wide.
$ErrorActionPreference = "Continue"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
New-Item -ItemType Directory -Force "$Here\logs" | Out-Null
$log = "$Here\logs\setup.txt"
"== $(Get-Date)  arch=$env:PROCESSOR_ARCHITECTURE" | Out-File -Encoding utf8 $log
$adb = "$Here\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
  $ProgressPreference = "SilentlyContinue"
  try {
    Invoke-WebRequest "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile "$Here\pt.zip" -UseBasicParsing
    Expand-Archive "$Here\pt.zip" -DestinationPath $Here -Force; Remove-Item "$Here\pt.zip"
    "downloaded platform-tools" | Out-File -Append -Encoding utf8 $log
  } catch { "download failed: $_" | Out-File -Append -Encoding utf8 $log }
}
(& $adb version 2>&1 | Out-String) | Out-File -Append -Encoding utf8 $log
(& $adb start-server 2>&1 | Out-String) | Out-File -Append -Encoding utf8 $log
Start-Sleep 3
(& $adb devices -l 2>&1 | Out-String) | Out-File -Append -Encoding utf8 $log
"== USB devices (Android/ADB)" | Out-File -Append -Encoding utf8 $log
(Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object { $_.FriendlyName -match 'ADB|Android|AYN|Thor|MTP|Composite' } | Format-Table -AutoSize Status,Class,FriendlyName,InstanceId | Out-String -Width 300) | Out-File -Append -Encoding utf8 $log
"done" | Out-File "$Here\logs\setup-done.txt"

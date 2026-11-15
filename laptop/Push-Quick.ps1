$ErrorActionPreference = 'Continue'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$adb = Join-Path $Here 'platform-tools\adb.exe'
$log = Join-Path $Here 'logs\push-quick.txt'
New-Item -ItemType Directory -Force -Path (Join-Path $Here 'logs') | Out-Null
"=== $(Get-Date) ===" | Out-File $log -Encoding utf8
& $adb devices 2>&1 | Out-File $log -Append -Encoding utf8
& $adb shell mkdir -p /sdcard/Download/W101Quick 2>&1 | Out-File $log -Append -Encoding utf8
& $adb push (Join-Path $Here 'W101Quick.exe') /sdcard/Download/W101Quick/W101Quick.exe 2>&1 | Out-File $log -Append -Encoding utf8
$ov = Join-Path $Here 'quick-overlay'
if (Test-Path (Join-Path $ov 'ENABLED')) {
  & $adb shell rm -rf /sdcard/Download/W101Quick/overlay 2>&1 | Out-File $log -Append -Encoding utf8
  & $adb push $ov /sdcard/Download/W101Quick/overlay 2>&1 | Out-File $log -Append -Encoding utf8
}
foreach ($f in Get-ChildItem (Join-Path $Here '*.icp')) {
  & $adb push $f.FullName "/sdcard/Download/$($f.Name)" 2>&1 | Out-File $log -Append -Encoding utf8
}
& $adb shell ls -laR /sdcard/Download/W101Quick 2>&1 | Out-File $log -Append -Encoding utf8
"done" | Out-File $log -Append -Encoding utf8

$ErrorActionPreference = 'Continue'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$adb = Join-Path $Here 'platform-tools\adb.exe'
$dst = Join-Path $Here 'logs\export'
Remove-Item -Recurse -Force $dst -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $dst | Out-Null
& $adb pull /sdcard/Download/W101Quick/export $dst 2>&1 | Out-File (Join-Path $Here 'logs\pull-export.txt') -Encoding utf8
& $adb pull /sdcard/Download/W101Quick/launcher.log (Join-Path $dst 'launcher.log') 2>&1 | Out-File (Join-Path $Here 'logs\pull-export.txt') -Append -Encoding utf8
"done" | Out-File (Join-Path $Here 'logs\pull-export.txt') -Append -Encoding utf8

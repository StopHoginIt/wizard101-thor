$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$adb = "$Here\platform-tools\adb.exe"
foreach ($n in 1..6) { & $adb pull "/data/local/tmp/s$n.png" "$Here\logs\s$n.png" 2>&1 | Out-Null }
& $adb shell "rm -f /data/local/tmp/s*.png" 2>&1 | Out-Null
"done" | Out-File "$Here\logs\shots-done.txt"

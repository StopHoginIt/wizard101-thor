$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$adb = "$Here\platform-tools\adb.exe"
$log = "$Here\logs\adbreset.txt"
"== $(Get-Date)" | Out-File $log
& $adb kill-server 2>&1 | Out-File -Append $log
Start-Sleep 2
Get-Process adb -ErrorAction SilentlyContinue | Stop-Process -Force
& $adb start-server 2>&1 | Out-File -Append $log
Start-Sleep 3
& $adb devices -l 2>&1 | Out-File -Append $log
$j = Start-Job { param($a) & $a shell "uptime; getprop sys.boot_completed; ps -A -o PID,TIME,ARGS | grep -E 'cp -r|rm -rf|mediaprovider' | grep -v grep" 2>&1 } -ArgumentList $adb
if (Wait-Job $j -Timeout 20) { Receive-Job $j | Out-File -Append $log } else { "shell timed out" | Out-File -Append $log; Stop-Job $j }
"done" | Out-File -Append $log

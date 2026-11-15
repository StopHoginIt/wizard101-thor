# Runs the adb shell steps listed in thor-commands.txt on the connected Thor (one per line; "sleep N" waits N seconds),
# then saves a screenshot and the on-screen UI layout to logs\ so you (or an assistant) can see the Thor's screen. Only talks to the Thor.
$ErrorActionPreference = "Continue"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$adb = "$Here\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source }
$log = "$Here\logs\thor-run.txt"
Remove-Item "$Here\logs\thor-done.txt","$Here\logs\thor-screen.png","$Here\logs\thor-ui.xml" -ErrorAction SilentlyContinue
"== $(Get-Date)" | Out-File $log
foreach ($line in Get-Content "$Here\thor-commands.txt") {
  $line = $line.Trim()
  if (-not $line -or $line.StartsWith("#")) { continue }
  if ($line -match '^sleep\s+(\d+)$') { Start-Sleep -Seconds ([int]$Matches[1]); continue }
  "> $line" | Out-File -Append $log
  & $adb shell $line 2>&1 | Out-File -Append $log
}
& $adb shell "screencap -p /data/local/tmp/thor.png; uiautomator dump /data/local/tmp/thor.xml" 2>&1 | Out-File -Append $log
& $adb pull /data/local/tmp/thor.png "$Here\logs\thor-screen.png" 2>&1 | Out-Null
& $adb pull /data/local/tmp/thor.xml "$Here\logs\thor-ui.xml" 2>&1 | Out-Null
& $adb shell "rm -f /data/local/tmp/thor.png /data/local/tmp/thor.xml" 2>&1 | Out-Null
"done" | Out-File "$Here\logs\thor-done.txt"

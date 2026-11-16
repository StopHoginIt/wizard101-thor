# Downloads the latest W101 Panel test build (W101Panel.apk) from this project's GitHub "nightly" release
# and installs it on the connected Thor with adb. Pass -Stable to use the latest numbered release instead.
param([switch]$Stable)
$ErrorActionPreference = "Continue"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$adb = "$Here\platform-tools\adb.exe"
$log = "$Here\logs\install-panel.txt"
New-Item -ItemType Directory -Force -Path "$Here\logs" | Out-Null
$url = if ($Stable) { "https://github.com/StopHoginIt/wizard101-thor/releases/latest/download/W101Panel.apk" } else { "https://github.com/StopHoginIt/wizard101-thor/releases/download/nightly/W101Panel.apk" }
"== $(Get-Date) $url" | Out-File $log
$ProgressPreference = "SilentlyContinue"
try { Invoke-WebRequest $url -OutFile "$Here\W101Panel.apk"; "downloaded $((Get-Item "$Here\W101Panel.apk").Length) bytes" | Out-File -Append $log }
catch { "download failed: $_" | Out-File -Append $log }
& $adb install -r "$Here\W101Panel.apk" 2>&1 | Out-File -Append $log
"done" | Out-File -Append $log

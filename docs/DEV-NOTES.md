# Developer notes

Practical notes for continuing the work, whether by a person or an AI assistant driving the Thor remotely.

## Driving the Thor from a Windows PC/laptop

`laptop/` holds the scripts. Put them in one folder; `Setup-Laptop.cmd` downloads Google's portable `platform-tools` next to them.

| Script | Does |
|---|---|
| `Run-Thor.cmd` | Runs each line of `thor-commands.txt` with `adb shell` (`sleep N` waits locally), then saves `logs\thor-screen.png` and a UI dump. |
| `Push-Quick.cmd` | Pushes `W101Quick.exe` → `/sdcard/Download/W101Quick/` and any `*.icp` → Download. Pushes `quick-overlay\` only if `quick-overlay\ENABLED` exists. |
| `Pull-Shots.cmd` | Pulls `/data/local/tmp/s1..s6.png` (take them with `screencap -p /data/local/tmp/sN.png` in a command list). |
| `Pull-Export.cmd` | Pulls the launcher's `export/` folder and `launcher.log`. |
| `Adb-Reset.cmd` | Restarts the adb server. If the device then shows offline, replug USB. |

Gotchas:
- PowerShell strips double quotes from `adb shell` lines. Use single quotes or regex dots (`grep -e has.died`).
- Heavy disk I/O on the Thor (big copies) can wedge adb until USB is replugged.
- `screencap` is sometimes black while the game renders full screen; it works while a GameNative overlay is open.
- `sendevent` on `/dev/input/event9` ("Odin Controller": `ABS_X/ABS_Y` ±32767, `BTN_A` 304, `BTN_B` 305, `BTN_Y` 308, `KEY_BACK` 158 opens GameNative's quick menu, D-pad = `ABS_HAT0X/Y`) reaches GameNative menus. Synthetic stick movement doesn't reliably move the character, so ask a human to test movement.
- GameNative pauses the game after its quick menu closes; press A to resume.

## GameNative UI coordinates (1920×1080, stock 1.4.0)

- Library search: tap (1602,72), type the name. Single result card ≈ (960,320).
- Game page: Play (207,536), ⚙ (1650,536) → Edit container (1380,328).
- Container tabs y=203: General 104, Graphics 315, Emulation 535, Controller 763, Wine 982, Win Components 1254, Environment 1557, Drives 1796. Save (1856,74).
- Quick menu (Back): after a swipe up, Control Profiles (530,314), Touchscreen Mode (560,478). Close (854,88).
- Control Profiles: Import (1745,128). In the file picker, search (1752,110). Add to library (1684,896). The newest profile is at the end of the list; Apply (1406,896) → confirm (1200,776).

## Building the launcher

```
cd launcher
go mod tidy
GOOS=windows GOARCH=arm64 go build -trimpath -ldflags "-s -w -H windowsgui" -o W101Quick-arm64.exe .
```

CI (`.github/workflows/build.yml`) does this on every push and attaches the binaries to releases for `v*` tags.

Offline test hook: `W101QUICK_TEST_FAKE_LOGIN=1` skips the network login and starts the client with a dummy token. It's only useful with a fake `Bin\WizardGraphicalClient.exe` under desktop Wine.

## Rules for this repo

- Never commit account data: usernames, passwords, tokens, `config.json`, game logs (they contain account and character IDs), screenshots of the account, or device serials.
- Keep `docs/FINDINGS.md` up to date with anything learned the hard way.

## Making a release

Releases are published by CI from `main`. To cut one:

1. Bump `VERSION` (e.g. `0.2.0`).
2. Optionally add `release-notes/v0.2.0.md`; otherwise GitHub generates notes.
3. Commit and push to `main`. The build job creates tag `v0.2.0` and a release with the arm64/amd64 launchers, the controller profile, the companion page and `laptop-tools.zip`.

Pushing to `main` without changing `VERSION` just builds; it doesn't re-release.

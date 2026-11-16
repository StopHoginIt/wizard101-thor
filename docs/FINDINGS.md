# Technical findings

What we learned getting the Wizard101 PC client playable on the AYN Thor, and why the setup is the way it is.

## The black screen (FUSE)

**Symptom:** the launcher logs in, `WizardGraphicalClient.exe` opens a 400×300 splash, then a black screen forever. `WizardClient.log` stops at `LOCALE = en-US`.

**Cause:** on the Thor (fuse-bpf), both `/sdcard` and `Android/data/<app>` go through **FUSE**. Wine resolves Windows' case-insensitive file names by trying `VFAT_IOCTL_READDIR_BOTH` (0x7201) and then scanning the directory. Over FUSE that is extremely slow, and the client does thousands of lookups in `Data\GameData` at startup.

**Fix:** GameNative 1.4.0 migrates custom games found in `/sdcard/GameNative/CustomGames/<name>` into its private internal storage (`/data/user/0/app.gamenative/CustomGames/<name>`) on first Play. Internal storage isn't FUSE.

## Slow startup

Even on internal storage, getting to character select takes about **2 minutes** (a PC takes about 8 s). The biggest gap in `WizardClient.log`:

```
Message Module: DOODLEDOUG_MESSAGES : Loaded OK
   ... 95 s ...   (PC: 3 s)
ObjStateManager has loaded 1918 object state sets
```

Meanwhile logcat shows a flood of SELinux denials: `avc: denied { ioctl } for comm="WizardGraphical" path=".../CustomGames/Wizard101/Data"`. That is the same VFAT ioctl. It's refused on app data, so Wine falls back to `readdir` scans of `Data\GameData` (about 2,900 entries) for every file name it doesn't find.

### What the client is looking for (traced 2026-11-15)

A run with GameNative's Wine debug channel `file` turned on (Settings → Debug → Wine Debug Channels) logs every failed path lookup as `nt_to_unix_file_name_no_root L"…" not found in "…"`. Counting them over one session (startup, character select, about 2 minutes in the world):

| Failed lookups | Path (A: = game folder) |
|---:|---|
| 76,175 | `Data\GameData\Locale_en-US-Root.wad` |
| 76,168 | `Data\Data\GameData\Locale_en-US-Root.wad` (the client's second search root) |
| 17,292 | `Data\GameData\CSR.wad` (+ the same under `Data\Data`) |
| 1,895 each | `Data\GameData\-WorldData.wad`, `Data\GameData\-.wad` (+ `Data\Data`) |
| ~100 | `Data\GameData\ClassicMode-WorldData.wad` |

Everything else was a handful of lookups. The client asks for these archives again and again because it doesn't remember that they're missing. Each miss in `GameData` makes Wine scan all ~2,900 entries for a different-case match.

**Fix (launcher 1.6):** before starting the client, W101Quick creates `Data\GameData\Locale_en-US-Root.wad` as an empty KIWAD archive (14 bytes: `KIWAD`, version 2, 0 files, flags 0; format per the [Kronos project](https://kronos-project.github.io/grimoire/internals/kiwad.html)). Result on the Thor: Play → character select went from about 2 min 50 s to **about 1 min 30 s**. It never overwrites a real file; `no-stubs.txt` in the launcher folder removes the stub again.

`CSR.wad` is deliberately *not* stubbed: the name points at KingsIsle's staff tools, and faking it isn't worth the risk to an account.

The GPU is mostly idle in game (≈15% busy at its lowest clock); the client's main thread uses a full CPU core under FEX, so the game is CPU-bound. FEX preset *Performance* (instead of *Intermediate*) was stable but made no noticeable difference.

## Bottom screen: GameNative's Hybrid mode

Tested 2026-11-15 on stock GameNative 1.4.0: container settings → **Controller** tab → scroll to the bottom → **External Display Input** → *Hybrid (touchpad + keyboard button)*, then save. "Show Game on External Display" stays off.

Result: the game stays on the top screen. The bottom screen (Android display 4, 1240×1080 as GameNative draws it) becomes a black touchpad with a keyboard button in the bottom-right corner. Wizard101 and the quick launcher start normally with it on.

Timing on that run (cold start after the Thor had been charging): Play → splash 2 min 47 s, character select by 3 min 17 s. Earlier warm runs reached character select in about 2 minutes.

To screenshot the bottom screen over adb: `screencap -d <ID> -p FILE`, where `<ID>` is the number after `uniqueId="local:` for "Screen-2" in `dumpsys display`.

## Stuck keys under gamescope

**Symptom:** with stick directions bound to keys (W/A/S/D), the wizard spins in circles or keeps running after the stick is released.

**How we found it:** the launcher polls `GetAsyncKeyState`, which is what Wizard101 uses for held movement. The client imports `GetAsyncKeyState`, not DirectInput or Raw Input. It logs every transition (`debug-keys.txt`). With GameNative's **Gamescope** display server:

```
keystate: S DOWN          <- stick pushed down
                          <- stick released: no "S up" ever arrives
keystate: SPACE DOWN / SPACE up    <- a face button bound to a key releases fine
```

Releases from stick-bound keys never reach Wine. Face buttons and mouse buttons are fine. Wizard101 sees the key held forever.

**Fix:** set **Graphics → Display Server: X11**. Releases then arrive (`S DOWN … S up`), movement works, and the game also felt smoother.

Side notes:
- Wizard101 starts movement on the key-down message, then polls `GetAsyncKeyState` every frame to keep moving. So a stuck key means endless movement, and a missing key state means only a twitch.
- Holding left and right mouse buttons ("mouse run") always worked, because the camera controller tracks button messages, not polled state. Profile v2 used that before the X11 fix.
- The middle mouse button can be bound to actions in the game's own **Options → Keybinding** tab (it records it as `MButton2`). We tried it for walking backward under gamescope; it didn't help.

## Wizard101's input bindings

The current client uses an action-based binding system:

- Defaults: `Data/GameData/InputBindings.xml` (`<m_action>MoveForward</m_action><m_input>keyboard.down.w</m_input><m_managerType>KBM</m_managerType>…`).
- User overrides: `Data/GameData/LocalInputBindings.xml`, written by the in-game **Keybinding** tab (Options → 4th icon; Input 1/2/3 per action).
- Mouse inputs look like `mouse.button0.down` (left, camera look), `mouse.button1.down` (right, player look), `mouse.move` and `mouse.wheel`.
- The PC client also contains `SteamInputManager` (Steam Input API, Steam build only) and gamepad glyph art from the console version, but no XInput path we could use.

## Quick login

- KingsIsle's launcher (`Wizard101.exe` → `PatchClient/BankA/WizardLauncher.exe`, a 32-bit CEF app) patches the game, logs in, then starts:
  `Bin\WizardGraphicalClient.exe -L login.us.wizard101.com 12000 -U ..<userId> <ck2 token> <username>`
- The CK2 token comes from the login server (`UserAuthenV3` → `UserAuthenRsp`). The protocol and crypto are implemented in `cedws/w101-client-go` and `cedws/w101-proto-go`, and used by `cedws/umbra-launcher`.
- `-L host port` alone opens the client's own login screen, and still needs typing.
- The patch server (`patch.us.wizard101.com:12500`, `MSG_LATEST_FILE_LIST_V2`) reports the current build in its file-list URL (for example `…/WizPatcher/V_r813697.WizardDev/…`). Its `LatestVersion` field is always 1, so the launcher compares URLs.
- W101Quick does the same flow natively and remembers the password with DPAPI. It skips the CEF launcher, which also removes the need for Proton 9 (the CEF launcher crashes on Proton 11).

## Running Go under GameNative

- An x86-64 Go binary hangs at startup under FEX (arm64ec): no log output, no window.
- A native **windows/arm64** Go binary runs fine under Proton arm64ec.
- Absolute executable paths work in GameNative's "Custom path…" (`D:/…`). The A: drive maps to the game folder.

## Memory

The client uses about 1.3–1.7 GB RSS in game. Once, under gamescope with a stuck key, Android's low-memory killer killed GameNative (system-wide pressure, about 12 GB used) about 2 minutes after entering the world. This hasn't been reproduced in X11 mode.

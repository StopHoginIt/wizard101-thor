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

### What the remaining stutters are (measured 2026-11-15)

GameNative's Performance HUD (Quick Menu → Performance HUD) shows 60 fps (the FPS limiter's cap) most of the time, with drops to 6–11 fps marked "Limited by: CPU" when entering new areas or casting. Sampling threads every 0.5 s (`top -H -p <pid>`) during those drops: one background client thread (the asset loader) runs at 70–100% while the main thread and DXVK's `dxvk-cs`/`dxvk-frame` threads go idle, so the main thread is waiting for it. DXVK's shader compile threads never showed up. The loader's time was about 60% user (the client's own code, translated by FEX) and 40% kernel (file access, partly the remaining failed lookups).

Tried and kept out:
- **Proton 11.0-1 arm64ec** instead of Proton 9.0 arm64ec: started fine, the launcher's saved login survived, but play felt the same or slightly worse. Back on Proton 9.0.
- A frame cap below 60 doesn't help: the drops are short loading stalls, not a low average.

The GPU is mostly idle in game (≈15% busy at its lowest clock); the client's main thread uses a full CPU core under FEX, so the game is CPU-bound. FEX preset *Performance* (instead of *Intermediate*) was stable but made no noticeable difference.

### Newer FEX with a disk cache (tried 2026-11-15)

FEX translates the client's x86-64 code to ARM the first time it runs, so a new area or spell costs translation time on top of loading. FEX 2609 added an opt-in disk cache that keeps translated code between launches (`FEX_DISKCACHE=1`); 2610 caps it at 1 GB. GameNative's own download list offers `2610-gn-5` and `2611-gn-6`; its default is 2605, and this container had 2512.

Set in the Wizard101 container: Emulation → FEXCore Version **2611-gn-6**, Environment → `FEX_DISKCACHE` = `1`. The FEX preset stays *Performance* (TSO already off). First impressions: play felt smoother. One launch with a filled cache hung before character select (client process alive, no CPU time); the next launch was fine. If that repeats, set `FEX_DISKCACHE` to `0` and keep 2611.

Ideas checked and parked:
- **Native ARM64EC DXVK.** GameNative's DXVK is x86-64 and runs under FEX. Dmage22/dxvk's ARM64EC builds are unmodified upstream DXVK built by GitHub Actions (reviewed: build workflow, no fork-only source commits, package contents and imports). Not tried yet.
- **A missing-file cache inside the client** (like the gamecache DLL in a GameNative fork) would remove the `CSR.wad` lookups, but it hooks file calls in the game client, so it's out.
- **Thor performance mode** was 1 (mid cores capped at 2.71 of 2.80 GHz); High is worth a try. The game already runs on cores 4-7 only, no thermal throttling.

## Bottom screen: GameNative's Hybrid mode

Tested 2026-11-15 on stock GameNative 1.4.0: container settings → **Controller** tab → scroll to the bottom → **External Display Input** → *Hybrid (touchpad + keyboard button)*, then save. "Show Game on External Display" stays off.

Result: the game stays on the top screen. The bottom screen (Android display 4, 1240×1080 as GameNative draws it) becomes a black touchpad with a keyboard button in the bottom-right corner. Wizard101 and the quick launcher start normally with it on.

Timing on that run (cold start after the Thor had been charging): Play → splash 2 min 47 s, character select by 3 min 17 s. Earlier warm runs reached character select in about 2 minutes.

To screenshot the bottom screen over adb: `screencap -d <ID> -p FILE`, where `<ID>` is the number after `uniqueId="local:` for "Screen-2" in `dumpsys display`.

## Screen capture

Inside Wine, GDI screen capture (`GetDC(0)` + `BitBlt`) returns an all-black image while the game renders through DXVK, so the launcher can't see the screen. Android's own capture (adb `screencap`, or MediaProjection in an app) does see the game, so the W101 Panel app reads the battle hand on the Android side.

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

## Analog movement

Wizard101's movement is four actions (MoveForward/Backward/Left/Right = W/S/A/D, where A/D turn at one fixed speed) plus mouse-look (`MousePlayerLook` = right button held while the mouse moves). Binding a stick to W/A/S/D gives no analog control, and GameNative's 8-way stick mode leaves the turn keys off until the stick is well over to the side.

Profile v7 binds the left stick to `GAMEPAD_LEFT_THUMB_*`, so GameNative passes it to Wine as an XInput stick (XInput is on by default in the container's Controller tab). W101Quick 2.0+ polls `XInputGetState` every 10 ms: up/down hold W/S; left/right hold the right mouse button and move the mouse sideways at a speed that grows with the tilt (`speed × ((tilt − deadzone)/(1 − deadzone))²` pixels per tick). Speed 3.5 felt right on the Thor; it can be changed live with `w101 stick speed <n>` on the panel bridge or in `stick.txt`. While the W101 Panel reports a battle, turning is off, because a right-click there discards the card under the pointer.

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

## Panel capture size

MediaProjection mirrors the top screen into a surface the panel sizes itself. Sized from `DEFAULT_DISPLAY`, the surface was sometimes the bottom screen's 1240x1080, so the 16:9 game was letterboxed and readers near the bottom edge (spellbook, health, pips) read the black bars; the hand reader near the middle still mostly worked. Panel 0.17+ sizes the capture from the largest display and crops off any letterbox.

## End of a battle

The panel tells the launcher a battle is on (the stick then doesn't right-click, which would discard a card). The world HUD's spellbook button is hidden for the whole battle, so the panel ends battle mode after two sightings of it in a row (panel 0.15+, `w101 explore` in launcher 2.3+). Before that it waited up to 40 s after the last hand, which blocked turning after a fight.

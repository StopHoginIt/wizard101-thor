# Setup: Wizard101 on the AYN Thor

Tested on: AYN Thor (Snapdragon 8 Gen 2, Android 13), GameNative 1.4.0, Proton 9.0 arm64ec, FEXCore 2512, Turnip 26.0.0 R8 (Wrapper driver).

## 1. Get the game files onto the Thor

1. On a Windows PC, install the **standalone** Wizard101 (not Steam), sign in once and let it finish downloading. The files are in `C:\ProgramData\KingsIsle Entertainment\Wizard101`, about 20–40 GB.
2. Copy that whole folder to the Thor as `/sdcard/GameNative/CustomGames/Wizard101` (for example `adb push`, or the `laptop/` scripts).
3. In GameNative, **Library → ＋ → Add custom game** and pick that folder.
4. Press **Play** once. GameNative 1.4.0 then **migrates** the folder into its private internal storage (`/data/user/0/app.gamenative/CustomGames/Wizard101`). That takes a minute.

   This step is essential. Run from `/sdcard`, the game sits on a black screen forever; see [FINDINGS](FINDINGS.md#the-black-screen-fuse).

## 2. Container settings (game page → ⚙ → Edit container)

| Tab | Setting | Value |
|---|---|---|
| General | Wine version | `proton-9.0-arm64ec` |
| General | Executable path | `D:/W101Quick/W101Quick.exe` (Custom path…, after step 3) |
| Graphics | Graphics driver | Wrapper + a recent Turnip (26.x tested) |
| Graphics | **Display Server** | **X11**. Gamescope leaves stick keys stuck "down" ([why](FINDINGS.md#stuck-keys-under-gamescope)). |
| Emulation | FEXCore preset | Intermediate (Performance is untested) |
| Environment | `WINEESYNC` | off (left from early testing; may be re-tested) |
| Drives | D: | `/storage/emulated/0/Download` (GameNative's default) |

Save.

## 3. Install the quick launcher

1. From the [latest release](../../../releases/latest), download **`W101Quick-arm64.exe`**. Use the ARM64 build: the x86-64 build hangs under FEX.
2. Put it on the Thor as `Download/W101Quick/W101Quick.exe` (`laptop/Push-Quick.cmd` does this over USB).
3. Set the container's executable path to `D:/W101Quick/W101Quick.exe`.

First launch: the login window appears at the top of the screen, so the keyboard doesn't cover it. Press **Y** for the keyboard and enter your KingsIsle username and password. Keep **Remember me** checked, then press Enter or tap **Log in** with R2.

After that, every launch shows *Starting Wizard101 in 3…*. Press **B** to change account, or wait. It then checks for updates, logs in and starts the game client.

If KingsIsle releases an update, the launcher downloads only the changed files. **Check game files** forces a full check. **Official launcher** opens KingsIsle's own launcher (needs Proton 9).

## 4. Controller profile

Copy `profiles/Wizard101-Thor-v6.icp` to the Thor's Download folder. In game: **Back** (quick menu) → controller tab → **Control Profiles** → ⤓ Import → pick the file → **Add to library** → find it in the list → **Apply**.

## 5. In-game settings that help

- Options → Graphics: the tested setup is Full Screen **On** at 1920×1080. If you ever get a black screen after changing it, use the radial menu's **Window <> Full** (Alt+Enter).
- Lower **Shadows/Particles** if busy areas stutter.

## 6. Launch without the GameNative UI (optional)

```
adb shell am start -p app.gamenative -a android.intent.action.VIEW \
  -d 'gamenative://run?appid=<your custom-game appId>&gamesource=CUSTOM_GAME'
```

The appId is the number GameNative assigned your custom game (shown in its `.gamenative` file and in launch links: game page → ⚙ → **Copy launch link**).

## Files the launcher writes (all in `Download/W101Quick/`)

| File | Contents |
|---|---|
| `launcher.log` | Launcher steps. Never contains your password or token, and doesn't log your username. |
| `crash.log` | Go runtime crash output, if any. |
| `WizardClient-tail.log` | A mirror of the last 256 KB of the game's own log, for debugging. |
| `export/` | One-time copy of the game's settings files and folder listing. Touch `export-again.txt` to redo it. |
| `overlay/` | Optional: files here are copied into the game folder before each launch. |
| `debug-keys.txt` | Create this empty file to log keyboard/mouse state (for input debugging). |

Your saved login itself lives in the Wine prefix (`%APPDATA%\W101Quick\config.json`), **not** in Download.

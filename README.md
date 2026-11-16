# Wizard101 on the AYN Thor

Dual-screen Wizard101 on the **AYN Thor** (Android 13, Snapdragon 8 Gen 2), running the normal Windows PC client through **[GameNative](https://github.com/utkarshdalal/GameNative)** (Wine/Proton + FEX + DXVK + Turnip), with full controller play and a growing set of bottom-screen tools.

> Unofficial fan project. Not affiliated with KingsIsle Entertainment or AYN. You need your own Wizard101 account and game files.

## What works today

| | Status |
|---|---|
| Game runs on the Thor (top screen, 1080p) | ✅ |
| **Quick launcher**: press Play → in game, no password typing, no KingsIsle launcher | ✅ `W101Quick.exe` |
| Controller: run / walk back / turn on the left stick, cursor on the right stick | ✅ profile **v6** |
| Smooth play | ✅ X11 display mode + launcher 1.6 file-lookup fix; some stutter remains (the game is CPU-bound under FEX) |
| Bottom screen | ✅ touchpad + keyboard (GameNative *External Display Input → Hybrid*); 🟡 shortcut/card panel planned ([roadmap](docs/ROADMAP.md)) |
| Startup time | 🟡 about 1½ minutes to character select, was about 3 ([why](docs/FINDINGS.md#slow-startup)) |

## Quick start

Full walkthrough: **[docs/SETUP.md](docs/SETUP.md)**. In short:

1. Install **GameNative 1.4.0** on the Thor and add your Wizard101 folder as a custom game. Put it in GameNative's **internal storage**, not `/sdcard`; see [FUSE](docs/FINDINGS.md#the-black-screen-fuse).
2. Container settings: **Wine version** `proton-9.0-arm64ec`, **Graphics → Display Server: X11** (important), FEXCore preset Intermediate.
3. Download `W101Quick-arm64.exe` from the [latest release](../../releases/latest) and copy it to the Thor as `Download/W101Quick/W101Quick.exe`.
4. In the container's **General → Executable Path → Custom path…**, enter `D:/W101Quick/W101Quick.exe` (`D:` is the Thor's Download folder).
5. Import `Wizard101-Thor-v6.icp`: in game, open GameNative's quick menu (Back) → **Control Profiles** → Import → Add to library → **Apply**.
6. Press Play, sign in once (Y opens the keyboard), and leave **Remember me** checked. From then on Play goes straight into the game.

## Controls (profile v6)

| Input | Action |
|---|---|
| Left stick | **Up** run (W) · **down** walk backward (S) · **left/right** turn (A/D), 8-way |
| Right stick | Mouse cursor / wand |
| R2 / L2 | Left click (talk, cast) / right click (hold + right stick turns the camera) |
| A / B / X / Y | Interact (X) / back (Esc) / jump (Space) / on-screen keyboard |
| L1 / R1 | Zoom in / out, scroll lists |
| L3 / R3 | Autorun / go to the nearest quest |
| Start / Select | Options (Esc) / hold for the radial menu |
| D-pad | Map / Quests / Spell deck / Backpack |

## Repo layout

| Path | What it is |
|---|---|
| [`launcher/`](launcher) | **W101Quick**: a quick-login launcher (Go, Win32 UI that runs under Wine). See [launcher/README.md](launcher/README.md). |
| [`profiles/`](profiles) | GameNative control profiles (`.icp`). |
| [`tools/`](tools) | The profile generator and validator. |
| [`companion/`](companion) | Controls reference and notes page for the bottom screen. |
| [`laptop/`](laptop) | Windows helper scripts that drive the Thor over ADB (setup, run commands, push the launcher, pull screenshots and logs). |
| [`gamenative-fork/`](gamenative-fork) | Patches for a GameNative fork with a Wizard101 bottom-screen panel and game fixes. |
| [`docs/`](docs) | [Setup](docs/SETUP.md), [technical findings](docs/FINDINGS.md), [roadmap](docs/ROADMAP.md), [developer notes](docs/DEV-NOTES.md). |

## Privacy and safety

- This repo contains **no account data**: no usernames, passwords, tokens, logs or screenshots of anyone's account.
- The launcher saves your password only on your Thor, inside GameNative's private app storage, encrypted with Windows DPAPI as implemented by Wine. Other apps can't read it, but a rooted device or someone with the unlocked Thor could. Untick **Remember me** if that matters to you.
- W101Quick signs in with KingsIsle's own login protocol (the same flow as the open-source [umbra-launcher](https://github.com/cedws/umbra-launcher)). It is unofficial software, so use it at your own risk.

## Credits

- [GameNative](https://github.com/utkarshdalal/GameNative) and the Winlator, Wine/Proton, FEX, DXVK and Mesa Turnip projects.
- [cedws/w101-client-go](https://github.com/cedws/w101-client-go), [cedws/w101-proto-go](https://github.com/cedws/w101-proto-go) and [cedws/umbra-launcher](https://github.com/cedws/umbra-launcher) for the Wizard101 login and patch protocol.

License: GPL-3.0 (the fork patches derive from GameNative, which is GPL-3.0). Third-party libraries keep their own terms.

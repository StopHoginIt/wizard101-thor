# GameNative fork patches ("W101 Launcher")

Changes on top of GameNative commit in `BASE_COMMIT.txt` (upstream: https://github.com/utkarshdalal/GameNative, GPL-3.0).
The build installs beside stock GameNative as `app.gamenative.w101`.

`files/` holds the full changed/new files; `w101-fork.diff` is the diff of the tracked files.

Main pieces:
- `externaldisplay/Wizard101ShortcutPanelView.kt`: **bottom-screen panel** with a touchpad and shortcut buttons that send Wizard101's default key bindings.
- `externaldisplay/ExternalDisplayInputController.kt` and `container/Container.java`: a new external display mode, `EXTERNAL_DISPLAY_MODE_WIZARD101`.
- `gamefixes/CUSTOM_GAME_Wizard101.kt`: pins Proton 9 arm64ec (the CEF launcher crashes on Proton 11) and enables the panel on first launch.
- `utils/PerfSampler.kt`, `DXVKHelper.java`, `WineUtils.java`, `WinHandler.java`, `BionicProgramLauncherComponent.java`: perf sampling and launch tweaks used during bring-up.

Status: built and sideloaded once during bring-up (0.3). Not yet rebased onto current GameNative or updated for the X11 and quick-launcher findings. See [docs/ROADMAP.md](../docs/ROADMAP.md).

To build: clone GameNative at the base commit, copy `files/` over the tree, and build the debug variant (`./gradlew assembleDebug`). The debug build type uses `applicationIdSuffix ".w101"`.

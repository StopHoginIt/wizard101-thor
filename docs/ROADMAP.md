# Roadmap

Goal: **dual-screen Wizard101 on the AYN Thor with full controller support, running smoothly.**

## The bottom screen

- [x] **W101 Panel** (0.8): Wizard101-styled shortcut tiles; in battle, the hand on the bottom screen (tap to cast, hold to discard), Pass/Draw/Flee, and tap-to-target. Next: customizable tiles, pips/health readout, bigger card art (raise the game's render size).

Hardware: 3.92″ AMOLED touchscreen, 1080×1240, 60 Hz. Android treats the **top** screen as the main display and the bottom as an *external* (secondary) display, so apps that support external displays can draw there.

What exists:
- **AYN dual-screen launcher**: any Android app can be pinned to the bottom screen. Today you can put `companion/W101-Companion.html` in a browser there.
- [x] **GameNative external-display input** (Hybrid mode tested and working, see FINDINGS): stock GameNative has *External Display Input* modes (touchpad, keyboard, hybrid) that put a virtual keyboard and/or touchpad on the second screen.
- **Our GameNative fork** (`gamenative-fork/`): adds a **Wizard101 mode**, a touchpad plus a grid of shortcut buttons (Backpack, Quests, Map, Spells, Friends, Pet, Crafting, Garden, Housing, Potion, Mark/Recall, Home/Hub…). Each button sends one of the game's default key bindings.

Ideas, roughly in order:
1. **Ship the fork as an APK release** (installs beside stock GameNative as `app.gamenative.w101`): the shortcut panel plus the touchpad on the bottom screen, and this repo's launcher and profile preconfigured.
2. **Spell-card helper**: during duels, show big tappable targets that map to card positions. Card slots sit at fixed screen positions per hand size, so a tap can become "move the cursor there, then click".
3. **Second game view**: render a minimap or a cropped region of the game (the Gamebryo UI layer) on the bottom screen. That needs a second capture/present path in GameNative and is research-level.
4. **Status panel**: health/mana/energy read from the game log or screen sampling (no memory reading or botting).
5. **Chat and keyboard** on the bottom screen, so typing doesn't cover the game.

## Performance

- [x] Skip the CEF launcher (W101Quick).
- [x] Gamescope → X11 display server (also fixes stuck keys).
- [x] **Startup / loading stalls**: launcher 1.6 stubs the missing `Locale_en-US-Root.wad` (76k lookups per session) → startup about 2× faster. Remaining: `CSR.wad` (17k, intentionally left), `-WorldData.wad`. Original plan: trace Wine file lookups (`WINEDEBUG=+file`) to find the missing paths the client probes in `Data\GameData`, then pre-create those folders so lookups stop scanning about 2,900 entries. Or find a way to give Wine a case-folded game directory.
- [x] Try **Proton 11 arm64ec**: no improvement, stayed on Proton 9.0.
- [x] FEX preset **Performance**: stable, no noticeable change.
- [ ] In-game: shadows/particles/texture detail presets for the Thor.
- [ ] Measure: DXVK HUD or the game's `PerfHUD`, with before/after numbers per change.

## Controls

- [x] v6: W/S/A/D on the left stick (8-way), cursor on the right stick.
- [ ] Optional mouse-steer mode (hold L2 = camera/player look) tuning.
- [ ] Duel mode profile: D-pad or face buttons pick cards directly (pairs with the bottom-screen card helper).

## Launcher

- [ ] Account switcher (several saved accounts).
- [ ] Show patch progress as a bar; resume interrupted downloads.
- [ ] Optional: start GameNative's game straight from an Android shortcut.

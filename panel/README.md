# W101 Panel (bottom-screen buttons and card helper)

A small Android app that turns the AYN Thor's **bottom screen** into a Wizard101 control panel, styled after the game's own UI (indigo panels, gold trim, brown battle buttons). It sits on top of GameNative's bottom-screen touchpad, which still works below it.

**Always:** the top bar shows ❤ health, 💧 mana and (outside battle) 🧪 potions; in battle, ❤ health/max and your pips (⚪ normal, 🟡 power), read when each round starts.

**Exploring:** shortcut tiles: Backpack, Spells, Quests, Map, Potion, Pet, Mark, Recall, Home, Friends, Chat, Menu.

**In battle** (it switches by itself when you're choosing spells):
- Your **hand** appears on the bottom screen. **Tap a card** to cast it, **hold** one to discard it.
- **PASS / DRAW / FLEE** buttons.
- When a card is chosen (on the panel or in the game) and the game asks for a target, a still picture of the battle appears: **tap who the spell is for**, or **◀ CARDS** to pick another card. Spells that need no target skip this. Then a small "Casting…" banner shows until your next turn.

## How it works

1. **Seeing the hand.** With your permission (Android's "Start recording or casting" prompt, once per panel start), the app takes a snapshot of the top screen about every 0.7 s. Nothing is saved or sent anywhere. In spell selection Wizard101 draws one centred row: the red deck box ("Cards 4 of 11") and then up to 7 cards. So the deck box's position tells how many cards you hold, and where each one is (`HandReader.java` has the measurements).
   Health and mana are read from the globes (only the outlined yellow lettering, then Android's on-device ML Kit text recognition); potions by counting bottles; in battle, health/max and pips from your nameplate in the bottom-right (`Stats.java`). Target selection is recognised from the hint line above the cards ("highlighted players to select target").
2. **Clicking in the game.** Each tap sends one UDP message (`w101 key backpack`, `w101 click 0.3771 0.4981`, …) to `127.0.0.1:47101` on the Thor. **W101Quick** (1.9+), still running inside GameNative/Wine next to the game, presses that key or clicks that spot with Windows' `SendInput`, then puts your mouse pointer back where it was. Only named keys and clicks are accepted, and only from the Thor itself (`launcher/panel.go`).
3. **Never in the way.** The panel is a non-focusable overlay window, so the controller keeps driving the game after you tap it.

Screen capture has to happen on the Android side: inside Wine, GDI capture of the game (rendered by DXVK) is just black.

## Install and use

- Install `W101Panel.apk` from a release (or run `laptop\Install-Panel.cmd`).
- Open **W101 Panel** once and allow *Display over other apps*.
- Start Wizard101 in GameNative, then open **W101 Panel** and tap *Start now* on Android's screen-capture prompt (*Cancel* still gives you the shortcut tiles).
- **▲ Hide** shrinks it to a small "▼ Spellbook" tab; opening the app again also brings it back. **Hold ✕** to remove it (or use *Stop* in its notification).
- For the touchpad under the panel: GameNative container settings → Controller → *External Display Input* → *Hybrid*.

Turn the launcher side off with an empty `no-panel.txt` next to `W101Quick.exe`.

## Build

CI builds it with Gradle 8.7 / Android Gradle Plugin 8.5 (`gradle -p panel assembleRelease`). Plain Java and Android views, no other libraries. `panel.keystore` is a throwaway signing key kept in the repo so new builds install over old ones; it identifies this sideloaded app and isn't a secret.

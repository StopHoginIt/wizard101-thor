# W101 Panel (bottom-screen buttons)

A tiny Android app that puts a strip of Wizard101 shortcut buttons on the AYN Thor's **bottom screen**, on top of GameNative's bottom-screen touchpad.

```
 bottom screen
┌──────────────────────────────────────────────┐
│ Backpack  Spells  Quests  Map  Potion  Pet   │  ← W101 Panel (overlay)
│ Mark  Recall  Home  Friends  Chat  Esc       │
│                       ● game  [▲ hide] [✕]   │
│                                              │
│        GameNative touchpad (still works)     │
│                                     [⌨]      │
└──────────────────────────────────────────────┘
```

## How it works

1. The app draws an **overlay window** on the second display. It's *not focusable*, so the controller keeps driving the game after you tap a button, and touches below the strip go to GameNative's touchpad.
2. Each button sends one UDP message, like `w101 key backpack`, to `127.0.0.1:47101` on the Thor itself.
3. **W101Quick** (1.7 or newer), which is still running inside GameNative/Wine next to the game, listens on that address. It presses the matching key in Wizard101 with Windows' `SendInput`. Only a fixed list of named actions is accepted (see `launcher/panel.go`), and only from the Thor itself.

"● game" means the launcher answered; "○ no game" means Wizard101 isn't running through W101Quick.

## Install and use

- Install `W101Panel.apk` from a release (or run `laptop\Install-Panel.cmd`).
- Open **W101 Panel** once and allow *Display over other apps*.
- Start Wizard101 in GameNative, then open **W101 Panel**: the buttons appear on the bottom screen.
- **▲ hide** shrinks it to a small "▼ panel" tab; opening the app again also brings it back. **Hold ✕** to remove it (or use *Stop* in its notification).
- For the touchpad under the buttons: GameNative container settings → Controller → *External Display Input* → *Hybrid*.

Turn the launcher side off by putting an empty `no-panel.txt` next to `W101Quick.exe`.

## Build

CI builds it with Gradle 8.7 / Android Gradle Plugin 8.5 (`gradle -p panel assembleRelease`). Plain Java and Android views, no other libraries. `panel.keystore` is a throwaway signing key kept in the repo so new builds install over old ones; it identifies this sideloaded app and isn't a secret.

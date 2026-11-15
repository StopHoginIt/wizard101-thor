"""Generates Wizard101-Thor-v6.icp, the GameNative control profile for Wizard101 on the AYN Thor.

Needs GameNative's X11 display server: under gamescope, stick-bound keys get stuck down."""
import json, sys

# Android keycodes
A, B, X, Y = 96, 97, 99, 100
L1, R1, L2, R2, L3, R3 = 102, 103, 104, 105, 106, 107
START, SELECT = 108, 109
DUP, DDOWN, DLEFT, DRIGHT = 19, 20, 21, 22
# Stick axes (GameNative ExternalControllerBinding): left stick X-/X+/Y(down)/Y(up), right stick likewise
LS_LEFT, LS_RIGHT, LS_DOWN, LS_UP = -1, -2, -3, -4
RS_LEFT, RS_RIGHT, RS_DOWN, RS_UP = -5, -6, -7, -8

def bind(code, *bindings):
    o = {"keyCode": code, "binding": bindings[0]}
    if len(bindings) > 1:
        o["bindings"] = list(bindings)
    return o

controller_bindings = [
    # Movement: W run, S walk back, A/D turn (8-way stick so "up" never leaks a turn)
    bind(LS_UP, "KEY_W"), bind(LS_DOWN, "KEY_S"), bind(LS_LEFT, "KEY_A"), bind(LS_RIGHT, "KEY_D"),
    bind(RS_UP, "MOUSE_MOVE_UP"), bind(RS_DOWN, "MOUSE_MOVE_DOWN"),
    bind(RS_LEFT, "MOUSE_MOVE_LEFT"), bind(RS_RIGHT, "MOUSE_MOVE_RIGHT"),
    # Triggers: click / cast card (R2), right-click: discard card, hold to steer camera (L2)
    bind(R2, "MOUSE_LEFT_BUTTON"), bind(L2, "MOUSE_RIGHT_BUTTON"),
    # Face buttons
    bind(A, "KEY_X"),            # Interact (talk, doors, sigils)
    bind(B, "KEY_ESC"),          # Back / close window
    bind(X, "KEY_SPACE"),        # Jump
    bind(Y, "SHOW_KEYBOARD"),    # On-screen keyboard (login, chat)
    # Bumpers: mouse wheel (zoom camera, scroll lists); repeats while held
    bind(L1, "MOUSE_SCROLL_UP"), bind(R1, "MOUSE_SCROLL_DOWN"),
    # Stick clicks
    bind(L3, "KEY_CTRL_L", "KEY_R"),               # Autorun toggle
    bind(R3, "KEY_CTRL_L", "KEY_SHIFT_L", "KEY_Q"),  # Quest Helper -> nearest quest
    # Start / Select
    bind(START, "KEY_ESC"),            # Options menu
    bind(SELECT, "OPEN_RADIAL_MENU"),  # Hold, flick a stick, release
    # D-pad: console-style quick menus
    bind(DUP, "KEY_M"),     # Map
    bind(DDOWN, "KEY_Q"),   # Quest Journal
    bind(DLEFT, "KEY_P"),   # Spell Deck (card pack)
    bind(DRIGHT, "KEY_B"),  # Backpack
]

def slot(label, *bindings):
    o = {"label": label, "binding": bindings[0]}
    if len(bindings) > 1:
        o["bindings"] = list(bindings)
    return o

radial = {
    "id": "default",
    "name": "Wizard101",
    "slots": [
        slot("Character", "KEY_C"),
        slot("Friends", "KEY_F"),
        slot("Pets", "KEY_I"),
        slot("Use Potion", "KEY_CTRL_L", "KEY_O"),
        slot("Chat", "KEY_ENTER", "SHOW_KEYBOARD"),
        slot("World Map", "KEY_SHIFT_L", "KEY_M"),
        slot("Go Home", "KEY_HOME"),
        slot("Common Area", "KEY_END"),
        slot("Set Marker", "KEY_PG_DOWN"),
        slot("Recall Marker", "KEY_PG_UP"),
        slot("Swap Deck", "KEY_CTRL_L", "KEY_K"),
        slot("Window <> Full", "ALT_ENTER"),  # fixes the fullscreen black screen
    ],
}

profile = {
    "schemaVersion": 1,
    "id": 0,
    "name": "Wizard101 (AYN Thor) v6",
    "includedSections": ["physicalController", "radialMenu"],
    "cursorSpeed": 1.5,
    "controllers": [{"id": "*", "name": "Wildcard Controller", "controllerBindings": controller_bindings}],
    "radialMenus": [radial],
    # Stick tuning: circular deadzone, a little more cursor speed on the right stick
    "leftStickDeadzone": 0.3,
    "rightStickDeadzone": 0.1,
    "leftStickSensitivity": 0.8,
    "rightStickSensitivity": 1.2,
    "leftStickDeadzoneMode": "circular",
    "rightStickDeadzoneMode": "circular",
    "leftStickDigitalMode": "eight_way",
    "rightStickDigitalMode": "unrestricted",
}

out = sys.argv[1] if len(sys.argv) > 1 else "Wizard101-Thor-v6.icp"
with open(out, "w") as f:
    json.dump(profile, f, indent=2)
print("wrote", out)

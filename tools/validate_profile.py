"""Checks an .icp against the rules in GameNative's ControlProfileService.validate() and Binding enum.

Usage: validate_profile.py PROFILE.icp [path/to/GameNative/.../Binding.java]
Without the second argument it uses the Binding names below (GameNative, late 2026).
"""
import json, re, sys
KNOWN = """NONE
MOUSE_LEFT_BUTTON
MOUSE_MIDDLE_BUTTON
MOUSE_RIGHT_BUTTON
MOUSE_MOVE_LEFT
MOUSE_MOVE_RIGHT
MOUSE_MOVE_UP
MOUSE_MOVE_DOWN
MOUSE_SCROLL_UP
MOUSE_SCROLL_DOWN
KEY_UP
KEY_RIGHT
KEY_DOWN
KEY_LEFT
KEY_ENTER
KEY_ESC
KEY_BKSP
KEY_DEL
KEY_TAB
KEY_SPACE
KEY_CTRL_L
KEY_CTRL_R
KEY_SHIFT_L
KEY_SHIFT_R
KEY_ALT_L
KEY_ALT_R
KEY_HOME
KEY_END
KEY_INSERT
KEY_PRTSCN
KEY_PG_UP
KEY_PG_DOWN
KEY_CAPS_LOCK
KEY_NUM_LOCK
KEY_0
KEY_1
KEY_2
KEY_3
KEY_4
KEY_5
KEY_6
KEY_7
KEY_8
KEY_9
KEY_A
KEY_B
KEY_C
KEY_D
KEY_E
KEY_F
KEY_G
KEY_H
KEY_I
KEY_J
KEY_K
KEY_L
KEY_M
KEY_N
KEY_O
KEY_P
KEY_Q
KEY_R
KEY_S
KEY_T
KEY_U
KEY_V
KEY_W
KEY_X
KEY_Y
KEY_Z
KEY_BRACKET_LEFT
KEY_BRACKET_RIGHT
KEY_BACKSLASH
KEY_SLASH
KEY_SEMICOLON
KEY_COMMA
KEY_PERIOD
KEY_APOSTROPHE
KEY_GRAVE
KEY_TILDE
KEY_EQUAL
KEY_KP_DIVIDE
KEY_KP_MULTIPLY
KEY_KP_SUBTRACT
KEY_KP_ADD
KEY_KP_DEL
KEY_MINUS
KEY_F1
KEY_F2
KEY_F3
KEY_F4
KEY_F5
KEY_F6
KEY_F7
KEY_F8
KEY_F9
KEY_F10
KEY_F11
KEY_F12
KEY_KP_0
KEY_KP_1
KEY_KP_2
KEY_KP_3
KEY_KP_4
KEY_KP_5
KEY_KP_6
KEY_KP_7
KEY_KP_8
KEY_KP_9
OPEN_NAVIGATION_MENU
SHOW_KEYBOARD
ALT_ENTER
OPEN_RADIAL_MENU
GYRO_MODIFIER
GAMEPAD_BUTTON_A
GAMEPAD_BUTTON_B
GAMEPAD_BUTTON_X
GAMEPAD_BUTTON_Y
GAMEPAD_BUTTON_L1
GAMEPAD_BUTTON_R1
GAMEPAD_BUTTON_SELECT
GAMEPAD_BUTTON_START
GAMEPAD_BUTTON_L3
GAMEPAD_BUTTON_R3
GAMEPAD_BUTTON_L2
GAMEPAD_BUTTON_R2
GAMEPAD_LEFT_THUMB_UP
GAMEPAD_LEFT_THUMB_RIGHT
GAMEPAD_LEFT_THUMB_DOWN
GAMEPAD_LEFT_THUMB_LEFT
GAMEPAD_RIGHT_THUMB_UP
GAMEPAD_RIGHT_THUMB_RIGHT
GAMEPAD_RIGHT_THUMB_DOWN
GAMEPAD_RIGHT_THUMB_LEFT
GAMEPAD_DPAD_UP
GAMEPAD_DPAD_RIGHT
GAMEPAD_DPAD_DOWN
GAMEPAD_DPAD_LEFT""".split()
if len(sys.argv) > 2:
    enum = re.search(r"enum Binding \{\s*([^;]+);", open(sys.argv[2]).read()).group(1)
    BINDINGS = {b.strip() for b in enum.split(",") if b.strip()}
else:
    BINDINGS = set(KNOWN)
p = json.load(open(sys.argv[1]))
errs = []
assert p.get("schemaVersion") == 1
secs = p["includedSections"]
assert set(secs) <= {"onScreen","physicalController","radialMenu","gyro","touchscreen","shooter"} and len(set(secs)) == len(secs)
def vb(v, where):
    if isinstance(v, str):
        if v not in BINDINGS: errs.append(f"{where}: unknown binding {v}")
    elif isinstance(v, list):
        if len(v) > 3: errs.append(f"{where}: combo > 3")
        for x in v: vb(x, where)
for c in p["controllers"]:
    assert c["id"] and c["name"]
    seen = set()
    for b in c["controllerBindings"]:
        k = b["keyCode"]; assert -32768 <= k <= 32767
        if k in seen: errs.append(f"duplicate keyCode {k}")
        seen.add(k)
        vb(b["bindings"] if "bindings" in b else b["binding"], f"key {k}")
        if "bindings" in b and b["binding"] != b["bindings"][0]: errs.append(f"key {k}: primary mismatch")
for m in p["radialMenus"]:
    if len(m["slots"]) > 12: errs.append("radial > 12 slots")
    for s in m["slots"]:
        assert s["label"]
        vb(s["bindings"] if "bindings" in s else s["binding"], "radial " + s["label"])
for k in ("leftStickDeadzone","rightStickDeadzone"): assert 0 <= p[k] <= 1
for k in ("leftStickSensitivity","rightStickSensitivity"): assert 0.1 <= p[k] <= 3
for k in ("leftStickDeadzoneMode","rightStickDeadzoneMode"): assert p[k] in ("axial","circular","hybrid")
for k in ("leftStickDigitalMode","rightStickDigitalMode"): assert p[k] in ("unrestricted","four_way","eight_way")
assert 0.01 <= p["cursorSpeed"] <= 20
if errs:
    print("ERRORS:", errs); sys.exit(1)
print(f"OK: {sum(len(c['controllerBindings']) for c in p['controllers'])} controller bindings, {len(p['radialMenus'][0]['slots'])} radial slots, {len(BINDINGS)} known bindings")

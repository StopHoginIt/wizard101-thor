"""Checks an .icp against the rules in GameNative's ControlProfileService.validate() and Binding enum."""
import json, re, sys
src = "/home/claude/src/GameNative/app/src/main/java/com/winlator/inputcontrols/Binding.java"
enum = re.search(r"enum Binding \{\s*([^;]+);", open(src).read()).group(1)
BINDINGS = {b.strip() for b in enum.split(",")}
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
print("ERRORS:", errs) if errs else print(f"OK: {sum(len(c['controllerBindings']) for c in p['controllers'])} controller bindings, {len(p['radialMenus'][0]['slots'])} radial slots, {len(BINDINGS)} known bindings")

//go:build windows

package main

// Bottom-screen panel bridge.
//
// The W101 Panel Android app (panel/ in the repo) draws buttons on the Thor's
// bottom screen. Each button sends one UDP datagram to 127.0.0.1:47101, and
// this listener turns it into keyboard or mouse input for Wizard101 with
// SendInput. Wine delivers SendInput to the foreground window (the game) and
// updates the async key state the client polls.
//
// Datagrams (ASCII, one per packet):
//
//	w101 ping                 -> replies "w101 pong <version>"
//	w101 key <action>         -> presses the key(s) for a named action
//	w101 click <x> <y> [r]    -> clicks at x,y (0..1 of the screen); "r" = right button
//	w101 battle               -> "a battle is on" (sent every second or so; pauses stick turning)
//	w101 explore              -> "the battle is over" (turning works again at once)
//	w101 clicktune <name> <ms> -> live click timing: arrive, nudge, settle, hold, restore
//	w101 stick <name> <value> -> live stick tuning: speed, deadzone, walk
//
// Only the named actions below are accepted, and the socket is bound to the
// loopback address, so nothing outside the Thor can reach it.

import (
	"encoding/binary"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
	"unsafe"
)

const panelAddr = "127.0.0.1:47101"

var (
	pSendInput      = user32.NewProc("SendInput")
	pMapVirtualKeyW = user32.NewProc("MapVirtualKeyW")
	pGetCursorPos   = user32.NewProc("GetCursorPos")
)

const (
	vkControl = 0x11
	vkShift   = 0x10
)

type keyAction struct {
	vk   uint16
	mods []uint16
}

// Wizard101's default bindings (Data/GameData/InputBindings.xml).
var panelActions = map[string]keyAction{
	"backpack": {vk: 'B'},
	"quests":   {vk: 'Q'},
	"map":      {vk: 'M'},
	"spells":   {vk: 'P'},
	"stats":    {vk: 'C'},
	"friends":  {vk: 'F'},
	"pet":      {vk: 'I'},
	"crafting": {vk: 'J'},
	"garden":   {vk: 'G'},
	"housing":  {vk: 'H'},
	"potion":   {vk: 'O', mods: []uint16{vkControl}},
	"mark":     {vk: 0x22}, // Page Down
	"recall":   {vk: 0x21}, // Page Up
	"home":     {vk: 0x24},
	"hub":      {vk: 0x23}, // End
	"menu":     {vk: 0x1B}, // Esc
	"chat":     {vk: 0x0D}, // Enter
	"realm":    {vk: 'M', mods: []uint16{vkControl}},
	"jump":     {vk: 0x20}, // Space
	"interact": {vk: 'X'},
	"tab":      {vk: 0x09},
}

// INPUT is 40 bytes on 64-bit Windows (4-byte type, 4 bytes padding, 32-byte union).
const inputSize = 40

const (
	inputMouse    = 0
	inputKeyboard = 1

	keyeventfKeyUp    = 0x0002
	keyeventfExtended = 0x0001

	mouseMove      = 0x0001
	mouseLeftDown  = 0x0002
	mouseLeftUp    = 0x0004
	mouseRightDown = 0x0008
	mouseRightUp   = 0x0010
	mouseAbsolute  = 0x8000
)

func keyInput(vk uint16, up bool) [inputSize]byte {
	var b [inputSize]byte
	binary.LittleEndian.PutUint32(b[0:], inputKeyboard)
	scan, _, _ := pMapVirtualKeyW.Call(uintptr(vk), 0) // MAPVK_VK_TO_VSC
	flags := uint32(0)
	switch vk {
	case 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E: // nav keys
		flags |= keyeventfExtended
	}
	if up {
		flags |= keyeventfKeyUp
	}
	binary.LittleEndian.PutUint16(b[8:], vk)
	binary.LittleEndian.PutUint16(b[10:], uint16(scan))
	binary.LittleEndian.PutUint32(b[12:], flags)
	return b
}

func mouseInput(dx, dy int32, flags uint32) [inputSize]byte {
	var b [inputSize]byte
	binary.LittleEndian.PutUint32(b[0:], inputMouse)
	binary.LittleEndian.PutUint32(b[8:], uint32(dx))
	binary.LittleEndian.PutUint32(b[12:], uint32(dy))
	binary.LittleEndian.PutUint32(b[20:], flags)
	return b
}

func sendInputs(in ...[inputSize]byte) {
	for i := range in {
		pSendInput.Call(1, uintptr(unsafe.Pointer(&in[i][0])), inputSize)
	}
}

// pressAction holds the keys long enough for a client that polls key state
// once per frame to see them, even during a slow frame.
func pressAction(a keyAction) {
	for _, m := range a.mods {
		sendInputs(keyInput(m, false))
	}
	sendInputs(keyInput(a.vk, false))
	time.Sleep(150 * time.Millisecond)
	sendInputs(keyInput(a.vk, true))
	for i := len(a.mods) - 1; i >= 0; i-- {
		sendInputs(keyInput(a.mods[i], true))
	}
}

// cursorFraction is the mouse position as fractions of the screen.
func cursorFraction() (float64, float64, bool) {
	var pt [2]int32
	if r, _, _ := pGetCursorPos.Call(uintptr(unsafe.Pointer(&pt[0]))); r == 0 {
		return 0, 0, false
	}
	w, _, _ := pGetSystemMetrics.Call(0)
	h, _, _ := pGetSystemMetrics.Call(1)
	if w == 0 || h == 0 {
		return 0, 0, false
	}
	return float64(pt[0]) / float64(w), float64(pt[1]) / float64(h), true
}

func moveTo(x, y float64) {
	sendInputs(mouseInput(int32(x*65535), int32(y*65535), mouseMove|mouseAbsolute))
}

// clickAt clicks without taking the player's pointer: afterwards the pointer goes
// back where it was. If that's over the battle hand (where a hovered card grows
// and hides its neighbours), it waits just below the cards instead.
// Click timing, in milliseconds. Defaults can be changed in click.txt next to the launcher
// ("hold 100", one per line; re-read when the file changes) or live with "w101 clicktune <name> <ms>".
//   arrive:  after moving onto the spot, before the hover nudge
//   nudge:   pointer briefly 40 units off, so the game registers the hover
//   settle:  back on the spot, before pressing
//   hold:    button held down
//   restore: wait before moving the pointer back (the game picks 3D targets from where the
//            pointer is when it handles the release, which can be a few frames later)
type clickTuning struct{ arrive, nudge, settle, hold, restore int }

var (
	clickMu       sync.Mutex
	clickT        = clickTuning{arrive: 30, nudge: 20, settle: 40, hold: 100, restore: 300}
	clickFileTime time.Time
	restoreGen    int
	homeX, homeY  float64
	homeSet       bool
)

func loadClickTuning() {
	path := filepath.Join(exeDir(), "click.txt")
	st, err := os.Stat(path)
	if err != nil || !st.ModTime().After(clickFileTime) {
		return
	}
	clickFileTime = st.ModTime()
	data, err := os.ReadFile(path)
	if err != nil {
		return
	}
	for _, line := range strings.Split(string(data), "\n") {
		f := strings.Fields(line)
		if len(f) == 2 {
			if v, err := strconv.Atoi(f[1]); err == nil {
				setClickValue(f[0], v)
			}
		}
	}
	logf("click: %+v", clickT)
}

func setClickValue(name string, ms int) {
	if ms < 0 || ms > 2000 {
		return
	}
	switch name {
	case "arrive":
		clickT.arrive = ms
	case "nudge":
		clickT.nudge = ms
	case "settle":
		clickT.settle = ms
	case "hold":
		clickT.hold = ms
	case "restore":
		clickT.restore = ms
	}
}

func sleepMs(ms int) {
	if ms > 0 {
		time.Sleep(time.Duration(ms) * time.Millisecond)
	}
}

// clickAt clicks at (x, y) as fractions of the screen. The pointer goes back to where it was a
// little later, without holding up the next click: taps in a row (an enchantment, then the card
// to enchant) go straight through, and the pointer returns to where it was before the first one.
func clickAt(x, y float64, right bool) {
	if x < 0 || x > 1 || y < 0 || y > 1 {
		return
	}
	clickMu.Lock()
	loadClickTuning()
	t := clickT
	if !homeSet {
		if px, py, ok := cursorFraction(); ok {
			homeX, homeY, homeSet = px, py, true
		}
	}
	restoreGen++
	gen := restoreGen
	clickMu.Unlock()

	ax, ay := int32(x*65535), int32(y*65535)
	down, up := uint32(mouseLeftDown), uint32(mouseLeftUp)
	if right {
		down, up = mouseRightDown, mouseRightUp
	}
	// Arrive, nudge (so the game sees the pointer over the target), then click.
	sendInputs(mouseInput(ax, ay, mouseMove|mouseAbsolute))
	sleepMs(t.arrive)
	if t.nudge > 0 {
		sendInputs(mouseInput(ax+40, ay+40, mouseMove|mouseAbsolute))
		sleepMs(t.nudge)
		sendInputs(mouseInput(ax, ay, mouseMove|mouseAbsolute))
	}
	sleepMs(t.settle)
	sendInputs(mouseInput(ax, ay, down|mouseAbsolute))
	sleepMs(t.hold)
	sendInputs(mouseInput(ax, ay, up|mouseAbsolute))

	go func() {
		sleepMs(t.restore)
		clickMu.Lock()
		defer clickMu.Unlock()
		if gen != restoreGen || !homeSet {
			return // another click came in; it will put the pointer back
		}
		homeSet = false
		if homeY > 0.42 && homeY < 0.58 {
			moveTo(0.5, 0.80) // don't leave it over the card row (a hovered card looks different)
		} else {
			moveTo(homeX, homeY)
		}
	}()
}

// runPanelBridge listens until done is closed. Input is handled one datagram
// at a time, in order.
func runPanelBridge(done <-chan struct{}) {
	addr, _ := net.ResolveUDPAddr("udp4", panelAddr)
	conn, err := net.ListenUDP("udp4", addr)
	if err != nil {
		logf("panel: listen %s: %v", panelAddr, err)
		return
	}
	logf("panel: listening on %s", panelAddr)
	go func() { <-done; conn.Close() }()
	buf := make([]byte, 256)
	for {
		n, from, err := conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if !from.IP.IsLoopback() {
			continue
		}
		f := strings.Fields(string(buf[:n]))
		if len(f) < 2 || f[0] != "w101" {
			continue
		}
		switch f[1] {
		case "ping":
			conn.WriteToUDP([]byte("w101 pong "+version), from)
		case "key":
			if len(f) == 3 {
				if a, ok := panelActions[f[2]]; ok {
					pressAction(a)
				}
			}
		case "stick":
			if len(f) == 4 {
				if v, err := strconv.ParseFloat(f[3], 64); err == nil && v >= 0 && v <= 50 {
					setStickValue(f[2], v)
				}
			}
		case "battle":
			// The panel says a battle is on; keep the stick from right-clicking for a few seconds.
			battleUntil.Store(time.Now().Add(3 * time.Second).UnixMilli())
		case "explore":
			// The panel saw the world HUD again: the battle is over, so turning works at once.
			battleUntil.Store(0)
			logf("panel: battle over (world HUD back)")
		case "clicktune":
			if len(f) == 4 {
				if v, err := strconv.Atoi(f[3]); err == nil {
					clickMu.Lock()
					setClickValue(f[2], v)
					logf("click: %+v", clickT)
					clickMu.Unlock()
				}
			}
		case "click":
			if len(f) >= 4 {
				x, e1 := strconv.ParseFloat(f[2], 64)
				y, e2 := strconv.ParseFloat(f[3], 64)
				if e1 == nil && e2 == nil {
					clickAt(x, y, len(f) == 5 && f[4] == "r")
				}
			}
		}
	}
}

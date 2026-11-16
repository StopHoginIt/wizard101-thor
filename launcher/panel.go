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
//
// Only the named actions below are accepted, and the socket is bound to the
// loopback address, so nothing outside the Thor can reach it.

import (
	"encoding/binary"
	"net"
	"strconv"
	"strings"
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
func clickAt(x, y float64, right bool) {
	if x < 0 || x > 1 || y < 0 || y > 1 {
		return
	}
	px, py, havePos := cursorFraction()
	defer func() {
		// Wizard101 picks 3D targets (players, enemies) from where the pointer is
		// when it handles the release, which can be a few frames later during a
		// slow frame. Give it time before moving the pointer back.
		time.Sleep(350 * time.Millisecond)
		switch {
		case !havePos:
		case py > 0.42 && py < 0.58:
			moveTo(0.5, 0.80)
		default:
			moveTo(px, py)
		}
	}()
	ax, ay := int32(x*65535), int32(y*65535)
	down, up := uint32(mouseLeftDown), uint32(mouseLeftUp)
	if right {
		down, up = mouseRightDown, mouseRightUp
	}
	// Arrive, nudge (so the game sees the pointer over the target), then click.
	sendInputs(mouseInput(ax, ay, mouseMove|mouseAbsolute))
	time.Sleep(60 * time.Millisecond)
	sendInputs(mouseInput(ax+40, ay+40, mouseMove|mouseAbsolute))
	time.Sleep(30 * time.Millisecond)
	sendInputs(mouseInput(ax, ay, mouseMove|mouseAbsolute))
	time.Sleep(90 * time.Millisecond)
	sendInputs(mouseInput(ax, ay, down|mouseAbsolute))
	time.Sleep(150 * time.Millisecond)
	sendInputs(mouseInput(ax, ay, up|mouseAbsolute))
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

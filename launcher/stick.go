//go:build windows

package main

// Analog movement for the left stick.
//
// Wizard101 has no analog movement: W/S walk, A/D turn at one fixed speed.
// Mapping a stick to those keys means turning only starts once the stick is
// well over to the side, always at full speed. Instead, the control profile
// (v7) passes the left stick through to Wine as an XInput stick, and this
// loop reads it:
//
//   - up/down holds W/S (forward/back);
//   - left/right turns with the game's mouse-look (right mouse button held
//     while the mouse moves), so a slight tilt turns slowly and a full tilt
//     turns fast, with no dead zones between directions.
//
// During a battle (the W101 Panel reports it) turning is off: a right-click
// there would discard the card under the pointer.
//
// Tuning lives in stick.txt next to the launcher ("speed 3.5", "deadzone 0.15",
// "walk 0.4"); it's re-read while the game runs, and "w101 stick speed 4" over
// the panel bridge changes it on the fly. no-stick.txt turns the whole thing off.

import (
	"bufio"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"
)

var (
	xinput          = syscall.NewLazyDLL("xinput1_4.dll")
	xinputFallback  = syscall.NewLazyDLL("xinput1_3.dll")
	pXInputGetState *syscall.LazyProc
	battleUntil     atomic.Int64 // unix ms; the panel refreshes it while you're in a battle
	stickOverride   atomic.Value // stickTuning set over the bridge, or nil
)

// setStickValue changes one tuning value live (from "w101 stick <name> <value>").
func setStickValue(name string, v float64) {
	t, ok := stickOverride.Load().(stickTuning)
	if !ok {
		t = loadStickTuning()
	}
	switch name {
	case "speed":
		t.speed = v
	case "deadzone":
		t.deadzone = v
	case "walk":
		t.walk = v
	default:
		return
	}
	stickOverride.Store(t)
	logf("stick: %s = %.2f", name, v)
}

const (
	vkW = 'W'
	vkS = 'S'
)

type stickTuning struct {
	speed    float64 // pixels of mouse-look per 10 ms at full tilt
	deadzone float64 // tilt below which nothing happens
	walk     float64 // forward/back threshold
}

func loadStickTuning() stickTuning {
	t := stickTuning{speed: 3.5, deadzone: 0.15, walk: 0.4}
	f, err := os.Open(filepath.Join(exeDir(), "stick.txt"))
	if err != nil {
		return t
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		k := strings.Fields(sc.Text())
		if len(k) != 2 {
			continue
		}
		v, err := strconv.ParseFloat(k[1], 64)
		if err != nil {
			continue
		}
		switch k[0] {
		case "speed":
			t.speed = v
		case "deadzone":
			t.deadzone = v
		case "walk":
			t.walk = v
		}
	}
	return t
}

// readStick returns the first connected pad's left stick (-1..1, up = +y).
func readStick() (x, y float64, ok bool) {
	var st [16]byte // XINPUT_STATE: packet(4) buttons(2) triggers(2) LX LY RX RY (2 each)
	for i := uintptr(0); i < 4; i++ {
		if r, _, _ := pXInputGetState.Call(i, uintptr(unsafe.Pointer(&st[0]))); r == 0 {
			lx := int16(uint16(st[8]) | uint16(st[9])<<8)
			ly := int16(uint16(st[10]) | uint16(st[11])<<8)
			return float64(lx) / 32767, float64(ly) / 32767, true
		}
	}
	return 0, 0, false
}

func inBattle() bool { return time.Now().UnixMilli() < battleUntil.Load() }

func runStick(done <-chan struct{}) {
	if _, err := os.Stat(filepath.Join(exeDir(), "no-stick.txt")); err == nil {
		return
	}
	if xinput.Load() == nil {
		pXInputGetState = xinput.NewProc("XInputGetState")
	} else if xinputFallback.Load() == nil {
		pXInputGetState = xinputFallback.NewProc("XInputGetState")
	} else {
		logf("stick: no XInput")
		return
	}
	t := loadStickTuning()
	logf("stick: analog movement on (speed %.1f, deadzone %.2f)", t.speed, t.deadzone)
	lastLoad := time.Now()

	var wDown, sDown, rmbDown bool
	var still time.Time
	var carry float64
	key := func(vk uint16, down bool) { sendInputs(keyInput(vk, !down)) }
	release := func() {
		if wDown {
			key(vkW, false)
			wDown = false
		}
		if sDown {
			key(vkS, false)
			sDown = false
		}
		if rmbDown {
			sendInputs(mouseInput(0, 0, mouseRightUp))
			rmbDown = false
		}
	}
	defer release()

	tick := time.NewTicker(10 * time.Millisecond)
	defer tick.Stop()
	wasBattle := false
	for {
		select {
		case <-done:
			return
		case <-tick.C:
		}
		if b := inBattle(); b != wasBattle {
			// Logged so a late "battle over" can be matched against the panel's log.
			logf("stick: battle %v", b)
			wasBattle = b
		}
		if o, ok := stickOverride.Load().(stickTuning); ok {
			t = o
		} else if time.Since(lastLoad) > 2*time.Second {
			t = loadStickTuning()
			lastLoad = time.Now()
		}
		x, y, ok := readStick()
		if !ok {
			release()
			continue
		}

		// Forward / back, with a little hysteresis.
		switch {
		case y > t.walk && !wDown:
			key(vkW, true)
			wDown = true
		case y < t.walk-0.1 && wDown:
			key(vkW, false)
			wDown = false
		}
		switch {
		case y < -t.walk && !sDown:
			key(vkS, true)
			sDown = true
		case y > -t.walk+0.1 && sDown:
			key(vkS, false)
			sDown = false
		}

		// Turning through mouse-look.
		ax := x
		if ax < 0 {
			ax = -ax
		}
		if ax > t.deadzone && !inBattle() {
			still = time.Time{}
			if !rmbDown {
				sendInputs(mouseInput(0, 0, mouseRightDown))
				rmbDown = true
			}
			f := (ax - t.deadzone) / (1 - t.deadzone)
			v := t.speed * f * f // gentle near the centre, fast at full tilt
			if x < 0 {
				v = -v
			}
			carry += v
			dx := int32(carry)
			carry -= float64(dx)
			if dx != 0 {
				sendInputs(mouseInput(dx, 0, mouseMove))
			}
		} else if rmbDown {
			if still.IsZero() {
				still = time.Now()
			} else if time.Since(still) > 150*time.Millisecond || inBattle() {
				sendInputs(mouseInput(0, 0, mouseRightUp))
				rmbDown = false
				carry = 0
			}
		}
	}
}

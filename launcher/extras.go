//go:build windows

package main

import (
	"bufio"
	"bytes"
	"debug/pe"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
	"unsafe"
)

// exeDir is the launcher's own folder (D:\W101Quick on the Thor = /sdcard/Download/W101Quick).
func exeDir() string {
	exe, err := os.Executable()
	if err != nil {
		return "."
	}
	return filepath.Dir(exe)
}

// applyOverlay copies every file under <exeDir>\overlay into the game folder
// (same relative path) when it differs. Backups of replaced originals go to
// <exeDir>\overlay-backup the first time.
func applyOverlay(gameDir string) {
	src := filepath.Join(exeDir(), "overlay")
	if _, err := os.Stat(src); err != nil {
		return
	}
	backup := filepath.Join(exeDir(), "overlay-backup")
	filepath.WalkDir(src, func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		rel, _ := filepath.Rel(src, p)
		dst := filepath.Join(gameDir, rel)
		nb, err := os.ReadFile(p)
		if err != nil {
			return nil
		}
		if ob, err := os.ReadFile(dst); err == nil {
			if bytes.Equal(ob, nb) {
				return nil
			}
			bk := filepath.Join(backup, rel)
			if _, err := os.Stat(bk); os.IsNotExist(err) {
				os.MkdirAll(filepath.Dir(bk), 0o755)
				os.WriteFile(bk, ob, 0o644)
			}
		}
		os.MkdirAll(filepath.Dir(dst), 0o755)
		if err := os.WriteFile(dst, nb, 0o644); err != nil {
			logf("overlay: %s: %v", rel, err)
		} else {
			logf("overlay: applied %s", rel)
		}
		return nil
	})
	// A file named "remove.txt" lists relative paths to delete (one per line).
	if b, err := os.ReadFile(filepath.Join(exeDir(), "overlay-remove.txt")); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			line = strings.TrimSpace(line)
			if line == "" || strings.Contains(line, "..") {
				continue
			}
			if err := os.Remove(filepath.Join(gameDir, line)); err == nil {
				logf("overlay: removed %s", line)
			}
		}
	}
}

// exportDiagnostics writes a one-time snapshot of small game config files,
// a folder listing and interesting strings from the client to
// <exeDir>\export, so they can be read from Android (/sdcard/Download).
// It never copies anything from the launcher's own config (no credentials).
func exportDiagnostics(gameDir string) {
	defer func() {
		if r := recover(); r != nil {
			logf("PANIC in export: %v", r)
		}
	}()
	if gameDir == "" {
		return
	}
	out := filepath.Join(exeDir(), "export")
	if _, err := os.Stat(filepath.Join(out, "done.txt")); err == nil {
		if _, err := os.Stat(filepath.Join(exeDir(), "export-again.txt")); err != nil {
			return
		}
		os.Remove(filepath.Join(exeDir(), "export-again.txt"))
	}
	os.MkdirAll(out, 0o755)

	var list bytes.Buffer
	copyCount := 0
	filepath.WalkDir(gameDir, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		rel, _ := filepath.Rel(gameDir, p)
		depth := strings.Count(rel, string(filepath.Separator))
		if d.IsDir() {
			if depth > 3 {
				return filepath.SkipDir
			}
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		fmt.Fprintf(&list, "%12d  %s\r\n", info.Size(), rel)
		low := strings.ToLower(rel)
		ext := strings.ToLower(filepath.Ext(rel))
		small := info.Size() < 4<<20
		if strings.Contains(low, "wizardlauncher.log") {
			return nil
		}
		interesting := ext == ".xml" || ext == ".txt" || ext == ".cfg" || ext == ".ini" ||
			strings.Contains(low, "binding") || strings.Contains(low, "config")
		if small && interesting && depth <= 2 && copyCount < 200 {
			dst := filepath.Join(out, "files", rel)
			os.MkdirAll(filepath.Dir(dst), 0o755)
			if b, err := os.ReadFile(p); err == nil {
				os.WriteFile(dst, b, 0o644)
				copyCount++
			}
		}
		return nil
	})
	os.WriteFile(filepath.Join(out, "listing.txt"), list.Bytes(), 0o644)

	// Newest client logs.
	for _, dir := range []string{filepath.Join(gameDir, "Bin", "Logs"), filepath.Join(gameDir, "Logs"), filepath.Join(gameDir, "Bin")} {
		ents, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		var logs []fs.DirEntry
		for _, e := range ents {
			if !e.IsDir() && strings.HasSuffix(strings.ToLower(e.Name()), ".log") {
				logs = append(logs, e)
			}
		}
		sort.Slice(logs, func(i, j int) bool {
			a, _ := logs[i].Info()
			b, _ := logs[j].Info()
			return a.ModTime().After(b.ModTime())
		})
		for i, e := range logs {
			if i >= 2 {
				break
			}
			if b, err := os.ReadFile(filepath.Join(dir, e.Name())); err == nil {
				os.MkdirAll(filepath.Join(out, "logs"), 0o755)
				os.WriteFile(filepath.Join(out, "logs", e.Name()), b, 0o644)
			}
		}
	}

	// Imported DLLs and functions of the client (which input APIs it uses).
	if pf, err := pe.Open(filepath.Join(gameDir, "Bin", "WizardGraphicalClient.exe")); err == nil {
		var sb strings.Builder
		if libs, err := pf.ImportedLibraries(); err == nil {
			sb.WriteString("LIBS: " + strings.Join(libs, ", ") + "\r\n")
		}
		if syms, err := pf.ImportedSymbols(); err == nil {
			for _, s := range syms {
				sb.WriteString(s + "\r\n")
			}
		}
		pf.Close()
		os.WriteFile(filepath.Join(out, "client-imports.txt"), []byte(sb.String()), 0o644)
	}

	// Full input-related strings (mouse./keyboard. tokens) for binding research.
	if b, err := os.ReadFile(filepath.Join(gameDir, "Bin", "WizardGraphicalClient.exe")); err == nil {
		re2 := regexp.MustCompile(`(?i)^(mouse|keyboard|gamepad|controller|kbm|steam)[._a-z0-9]*$|button[0-9]|wheel|middle|xbutton`)
		var sb strings.Builder
		seen := map[string]bool{}
		for _, s := range extractStrings(bytes.NewReader(b), 4) {
			if re2.MatchString(s) && !seen[s] && len(s) < 200 {
				seen[s] = true
				sb.WriteString(s + "\r\n")
			}
		}
		os.WriteFile(filepath.Join(out, "client-input-tokens.txt"), []byte(sb.String()), 0o644)
	}

	// Strings from the client that mention input handling.
	if f, err := os.Open(filepath.Join(gameDir, "Bin", "WizardGraphicalClient.exe")); err == nil {
		re := regexp.MustCompile(`(?i)binding|inputtype|mousetype|buttonstate|keystate|gamepad|joystick|xinput|dinput|controller|movebackward|moveforward|turnleft|turnright|strafe|autorun|mouselook`)
		var sb strings.Builder
		seen := map[string]bool{}
		for _, s := range extractStrings(bufio.NewReaderSize(f, 1<<20), 5) {
			if re.MatchString(s) && !seen[s] && len(s) < 300 {
				seen[s] = true
				sb.WriteString(s + "\r\n")
			}
		}
		f.Close()
		os.WriteFile(filepath.Join(out, "client-strings.txt"), []byte(sb.String()), 0o644)
	}
	os.WriteFile(filepath.Join(out, "done.txt"), []byte("ok\r\n"), 0o644)
	logf("export: wrote diagnostics (%d files)", copyCount)
}

// extractStrings returns printable ASCII and UTF-16LE runs of at least min chars.
func extractStrings(r io.Reader, min int) []string {
	data, err := io.ReadAll(r)
	if err != nil {
		return nil
	}
	var res []string
	var cur []byte
	flush := func() {
		if len(cur) >= min {
			res = append(res, string(cur))
		}
		cur = cur[:0]
	}
	for _, b := range data {
		if b >= 0x20 && b < 0x7f {
			cur = append(cur, b)
		} else {
			flush()
		}
	}
	flush()
	// UTF-16LE
	for i := 0; i+1 < len(data); i += 2 {
		if data[i] >= 0x20 && data[i] < 0x7f && data[i+1] == 0 {
			cur = append(cur, data[i])
		} else {
			flush()
		}
	}
	flush()
	return res
}

// mirrorClientLog copies the tail of the game's own log (Bin\WizardClient.log,
// inside GameNative's private storage) to <exeDir>\WizardClient-tail.log every
// few seconds while the game runs, so it can be read from Android for debugging.
func mirrorClientLog(src string, done <-chan struct{}) {
	dst := filepath.Join(exeDir(), "WizardClient-tail.log")
	for {
		if f, err := os.Open(src); err == nil {
			if st, err := f.Stat(); err == nil {
				const keep = 256 << 10
				off := st.Size() - keep
				if off < 0 {
					off = 0
				}
				f.Seek(off, io.SeekStart)
				if b, err := io.ReadAll(f); err == nil {
					os.WriteFile(dst, b, 0o644)
				}
			}
			f.Close()
		}
		select {
		case <-done:
			return
		case <-time.After(5 * time.Second):
		}
	}
}

var (
	pGetAsyncKeyState         = user32.NewProc("GetAsyncKeyState")
	pGetForegroundWindow      = user32.NewProc("GetForegroundWindow")
	pGetWindowThreadProcessId = user32.NewProc("GetWindowThreadProcessId")
)

// keyStateMonitor logs what GetAsyncKeyState reports for the movement keys and
// mouse buttons (the API Wizard101 polls for held input), plus which process
// owns the foreground window, for the first minutes of a game session.
func keyStateMonitor(clientPid int, done <-chan struct{}) {
	keys := []struct {
		vk   uintptr
		name string
	}{{0x57, "W"}, {0x41, "A"}, {0x53, "S"}, {0x44, "D"}, {0x01, "LBUTTON"}, {0x02, "RBUTTON"}, {0x04, "MBUTTON"}, {0x20, "SPACE"}}
	state := make([]bool, len(keys))
	lastFg := uint32(0)
	deadline := time.Now().Add(15 * time.Minute)
	for time.Now().Before(deadline) {
		select {
		case <-done:
			return
		case <-time.After(50 * time.Millisecond):
		}
		for i, k := range keys {
			r, _, _ := pGetAsyncKeyState.Call(k.vk)
			down := r&0x8000 != 0
			if down != state[i] {
				state[i] = down
				logf("keystate: %s %v", k.name, map[bool]string{true: "DOWN", false: "up"}[down])
			}
		}
		fg, _, _ := pGetForegroundWindow.Call()
		var pid uint32
		if fg != 0 {
			pGetWindowThreadProcessId.Call(fg, uintptr(unsafe.Pointer(&pid)))
		}
		if pid != lastFg {
			lastFg = pid
			logf("foreground window pid=%d (client pid=%d, launcher pid=%d)", pid, clientPid, os.Getpid())
		}
	}
}

// lookupStubs are archives the client asks for tens of thousands of times even
// though they don't exist on an en-US install. On Windows each miss is cheap.
// Under Wine on Android every miss makes Wine scan Data\GameData (~2,900
// entries) to rule out a different-case match, on the game's main thread.
// An empty KIWAD archive (header only, zero files) answers the lookup at once.
var lookupStubs = []string{
	`Data\GameData\Locale_en-US-Root.wad`,
}

// emptyKIWAD: "KIWAD", version 2, 0 files, flags 0.
var emptyKIWAD = []byte{'K', 'I', 'W', 'A', 'D', 2, 0, 0, 0, 0, 0, 0, 0, 0}

// ensureLookupStubs creates the stubs, or removes them again if
// <exeDir>\no-stubs.txt exists. It never touches a file that isn't its own stub.
func ensureLookupStubs(gameDir string) {
	_, err := os.Stat(filepath.Join(exeDir(), "no-stubs.txt"))
	remove := err == nil
	for _, rel := range lookupStubs {
		p := filepath.Join(gameDir, rel)
		b, err := os.ReadFile(p)
		switch {
		case remove && err == nil && bytes.Equal(b, emptyKIWAD):
			if err := os.Remove(p); err == nil {
				logf("stubs: removed %s", rel)
			}
		case !remove && os.IsNotExist(err):
			if _, derr := os.Stat(filepath.Dir(p)); derr != nil {
				continue
			}
			if err := os.WriteFile(p, emptyKIWAD, 0o644); err != nil {
				logf("stubs: %s: %v", rel, err)
			} else {
				logf("stubs: created %s", rel)
			}
		}
	}
}

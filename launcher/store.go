//go:build windows

package main

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

type config struct {
	Username      string `json:"username"`
	Password      string `json:"password_dpapi,omitempty"` // DPAPI-protected, base64
	Remember      bool   `json:"remember"`
	LastList      string `json:"last_list"` // LatestFileList URL of the last good launch (contains the build, e.g. V_r813697)
	GameDir       string `json:"game_dir,omitempty"`
	LoginServer   string `json:"login_server,omitempty"`
	PatchServer   string `json:"patch_server,omitempty"`
	CountdownSecs int    `json:"countdown_secs,omitempty"`
}

func appDir() string {
	base := os.Getenv("APPDATA")
	if base == "" {
		base = os.TempDir()
	}
	d := filepath.Join(base, "W101Quick")
	os.MkdirAll(d, 0o700)
	return d
}

func configPath() string { return filepath.Join(appDir(), "config.json") }

func loadConfig() config {
	c := config{Remember: true}
	b, err := os.ReadFile(configPath())
	if err == nil {
		json.Unmarshal(b, &c)
	}
	if c.LoginServer == "" {
		c.LoginServer = "login.us.wizard101.com:12000"
	}
	if c.PatchServer == "" {
		c.PatchServer = "patch.us.wizard101.com:12500"
	}
	if c.CountdownSecs <= 0 {
		c.CountdownSecs = 3
	}
	return c
}

func saveConfig(c config) error {
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	tmp := configPath() + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, configPath())
}

// ---- DPAPI ----

var (
	crypt32                = syscall.NewLazyDLL("crypt32.dll")
	procCryptProtectData   = crypt32.NewProc("CryptProtectData")
	procCryptUnprotectData = crypt32.NewProc("CryptUnprotectData")
	kernel32               = syscall.NewLazyDLL("kernel32.dll")
	procLocalFree          = kernel32.NewProc("LocalFree")
)

type dataBlob struct {
	cb uint32
	pb *byte
}

func newBlob(b []byte) *dataBlob {
	if len(b) == 0 {
		return &dataBlob{}
	}
	return &dataBlob{cb: uint32(len(b)), pb: &b[0]}
}

func (b *dataBlob) bytes() []byte {
	if b.pb == nil || b.cb == 0 {
		return nil
	}
	out := make([]byte, b.cb)
	copy(out, unsafe.Slice(b.pb, b.cb))
	return out
}

var entropy = []byte("W101Quick/AYN-Thor/v1")

const cryptprotectUIForbidden = 0x1

func protect(plain string) (string, error) {
	in := newBlob([]byte(plain))
	ent := newBlob(entropy)
	var out dataBlob
	r, _, err := procCryptProtectData.Call(uintptr(unsafe.Pointer(in)), 0, uintptr(unsafe.Pointer(ent)), 0, 0,
		cryptprotectUIForbidden, uintptr(unsafe.Pointer(&out)))
	if r == 0 {
		return "", fmt.Errorf("CryptProtectData: %v", err)
	}
	defer procLocalFree.Call(uintptr(unsafe.Pointer(out.pb)))
	return base64.StdEncoding.EncodeToString(out.bytes()), nil
}

func unprotect(enc string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(enc)
	if err != nil || len(raw) == 0 {
		return "", errors.New("no saved password")
	}
	in := newBlob(raw)
	ent := newBlob(entropy)
	var out dataBlob
	r, _, e := procCryptUnprotectData.Call(uintptr(unsafe.Pointer(in)), 0, uintptr(unsafe.Pointer(ent)), 0, 0,
		cryptprotectUIForbidden, uintptr(unsafe.Pointer(&out)))
	if r == 0 {
		return "", fmt.Errorf("CryptUnprotectData: %v", e)
	}
	defer procLocalFree.Call(uintptr(unsafe.Pointer(out.pb)))
	return string(out.bytes()), nil
}

// ---- logging (never logs passwords, tokens or the account name) ----

var (
	logMu    sync.Mutex
	logFiles []*os.File
)

func openLogs() {
	paths := []string{filepath.Join(appDir(), "launcher.log")}
	if exe, err := os.Executable(); err == nil {
		paths = append(paths, filepath.Join(filepath.Dir(exe), "launcher.log"))
	}
	for _, p := range paths {
		if st, err := os.Stat(p); err == nil && st.Size() > 512*1024 {
			os.Remove(p)
		}
		if f, err := os.OpenFile(p, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600); err == nil {
			logFiles = append(logFiles, f)
		}
	}
}

func logf(format string, a ...any) {
	line := time.Now().Format("2006-01-02 15:04:05.000 ") + strings.TrimRight(fmt.Sprintf(format, a...), "\n") + "\r\n"
	logMu.Lock()
	defer logMu.Unlock()
	for _, f := range logFiles {
		f.WriteString(line)
	}
}

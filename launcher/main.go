//go:build windows

// W101Quick: a quick launcher for Wizard101 on GameNative (AYN Thor).
//
// First run: asks for the KingsIsle username/password once (optionally
// remembered, DPAPI-protected inside the Wine prefix). Later runs: a short
// countdown ("press B / Esc to change account"), a fast update check, login,
// then WizardGraphicalClient.exe starts straight into the game - no KingsIsle
// launcher, no browser, no typing.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

const version = "1.6"

const (
	idOK       = 1
	idCancel   = 2
	idCheck    = 101
	idOfficial = 102
	idChange   = 103
	idUser     = 201
	idPass     = 202
	idRemember = 203

	wmStatus = wmApp + 1
	wmDone   = wmApp + 2
	wmFailed = wmApp + 3
	wmHide   = wmApp + 4

	timerCountdown = 1
)

type mode int

const (
	modeSplash mode = iota
	modeForm
	modeBusy
)

type app struct {
	cfg     config
	gameDir string

	hwnd                                     uintptr
	title, status, lblUser, lblPass          uintptr
	editUser, editPass, chkRemember          uintptr
	btnLogin, btnCheck, btnOfficial, btnExit uintptr
	btnChange, btnStart                      uintptr

	mode      mode
	countdown int

	mu         sync.Mutex
	statusText string
	failText   string

	password  string // in memory only
	forceScan bool
}

var theApp *app

var (
	flagForm  = flag.Bool("form", false, "always show the login form")
	flagCheck = flag.Bool("check", false, "verify and repair game files before launching")
	flagGame  = flag.String("game", "", "Wizard101 folder (default: A:\\ or the saved one)")
	flagProbe = flag.Bool("probe", false, "only check the patch server, log the result and exit")
)

func main() {
	runtime.LockOSThread()
	flag.Parse()
	openLogs()
	if f, err := os.OpenFile(filepath.Join(exeDir(), "crash.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644); err == nil {
		debug.SetCrashOutput(f, debug.CrashOptions{})
	}
	logf("W101Quick %s starting (%s/%s)", version, runtime.GOOS, runtime.GOARCH)

	a := &app{cfg: loadConfig()}
	theApp = a
	a.gameDir = findGameDir(a.cfg)
	logf("game dir: %q", a.gameDir)
	a.forceScan = *flagCheck

	if *flagProbe {
		fl, err := latestFileList(context.Background(), a.cfg.PatchServer)
		if err != nil {
			logf("probe: patch server error: %v", err)
			os.Exit(1)
		}
		logf("probe: patch server OK, list %s (saved %s)", fl.ListFileURL, a.cfg.LastList)
		os.Exit(0)
	}

	go exportDiagnostics(a.gameDir)

	a.createWindow()

	switch {
	case a.gameDir == "":
		a.showForm("Wizard101 was not found on A:\\. Set the game folder with -game.")
	case *flagForm || a.cfg.Username == "" || a.cfg.Password == "":
		a.showForm("")
	default:
		pw, err := unprotect(a.cfg.Password)
		if err == nil && pw == "" {
			err = errors.New("empty")
		}
		if err != nil {
			logf("saved password unreadable: %v", err)
			a.showForm("Your saved password could not be read. Please enter it again.")
		} else {
			a.password = pw
			a.showSplash()
		}
	}

	var m msgT
	for {
		r, _, _ := pGetMessageW.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(r) <= 0 {
			break
		}
		if d, _, _ := pIsDialogMessageW.Call(a.hwnd, uintptr(unsafe.Pointer(&m))); d != 0 {
			continue
		}
		pTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
		pDispatchMessageW.Call(uintptr(unsafe.Pointer(&m)))
	}
	logf("exit")
}

func findGameDir(c config) string {
	cands := []string{}
	if *flagGame != "" {
		cands = append(cands, *flagGame)
	}
	if c.GameDir != "" {
		cands = append(cands, c.GameDir)
	}
	cands = append(cands, `A:\`)
	if exe, err := os.Executable(); err == nil {
		d := filepath.Dir(exe)
		cands = append(cands, d, filepath.Dir(d))
	}
	cands = append(cands, `C:\ProgramData\KingsIsle Entertainment\Wizard101`)
	for _, d := range cands {
		if st, err := os.Stat(filepath.Join(d, "Bin", "WizardGraphicalClient.exe")); err == nil && !st.IsDir() {
			return d
		}
	}
	return ""
}

// ---------------- window ----------------

func (a *app) createWindow() {
	hInst, _, _ := pGetModuleHandleW.Call(0)
	cursor, _, _ := pLoadCursorW.Call(0, idcArrow)
	brush, _, _ := pGetSysColorBrush.Call(colorBtnFace)
	wc := wndClassEx{
		lpfnWndProc:   syscall.NewCallback(wndProc),
		hInstance:     hInst,
		hCursor:       cursor,
		hbrBackground: brush,
		lpszClassName: u16("W101QuickWindow"),
	}
	wc.cbSize = uint32(unsafe.Sizeof(wc))
	pRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc)))

	// Kept at the top of the screen so the Android keyboard (bottom ~60%) never covers the fields.
	const W, H = 1000, 420
	sw, _, _ := pGetSystemMetrics.Call(smCxScreen)
	x, y := (int32(sw)-W)/2, int32(0)
	if x < 0 {
		x = 0
	}
	a.hwnd, _, _ = pCreateWindowExW.Call(wsExControlPar, uintptr(unsafe.Pointer(u16("W101QuickWindow"))),
		uintptr(unsafe.Pointer(u16("Wizard101 Quick Launch"))), wsOverlapped|wsCaption|wsSysMenu|wsMinimizeBox,
		uintptr(x), uintptr(y), W, H, 0, 0, hInst, 0)

	big := makeFont(40, true)
	body := makeFont(30, false)
	small := makeFont(26, false)

	a.title = child(a.hwnd, "STATIC", "Wizard101 Quick Launch", 0, 0, 30, 8, 900, 50, 0, big)
	a.status = child(a.hwnd, "STATIC", "", 0, 0, 30, 60, 930, 66, 0, small)

	a.lblUser = child(a.hwnd, "STATIC", "Username", 0, 0, 30, 140, 200, 40, 0, body)
	a.editUser = child(a.hwnd, "EDIT", a.cfg.Username, wsTabStop|wsBorder|esAutoHScroll, wsExClientEdge, 240, 132, 720, 48, idUser, body)
	a.lblPass = child(a.hwnd, "STATIC", "Password", 0, 0, 30, 198, 200, 40, 0, body)
	a.editPass = child(a.hwnd, "EDIT", "", wsTabStop|wsBorder|esAutoHScroll|esPassword, wsExClientEdge, 240, 190, 720, 48, idPass, body)
	a.chkRemember = child(a.hwnd, "BUTTON", "Remember me on this device", wsTabStop|bsAutoCheck, 0, 240, 244, 720, 40, idRemember, body)
	if a.cfg.Remember {
		pSendMessageW.Call(a.chkRemember, bmSetCheck, 1, 0)
	}

	a.btnLogin = child(a.hwnd, "BUTTON", "Log in", wsTabStop|bsDefPush, 0, 30, 300, 200, 64, idOK, body)
	a.btnCheck = child(a.hwnd, "BUTTON", "Check game files", wsTabStop, 0, 250, 300, 270, 64, idCheck, body)
	a.btnOfficial = child(a.hwnd, "BUTTON", "Official launcher", wsTabStop, 0, 540, 300, 260, 64, idOfficial, body)
	a.btnExit = child(a.hwnd, "BUTTON", "Exit", wsTabStop, 0, 820, 300, 140, 64, idCancel, body)

	a.btnStart = child(a.hwnd, "BUTTON", "Start now", wsTabStop|bsDefPush, 0, 30, 160, 300, 80, idOK, body)
	a.btnChange = child(a.hwnd, "BUTTON", "Change account (B)", wsTabStop, 0, 360, 160, 380, 80, idChange, body)

	pShowWindow.Call(a.hwnd, swShow)
	pUpdateWindow.Call(a.hwnd)
	pSetForegroundWindow.Call(a.hwnd)
}

func (a *app) setControls(form, splash bool) {
	for _, h := range []uintptr{a.lblUser, a.editUser, a.lblPass, a.editPass, a.chkRemember, a.btnLogin, a.btnCheck, a.btnOfficial, a.btnExit} {
		show(h, form)
	}
	show(a.btnStart, splash)
	show(a.btnChange, splash)
}

func (a *app) showForm(msg string) {
	a.mode = modeForm
	pKillTimer.Call(a.hwnd, timerCountdown)
	a.setControls(true, false)
	if msg == "" {
		msg = "Sign in once with your KingsIsle account. Press Y for the keyboard.\r\nNext time the game starts by itself."
	}
	setText(a.status, msg)
	if getText(a.editUser) == "" {
		pSetFocus.Call(a.editUser)
	} else {
		pSetFocus.Call(a.editPass)
	}
}

func (a *app) showSplash() {
	a.mode = modeSplash
	a.setControls(false, true)
	a.countdown = a.cfg.CountdownSecs
	a.updateSplash()
	pSetFocus.Call(a.btnStart)
	pSetTimer.Call(a.hwnd, timerCountdown, 1000, 0)
}

func (a *app) updateSplash() {
	setText(a.status, fmt.Sprintf("Signed in as %s.\r\nStarting Wizard101 in %d...  Press B to change account.", a.cfg.Username, a.countdown))
}

func (a *app) setStatus(s string) {
	a.mu.Lock()
	a.statusText = s
	a.mu.Unlock()
	pPostMessageW.Call(a.hwnd, wmStatus, 0, 0)
}

func wndProc(hwnd uintptr, msg uint32, wParam, lParam uintptr) uintptr {
	a := theApp
	switch msg {
	case wmCommand:
		id := wParam & 0xFFFF
		a.onCommand(id)
		return 0
	case wmTimer:
		if wParam == timerCountdown && a.mode == modeSplash {
			a.countdown--
			if a.countdown <= 0 {
				pKillTimer.Call(hwnd, timerCountdown)
				a.startWork(a.cfg.Username, a.password, a.cfg.Remember, a.forceScan)
			} else {
				a.updateSplash()
			}
		}
		return 0
	case wmStatus:
		a.mu.Lock()
		s := a.statusText
		a.mu.Unlock()
		setText(a.status, s)
		return 0
	case wmFailed:
		a.mu.Lock()
		s := a.failText
		a.mu.Unlock()
		pShowWindow.Call(hwnd, swShow)
		a.showForm(s)
		return 0
	case wmHide:
		pShowWindow.Call(hwnd, swHide)
		return 0
	case wmDone:
		pDestroyWindow.Call(hwnd)
		return 0
	case wmClose:
		if a.mode == modeBusy {
			return 0
		}
		pDestroyWindow.Call(hwnd)
		return 0
	case wmDestroy:
		pPostQuitMessage.Call(0)
		return 0
	}
	r, _, _ := pDefWindowProcW.Call(hwnd, uintptr(msg), wParam, lParam)
	return r
}

func (a *app) onCommand(id uintptr) {
	switch a.mode {
	case modeSplash:
		switch id {
		case idOK:
			pKillTimer.Call(a.hwnd, timerCountdown)
			a.startWork(a.cfg.Username, a.password, a.cfg.Remember, a.forceScan)
		case idCancel, idChange:
			a.password = ""
			setText(a.editPass, "")
			a.showForm("Enter the account you want to use.")
		}
	case modeForm:
		switch id {
		case idOK, idCheck:
			user := strings.TrimSpace(getText(a.editUser))
			pass := getText(a.editPass)
			if user == "" {
				pSetFocus.Call(a.editUser)
				return
			}
			if pass == "" {
				// Enter in the username box moves on to the password box.
				pSetFocus.Call(a.editPass)
				return
			}
			r, _, _ := pSendMessageW.Call(a.chkRemember, bmGetCheck, 0, 0)
			a.startWork(user, pass, r == 1, id == idCheck || a.forceScan)
		case idOfficial:
			a.runOfficial()
		case idCancel:
			pDestroyWindow.Call(a.hwnd)
		}
	}
}

func (a *app) runOfficial() {
	exe := filepath.Join(a.gameDir, "Wizard101.exe")
	cmd := exec.Command(exe)
	cmd.Dir = a.gameDir
	if err := cmd.Start(); err != nil {
		setText(a.status, "Could not start the official launcher: "+err.Error())
		return
	}
	logf("started official launcher")
	a.mode = modeBusy
	pShowWindow.Call(a.hwnd, swHide)
	go func() {
		cmd.Wait()
		pPostMessageW.Call(a.hwnd, wmDone, 0, 0)
	}()
}

// ---------------- work ----------------

func (a *app) fail(msg string) {
	logf("failed: %s", msg)
	a.mu.Lock()
	a.failText = msg
	a.mu.Unlock()
	pPostMessageW.Call(a.hwnd, wmFailed, 0, 0)
}

func (a *app) startWork(user, pass string, remember, scan bool) {
	a.mode = modeBusy
	a.setControls(false, false)
	setText(a.status, "Connecting to KingsIsle...")
	go a.work(user, pass, remember, scan)
}

func (a *app) work(user, pass string, remember, scan bool) {
	defer func() {
		if r := recover(); r != nil {
			logf("PANIC in work: %v\n%s", r, debug.Stack())
			a.fail(fmt.Sprintf("Internal error: %v", r))
		}
	}()
	ctx := context.Background()
	if pass == "" {
		logf("work: password is empty")
	}

	// 1. Update check (cheap). Never blocks the launch if the patch server is unreachable.
	a.setStatus("Checking for game updates...")
	fl, err := latestFileList(ctx, a.cfg.PatchServer)
	if err != nil {
		logf("patch server unreachable: %v", err)
	} else {
		logf("latest list %s, saved %s", fl.ListFileURL, a.cfg.LastList)
		needPatch := scan || (a.cfg.LastList != "" && fl.ListFileURL != a.cfg.LastList)
		if needPatch {
			root, err := os.OpenRoot(a.gameDir)
			if err != nil {
				a.fail("Could not open the game folder: " + err.Error())
				return
			}
			p := &patcher{root: root, http: &http.Client{Timeout: 10 * time.Minute}}
			start := time.Now()
			p.progress = func(done, total int64, note string) {
				if note == "" {
					note = "Checking game files"
				}
				a.setStatus(fmt.Sprintf("Updating Wizard101 (%d of %d files)\r\n%s", done, total, note))
			}
			a.setStatus("A Wizard101 update is available. Checking game files...")
			err = p.run(ctx, fl, !scan)
			root.Close()
			if err != nil {
				a.fail("The update did not finish: " + err.Error() + "\r\nTry again, or use the official launcher.")
				return
			}
			logf("patch finished in %s", time.Since(start).Round(time.Second))
		}
		a.cfg.LastList = fl.ListFileURL
	}

	logf("applying overlay")
	// 1b. Apply tweak files (D:\W101Quick\overlay\...) on top of the game folder.
	applyOverlay(a.gameDir)
	ensureLookupStubs(a.gameDir)

	// 2. Login.
	a.setStatus("Logging in...")
	var (
		userID uint64
		ck2    string
	)
	if os.Getenv("W101QUICK_TEST_FAKE_LOGIN") == "1" { // offline test hook only
		userID, ck2, err = 42, "TESTTOKEN", nil
	} else {
		logf("logging in...")
		userID, ck2, err = requestCK2(ctx, a.cfg.LoginServer, user, pass)
	}
	if err != nil {
		var ae *authError
		if errors.As(err, &ae) {
			a.fail(ae.Error() + "\r\nCheck your username and password.")
		} else {
			a.fail("Login failed: " + err.Error())
		}
		return
	}
	logf("login OK")

	// 3. Remember the account.
	a.cfg.Username = user
	a.cfg.Remember = remember
	a.cfg.GameDir = a.gameDir
	if remember {
		if enc, err := protect(pass); err == nil {
			a.cfg.Password = enc
		} else {
			logf("could not protect password: %v", err)
		}
	} else {
		a.cfg.Password = ""
	}
	if err := saveConfig(a.cfg); err != nil {
		logf("save config: %v", err)
	}

	// 4. Start the game client straight into the game.
	host, port, _ := strings.Cut(a.cfg.LoginServer, ":")
	bin := filepath.Join(a.gameDir, "Bin")
	cmd := exec.Command(filepath.Join(bin, "WizardGraphicalClient.exe"),
		"-L", host, port, "-U", fmt.Sprintf("..%d", userID), ck2, user)
	cmd.Dir = bin
	a.setStatus("Starting Wizard101...")
	if err := cmd.Start(); err != nil {
		a.fail("Could not start the game: " + err.Error())
		return
	}
	logf("client started, pid %d", cmd.Process.Pid)
	pPostMessageW.Call(a.hwnd, wmHide, 0, 0)

	// Stay alive (hidden) while the game runs, so GameNative keeps the session.
	exited := make(chan error, 1)
	gone := make(chan struct{})
	go func() { err := cmd.Wait(); close(gone); exited <- err }()
	go mirrorClientLog(filepath.Join(bin, "WizardClient.log"), gone)
	if _, err := os.Stat(filepath.Join(exeDir(), "debug-keys.txt")); err == nil {
		go keyStateMonitor(cmd.Process.Pid, gone)
	}
	select {
	case err := <-exited:
		logf("client exited early: %v", err)
		a.fail("Wizard101 closed right after starting. Try again, or use \"Check game files\".")
		return
	case <-time.After(20 * time.Second):
	}
	err = <-exited
	logf("client exited: %v", err)
	pPostMessageW.Call(a.hwnd, wmDone, 0, 0)
}

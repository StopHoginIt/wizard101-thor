//go:build windows

package main

// Minimal Win32 UI (no external toolkits) so it runs cleanly under Wine.

import (
	"syscall"
	"unsafe"
)

var (
	user32 = syscall.NewLazyDLL("user32.dll")
	gdi32  = syscall.NewLazyDLL("gdi32.dll")

	pRegisterClassExW    = user32.NewProc("RegisterClassExW")
	pCreateWindowExW     = user32.NewProc("CreateWindowExW")
	pDefWindowProcW      = user32.NewProc("DefWindowProcW")
	pShowWindow          = user32.NewProc("ShowWindow")
	pUpdateWindow        = user32.NewProc("UpdateWindow")
	pGetMessageW         = user32.NewProc("GetMessageW")
	pIsDialogMessageW    = user32.NewProc("IsDialogMessageW")
	pTranslateMessage    = user32.NewProc("TranslateMessage")
	pDispatchMessageW    = user32.NewProc("DispatchMessageW")
	pPostQuitMessage     = user32.NewProc("PostQuitMessage")
	pPostMessageW        = user32.NewProc("PostMessageW")
	pSendMessageW        = user32.NewProc("SendMessageW")
	pSetWindowTextW      = user32.NewProc("SetWindowTextW")
	pGetWindowTextW      = user32.NewProc("GetWindowTextW")
	pGetWindowTextLength = user32.NewProc("GetWindowTextLengthW")
	pSetFocus            = user32.NewProc("SetFocus")
	pSetTimer            = user32.NewProc("SetTimer")
	pKillTimer           = user32.NewProc("KillTimer")
	pGetSystemMetrics    = user32.NewProc("GetSystemMetrics")
	pLoadCursorW         = user32.NewProc("LoadCursorW")
	pSetForegroundWindow = user32.NewProc("SetForegroundWindow")
	pDestroyWindow       = user32.NewProc("DestroyWindow")
	pEnableWindow        = user32.NewProc("EnableWindow")
	pGetSysColorBrush    = user32.NewProc("GetSysColorBrush")
	pCreateFontW         = gdi32.NewProc("CreateFontW")
	pGetModuleHandleW    = kernel32.NewProc("GetModuleHandleW")
)

const (
	wsOverlapped   = 0x00000000
	wsCaption      = 0x00C00000
	wsSysMenu      = 0x00080000
	wsMinimizeBox  = 0x00020000
	wsVisible      = 0x10000000
	wsChild        = 0x40000000
	wsTabStop      = 0x00010000
	wsBorder       = 0x00800000
	wsExControlPar = 0x00010000
	wsExClientEdge = 0x00000200

	esAutoHScroll = 0x0080
	esPassword    = 0x0020
	bsDefPush     = 0x0001
	bsAutoCheck   = 0x0003

	wmDestroy   = 0x0002
	wmClose     = 0x0010
	wmSetFont   = 0x0030
	wmCommand   = 0x0111
	wmTimer     = 0x0113
	wmApp       = 0x8000
	bmGetCheck  = 0x00F0
	bmSetCheck  = 0x00F1
	emSetSel    = 0x00B1
	swHide      = 0
	swShow      = 5
	smCxScreen  = 0
	smCyScreen  = 1
	idcArrow    = 32512
	colorBtnFace = 15
)

type wndClassEx struct {
	cbSize        uint32
	style         uint32
	lpfnWndProc   uintptr
	cbClsExtra    int32
	cbWndExtra    int32
	hInstance     uintptr
	hIcon         uintptr
	hCursor       uintptr
	hbrBackground uintptr
	lpszMenuName  *uint16
	lpszClassName *uint16
	hIconSm       uintptr
}

type msgT struct {
	hwnd    uintptr
	message uint32
	wParam  uintptr
	lParam  uintptr
	time    uint32
	ptX     int32
	ptY     int32
	private uint32
}

func u16(s string) *uint16 { p, _ := syscall.UTF16PtrFromString(s); return p }

func setText(h uintptr, s string) { pSetWindowTextW.Call(h, uintptr(unsafe.Pointer(u16(s)))) }

func getText(h uintptr) string {
	n, _, _ := pGetWindowTextLength.Call(h)
	buf := make([]uint16, n+1)
	pGetWindowTextW.Call(h, uintptr(unsafe.Pointer(&buf[0])), n+1)
	return syscall.UTF16ToString(buf)
}

func show(h uintptr, on bool) {
	if on {
		pShowWindow.Call(h, swShow)
	} else {
		pShowWindow.Call(h, swHide)
	}
}

func makeFont(height int32, bold bool) uintptr {
	weight := int32(400)
	if bold {
		weight = 700
	}
	neg := int64(-height)
	f, _, _ := pCreateFontW.Call(uintptr(neg), 0, 0, 0, uintptr(weight), 0, 0, 0, 1, 0, 0, 5, 0,
		uintptr(unsafe.Pointer(u16("Tahoma"))))
	return f
}

func child(parent uintptr, class, text string, style uint32, exStyle uint32, x, y, w, h int32, id uintptr, font uintptr) uintptr {
	hInst, _, _ := pGetModuleHandleW.Call(0)
	hwnd, _, _ := pCreateWindowExW.Call(uintptr(exStyle), uintptr(unsafe.Pointer(u16(class))), uintptr(unsafe.Pointer(u16(text))),
		uintptr(style|wsChild|wsVisible), uintptr(x), uintptr(y), uintptr(w), uintptr(h), parent, id, hInst, 0)
	pSendMessageW.Call(hwnd, wmSetFont, font, 1)
	return hwnd
}

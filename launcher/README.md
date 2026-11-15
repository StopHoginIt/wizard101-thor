# W101Quick: quick launcher for Wizard101 under GameNative

A small Windows program (Go, plain Win32 UI) that replaces KingsIsle's launcher when Wizard101 runs in GameNative on the AYN Thor.

- **First run:** login form at the top of the screen, so the Android keyboard doesn't cover it. "Remember me" saves the password DPAPI-encrypted in the Wine prefix (`%APPDATA%\W101Quick\config.json`).
- **Every run after:** a 3 s countdown (B/Esc = change account), then an update check against the patch server, a login, and `Bin\WizardGraphicalClient.exe -L … -U ..<uid> <token> <user>`.
- **Updates:** if the build changed, it verifies and downloads changed files. A CRC cache shortcut keeps this quick.
- **Fallbacks:** "Check game files", and "Official launcher" (runs `Wizard101.exe`).
- It stays alive, hidden, while the game runs, so GameNative keeps the session.

Build the **arm64** binary for the Thor; the amd64 build hangs under FEX. See [docs/SETUP.md](../docs/SETUP.md).

Files: `main.go` (flow and UI), `gui.go` (Win32 helpers), `auth.go` (login), `patch.go` (update check and patcher, approach after cedws/umbra-launcher), `store.go` (config, DPAPI, logging), `extras.go` (overlay, diagnostics export, log mirror, optional key-state monitor).

Flags: `-form` (always show the login form), `-check` (verify files), `-game <dir>`, `-probe` (patch server check only).

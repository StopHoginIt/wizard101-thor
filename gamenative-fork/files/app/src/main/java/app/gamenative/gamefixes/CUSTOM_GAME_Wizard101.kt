package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import timber.log.Timber

/**
 * Wizard101 (custom game, Wizard101.exe launcher or Bin/WizardGraphicalClient.exe).
 *
 * Turns on the Wizard101 second-screen panel the first time the game is launched.
 * It only replaces the default "off" value, so a mode the user picked later in the
 * container settings is left alone.
 */
private const val WIZARD101_WINE_VERSION = "proton-9.0-arm64ec"
private const val WINE_PINNED_EXTRA = "w101WinePinned"

private fun wizard101Fix(exeName: String): KeyedGameFix = object : KeyedGameFix {
    override val gameSource = GameSource.CUSTOM_GAME
    override val gameId = exeName

    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean = try {
        // WizardLauncher.exe (32-bit, CEF) dies with a stack overflow right after loading
        // WizardLauncherUI.dll under the Proton 11 arm64ec default; Proton 9 arm64ec runs it.
        // Applied once, so a version the user picks afterwards is kept.
        if (container.getExtra(WINE_PINNED_EXTRA).isEmpty()) {
            if (container.wineVersion.startsWith("proton-11")) {
                container.wineVersion = WIZARD101_WINE_VERSION
                Timber.tag("GameFixes").i("Wizard101: switched container to $WIZARD101_WINE_VERSION")
            }
            container.putExtra(WINE_PINNED_EXTRA, "1")
            container.saveData()
        }
        if (container.externalDisplayMode == Container.EXTERNAL_DISPLAY_MODE_OFF) {
            container.externalDisplayMode = Container.EXTERNAL_DISPLAY_MODE_WIZARD101
            container.saveData()
            Timber.tag("GameFixes").i("Wizard101: enabled second-screen shortcut panel")
        }
        true
    } catch (e: Exception) {
        Timber.tag("GameFixes").e(e, "Failed to apply Wizard101 fix")
        false
    }
}

val CUSTOM_GAME_Fix_Wizard101_Launcher: KeyedGameFix = wizard101Fix("wizard101.exe")
val CUSTOM_GAME_Fix_Wizard101_Client: KeyedGameFix = wizard101Fix("wizardgraphicalclient.exe")

package app.gamenative.display

import android.content.Context
import app.gamenative.service.SteamService
import app.gamenative.utils.AssetUtils
import com.winlator.core.TarCompressorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** Versioned gamescope payload (gamescope, XWayland, scaling clients), fetched on the first gamescope launch. */
object GamescopePayload {
    const val VERSION = "20261105.4"
    const val ARCHIVE = "gamescope-gamenative-$VERSION.tzst"
    private const val STAMP = ".payload.version"

    fun directory(filesDir: File): File = GamescopeSettings.gamescopeDir(filesDir)

    fun isReady(filesDir: File): Boolean {
        val dir = directory(filesDir)
        return File(dir, "bin").isDirectory &&
            runCatching { File(dir, STAMP).readText().trim() }.getOrNull() == VERSION
    }

    suspend fun ensure(context: Context, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        if (isReady(filesDir)) return@withContext directory(filesDir).also { relocate(filesDir) }
        val archive = File(filesDir, ARCHIVE)
        if (!archive.isFile || archive.length() == 0L) {
            archive.delete()
            // A payload shipped inside the APK wins over the download: it is the
            // build that was exercised with this app, and it needs no network.
            // Only when the asset is absent does the published archive get
            // fetched.
            if (copyFromAssets(context, archive)) {
                onProgress(1.0f)
            } else {
                try {
                    SteamService.fetchFileWithFallback(ARCHIVE, archive, context, onProgress)
                } catch (e: Exception) {
                    archive.delete()
                    throw e
                }
            }
        }
        install(archive, filesDir).also { relocate(filesDir) }
    }

    private const val STOCK_PREFIX = "/data/user/0/app.gamenative/files/gamescope"
    private const val RELOCATED_STAMP = ".payload.relocated"

    /**
     * The payload's binaries (Xwayland, xkbcomp, gamescope) have the stock package's
     * install prefix compiled in. Under any other application id those paths do not
     * exist, so Xwayland cannot compile its keymap and the session dies. Rewrite the
     * prefix in place to a same-length path that resolves into this package: a short
     * "gs" symlink next to the payload, padded with extra slashes so no bytes move.
     */
    @Synchronized
    internal fun relocate(filesDir: File) {
        val pkg = app.gamenative.BuildConfig.APPLICATION_ID
        if (pkg == "app.gamenative") return
        val target = directory(filesDir)
        val stamp = File(target, RELOCATED_STAMP)
        if (runCatching { stamp.readText().trim() }.getOrNull() == pkg) return

        val base = "/data/data/$pkg/files"
        val pad = STOCK_PREFIX.length - base.length - "gs".length
        if (pad < 1) {
            Timber.e("Gamescope payload cannot be relocated: package id $pkg is too long")
            return
        }
        val newPrefix = base + "/".repeat(pad) + "gs"
        val link = File(filesDir, "gs")
        runCatching {
            if (!link.exists()) android.system.Os.symlink(target.name, link.path)
        }.onFailure { Timber.e(it, "Failed to create gamescope alias ${link.path}") }

        val old = STOCK_PREFIX.toByteArray(Charsets.US_ASCII)
        val new = newPrefix.toByteArray(Charsets.US_ASCII)
        var patched = 0
        File(target, "bin").listFiles()?.filter { it.isFile }?.forEach { file ->
            val bytes = file.readBytes()
            var hits = 0
            var i = 0
            while (i <= bytes.size - old.size) {
                var match = true
                for (j in old.indices) {
                    if (bytes[i + j] != old[j]) { match = false; break }
                }
                if (match) {
                    System.arraycopy(new, 0, bytes, i, new.size)
                    hits++
                    i += old.size
                } else {
                    i++
                }
            }
            if (hits > 0) {
                val executable = file.canExecute()
                file.writeBytes(bytes)
                if (executable) file.setExecutable(true, false)
                patched += hits
            }
        }
        stamp.writeText(pkg)
        Timber.i("Gamescope payload relocated for $pkg ($patched path(s) rewritten to $newPrefix)")
    }

    /** Whether this APK carries the payload, and if so, copy it out to [dest]. */
    private fun copyFromAssets(context: Context, dest: File): Boolean {
        val assets = context.assets
        val present = runCatching { assets.open(ARCHIVE).close() }.isSuccess
        if (!present) return false
        return runCatching {
            assets.open(ARCHIVE).use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        }
            .onFailure {
                Timber.w(it, "Failed to copy $ARCHIVE from assets")
                dest.delete()
            }
            .isSuccess
    }

    @Synchronized
    internal fun install(archive: File, filesDir: File): File {
        val target = directory(filesDir)
        if (isReady(filesDir)) return target
        val tempDir = File(target.parentFile, "${target.name}.tmp")
        AssetUtils.deleteTree(tempDir)
        tempDir.mkdirs()
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, tempDir)) {
            AssetUtils.deleteTree(tempDir)
            archive.delete()
            error("Failed to extract $ARCHIVE")
        }
        File(tempDir, STAMP).writeText(VERSION)
        AssetUtils.deleteTree(target)
        if (!tempDir.renameTo(target)) {
            AssetUtils.deleteTree(tempDir)
            error("Failed to promote $ARCHIVE")
        }
        archive.delete()
        Timber.i("Gamescope payload $VERSION ready at ${target.absolutePath}")
        return target
    }
}

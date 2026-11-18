package dev.mattdev0.crispshot.share

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import dev.mattdev0.crispshot.App
import dev.mattdev0.crispshot.policy.ScalePlanner
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Translucent share-sheet proxy for "WhatsApp HD".
 * Reads incoming `content://` URIs via ContentResolver (rejecting `file://` and own provider),
 * upscales if needed under [App.policy], and forwards via FileProvider directly to WhatsApp.
 */
class ShareProxyActivity : ComponentActivity() {
    private data class PreparedShare(
        val uri: Uri,
        val mimeType: String,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targets = installedWhatsAppPackages()
        if (targets.isEmpty()) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val incomingUris = extractUris(intent)
        if (incomingUris.isEmpty()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (incomingUris.any { !isSupportedSourceUri(it) }) {
            Log.w(App.LOG_TAG, "share rejected unsupported source uri(s): $incomingUris")
            Toast.makeText(this, "Unsupported source", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val app = application as App

        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                pruneShareCache()
                prepareHdUris(app, incomingUris)
            }

            if (prepared.isEmpty()) {
                Toast.makeText(this@ShareProxyActivity, "Could not prepare image", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            val sendIntent = buildSendIntent(prepared)
            launchWhatsApp(sendIntent, targets)
            finish()
        }
    }

    private fun isSupportedSourceUri(uri: Uri): Boolean {
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            return false
        }
        val authority = uri.authority
        if (authority.isNullOrBlank()) {
            return false
        }
        return !authority.equals("$packageName.share", ignoreCase = true)
    }

    private fun extractUris(intent: Intent): List<Uri> {
        val action = intent.action
        val uris = mutableListOf<Uri>()
        if (action == Intent.ACTION_SEND) {
            getParcelableUri(intent, Intent.EXTRA_STREAM)?.let { uris.add(it) }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            getParcelableUriList(intent, Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (uris.isEmpty()) {
            val clip = intent.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { uris.add(it) }
                }
            }
        }
        return uris
    }

    @Suppress("DEPRECATION")
    private fun getParcelableUri(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }

    @Suppress("DEPRECATION")
    private fun getParcelableUriList(intent: Intent, key: String): ArrayList<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(key, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(key)
        }

    private fun prepareHdUris(app: App, uris: List<Uri>): List<PreparedShare> {
        val shareRoot = File(cacheDir, SHARE_DIR).apply { mkdirs() }
        val result = mutableListOf<PreparedShare>()
        val authority = "$packageName.share"

        for (uri in uris) {
            val displayName = queryDisplayName(uri)
            val baseName = displayName.substringBeforeLast('.').ifBlank { "screenshot" }
            val batchDir = File(shareRoot, UUID.randomUUID().toString()).apply { mkdirs() }
            val rawInput = File(batchDir, "src_${System.nanoTime()}.tmp")

            try {
                val copied = contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(rawInput).use { out -> input.copyTo(out) }
                } ?: 0L
                if (copied <= 0L) continue

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(rawInput.absolutePath, bounds)
                val w = bounds.outWidth
                val h = bounds.outHeight
                if (w <= 0 || h <= 0) continue

                val plan = ScalePlanner.plan(w, h, app.policy)
                if (plan != null) {
                    val outFile = File(batchDir, "$baseName.png")
                    app.upscaler.upscaleToFile(rawInput, outFile, plan, "image/png")
                    if (outFile.isFile && outFile.length() > 0L) {
                        result.add(PreparedShare(FileProvider.getUriForFile(this, authority, outFile), "image/png"))
                    }
                } else {
                    val origMime = (contentResolver.getType(uri) ?: bounds.outMimeType ?: "image/*").lowercase()
                    val outName = resolvePassthroughName(displayName, origMime)
                    val outFile = File(batchDir, outName)
                    rawInput.copyTo(outFile, overwrite = true)
                    if (outFile.isFile && outFile.length() > 0L) {
                        result.add(PreparedShare(FileProvider.getUriForFile(this, authority, outFile), origMime))
                    }
                }
            } catch (t: Throwable) {
                Log.w(App.LOG_TAG, "share HD prep failed for $uri: ${t.message}")
            } finally {
                rawInput.delete()
            }
        }
        return result
    }

    private fun resolvePassthroughName(displayName: String, mimeType: String): String {
        if (displayName.contains('.')) return displayName
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return if (!ext.isNullOrBlank()) "$displayName.$ext" else displayName
    }

    private fun buildSendIntent(prepared: List<PreparedShare>): Intent {
        val distinctMimes = prepared.map { it.mimeType }.distinct()
        val intentMime = if (distinctMimes.size == 1) distinctMimes.first() else "image/*"
        val uris = prepared.map { it.uri }
        val isSingle = uris.size == 1

        val sendIntent = Intent(if (isSingle) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = intentMime
            if (isSingle) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val clip = ClipData.newUri(contentResolver, "shared_image", uris.first())
        for (i in 1 until uris.size) {
            clip.addItem(ClipData.Item(uris[i]))
        }
        sendIntent.clipData = clip
        return sendIntent
    }

    private fun launchWhatsApp(baseIntent: Intent, targets: List<String>) {
        if (targets.size == 1) {
            val singleIntent = Intent(baseIntent).setPackage(targets.first())
            Log.i(App.LOG_TAG, "share mode=HD pkg=${targets.first()} mime=${baseIntent.type}")
            startActivity(singleIntent)
            return
        }

        val primary = Intent(baseIntent).setPackage(targets.first())
        val extras = targets.drop(1).map { pkg -> Intent(baseIntent).setPackage(pkg) }.toTypedArray()
        val chooser = Intent.createChooser(primary, "Share via WhatsApp").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, extras)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Log.i(App.LOG_TAG, "share mode=HD chooser=$targets mime=${baseIntent.type}")
        startActivity(chooser)
    }

    private fun installedWhatsAppPackages(): List<String> =
        listOf(PKG_WHATSAPP, PKG_WHATSAPP_BUSINESS).filter { pkg ->
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(pkg, 0)
                }
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

    private fun queryDisplayName(uri: Uri): String {
        val queried = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        if (!queried.isNullOrBlank()) return sanitizeFileName(queried)

        val fallback = uri.lastPathSegment?.substringAfterLast('/')
        return if (!fallback.isNullOrBlank()) sanitizeFileName(fallback) else "Screenshot_${System.currentTimeMillis()}.png"
    }

    private fun sanitizeFileName(name: String): String =
        name.replace('/', '_').replace('\\', '_').ifBlank { "Screenshot_${System.currentTimeMillis()}.png" }

    private fun pruneShareCache() {
        val shareRoot = File(cacheDir, SHARE_DIR)
        if (!shareRoot.isDirectory) return
        val cutoff = System.currentTimeMillis() - SHARE_MAX_AGE_MS
        val children = shareRoot.listFiles() ?: return
        for (child in children) {
            if (child.lastModified() < cutoff) {
                child.deleteRecursively()
            }
        }
    }

    companion object {
        private const val SHARE_DIR = "share"
        private const val SHARE_MAX_AGE_MS = 60L * 60L * 1000L
        private const val PKG_WHATSAPP = "com.whatsapp"
        private const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    }
}

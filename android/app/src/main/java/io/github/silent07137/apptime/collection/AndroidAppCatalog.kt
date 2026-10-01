// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.collection

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import io.github.silent07137.apptime.data.AppInspection
import java.io.File
import java.security.MessageDigest

/** Internal icon cache survives package removal; missing visibility is always unknown. */
class AndroidAppCatalog(private val context: Context) {
    private val icons = LruCache<String, Bitmap>(48)
    private val directory = File(context.filesDir, "app-icons").apply { mkdirs() }
    private fun file(pkg: String) = File(directory, digest(pkg.toByteArray()) + ".png")
    @Suppress("DEPRECATION")
    fun inspect(pkg: String): AppInspection = try {
        val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val application = info.applicationInfo
        val name = application?.let { context.packageManager.getApplicationLabel(it).toString() }
        val signing = info.signingInfo
        val digests = if (signing?.hasMultipleSigners() == true) listOf(digest(signing.apkContentsSigners.map { digest(it.toByteArray()) }.sorted().joinToString("|").toByteArray()))
            else signing?.signingCertificateHistory?.map { digest(it.toByteArray()) }?.sorted().orEmpty()
        icon(pkg)
        AppInspection(name, true, digests)
    } catch (_: PackageManager.NameNotFoundException) { AppInspection(null, false) }
      catch (_: SecurityException) { AppInspection(null, false) }

    fun icon(pkg: String): Bitmap? {
        icons.get(pkg)?.let { return it }
        val target = file(pkg)
        val bitmap = try {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
                drawable.setBounds(0, 0, 96, 96); drawable.draw(Canvas(it))
                try { target.outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) } }
                catch (_: java.io.IOException) { /* A cache failure never prevents collection. */ }
            }
        } catch (_: PackageManager.NameNotFoundException) { BitmapFactory.decodeFile(target.absolutePath) }
          catch (_: SecurityException) { BitmapFactory.decodeFile(target.absolutePath) }
        if (bitmap != null) icons.put(pkg, bitmap)
        return bitmap
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

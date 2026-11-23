package com.chimu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** What [ReceiptPhotos.load] found for a transaction's receipt photo. */
sealed interface PhotoResult {
    class Loaded(val bitmap: Bitmap) : PhotoResult

    /** [message] is in words for the user. */
    class Failed(val message: String) : PhotoResult
}

/**
 * The receipt photo files on this phone (RFC-004). Android types live here so [ReceiptImage]'s size
 * maths stays testable without a device.
 *
 * - `filesDir/receipts/<txn id>.jpg`: the phone's own copy, at most 1600 px on the long side, JPEG
 *   quality 80. This is the file sync uploads.
 * - `cacheDir/captures/<txn id>.jpg`: where the camera app writes the full-size shot, shared through
 *   a [FileProvider] URI. Deleted as soon as the shrunk copy exists.
 * - `cacheDir/receipt-downloads/<txn id>.jpg`: a photo fetched from the server for a phone that
 *   doesn't have the file (after a reinstall). The OS may clear it; it is fetched again when needed.
 */
object ReceiptPhotos {
    private fun dir(context: Context, parent: File, name: String) = File(parent, name).apply { mkdirs() }

    /** Where the phone keeps the shrunk photo for [txnId]. */
    fun photoFile(context: Context, txnId: String) = File(dir(context, context.filesDir, "receipts"), "$txnId.jpg")

    /** The full-size camera shot for [txnId] goes here before it is shrunk. */
    fun captureFile(context: Context, txnId: String) = File(dir(context, context.cacheDir, "captures"), "$txnId.jpg")

    /** A URI the camera app may write to. The authority matches the provider in AndroidManifest.xml. */
    fun captureUri(context: Context, txnId: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", captureFile(context, txnId))

    fun downloadedFile(context: Context, txnId: String) =
        File(dir(context, context.cacheDir, "receipt-downloads"), "$txnId.jpg")

    /**
     * Reads the picture at [source] (a camera shot or a gallery pick), turns it upright using its
     * EXIF tag, shrinks it to [ReceiptImage.MAX_EDGE] and writes it to [dest] as JPEG. The file
     * appears only when complete (written beside it, then renamed), so a crash never leaves half a photo.
     *
     * @throws IOException with a message fit for the user if the picture can't be read or saved.
     */
    fun saveResized(context: Context, source: Uri, dest: File) {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("Couldn't open that picture.")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("That file isn't a picture the app can read.")

        val decode = BitmapFactory.Options().apply { inSampleSize = ReceiptImage.sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = try {
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, decode) }
        } catch (e: OutOfMemoryError) {
            throw IOException("That picture is too big for this phone to handle.")
        } ?: throw IOException("Couldn't read that picture.")

        val orientation = resolver.openInputStream(source)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val upright = ReceiptImage.upright(orientation)
        val matrix = Matrix().apply {
            if (upright.degrees != 0) postRotate(upright.degrees.toFloat())
            if (upright.mirror) postScale(-1f, 1f)
        }
        val sideways = upright.degrees == 90 || upright.degrees == 270
        val uprightWidth = if (sideways) decoded.height else decoded.width
        val uprightHeight = if (sideways) decoded.width else decoded.height
        val (targetWidth, targetHeight) = ReceiptImage.fit(uprightWidth, uprightHeight)
        if (targetWidth != uprightWidth) matrix.postScale(targetWidth.toFloat() / uprightWidth, targetHeight.toFloat() / uprightHeight)

        val finished = try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            throw IOException("That picture is too big for this phone to handle.")
        }

        val temp = File(dest.parentFile, dest.name + ".part")
        try {
            temp.outputStream().use { out ->
                if (!finished.compress(Bitmap.CompressFormat.JPEG, ReceiptImage.JPEG_QUALITY, out)) {
                    throw IOException("Couldn't save the photo.")
                }
            }
            if (!temp.renameTo(dest)) throw IOException("Couldn't save the photo.")
        } finally {
            temp.delete() // gone already after a successful rename
            if (finished !== decoded) decoded.recycle()
            finished.recycle()
        }
    }

    /**
     * The picture for a transaction's receipt: this phone's own file if it has one, else a cached
     * download, else a fresh download of [receiptPath] through [download] (a phone after a reinstall).
     * Call off the main thread.
     */
    suspend fun load(
        context: Context,
        txnId: String,
        localPhoto: String?,
        receiptPath: String?,
        download: suspend (String) -> ByteArray,
    ): PhotoResult {
        val own = localPhoto?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
        if (own != null) return decode(own) ?: PhotoResult.Failed("The photo file on this phone is damaged.")

        val cached = downloadedFile(context, txnId)
        if (cached.isFile) decode(cached)?.let { return it }

        if (receiptPath.isNullOrEmpty()) return PhotoResult.Failed("This receipt's photo isn't on this phone.")
        return try {
            val bytes = download(receiptPath)
            val temp = File(cached.parentFile, cached.name + ".part")
            temp.writeBytes(bytes)
            if (!temp.renameTo(cached)) temp.delete()
            decode(cached)?.let { it } ?: PhotoResult.Failed("The photo couldn't be shown.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SyncUnavailable) {
            PhotoResult.Failed("Couldn't load the photo. Check your connection and open this entry again.")
        } catch (e: Exception) {
            PhotoResult.Failed("The photo couldn't be loaded.")
        }
    }

    private fun decode(file: File): PhotoResult.Loaded? =
        runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()?.let { PhotoResult.Loaded(it) }
}

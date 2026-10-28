package com.audiophile.engine.library.source

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** What the user is asked to approve before the local copy is deleted. */
data class MoveReport(val localFile: File, val sizeBytes: Long, val md5: String, val remote: UploadedFile)

sealed interface MoveOutcome {
    /** Uploaded, verified, approved, and the local copy is gone. */
    data class Moved(val remote: UploadedFile) : MoveOutcome

    /** Uploaded and verified, but the local copy stays. */
    data class KeptLocal(val remote: UploadedFile, val why: Why) : MoveOutcome {
        enum class Why { USER_DECLINED, LOCAL_FILE_CHANGED, DELETE_FAILED }
    }

    /** Drive's copy did not match the local file. Nothing was deleted locally; the bad upload was removed if possible. */
    data class ChecksumMismatch(val localMd5: String, val remoteMd5: String?, val remoteRemoved: Boolean) : MoveOutcome

    data class Failed(val reason: String) : MoveOutcome
}

/**
 * Move-to-Drive for a Telegram download (Master Plan 5.F.5). The local copy is deleted only when
 * both conditions hold, in this order:
 *
 *  1. The md5Checksum Drive computed for the stored file equals the md5 of the local file.
 *  2. The user approved the deletion, through [confirmDelete], which is called after step 1.
 *
 * A file that changed on disk between hashing and deleting is also kept. Nothing here deletes
 * on error: every failure path leaves the local file exactly as it was.
 */
class SafeMoveToDrive(private val uploader: DriveUploader) {

    fun move(
        local: File,
        mimeType: String,
        name: String = local.name,
        parentId: String? = null,
        confirmDelete: (MoveReport) -> Boolean,
        cancel: AtomicBoolean = AtomicBoolean(false),
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): MoveOutcome {
        if (!local.isFile) return MoveOutcome.Failed("not a file: ${local.path}")
        val size = local.length()
        val modified = local.lastModified()
        val localMd5 = try {
            md5Of(local)
        } catch (e: IOException) {
            return MoveOutcome.Failed("could not read the local file: ${e.message}")
        }

        val remote = try {
            uploader.upload(local, name, mimeType, parentId, cancel, onProgress)
        } catch (e: IOException) {
            return MoveOutcome.Failed(e.message ?: "upload failed")
        }

        if (remote.md5Checksum == null || !remote.md5Checksum.equals(localMd5, ignoreCase = true) || remote.sizeBytes != size) {
            val removed = uploader.delete(remote.id)
            return MoveOutcome.ChecksumMismatch(localMd5, remote.md5Checksum, removed)
        }

        if (!confirmDelete(MoveReport(local, size, localMd5, remote))) {
            return MoveOutcome.KeptLocal(remote, MoveOutcome.KeptLocal.Why.USER_DECLINED)
        }
        // The user may have waited a while; make sure this is still the file that was verified.
        if (!local.isFile || local.length() != size || local.lastModified() != modified) {
            return MoveOutcome.KeptLocal(remote, MoveOutcome.KeptLocal.Why.LOCAL_FILE_CHANGED)
        }
        return if (local.delete()) MoveOutcome.Moved(remote)
        else MoveOutcome.KeptLocal(remote, MoveOutcome.KeptLocal.Why.DELETE_FAILED)
    }

    internal fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return NextTrackPrefetcher.hex(digest.digest())
    }
}

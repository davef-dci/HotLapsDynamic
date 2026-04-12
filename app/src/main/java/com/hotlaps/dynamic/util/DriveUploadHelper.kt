package com.hotlaps.dynamic.util

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.hotlaps.dynamic.data.FileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Handles Google Drive authentication and CSV backup uploads.
 *
 * Uses the drive.file scope (narrowest possible — only sees files this app created).
 * Sign-in is triggered from the UI; uploads happen automatically from the backup job.
 *
 * Architecture:
 *  - hasDrivePermission() / getSignedInEmail() — read-only, safe to call on any thread
 *  - uploadBackupFile() — suspending, runs on IO dispatcher
 *  - getSignInOptions() / getSignInClient() — used by GGUi to launch the sign-in intent
 */
object DriveUploadHelper {

    private const val TAG = "DriveUpload"
    private const val FOLDER_NAME = "ApexDynamics"
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val BASE_URL = "https://www.googleapis.com"

    // Process-lifetime caches — cleared on process restart, re-fetched as needed
    private var cachedFolderId: String? = null
    private val cachedFileIds = mutableMapOf<String, String>()   // remoteName → Drive file ID

    // ---- Public API ----------------------------------------------------------

    fun getSignInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_FILE_SCOPE))
            .build()

    /** True if the user has signed in and granted the Drive scope. */
    fun hasDrivePermission(context: Context): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return GoogleSignIn.hasPermissions(account, Scope(DRIVE_FILE_SCOPE))
    }

    /** The email of the signed-in account, or null if not signed in. */
    fun getSignedInEmail(context: Context): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    /**
     * Uploads the backup CSV for [eventId] to the ApexDynamics folder in Drive.
     * No-ops silently if the user is not signed in.
     * Safe to call from a background coroutine. Returns true on success.
     */
    suspend fun uploadBackupFile(context: Context, eventId: Long): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val dir = FileHelper.eventsDir(context) ?: return@withContext false
                val backup = File(dir, "event_${eventId}_backup.csv")
                if (!backup.exists()) return@withContext false

                val account = GoogleSignIn.getLastSignedInAccount(context)
                    ?: return@withContext false.also { Log.d(TAG, "Not signed in — skipping upload") }

                if (!GoogleSignIn.hasPermissions(account, Scope(DRIVE_FILE_SCOPE))) {
                    Log.d(TAG, "No Drive permission — skipping upload")
                    return@withContext false
                }

                val token = getToken(context, account)
                    ?: return@withContext false.also { Log.w(TAG, "Could not get access token") }

                val folderId = ensureFolder(token)
                    ?: return@withContext false.also { Log.w(TAG, "Could not find/create Drive folder") }

                val remoteName = backup.name
                val existingId = cachedFileIds[remoteName]
                    ?: findFile(token, remoteName, folderId)?.also { cachedFileIds[remoteName] = it }

                val success = if (existingId != null) {
                    updateFile(token, existingId, backup)
                } else {
                    createFile(token, remoteName, backup, folderId)
                        ?.also { cachedFileIds[remoteName] = it } != null
                }

                if (success) Log.i(TAG, "Drive upload OK: ${backup.name} (${backup.length() / 1024} KB)")
                else Log.w(TAG, "Drive upload failed for $remoteName")
                success
            } catch (e: Exception) {
                Log.e(TAG, "uploadBackupFile failed: ${e.message}", e)
                false
            }
        }

    // ---- Auth ---------------------------------------------------------------

    private fun getToken(context: Context, account: GoogleSignInAccount): String? {
        val androidAccount = account.account ?: return null
        return try {
            GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$DRIVE_FILE_SCOPE")
        } catch (e: Exception) {
            Log.e(TAG, "getToken: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ---- Folder management --------------------------------------------------

    private fun ensureFolder(token: String): String? {
        cachedFolderId?.let { return it }
        val id = findFolder(token) ?: createFolder(token) ?: return null
        cachedFolderId = id
        return id
    }

    private fun findFolder(token: String): String? {
        val q = "name='$FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false"
        val conn = URL("$BASE_URL/drive/v3/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)")
            .openConnection() as HttpURLConnection
        return try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode == 200) {
                val files = JSONObject(conn.inputStream.bufferedReader().readText())
                    .getJSONArray("files")
                if (files.length() > 0) files.getJSONObject(0).getString("id") else null
            } else null
        } finally { conn.disconnect() }
    }

    private fun createFolder(token: String): String? {
        val conn = URL("$BASE_URL/drive/v3/files?fields=id")
            .openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.write(
                JSONObject().apply {
                    put("name", FOLDER_NAME)
                    put("mimeType", "application/vnd.google-apps.folder")
                }.toString().toByteArray(Charsets.UTF_8)
            )
            val code = conn.responseCode
            if (code == 200 || code == 201) {
                JSONObject(conn.inputStream.bufferedReader().readText()).getString("id")
            } else { Log.w(TAG, "createFolder: HTTP $code"); null }
        } finally { conn.disconnect() }
    }

    // ---- File operations ----------------------------------------------------

    private fun findFile(token: String, name: String, folderId: String): String? {
        val q = "name='$name' and '$folderId' in parents and trashed=false"
        val conn = URL("$BASE_URL/drive/v3/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)")
            .openConnection() as HttpURLConnection
        return try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode == 200) {
                val files = JSONObject(conn.inputStream.bufferedReader().readText())
                    .getJSONArray("files")
                if (files.length() > 0) files.getJSONObject(0).getString("id") else null
            } else null
        } finally { conn.disconnect() }
    }

    /** Creates a new file in Drive with metadata (name + parent folder). Returns the new file ID. */
    private fun createFile(token: String, name: String, file: File, folderId: String): String? {
        val boundary = "apexdyn_${System.currentTimeMillis()}"
        val conn = URL("$BASE_URL/upload/drive/v3/files?uploadType=multipart&fields=id")
            .openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            conn.doOutput = true

            val meta = JSONObject().apply {
                put("name", name)
                put("parents", JSONArray().put(folderId))
            }.toString()

            conn.outputStream.write(buildMultipartBody(boundary, meta, file.readBytes()))

            val code = conn.responseCode
            if (code == 200 || code == 201) {
                JSONObject(conn.inputStream.bufferedReader().readText()).getString("id")
            } else { Log.w(TAG, "createFile: HTTP $code"); null }
        } finally { conn.disconnect() }
    }

    /** Updates an existing Drive file's content in-place. */
    private fun updateFile(token: String, fileId: String, file: File): Boolean {
        val conn = URL("$BASE_URL/upload/drive/v3/files/$fileId?uploadType=media")
            .openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "PATCH"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "text/csv")
            conn.doOutput = true
            conn.outputStream.write(file.readBytes())
            conn.responseCode in 200..299
        } finally { conn.disconnect() }
    }

    private fun buildMultipartBody(boundary: String, meta: String, fileBytes: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray(Charsets.UTF_8))
        baos.write(meta.toByteArray(Charsets.UTF_8))
        baos.write("\r\n--$boundary\r\nContent-Type: text/csv\r\n\r\n".toByteArray(Charsets.UTF_8))
        baos.write(fileBytes)
        baos.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return baos.toByteArray()
    }
}

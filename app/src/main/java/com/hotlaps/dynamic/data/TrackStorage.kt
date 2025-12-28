package com.hotlaps.dynamic.data

import android.content.Context
import android.util.Log
import com.hotlaps.dynamic.model.Track
import com.hotlaps.dynamic.model.Corner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.net.Uri
import androidx.core.content.FileProvider



object TrackStorage {

    private const val TAG = "TrackStorage"

    data class TrackWithFile(
        val id: Long,
        val file: File,
        val track: Track
    )

    data class ImportResult(
        val imported: Int,
        val skipped: Int,
        val errors: Int,
        val messages: List<String>
    )

    fun importTracksFromPublicDownloads(context: Context): ImportResult {
        val publicDir = FileHelper.publicTracksDir(context)
        val candidates = publicDir.listFiles { f ->
            f.isFile && f.name.endsWith(".json", ignoreCase = true)
        } ?: emptyArray()

        if (candidates.isEmpty()) {
            return ImportResult(
                imported = 0,
                skipped = 0,
                errors = 0,
                messages = listOf("No .json files found in ${publicDir.absolutePath}")
            )
        }

        var imported = 0
        var skipped = 0
        var errors = 0
        val msgs = mutableListOf<String>()

        for (file in candidates) {
            try {
                val json = file.readText()

                // Try to extract an id from filename if it matches track_<id>.json, otherwise fake it.
                val idFromName = extractIdFromFileName(file.name) ?: System.currentTimeMillis()

                val parsed = jsonToTrack(idFromName, json)

                // IMPORTANT: avoid collisions with existing IDs by assigning a fresh ID on import
                val importedTrack = parsed.copy(id = System.currentTimeMillis())

                val ok = saveTrack(context, importedTrack)
                if (ok) {
                    imported++
                    msgs += "Imported: ${file.name} → ${importedTrack.name}"
                } else {
                    errors++
                    msgs += "Failed saving: ${file.name}"
                }
            } catch (t: Throwable) {
                errors++
                msgs += "Error importing ${file.name}: ${t.message}"
            }
        }

        return ImportResult(imported, skipped, errors, msgs)
    }







    // Get (and create) the tracks directory. Returns null if unavailable.
    private fun tracksDir(context: Context): File? {
        val base = context.getExternalFilesDir(null)
        if (base == null) {
            Log.e(TAG, "tracksDir: getExternalFilesDir(null) returned null")
            return null
        }

        val dir = File(base, "tracks")
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Log.e(TAG, "tracksDir: failed to create ${dir.absolutePath}")
                return null
            }
        }
        return dir
    }

    // ---- Save a Track to JSON file ----
    fun saveTrack(context: Context, track: Track): Boolean {
        val dir = tracksDir(context)
        if (dir == null) {
            Log.e(TAG, "saveTrack: tracksDir is null, not saving")
            return false
        }

        return try {
            val file = File(dir, "track_${track.id}.json")
            val json = trackToJsonString(track)
            file.writeText(json)
            Log.d(TAG, "saveTrack: Saved ${track.name} to ${file.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "saveTrack: Error saving track ${track.name}", t)
            false
        }
    }

    // ---- List all saved tracks ----
    fun listTracks(context: Context): List<TrackWithFile> {
        val dir = tracksDir(context)
        if (dir == null) {
            Log.w(TAG, "listTracks: tracksDir is null, returning empty list")
            return emptyList()
        }

        val files = dir.listFiles { file ->
            file.isFile && file.name.startsWith("track_") && file.name.endsWith(".json")
        } ?: return emptyList()

        val result = mutableListOf<TrackWithFile>()

        for (file in files) {
            val idFromName = extractIdFromFileName(file.name) ?: continue

            val json: String = try {
                file.readText()
            } catch (e: Exception) {
                Log.e(TAG, "listTracks: Error reading ${file.name}", e)
                continue
            }

            val track = try {
                jsonToTrack(idFromName, json)
            } catch (e: Exception) {
                Log.e(TAG, "listTracks: Error parsing ${file.name}", e)
                null
            }

            if (track != null) {
                result += TrackWithFile(
                    id = track.id,
                    file = file,
                    track = track
                )
            }
        }

        return result.sortedWith(compareBy({ it.track.name.lowercase() }, { it.id }))
    }

    private fun extractIdFromFileName(name: String): Long? {
        // Expect: track_<id>.json
        return try {
            val idPart = name.removePrefix("track_").removeSuffix(".json")
            idPart.toLong()
        } catch (e: Exception) {
            Log.e(TAG, "extractIdFromFileName: cannot parse id from $name", e)
            null
        }
    }

    // ---- Delete a track file ----
    fun deleteTrackFile(trackWithFile: TrackWithFile): Boolean {
        return try {
            val ok = trackWithFile.file.delete()
            if (!ok) {
                Log.e(TAG, "deleteTrackFile: Failed to delete ${trackWithFile.file.absolutePath}")
            } else {
                Log.d(TAG, "deleteTrackFile: Deleted ${trackWithFile.file.absolutePath}")
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "deleteTrackFile: Error deleting ${trackWithFile.file.absolutePath}", t)
            false
        }
    }

    // ---- JSON helpers (no Gson) ----

    private fun trackToJsonString(track: Track): String {
        val root = JSONObject()
        root.put("id", track.id)
        root.put("name", track.name)

        val cornersArray = JSONArray()
        for (corner in track.corners) {
            val c = JSONObject()
            c.put("index", corner.index)
            // officialNumber may be null
            if (corner.officialNumber != null) {
                c.put("officialNumber", corner.officialNumber)
            } else {
                c.put("officialNumber", JSONObject.NULL)
            }
            // name may be null / blank
            if (corner.name != null) {
                c.put("name", corner.name)
            } else {
                c.put("name", JSONObject.NULL)
            }
            c.put("lat", corner.lat)
            c.put("lon", corner.lon)
            cornersArray.put(c)
        }
        root.put("corners", cornersArray)

        return root.toString()
    }

    private fun jsonToTrack(idFromFileName: Long, json: String): Track {
        val root = JSONObject(json)

        val id = if (root.has("id")) root.getLong("id") else idFromFileName
        val name = root.optString("name", "")

        val cornersJson = root.optJSONArray("corners") ?: JSONArray()
        val corners = mutableListOf<Corner>()

        for (i in 0 until cornersJson.length()) {
            val c = cornersJson.getJSONObject(i)

            val index = c.optInt("index", i + 1)
            val officialNumber =
                if (c.has("officialNumber") && !c.isNull("officialNumber")) {
                    c.getInt("officialNumber")
                } else {
                    null
                }
            val cornerNameRaw =
                if (c.has("name") && !c.isNull("name")) c.optString("name", null) else null
            val cornerName = cornerNameRaw?.takeIf { it.isNotBlank() }

            val lat = c.getDouble("lat")
            val lon = c.getDouble("lon")

            corners += Corner(
                index = index,
                officialNumber = officialNumber,
                name = cornerName,
                lat = lat,
                lon = lon
            )
        }

        return Track(
            id = id,
            name = name,
            corners = corners
        )
    }

    fun importSingleTrackFromUri(context: Context, uri: Uri): ImportResult {
        return try {
            val json = context.contentResolver
                .openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return ImportResult(
                    imported = 0,
                    skipped = 0,
                    errors = 1,
                    messages = listOf("Unable to read selected file")
                )

            // Parse using your existing JSON parser
            val parsed = jsonToTrack(System.currentTimeMillis(), json)

            // Avoid overwriting an existing track ID by assigning a fresh ID
            val importedTrack = parsed.copy(id = System.currentTimeMillis())

            val ok = saveTrack(context, importedTrack)

            if (ok) {
                ImportResult(
                    imported = 1,
                    skipped = 0,
                    errors = 0,
                    messages = listOf("Imported: ${importedTrack.name}")
                )
            } else {
                ImportResult(
                    imported = 0,
                    skipped = 0,
                    errors = 1,
                    messages = listOf("Failed to save imported track")
                )
            }
        } catch (t: Throwable) {
            ImportResult(
                imported = 0,
                skipped = 0,
                errors = 1,
                messages = listOf("Import error: ${t.message}")
            )
        }
    }

    /**
     * Export an authoritative CSV template for track creation.
     *
     * Columns:
     *   Track Name, Corner Name, Latitude, Longitude
     */
    fun exportTrackCsvTemplateToUri(
        context: Context,
        uri: Uri
    ): Boolean {
        return try {
            val csv = buildString {
                append("Track Name,Corner Name,Latitude,Longitude\n")
                append("Road America,Turn 1,43.801234,-87.989876\n")
                append("Road America,Turn 3 (Carousel),43.792222,-87.975555\n")
            }

            context.contentResolver
                .openOutputStream(uri, "w")
                ?.bufferedWriter(Charsets.UTF_8)
                ?.use { writer ->
                    writer.write(csv)
                } ?: return false

            Log.d(TAG, "exportTrackCsvTemplateToUri: wrote template to $uri")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "exportTrackCsvTemplateToUri: failed", t)
            false
        }
    }

    /**
     * Copies the static CSV template from assets to cache
     * and returns a FileProvider Uri suitable for sharing.
     */
    fun buildShareableCsvTemplateUri(context: Context): Uri? {
        return try {
            val outFile = File(
                context.cacheDir,
                "ApexDynamics_TrackTemplate.csv"
            )

            context.assets.open("track_template.csv").use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
        } catch (t: Throwable) {
            Log.e(TAG, "buildShareableCsvTemplateUri failed", t)
            null
        }
    }

    fun importSingleTrackCsvFromText(context: Context, csvText: String): ImportResult {
        return try {
            val lines = csvText
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filter { !it.startsWith("#") }
                .toList()

            if (lines.size < 2) {
                return ImportResult(
                    imported = 0,
                    skipped = 0,
                    errors = 1,
                    messages = listOf("CSV too short. Need at least a header + 1 corner row.")
                )
            }

            // Expect header: Track Name,Corner Name,Latitude,Longitude
            val header = splitCsvLine(lines[0]).map { it.trim().lowercase() }

            val trackIdx = header.indexOfFirst { it.contains("track") && it.contains("name") }
                .takeIf { it >= 0 }
                ?: return ImportResult(0, 0, 1, listOf("Header must include 'Track Name' column."))

            val cornerIdx = header.indexOfFirst { it.contains("corner") && it.contains("name") }
                .takeIf { it >= 0 }
                ?: return ImportResult(0, 0, 1, listOf("Header must include 'Corner Name' column."))

            val latIdx = header.indexOfFirst { it.contains("lat") }
                .takeIf { it >= 0 }
                ?: return ImportResult(0, 0, 1, listOf("Header must include 'Latitude' column."))

            val lonIdx = header.indexOfFirst { it.contains("lon") }
                .takeIf { it >= 0 }
                ?: return ImportResult(0, 0, 1, listOf("Header must include 'Longitude' column."))

            val dataLines = lines.drop(1)
            if (dataLines.isEmpty()) {
                return ImportResult(0, 0, 1, listOf("No corner rows found under header."))
            }

            var errors = 0
            val messages = mutableListOf<String>()
            val corners = mutableListOf<Corner>()

            var trackName: String? = null

            dataLines.forEachIndexed { i, raw ->
                val rowNum = i + 2 // human-readable row number (1 header + 1-based)
                val cols = splitCsvLine(raw)

                fun colOrNull(idx: Int): String? = cols.getOrNull(idx)?.trim()

                val tn = colOrNull(trackIdx).orEmpty()
                val cn = colOrNull(cornerIdx).orEmpty()
                val latStr = colOrNull(latIdx)
                val lonStr = colOrNull(lonIdx)

                if (trackName == null && tn.isNotBlank()) trackName = tn

                val lat = latStr?.toDoubleOrNull()
                val lon = lonStr?.toDoubleOrNull()

                if (cn.isBlank() || lat == null || lon == null) {
                    errors++
                    messages += "Row $rowNum: invalid (need Corner Name, Latitude, Longitude)."
                    return@forEachIndexed
                }

                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    errors++
                    messages += "Row $rowNum: lat/lon out of range."
                    return@forEachIndexed
                }

                val index = corners.size + 1

                corners += Corner(
                    index = index,
                    officialNumber = null,
                    name = cn,
                    lat = lat,
                    lon = lon
                )
            }

            if (corners.isEmpty()) {
                return ImportResult(0, 0, 1, listOf("No valid corner rows found.") + messages.take(5))
            }

            val newTrack = Track(
                id = System.currentTimeMillis(),
                name = (trackName ?: "Imported Track").ifBlank { "Imported Track" },
                corners = corners
            )

            val ok = saveTrack(context, newTrack)
            if (ok) {
                ImportResult(
                    imported = 1,
                    skipped = 0,
                    errors = errors,
                    messages = listOf("Imported: ${newTrack.name} (${corners.size} corners)") + messages.take(5)
                )
            } else {
                ImportResult(0, 0, 1, listOf("Failed to save imported track."))
            }
        } catch (t: Throwable) {
            ImportResult(0, 0, 1, listOf("CSV import error: ${t.message}"))
        }
    }

    /**
     * Tiny CSV splitter that supports quoted fields ("like, this").
     * Good enough for names with commas if user puts quotes around them.
     */
    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    // handle escaped quotes ""
                    val nextIsQuote = (i + 1 < line.length && line[i + 1] == '"')
                    if (inQuotes && nextIsQuote) {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    out += sb.toString()
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }




}

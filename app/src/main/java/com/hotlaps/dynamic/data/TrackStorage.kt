package com.hotlaps.dynamic.data

import android.content.Context
import android.util.Log
import com.hotlaps.dynamic.model.Track
import com.hotlaps.dynamic.model.Corner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object TrackStorage {

    private const val TAG = "TrackStorage"

    data class TrackWithFile(
        val id: Long,
        val file: File,
        val track: Track
    )

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
}

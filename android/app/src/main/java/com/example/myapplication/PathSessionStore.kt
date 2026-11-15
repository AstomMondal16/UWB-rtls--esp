package com.example.myapplication

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

internal data class SavedPathSummary(val id: String, val startedAtMs: Long, val endedAtMs: Long,
    val pointCount: Int, val tags: List<String>, val interrupted: Boolean, val active: Boolean = false)

internal data class SavedPath(val summary: SavedPathSummary, val devices: List<DeviceRecord>,
    val trails: Map<String, List<TrailPoint>>) {
    val positions: Map<String, Position> get() = trails.mapValues { (_, points) ->
        points.last().let { Position(it.x, it.y, 0.0, 0.0, emptyList()) }
    }
}

/** App-private paths are separate from CSV recording and from the short live trail. */
internal class PathSessionStore(private val directory: File, private val maxPoints: Int = 100_000,
    private val maxBytes: Long = Long.MAX_VALUE, private val maxSessions: Int = Int.MAX_VALUE,
    private val clock: () -> Long = System::currentTimeMillis, private val minFreeBytes: Long = 256L * 1024 * 1024) {
    private data class Active(val id: String, val started: Long, val file: File,
        val output: FileOutputStream, var count: Int = 0, var lastAt: Long = started, val tags: MutableSet<String> = linkedSetOf())
    private var active: Active? = null
    private var bytesUsed = 0L
    private var nextCleanupAt = Long.MIN_VALUE
    private var nextSpaceCheckAt = Long.MIN_VALUE
    private var freeSpaceAvailable = true

    init {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create path storage")
        // A killed process may leave a partial last line; retain every complete, valid position.
        recoverInterrupted()
        maintenance(clock())
    }

    @Synchronized fun start(devices: List<DeviceRecord>, now: Long) {
        check(active == null) { "A path is already recording" }
        recoverInterrupted()
        bytesUsed = directory.listFiles().orEmpty().sumOf { it.length() }
        if (directory.listFiles().orEmpty().count { it.extension == "jsonl" } >= maxSessions)
            throw IOException("Path history is full ($maxSessions sessions). Delete an old path first.")
        val id = UUID.randomUUID().toString()
        val header = JSONObject().put("version", 1).put("id", id).put("started_at_ms", now)
            .put("devices", JSONArray(encodeDevices(devices))).toString() + "\n"
        val bytes = header.toByteArray(Charsets.UTF_8)
        if (bytes.size > 128 * 1024 || bytesUsed + bytes.size + 4096 > maxBytes || !hasFreeSpace())
            throw IOException("Phone storage is low. Path saving will retry automatically; newer history has been kept.")
        val file = File(directory, "$id.recording")
        val output = FileOutputStream(file)
        try { output.write(bytes) } catch (e: IOException) { output.close(); file.delete(); throw e }
        bytesUsed += bytes.size
        active = Active(id, now, file, output)
        openFiles.add(file.absolutePath)
    }

    @Synchronized fun pointCount(): Int = active?.count ?: 0
    @Synchronized fun isRecording(): Boolean = active != null

    private fun hasFreeSpace(): Boolean {
        if (minFreeBytes == 0L) return true
        val now = clock()
        if (now >= nextSpaceCheckAt) {
            freeSpaceAvailable = directory.usableSpace >= minFreeBytes
            nextSpaceCheckAt = now + 1000
        }
        return freeSpaceAvailable
    }

    /** Rotate bounded files without losing the sample that starts the next file. */
    @Synchronized fun record(devices: List<DeviceRecord>, points: Map<String, TrailPoint>, now: Long) {
        if (now >= nextCleanupAt) maintenance(now)
        points.forEach { (tag, point) ->
            if (tag.isBlank() || !point.x.isFinite() || !point.y.isFinite()) return@forEach
            val session = active
            if (session != null && (session.count >= maxPoints ||
                (tag !in session.tags && session.tags.size >= 64) || utcDay(session.started) != utcDay(now))) seal()
            if (active == null) start(devices, now)
            append(mapOf(tag to point))
        }
    }

    @Synchronized fun seal(): SavedPathSummary? = active?.let { finish(it.lastAt) }

    @Synchronized fun maintenance(now: Long) {
        active?.let { if (utcDay(it.started) != utcDay(now)) seal() }
        deleteExpiredFiles(directory, now)
        bytesUsed = directory.listFiles().orEmpty().sumOf { it.length() }
        nextCleanupAt = now + 60 * 60 * 1000L
    }

    @Synchronized fun append(points: Map<String, TrailPoint>) {
        val session = active ?: return
        points.forEach { (tag, point) ->
            if (tag.isBlank() || !point.x.isFinite() || !point.y.isFinite()) return@forEach
            val row = JSONArray().put(tag).put(point.x).put(point.y).put(point.receivedAtMs)
                .put(point.startsSegment || tag !in session.tags).toString() + "\n"
            val bytes = row.toByteArray(Charsets.UTF_8)
            if (session.count >= maxPoints || (tag !in session.tags && session.tags.size >= 64) ||
                bytesUsed + bytes.size + 4096 > maxBytes || !hasFreeSpace()) {
                seal()
                throw IOException("Path saved at the storage limit. Automatic saving will retry; newer history has been kept.")
            }
            session.output.write(bytes)
            bytesUsed += bytes.size
            session.count++
            session.lastAt = maxOf(session.lastAt, point.receivedAtMs)
            session.tags.add(tag)
        }
    }

    @Synchronized fun finish(now: Long): SavedPathSummary? {
        val session = active ?: return null
        active = null
        if (session.count == 0) {
            try { session.output.close() } finally { openFiles.remove(session.file.absolutePath) }
            bytesUsed -= session.file.length()
            if (!session.file.delete()) throw IOException("Could not remove the empty path")
            return null
        }
        val summary = SavedPathSummary(session.id, session.started, now, session.count, session.tags.toList(), false)
        try {
            val bytes = (footer(summary).toString() + "\n").toByteArray(Charsets.UTF_8)
            session.output.write(bytes)
            bytesUsed += bytes.size
            session.output.fd.sync()
        } finally {
            try { session.output.close() } finally { openFiles.remove(session.file.absolutePath) }
        }
        if (!session.file.renameTo(pathFile(session.id))) throw IOException("Could not finish saving the path")
        return summary
    }

    @Synchronized fun list(): List<SavedPathSummary> {
        if (active == null) recoverInterrupted()
        if (clock() >= nextCleanupAt) maintenance(clock())
        val saved = directory.listFiles().orEmpty().filter { it.extension == "jsonl" }
            .mapNotNull { runCatching { readSummary(it) }.getOrNull() }
        val live = active?.takeIf { it.count > 0 }?.let { summary(it) }
        return (saved + listOfNotNull(live)).sortedByDescending { it.startedAtMs }
    }

    fun load(id: String, previewPoints: Int = 5000): SavedPath {
        require(previewPoints > 0)
        val live = synchronized(this) {
            active?.takeIf { it.id == id }?.let { summary(it) to it.file.readBytes() }
        }
        val file = pathFile(id)
        if (live == null && !file.isFile) throw IOException("This path has expired or is no longer available.")
        val summary = live?.first ?: readSummary(file)
        val stride = ceil(summary.pointCount.toDouble() / previewPoints).toInt().coerceAtLeast(1)
        val trails = linkedMapOf<String, MutableList<TrailPoint>>()
        val pendingBreak = mutableMapOf<String, Boolean>()
        val last = mutableMapOf<String, TrailPoint>()
        val tagCounts = mutableMapOf<String, Int>()
        var devices = emptyList<DeviceRecord>()
        var count = 0
        val reader = live?.second?.inputStream()?.bufferedReader() ?: file.bufferedReader()
        reader.use { input ->
            devices = decodeDevices(JSONObject(input.readLine()).getJSONArray("devices").toString())
            input.lineSequence().forEach { line ->
                val (tag, point) = parsePoint(line) ?: return@forEach
                if (++count > maxPoints) throw IOException("Path exceeds the supported position limit")
                val history = trails.getOrPut(tag) { mutableListOf() }
                val broken = pendingBreak[tag] == true || point.startsSegment
                val next = point.copy(startsSegment = broken || history.isEmpty())
                last[tag] = next
                val tagCount = (tagCounts[tag] ?: 0) + 1
                tagCounts[tag] = tagCount
                if (history.isEmpty() || tagCount % stride == 0) {
                    history.add(next)
                    pendingBreak[tag] = false
                } else pendingBreak[tag] = broken
            }
        }
        last.forEach { (tag, point) ->
            val history = trails.getValue(tag)
            if (history.last() != point) history.add(point)
        }
        if (count != summary.pointCount) throw IOException("Saved path is incomplete or damaged")
        return SavedPath(summary, devices, trails)
    }

    @Synchronized fun delete(id: String) {
        val file = pathFile(id)
        val bytes = file.length()
        if (!file.isFile || !file.delete()) throw IOException("Could not delete this path")
        bytesUsed -= bytes
    }

    private fun pathFile(id: String): File {
        require(Regex("[0-9a-fA-F-]{36}").matches(id)) { "Invalid path ID" }
        return File(directory, "$id.jsonl")
    }

    private fun footer(s: SavedPathSummary) = JSONObject().put("ended_at_ms", s.endedAtMs)
        .put("point_count", s.pointCount).put("tags", JSONArray(s.tags)).put("interrupted", s.interrupted)

    private fun readSummary(file: File): SavedPathSummary = summaryOf(file, maxBytes)

    private fun summary(session: Active) = SavedPathSummary(session.id, session.started, session.lastAt,
        session.count, session.tags.toList(), false, active = true)

    companion object {
        private val openFiles = ConcurrentHashMap.newKeySet<String>()

        private fun utcDay(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).toLocalDate()

        internal fun expiresAt(time: Long): Long = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC)
            .plusMonths(1).toInstant().toEpochMilli()

        /** Also used by the OS job; never recover or touch a writer that is open in this process. */
        @Synchronized internal fun deleteExpiredFiles(directory: File, now: Long) {
            directory.listFiles().orEmpty().forEach { file ->
                if (Thread.currentThread().isInterrupted) return
                if (!file.isFile || file.absolutePath in openFiles ||
                    file.extension !in setOf("jsonl", "recording", "damaged")) return@forEach
                val last = if (file.extension == "jsonl")
                    runCatching { summaryOf(file).endedAtMs }.getOrElse { file.lastModified() }
                    else file.lastModified()
                if (expiresAt(last) <= now && !file.delete() && file.exists())
                    throw IOException("Could not remove expired path history")
            }
        }

        private fun summaryOf(file: File, maxBytes: Long = Long.MAX_VALUE): SavedPathSummary {
            if (file.length() > maxBytes) throw IOException("Path file exceeds storage limit")
            val header = file.bufferedReader().use { JSONObject(it.readLine()) }
            if (header.getInt("version") != 1) throw IOException("Unsupported path format")
            val end = RandomAccessFile(file, "r").use { input ->
                val length = minOf(input.length(), 4096).toInt()
                val bytes = ByteArray(length)
                input.seek(input.length() - length); input.readFully(bytes)
                JSONObject(bytes.toString(Charsets.UTF_8).trimEnd().substringAfterLast('\n'))
            }
            val tags = end.getJSONArray("tags")
            return SavedPathSummary(header.getString("id"), header.getLong("started_at_ms"),
                end.getLong("ended_at_ms"), end.getInt("point_count"),
                (0 until tags.length()).map { tags.getString(it) }, end.optBoolean("interrupted"))
        }
    }

    private fun parsePoint(line: String): Pair<String, TrailPoint>? = try {
        val row = JSONArray(line)
        val tag = row.getString(0)
        val x = row.getDouble(1); val y = row.getDouble(2)
        if (tag.isBlank() || !x.isFinite() || !y.isFinite()) null
        else tag to TrailPoint(x, y, row.getLong(3), row.getBoolean(4))
    } catch (_: Exception) { null }

    private fun recoverInterrupted() {
        directory.listFiles().orEmpty().filter { it.extension == "recording" && it.absolutePath !in openFiles }.forEach { file ->
            // Keep an unreadable interrupted file for recovery instead of blocking all saved paths.
            try { recover(file) } catch (_: Exception) {
                if (!file.renameTo(File(directory, file.nameWithoutExtension + ".damaged")))
                    throw IOException("Could not recover an interrupted path")
            }
        }
    }

    private fun recover(file: File) {
        var count = 0; var ended = 0L
        val tags = linkedSetOf<String>()
        val header = file.bufferedReader().use { input ->
            val first = JSONObject(input.readLine())
            input.lineSequence().forEach { line -> parsePoint(line)?.let { (tag, point) ->
                count++; ended = point.receivedAtMs; tags.add(tag)
            } }
            first
        }
        if (count == 0) {
            if (!file.delete()) throw IOException("Could not recover an empty path")
            return
        }
        val summary = SavedPathSummary(header.getString("id"), header.getLong("started_at_ms"),
            ended, count, tags.toList(), true)
        FileOutputStream(file, true).use {
            it.write(("\n" + footer(summary) + "\n").toByteArray(Charsets.UTF_8)); it.fd.sync()
        }
        if (!file.renameTo(pathFile(summary.id))) throw IOException("Could not recover an interrupted path")
    }
}

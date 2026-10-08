package com.pronskiy.agenstorm.terminal.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/** One past Claude Code session of a project: its id, the title `/resume` shows for it, and when it was last written. */
data class PastSession(val sessionId: String, val cwd: String, val title: String, val lastActivity: Long)

/**
 * Step X3.1. Reads a project's past sessions from Claude Code's transcripts, `projects/<base path with every character
 * but letters and digits turned into ->/<session id>.jsonl` in an injected `~/.claude` (checked against 206 of 207 real
 * folders: the one other is a session whose folder changed after it started). A transcript can run to tens of MB, so
 * only its end is read — titles are appended as they change, the last of each kind is the current one — and its
 * beginning when the end holds none; a file is read again only when its size or time changes. A transcript with no
 * title and no prompt (opened and left) is not listed, as `/resume` does not list it.
 */
class ClaudeTranscripts {

    private data class Seen(val size: Long, val modified: Long, val session: PastSession?)

    private val cache = ConcurrentHashMap<Path, Seen>()

    /** The newest [limit] past sessions of the project at [basePath], newest first, leaving out [running]. */
    fun recent(home: Path, basePath: String, limit: Int, running: Set<String>): List<PastSession> {
        if (limit <= 0) return emptyList()
        val dir = folderOf(home, basePath)
        if (!dir.isDirectory()) return emptyList()
        val files = runCatching { dir.listDirectoryEntries("*.jsonl") }.getOrDefault(emptyList())
            .filter { SESSION_ID.matches(it.nameWithoutExtension) && it.nameWithoutExtension !in running }
            .mapNotNull { file -> runCatching { file to Files.getLastModifiedTime(file).toMillis() }.getOrNull() }
            .sortedByDescending { it.second }
        val found = ArrayList<PastSession>(limit)
        for ((file, modified) in files) {
            read(file, modified, basePath)?.let(found::add)
            if (found.size == limit) break
        }
        cache.keys.retainAll { it.parent != dir || Files.exists(it) }
        return found
    }

    private fun read(file: Path, modified: Long, basePath: String): PastSession? {
        val size = runCatching { Files.size(file) }.getOrNull() ?: return null
        cache[file]?.takeIf { it.size == size && it.modified == modified }?.let { return it.session }
        val title = titleOf(file, size)
        val session = title?.let { PastSession(file.nameWithoutExtension, basePath, it, modified) }
        cache[file] = Seen(size, modified, session)
        return session
    }

    companion object {
        /** Bytes read from a transcript's end, and from its beginning when the end holds no title. */
        const val TAIL_BYTES: Int = 256 * 1024
        const val HEAD_BYTES: Int = 64 * 1024

        private val SESSION_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val json = Json { ignoreUnknownKeys = true }

        /** In the order they win: the user's own name, Claude's name for it, Claude's summary, the last prompt. */
        private val TITLES = listOf("custom-title" to "customTitle", "agent-name" to "agentName", "ai-title" to "aiTitle", "last-prompt" to "lastPrompt")

        fun folderOf(home: Path, basePath: String): Path = home.resolve("projects").resolve(basePath.replace(Regex("[^A-Za-z0-9]"), "-"))

        /** The title of the transcript [file] of [size] bytes, or null when it has none. */
        fun titleOf(file: Path, size: Long): String? {
            val tail = runCatching { chunk(file, (size - TAIL_BYTES).coerceAtLeast(0), size) }.getOrNull() ?: return null
            titleIn(tail, cutFirst = size > TAIL_BYTES)?.let { return it }
            if (size <= TAIL_BYTES) return null
            val head = runCatching { chunk(file, 0, minOf(size, HEAD_BYTES.toLong())) }.getOrNull() ?: return null
            return titleIn(head, cutLast = size > HEAD_BYTES)
        }

        /**
         * The best title among the lines of [text]: for each kind the last one, then the first kind that has one. A chunk
         * cut out of a file starts or ends mid-line; that line is dropped ([cutFirst], [cutLast]).
         */
        fun titleIn(text: String, cutFirst: Boolean = false, cutLast: Boolean = false): String? {
            var lines = text.split('\n')
            if (cutFirst) lines = lines.drop(1)
            if (cutLast) lines = lines.dropLast(1)
            val latest = HashMap<String, String>()
            for (line in lines) {
                if (line.isBlank() || TITLES.none { (type, _) -> line.contains("\"$type\"") }) continue
                val o = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                val type = (o["type"] as? JsonPrimitive)?.content ?: continue
                val field = TITLES.firstOrNull { it.first == type }?.second ?: continue
                (o[field] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::oneLine)?.takeIf { it.isNotEmpty() }?.let { latest[type] = it }
            }
            return TITLES.firstNotNullOfOrNull { (type, _) -> latest[type] }
        }

        private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

        private fun chunk(file: Path, from: Long, to: Long): String =
            RandomAccessFile(file.toFile(), "r").use { raf ->
                val bytes = ByteArray((to - from).toInt())
                raf.seek(from)
                raf.readFully(bytes)
                String(bytes, StandardCharsets.UTF_8)
            }
    }
}

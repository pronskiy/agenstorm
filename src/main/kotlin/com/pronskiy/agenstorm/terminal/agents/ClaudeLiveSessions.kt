package com.pronskiy.agenstorm.terminal.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.math.abs

/**
 * One running Claude Code, as it describes itself in `~/.claude/sessions/<pid>.json` (decision 101). [status] is
 * Claude's own word — `busy`, `idle` and `waiting` seen (X0.2) — shown as it comes; [tmuxSession] is the tmux session it
 * runs in, from the `tmux` field `<session>:@<window>.%<pane>`.
 */
data class LiveSession(
    val pid: Long,
    val sessionId: String,
    val cwd: String,
    val name: String?,
    val status: String?,
    val tmuxSession: String?,
    val updatedAt: Long?,
    val entrypoint: String?,
    val startedAt: Long? = null,
)

/**
 * Step X2.1. Reads the running Claude Code sessions from an injected `~/.claude`, so tests never touch the real one. The
 * format is Claude Code's and undocumented (risk register): a file missing `pid`, `sessionId` or `cwd`, or not JSON at
 * all, is skipped; every other field may be missing; fields it does not know are ignored. A file can outlive its
 * process, so only sessions whose process is still the one that wrote the file are kept ([alive]).
 */
object ClaudeLiveSessions {

    private val json = Json { ignoreUnknownKeys = true }

    /** A started process this close to the file's `startedAt` is the one that wrote it, not a later one given its pid. */
    private const val SAME_START_MS = 60_000L

    fun read(claudeHome: Path, alive: (pid: Long, startedAt: Long?) -> Boolean = ::isAlive): List<LiveSession> {
        val dir = claudeHome.resolve("sessions")
        if (!dir.isDirectory()) return emptyList()
        return dir.listDirectoryEntries("*.json")
            .mapNotNull { file -> runCatching { file.readText() }.getOrNull()?.let(::parse) }
            .filter { alive(it.pid, it.startedAt) }
            .sortedBy { it.pid }
    }

    /** One file's text → its session, or null when it is not a session. */
    fun parse(text: String): LiveSession? {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun string(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        fun long(key: String) = (o[key] as? JsonPrimitive)?.longOrNull
        val pid = long("pid") ?: return null
        val session = LiveSession(
            pid = pid,
            sessionId = string("sessionId") ?: return null,
            cwd = string("cwd") ?: return null,
            name = string("name"),
            status = string("status"),
            tmuxSession = string("tmux")?.substringBefore(':')?.takeIf { it.isNotEmpty() },
            updatedAt = long("updatedAt"),
            entrypoint = string("entrypoint"),
            startedAt = long("startedAt"),
        )
        return session
    }

    /** The process [pid] runs and started when the file says it did, give or take a minute; no start time to compare is a yes. */
    fun isAlive(pid: Long, startedAt: Long?): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        if (!handle.isAlive) return false
        val started = handle.info().startInstant().orElse(null) ?: return true
        return startedAt == null || abs(started.toEpochMilli() - startedAt) <= SAME_START_MS
    }

    /** `~/.claude`. */
    fun home(): Path = Path.of(System.getProperty("user.home"), ".claude")
}

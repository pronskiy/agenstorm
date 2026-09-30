package com.pronskiy.agenstorm.worktrees.status

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Step T3.2, pure. The process a lock reason names. Claude Code locks the worktree of a session as
 * `claude session <name> (pid 68643 start Wed Sep 30 15:48:15 2026)` — the start in UTC — and leaves the lock in
 * place when the session ends (seen 2026-09-30 with `claude -w <name> -p …`), so "locked" alone does not say an agent
 * is at work; whether that process still runs does. A reason with no pid names no one, and the lock is taken at its
 * word.
 */
data class LockOwner(val pid: Long, val start: Instant?) {

    enum class State { LIVE, ENDED, UNKNOWN }

    companion object {
        private val PID = Regex("""\bpid (\d+)""")
        private val START = Regex("""\bstart (\w{3} \w{3} +\d{1,2} \d{2}:\d{2}:\d{2} \d{4})""")
        private val START_FORMAT = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ROOT)
        private val TOLERANCE = Duration.ofSeconds(30)

        fun parse(reason: String?): LockOwner? {
            if (reason == null) return null
            val pid = PID.find(reason)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val start = START.find(reason)?.groupValues?.get(1)?.let { text ->
                try {
                    LocalDateTime.parse(text.replace(Regex(" +"), " "), START_FORMAT).toInstant(ZoneOffset.UTC)
                } catch (_: DateTimeParseException) {
                    null
                }
            }
            return LockOwner(pid, start)
        }

        /**
         * [alive] says whether a process with the pid runs; [started] when it started, null when that is unknown (which
         * counts as a match). A recorded start more than 30 s off means the pid now belongs to another process — the
         * slack covers a session that takes a while to lock after it starts.
         */
        fun state(reason: String?, started: (Long) -> Instant?, alive: (Long) -> Boolean): State {
            val owner = parse(reason) ?: return State.UNKNOWN
            if (!alive(owner.pid)) return State.ENDED
            val recorded = owner.start ?: return State.LIVE
            val actual = started(owner.pid) ?: return State.LIVE
            return if (Duration.between(recorded, actual).abs() <= TOLERANCE) State.LIVE else State.ENDED
        }

        /** [state] against the processes of this machine. */
        fun current(reason: String?): State =
            state(
                reason,
                started = { pid -> ProcessHandle.of(pid).flatMap { it.info().startInstant() }.orElse(null) },
                alive = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
            )
    }
}

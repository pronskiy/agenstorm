package com.pronskiy.agenstorm.terminal.agents

/**
 * Step X2.4. The sessions that finished while the user was away: a session that was `busy` at one read and is not at
 * the next, while its project is not the one in front, keeps a dot until it is clicked ([seen]), goes busy again or
 * ends. Fed one read at a time; thread-safe.
 */
class FinishedTracker {

    private val lastStatus = HashMap<String, String?>()
    private val finished = HashSet<String>()

    /** After a read: each running session with the base path it belongs to, and the base path in front. Returns the finished ids. */
    @Synchronized
    fun update(sessions: List<Pair<LiveSession, String>>, front: String?): Set<String> {
        val ids = sessions.mapTo(HashSet()) { it.first.sessionId }
        finished.retainAll(ids)
        lastStatus.keys.retainAll(ids)
        for ((session, base) in sessions) {
            val before = lastStatus[session.sessionId]
            if (session.status == BUSY) finished.remove(session.sessionId)
            else if (before == BUSY && base != front) finished.add(session.sessionId)
            lastStatus[session.sessionId] = session.status
        }
        return finished.toSet()
    }

    /** The finished ids now, with every [seen] since the last [update] taken into account. */
    @Synchronized
    fun current(): Set<String> = finished.toSet()

    @Synchronized
    fun seen(sessionId: String) {
        finished.remove(sessionId)
    }

    private companion object {
        const val BUSY = "busy"
    }
}

package com.pronskiy.agenstorm.terminal.agents

import com.pronskiy.agenstorm.core.AgenstormBundle

/** Step X2.5. Pure: what a session row says. */
object SessionRowText {

    /** The row's title: the name Claude gives the session, else the start of its id. */
    fun title(session: LiveSession): String = session.name ?: session.sessionId.take(8)

    /** How long ago [then] was, in one short word: `now`, `5m`, `2h`, `3d`. */
    fun ago(then: Long?, now: Long): String? {
        if (then == null) return null
        val seconds = ((now - then) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> AgenstormBundle.message("agents.ago.now")
            seconds < 3600 -> AgenstormBundle.message("agents.ago.minutes", seconds / 60)
            seconds < 86_400 -> AgenstormBundle.message("agents.ago.hours", seconds / 3600)
            else -> AgenstormBundle.message("agents.ago.days", seconds / 86_400)
        }
    }

    /** Where it runs, when that is worth saying: in tmux, in the background, outside the IDE. */
    fun hint(row: SessionRow): String? = when (row.place) {
        is SessionPlace.InTab -> if (row.session.tmuxSession != null) AgenstormBundle.message("agents.hint.tmux") else null
        is SessionPlace.Background -> AgenstormBundle.message("agents.hint.background")
        SessionPlace.Elsewhere -> AgenstormBundle.message("agents.hint.elsewhere")
    }

    /** The tooltip: Claude's own status word and what a click does. */
    fun tooltip(row: SessionRow): String {
        val status = row.session.status ?: AgenstormBundle.message("agents.status.unknown")
        val click = when (row.place) {
            is SessionPlace.InTab -> AgenstormBundle.message("agents.click.tab")
            is SessionPlace.Background -> AgenstormBundle.message("agents.click.background")
            SessionPlace.Elsewhere -> AgenstormBundle.message("agents.click.elsewhere")
        }
        return AgenstormBundle.message("agents.tooltip", status, click)
    }
}

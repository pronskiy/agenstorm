package com.pronskiy.agenstorm.terminal.tmux

import org.junit.Assert.assertEquals
import org.junit.Test

/** Step U1.7: a tmux tab is named after the program's own title while it has one, and after itself otherwise. */
class TmuxTitleMirrorTest {

    @Test
    fun aProgramsTitleNamesTheTabWhileItHasOne() {
        assertEquals("✳ Claude Code", TmuxTitleMirror.defaultTitle("✳ Claude Code", showApplicationTitles = true, original = "Local"))
        assertEquals("Local", TmuxTitleMirror.defaultTitle(null, showApplicationTitles = true, original = "Local"))
        assertEquals("Local", TmuxTitleMirror.defaultTitle("  ", showApplicationTitles = true, original = "Local"))
    }

    @Test
    fun nothingIsMirroredWithApplicationTitlesSwitchedOff() {
        assertEquals("Local", TmuxTitleMirror.defaultTitle("✳ Claude Code", showApplicationTitles = false, original = "Local"))
    }
}

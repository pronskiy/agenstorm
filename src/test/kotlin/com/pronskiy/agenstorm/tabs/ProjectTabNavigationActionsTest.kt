package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Step E2.3: the three keyboard actions exist, carry no default shortcut, and cycle in tab order. */
class ProjectTabNavigationActionsTest : BasePlatformTestCase() {

    fun testActionsAreRegisteredWithoutDefaultShortcuts() {
        val expected = mapOf(
            "Agenstorm.NextProjectTab" to NextProjectTabAction::class.java,
            "Agenstorm.PrevProjectTab" to PrevProjectTabAction::class.java,
            "Agenstorm.CloseProjectTab" to CloseProjectTabAction::class.java,
        )
        for ((id, type) in expected) {
            val action = ActionManager.getInstance().getAction(id)
            assertNotNull("$id is not registered", action)
            assertTrue("$id is ${action.javaClass}", type.isInstance(action))
            assertTrue("$id must not ship a shortcut", KeymapManager.getInstance().activeKeymap.getShortcuts(id).isEmpty())
            assertFalse(action.templatePresentation.text.isNullOrBlank())
        }
    }

    fun testCyclicNeighbourWrapsAroundInBothDirections() {
        val a = project
        val b = FakeProjectHolder.another(a, "b")
        val c = FakeProjectHolder.another(a, "c")
        val tabs = listOf(a, b, c)
        assertSame(b, ProjectTabNavigationAction.cyclicNeighbour(tabs, a, +1))
        assertSame(a, ProjectTabNavigationAction.cyclicNeighbour(tabs, c, +1))
        assertSame(c, ProjectTabNavigationAction.cyclicNeighbour(tabs, a, -1))
        assertSame(a, ProjectTabNavigationAction.cyclicNeighbour(tabs, null, +1))
        assertNull(ProjectTabNavigationAction.cyclicNeighbour(listOf(a), a, +1))
    }

    fun testNextAndPreviousAreDisabledWithASingleOpenProjectButCloseIsNot() {
        val context = SimpleDataContext.getProjectContext(project)
        for (action in listOf(NextProjectTabAction(), PrevProjectTabAction())) {
            val event = TestActionEvent.createTestEvent(action, context)
            action.update(event)
            assertFalse(action.javaClass.simpleName, event.presentation.isEnabled)
        }
        val close = CloseProjectTabAction()
        val event = TestActionEvent.createTestEvent(close, context)
        close.update(event)
        assertTrue(event.presentation.isEnabled)
    }
}

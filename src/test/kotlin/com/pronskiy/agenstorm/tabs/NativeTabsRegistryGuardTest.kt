package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.tabs.NativeTabsRegistryGuard.Change

/**
 * Step E1.1: the macOS window tabs are never turned off (they are what merges the projects into one window);
 * the guard only gives them back to anyone whose 1.0 install had them disabled.
 */
class NativeTabsRegistryGuardTest : BasePlatformTestCase() {

    private lateinit var nativeTabs: RegistryValue
    private var original = true
    private lateinit var state: AgenstormSettings.State
    private val notifications = mutableListOf<String>()

    override fun setUp() {
        super.setUp()
        nativeTabs = Registry.get(NativeTabsRegistryGuard.REGISTRY_KEY)
        original = nativeTabs.asBoolean()
        state = AgenstormSettings.State()
        notifications.clear()
    }

    override fun tearDown() {
        try {
            nativeTabs.setValue(original)
        } finally {
            super.tearDown()
        }
    }

    private fun guard(isMac: Boolean = true, key: String = NativeTabsRegistryGuard.REGISTRY_KEY) =
        NativeTabsRegistryGuard(isMac = isMac, registryKey = key, settings = { state }, notify = { _, message -> notifications += message })

    fun testTheFeatureNeverTurnsWindowTabsOff() {
        nativeTabs.setValue(true)
        state.projectTabsEnabled = true

        assertEquals(Change.NONE, guard().sync(project))
        assertTrue("window tabs are what merges the projects into one window", nativeTabs.asBoolean())
        assertFalse(state.nativeTabsDisabledByAgenstorm)
        assertTrue(notifications.isEmpty())
    }

    fun testAnInstallLeftDisabledBy10IsRestoredOnceAndAsksForARestart() {
        nativeTabs.setValue(false)
        state.projectTabsEnabled = true
        state.nativeTabsDisabledByAgenstorm = true

        assertEquals(Change.NATIVE_TABS_RESTORED, guard().sync(project))
        assertTrue(nativeTabs.asBoolean())
        assertFalse(state.nativeTabsDisabledByAgenstorm)
        assertEquals(listOf(AgenstormBundle.message("tabs.notification.nativeTabsRestored")), notifications)

        assertEquals(Change.NONE, guard().sync(project))
        assertEquals(1, notifications.size)
    }

    fun testRestoringHappensEvenWithTheFeatureOff() {
        nativeTabs.setValue(false)
        state.projectTabsEnabled = false
        state.nativeTabsDisabledByAgenstorm = true

        assertEquals(Change.NATIVE_TABS_RESTORED, guard().sync(project))
        assertTrue(nativeTabs.asBoolean())
    }

    fun testAUsersOwnRegistryChoiceIsLeftAlone() {
        nativeTabs.setValue(false)
        state.projectTabsEnabled = true

        assertEquals(Change.NONE, guard().sync(project))
        assertFalse("a user's own registry choice must survive", nativeTabs.asBoolean())
        assertTrue(notifications.isEmpty())
    }

    fun testAlreadyBackOnJustForgetsTheFlag() {
        nativeTabs.setValue(true)
        state.nativeTabsDisabledByAgenstorm = true

        assertEquals(Change.NONE, guard().sync(project))
        assertTrue(nativeTabs.asBoolean())
        assertFalse(state.nativeTabsDisabledByAgenstorm)
        assertTrue(notifications.isEmpty())
    }

    fun testNothingHappensOffMacOs() {
        nativeTabs.setValue(false)
        state.nativeTabsDisabledByAgenstorm = true

        assertEquals(Change.NONE, guard(isMac = false).sync(project))
        assertFalse(nativeTabs.asBoolean())
        assertTrue("the flag is macOS bookkeeping; another OS must not clear it", state.nativeTabsDisabledByAgenstorm)
        assertTrue(notifications.isEmpty())
    }

    fun testAMissingRegistryKeyIsLoggedNotThrown() {
        state.nativeTabsDisabledByAgenstorm = true
        assertEquals(Change.NONE, guard(key = "agenstorm.test.no.such.key").sync(project))
        assertTrue("the flag survives an IDE without the key", state.nativeTabsDisabledByAgenstorm)
        assertTrue(notifications.isEmpty())
    }
}

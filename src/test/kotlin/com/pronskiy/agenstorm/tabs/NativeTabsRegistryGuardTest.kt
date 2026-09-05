package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.tabs.NativeTabsRegistryGuard.Change

/** Step E1.1: the native macOS tabs registry key follows the feature toggle, and only Agenstorm's own change is undone. */
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

    fun testFeatureOnTurnsNativeTabsOffOnceAndAsksForARestart() {
        nativeTabs.setValue(true)
        state.projectTabsEnabled = true

        assertEquals(Change.NATIVE_TABS_DISABLED, guard().sync(project))
        assertFalse(nativeTabs.asBoolean())
        assertTrue(state.nativeTabsDisabledByAgenstorm)
        assertEquals(listOf(AgenstormBundle.message("tabs.notification.nativeTabsOff")), notifications)

        assertEquals(Change.NONE, guard().sync(project))
        assertEquals(1, notifications.size)
    }

    fun testFeatureOnLeavesNativeTabsAloneWhenTheUserAlreadyTurnedThemOff() {
        nativeTabs.setValue(false)
        state.projectTabsEnabled = true

        assertEquals(Change.NONE, guard().sync(project))
        assertFalse(nativeTabs.asBoolean())
        assertFalse(state.nativeTabsDisabledByAgenstorm)
        assertTrue(notifications.isEmpty())
    }

    fun testFeatureOffRestoresOnlyWhatAgenstormChanged() {
        nativeTabs.setValue(false)
        state.projectTabsEnabled = false

        assertEquals(Change.NONE, guard().sync(project))
        assertFalse("a user's own registry choice must survive", nativeTabs.asBoolean())

        state.nativeTabsDisabledByAgenstorm = true
        assertEquals(Change.NATIVE_TABS_RESTORED, guard().sync(project))
        assertTrue(nativeTabs.asBoolean())
        assertFalse(state.nativeTabsDisabledByAgenstorm)
        assertEquals(listOf(AgenstormBundle.message("tabs.notification.nativeTabsRestored")), notifications)
    }

    fun testFeatureOffWithNativeTabsAlreadyBackOnJustForgetsTheFlag() {
        nativeTabs.setValue(true)
        state.projectTabsEnabled = false
        state.nativeTabsDisabledByAgenstorm = true

        assertEquals(Change.NONE, guard().sync(project))
        assertTrue(nativeTabs.asBoolean())
        assertFalse(state.nativeTabsDisabledByAgenstorm)
        assertTrue(notifications.isEmpty())
    }

    fun testNothingHappensOffMacOs() {
        nativeTabs.setValue(true)
        state.projectTabsEnabled = true

        assertEquals(Change.NONE, guard(isMac = false).sync(project))
        assertTrue(nativeTabs.asBoolean())
        assertTrue(notifications.isEmpty())
    }

    fun testAMissingRegistryKeyIsLoggedNotThrown() {
        state.projectTabsEnabled = true
        assertEquals(Change.NONE, guard(key = "agenstorm.test.no.such.key").sync(project))
        assertFalse(state.nativeTabsDisabledByAgenstorm)
        assertTrue(notifications.isEmpty())
    }
}

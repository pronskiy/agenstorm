package com.pronskiy.agenstorm

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormConfigurable
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Smoke test for step 01.4: the plugin descriptor loads in the test IDE and the pieces wired in
 * `plugin.xml` (dependencies, settings service, Tools → Agenstorm configurable, bundle) resolve.
 */
class SettingsSmokeTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Startup activities (e.g. the native-tabs registry guard) may already have touched the app-level state.
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
    }

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testPluginDescriptorIsLoadedWithOptionalDependencies() {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
        assertNotNull("plugin $PLUGIN_ID is not loaded in the test IDE", plugin)
        assertTrue(PluginManagerCore.loadedPlugins.contains(plugin))
        plugin!!
        assertEquals("Agenstorm", plugin.name)

        val required = plugin.dependencies.filterNot { it.isOptional }.map { it.pluginId.idString }.toSet()
        val optional = plugin.dependencies.filter { it.isOptional }.map { it.pluginId.idString }.toSet()
        assertEquals(setOf("com.intellij.modules.platform", "com.intellij.modules.lang", "com.intellij.modules.vcs"), required)
        assertEquals(setOf("org.intellij.plugins.markdown", "com.jetbrains.php", "Git4Idea"), optional)
    }

    fun testSettingsServiceIsAvailable() {
        val settings = AgenstormSettings.getInstance()
        assertNotNull(settings)
        assertSame(settings, AgenstormSettings.getInstance())
        assertEquals(AgenstormSettings.State(), settings.state)
    }

    fun testConfigurableIsRegisteredUnderTools() {
        val ep = Configurable.APPLICATION_CONFIGURABLE.extensionList.single { it.id == PLUGIN_ID }
        assertEquals("tools", ep.parentId)
        // getDisplayName() resolves key + bundle; the displayName field is only set for the inline attribute.
        assertEquals("Agenstorm", ep.getDisplayName())
        assertInstanceOf(ep.createConfigurable(), AgenstormConfigurable::class.java)
    }

    fun testBundleResolves() {
        assertEquals("Agenstorm", AgenstormBundle.message("name"))
        assertEquals("Agenstorm", AgenstormBundle.message("settings.display.name"))
    }

    private companion object {
        const val PLUGIN_ID = "com.pronskiy.agenstorm"
    }
}

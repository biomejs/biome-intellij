package com.github.biomejs.intellijbiome.settings

import com.intellij.ide.actionsOnSave.ActionOnSaveBackedByOwnConfigurable
import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableGroup
import com.intellij.openapi.options.ex.Settings
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.intellij.util.xmlb.XmlSerializer
import org.jetbrains.concurrency.Promise
import javax.swing.JRadioButton
import java.awt.Component
import java.awt.Container

class BiomeDisabledPreferencesTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private val settings get() = BiomeSettings.getInstance(project)

    override fun setUp() {
        super.setUp()
        settings.loadState(BiomeSettingsState().apply {
            enableLspFormat = true
            formatOnSave = true
            applySafeFixesOnSave = true
            sortImportOnSave = true
        })
    }

    fun testDisableApplyReopenEnablePreservesPreferences() {
        settings.loadState(BiomeSettingsState())
        panel { configurable ->
            boxes(configurable).forEach { it.isSelected = true }
            configurable.apply()
        }
        assertStored()
        panel { configurable ->
            assertSelected(configurable)
            configurable.disabledConfiguration.isSelected = true
            assertSelected(configurable)
            configurable.apply()
        }
        assertStored()
        assertDisabledRuntime()
        panel { configurable ->
            assertSelected(configurable)
            assertTrue(boxes(configurable).all { !it.isEnabled })
            configurable.apply()
        }
        assertStored()
        panel { configurable ->
            radios(configurable.createComponent()!!)[1].isSelected = true
            assertSelected(configurable)
            configurable.apply()
        }
        assertTrue(settings.enableLspFormat)
        assertEquals(3, settings.getEnabledFeatures().size)
    }

    fun testInitiallyDisabledApplyPreservesPreferences() {
        settings.configurationMode = ConfigurationMode.DISABLED
        panel { configurable ->
            assertSelected(configurable)
            assertFalse(configurable.isModified)
            configurable.apply()
        }
        assertStored()
        assertDisabledRuntime()
    }

    fun testDisabledSerializationPreservesPreferences() {
        settings.configurationMode = ConfigurationMode.DISABLED
        settings.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), BiomeSettingsState::class.java))
        assertStored()
        assertDisabledRuntime()
        panel { assertSelected(it) }
    }

    fun testCancelDoesNotChangePreferencesOrMode() {
        panel { configurable ->
            configurable.disabledConfiguration.isSelected = true
            boxes(configurable).forEach { it.isSelected = false }
            assertTrue(configurable.isModified)
        }
        assertStored()
        assertEquals(ConfigurationMode.AUTOMATIC, settings.configurationMode)
        panel { configurable ->
            assertSelected(configurable)
            assertFalse(configurable.disabledConfiguration.isSelected)
        }
    }

    fun testActionsOnSaveResetAndApplyPreserveDisabledPreferences() {
        settings.configurationMode = ConfigurationMode.DISABLED
        panel { configurable ->
            val group = object : ConfigurableGroup {
                override fun getDisplayName() = "Test settings"
                override fun getConfigurables() = arrayOf<Configurable>(configurable)
            }
            val dialogSettings = object : Settings(listOf(group)) {
                override fun selectImpl(configurable: Configurable): Promise<in Any> = error("Unused")
                override fun getConfigurableWithInitializedUiComponentImpl(c: Configurable, create: Boolean) = c
                override fun checkModifiedImpl(c: Configurable) {}
                override fun setSearchText(text: String) {}
            }
            val constructor = ActionOnSaveContext::class.java.declaredConstructors.single().apply { isAccessible = true }
            val context = constructor.newInstance(project, dialogSettings, testRootDisposable) as ActionOnSaveContext
            val reset = ActionOnSaveBackedByOwnConfigurable::class.java.getDeclaredMethod("resetUiOnOwnPageThatIsMirroredOnActionsOnSavePage").apply { isAccessible = true }
            val infos = listOf(BiomeOnSaveFormatActionInfo(context), BiomeOnSaveApplySafeFixesActionInfo(context), BiomeOnSaveSortImportActionInfo(context))
            for (info in infos) {
                assertFalse(info.isSaveActionApplicable)
                assertNotNull(info.comment)
                reset.invoke(info)
            }
            assertSelected(configurable)
            configurable.apply()
            assertStored()
            radios(configurable.createComponent()!!)[1].isSelected = true
            assertTrue(infos.all { it.isSaveActionApplicable && it.isActionOnSaveEnabled })
            infos[0].setActionOnSaveEnabled(false)
            configurable.apply()
            assertFalse(settings.state.formatOnSave)
            assertTrue(settings.state.applySafeFixesOnSave)
            assertTrue(settings.state.sortImportOnSave)
        }
    }

    fun testDisabledPreferencesDoNotEnableSaveAction() {
        settings.configurationMode = ConfigurationMode.DISABLED
        val action = com.github.biomejs.intellijbiome.actions.BiomeCheckOnSaveAction()
        assertFalse(action.isEnabledForProject(project))
        assertStored()
        assertDisabledRuntime()
    }

    private fun assertDisabledRuntime() {
        assertFalse(settings.enableLspFormat)
        assertFalse(settings.formatOnSave)
        assertFalse(settings.applySafeFixesOnSave)
        assertFalse(settings.sortImportOnSave)
        assertTrue(settings.getEnabledFeatures().isEmpty())
    }
    private fun assertStored() {
        assertTrue(settings.state.enableLspFormat)
        assertTrue(settings.state.formatOnSave)
        assertTrue(settings.state.applySafeFixesOnSave)
        assertTrue(settings.state.sortImportOnSave)
    }
    private fun boxes(c: BiomeConfigurable) = listOf(c.enableLspFormatCheckBox, c.runFormatOnSaveCheckBox,
        c.runSafeFixesOnSaveCheckBox, c.sortImportOnSaveCheckBox)
    private fun assertSelected(c: BiomeConfigurable) = boxes(c).forEach { assertTrue("Preference must remain selected: ${it.text}", it.isSelected) }
    private fun panel(test: (BiomeConfigurable) -> Unit) {
        val configurable = BiomeConfigurable(project)
        try {
            configurable.createComponent()
            configurable.reset()
            test(configurable)
        } finally { configurable.disposeUIResources() }
    }
    private fun radios(component: Component): List<JRadioButton> = descendants(component).filterIsInstance<JRadioButton>().toList()
    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }
}

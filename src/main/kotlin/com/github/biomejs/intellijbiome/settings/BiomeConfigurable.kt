package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.intellij.ide.actionsOnSave.ActionsOnSaveConfigurable
import com.intellij.lang.javascript.JavaScriptBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.observable.properties.ObservableMutableProperty
import com.intellij.openapi.observable.util.whenItemSelected
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ContextHelpLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.layout.ValidationInfoBuilder
import com.intellij.ui.layout.not
import com.intellij.ui.layout.selected
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.io.File
import java.nio.file.FileSystems
import java.util.regex.PatternSyntaxException
import javax.swing.JCheckBox
import javax.swing.JRadioButton
import javax.swing.text.JTextComponent

private const val HELP_TOPIC = "reference.settings.biome"

class BiomeConfigurable(internal val project: Project) :
    BoundSearchableConfigurable(
        BiomeBundle.message("biome.settings.name"),
        HELP_TOPIC,
        CONFIGURABLE_ID
    ) {
    lateinit var runFormatOnSaveCheckBox: JCheckBox
    lateinit var enableLspFormatCheckBox: JCheckBox
    lateinit var runSafeFixesOnSaveCheckBox: JCheckBox
    lateinit var runUnsafeFixesOnSaveCheckBox: JCheckBox

    lateinit var runForFilesField: JBTextField

    private lateinit var disabledConfiguration: JRadioButton
    private lateinit var automaticConfiguration: JRadioButton
    private lateinit var manualConfiguration: JRadioButton
    override fun createPanel(): DialogPanel {
        val settings: BiomeSettings = BiomeSettings.getInstance(project)
        val biomeServerService = project.service<BiomeServerService>()

        // *********************
        // Configuration mode row
        // *********************

        return panel {
            buttonsGroup {
                row {
                    disabledConfiguration =
                        radioButton(
                            JavaScriptBundle.message(
                                "settings.javascript.linters.autodetect.disabled",
                                displayName
                            )
                        )
                            .bindSelected(ConfigurationModeProperty(settings, ConfigurationMode.DISABLED))
                            .component
                }
                row {
                    automaticConfiguration =
                        radioButton(
                            JavaScriptBundle.message(
                                "settings.javascript.linters.autodetect.configure.automatically",
                                displayName
                            )
                        )
                            .bindSelected(ConfigurationModeProperty(settings, ConfigurationMode.AUTOMATIC))
                            .component

                    val detectAutomaticallyHelpText = JavaScriptBundle.message(
                        "settings.javascript.linters.autodetect.configure.automatically.help.text",
                        ApplicationNamesInfo.getInstance().fullProductName,
                        displayName,
                        "${BiomePackage.configName}.json"
                    )

                    val helpLabel = ContextHelpLabel.create(detectAutomaticallyHelpText)
                    helpLabel.border = JBUI.Borders.emptyLeft(UIUtil.DEFAULT_HGAP)
                    cell(helpLabel)
                }
                row {
                    manualConfiguration =
                        radioButton(
                            JavaScriptBundle.message(
                                "settings.javascript.linters.autodetect.configure.manually",
                                displayName
                            )
                        )
                            .bindSelected(ConfigurationModeProperty(settings, ConfigurationMode.MANUAL))
                            .component
                }
            }

            // *********************
            // Manual configuration row
            // *********************
            panel {
                row(BiomeBundle.message("biome.path.executable")) {
                    textFieldWithBrowseButton(BiomeBundle.message("biome.path.executable")) { fileChosen(it) }
                        .bindText(settings::executablePath)
                }.visibleIf(manualConfiguration.selected)

                row(BiomeBundle.message("biome.config.path.label")) {
                    textFieldWithBrowseButton(
                        BiomeBundle.message("biome.config.path.label"),
                        project,
                    ) { fileChosen(it) }
                        .bindText(settings::configPath)
                        .validationOnInput(validateConfigDir())
                }.visibleIf(manualConfiguration.selected)
            }

            // *********************
            // Format pattern row
            // *********************
            row(BiomeBundle.message("biome.run.format.for.files.label")) {
                runForFilesField = textField()
                    .align(AlignX.FILL)
                    .bind(
                        { textField -> textField.text.trim() },
                        JTextComponent::setText,
                        MutableProperty({ settings.filePattern }, { settings.filePattern = it })
                    )
                    .validationOnInput(validateGlob())
                    .component
            }.enabledIf(!disabledConfiguration.selected)

            // *********************
            // LSP row
            // *********************
            row {
                enableLspFormatCheckBox = checkBox(BiomeBundle.message("biome.enable.lsp.format.label"))
                    .bindSelected(RunOnObservableProperty(
                        { settings.configurationMode != ConfigurationMode.DISABLED && settings.enableLspFormat },
                        { settings.enableLspFormat = it },
                        { !disabledConfiguration.isSelected && enableLspFormatCheckBox.isSelected }
                    ))
                    .component

                val helpLabel = ContextHelpLabel.create(BiomeBundle.message("biome.enable.lsp.format.help.label"))
                helpLabel.border = JBUI.Borders.emptyLeft(UIUtil.DEFAULT_HGAP)
                cell(helpLabel)

                val link = ActionsOnSaveConfigurable.createGoToActionsOnSavePageLink()
                cell(link)
            }.enabledIf(!disabledConfiguration.selected)

            // *********************
            // Format on save row
            // *********************
            row {
                runFormatOnSaveCheckBox = checkBox(BiomeBundle.message("biome.run.format.on.save.label"))
                    .bindSelected(RunOnObservableProperty(
                        { settings.configurationMode != ConfigurationMode.DISABLED && settings.formatOnSave },
                        { settings.formatOnSave = it },
                        { !disabledConfiguration.isSelected && runFormatOnSaveCheckBox.isSelected }
                    ))
                    .component

                val link = ActionsOnSaveConfigurable.createGoToActionsOnSavePageLink()
                cell(link)
            }.enabledIf(!disabledConfiguration.selected)

            // *********************
            // Apply safe fixes on save row
            // *********************
            row {
                runSafeFixesOnSaveCheckBox = checkBox(BiomeBundle.message("biome.run.safe.fixes.on.save.label"))
                    .bindSelected(RunOnObservableProperty(
                        { settings.configurationMode != ConfigurationMode.DISABLED && settings.applySafeFixesOnSave },
                        { settings.applySafeFixesOnSave = it },
                        { !disabledConfiguration.isSelected && runSafeFixesOnSaveCheckBox.isSelected }
                    ))
                    .component

                val link = ActionsOnSaveConfigurable.createGoToActionsOnSavePageLink()
                cell(link)
            }.enabledIf(!disabledConfiguration.selected)


            // *********************
            // Apply unsafe fixes on save row
            // *********************
            row {
                runUnsafeFixesOnSaveCheckBox = checkBox(BiomeBundle.message("biome.run.unsafe.fixes.on.save.label"))
                    .bindSelected(RunOnObservableProperty(
                        { settings.configurationMode != ConfigurationMode.DISABLED && settings.applyUnsafeFixesOnSave },
                        { settings.applyUnsafeFixesOnSave = it },
                        { !disabledConfiguration.isSelected && runUnsafeFixesOnSaveCheckBox.isSelected }
                    ))
                    .component

                val link = ActionsOnSaveConfigurable.createGoToActionsOnSavePageLink()
                cell(link)
            }.enabledIf(!disabledConfiguration.selected)

            onApply {
                biomeServerService.restartBiomeServer()
                biomeServerService.notifyRestart()
            }
        }

    }

    private fun validateGlob(): ValidationInfoBuilder.(JBTextField) -> ValidationInfo? =
        {
            try {
                FileSystems.getDefault().getPathMatcher("glob:" + it.text)
                null
            }
            catch (e: PatternSyntaxException) {
                ValidationInfo(BiomeBundle.message("biome.invalid.pattern"), it)
            }
        }

    private fun validateConfigDir(): ValidationInfoBuilder.(TextFieldWithBrowseButton) -> ValidationInfo? =
        {
            val selected = File(it.text)

            if (!selected.exists()) {
                ValidationInfo(BiomeBundle.message("biome.configuration.file.not.found"), it)
            }
            else {
                if (!selected.name.contains(BiomePackage.configName) && BiomePackage.configValidExtensions.contains(
                        selected.extension
                    )
                ) {
                    ValidationInfo(BiomeBundle.message("biome.configuration.file.not.found"), it)
                }
                else {
                    null
                }
            }
        }

    private fun fileChosen(file: VirtualFile): String {
        return file.path
    }

    private class ConfigurationModeProperty(
        private val settings: BiomeSettings,
        private val mode: ConfigurationMode,
    ) : MutableProperty<Boolean> {
        override fun get(): Boolean =
            settings.configurationMode == mode

        override fun set(value: Boolean) {
            if (value) {
                settings.configurationMode = mode
            }
        }
    }

    private inner class RunOnObservableProperty(
        private val getter: () -> Boolean,
        private val setter: (Boolean) -> Unit,
        private val afterConfigModeChangeGetter: () -> Boolean,
    ) : ObservableMutableProperty<Boolean> {
        override fun set(value: Boolean) {
            setter(value)
        }

        override fun get(): Boolean =
            getter()

        override fun afterChange(parentDisposable: Disposable?, listener: (Boolean) -> Unit) {
            fun emitChange(radio: JBRadioButton) {
                if (radio.isSelected) {
                    listener(afterConfigModeChangeGetter())
                }
            }

            manualConfiguration.whenItemSelected(parentDisposable, ::emitChange)
            automaticConfiguration.whenItemSelected(parentDisposable, ::emitChange)
            disabledConfiguration.whenItemSelected(parentDisposable, ::emitChange)
        }
    }

    companion object {
        const val CONFIGURABLE_ID = "Settings.Biome"
    }
}

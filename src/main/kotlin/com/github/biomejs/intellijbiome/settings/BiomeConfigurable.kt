package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.schema.BiomeSchemaAssociationService
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.intellij.ide.actionsOnSave.ActionsOnSaveConfigurable
import com.intellij.lang.javascript.JavaScriptBundle
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ContextHelpLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.layout.not
import com.intellij.ui.layout.selected
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JCheckBox
import javax.swing.JRadioButton
import javax.swing.event.HyperlinkEvent
import java.nio.file.Files
import java.nio.file.InvalidPathException
import kotlin.io.path.Path

private const val HELP_TOPIC = "reference.settings.biome"

class BiomeConfigurable(internal val project: Project) :
    BoundSearchableConfigurable(BiomeBundle.message("biome.settings.name"), HELP_TOPIC, CONFIGURABLE_ID) {
    lateinit var runFormatOnSaveCheckBox: JCheckBox
    lateinit var enableLspFormatCheckBox: JCheckBox
    lateinit var runSafeFixesOnSaveCheckBox: JCheckBox
    lateinit var sortImportOnSaveCheckBox: JCheckBox

    lateinit var disabledConfiguration: JRadioButton
    private lateinit var automaticConfiguration: JRadioButton
    private lateinit var manualConfiguration: JRadioButton
    private lateinit var extensionsField: JBTextField
    private lateinit var configPathField: TextFieldWithBrowseButton

    override fun apply() {
        createComponent()
        validateConfigPath(configPathField)?.let { throw ConfigurationException(it.message) }
        super.apply()
    }

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
                        radioButton(JavaScriptBundle.message("settings.javascript.linters.autodetect.disabled",
                            displayName)).bindSelected(ConfigurationModeProperty(settings,
                            ConfigurationMode.DISABLED)).component
                }
                row {
                    automaticConfiguration =
                        radioButton(JavaScriptBundle.message("settings.javascript.linters.autodetect.configure.automatically",
                            displayName)).bindSelected(ConfigurationModeProperty(settings,
                            ConfigurationMode.AUTOMATIC)).component

                    val detectAutomaticallyHelpText =
                        JavaScriptBundle.message("settings.javascript.linters.autodetect.configure.automatically.help.text",
                            ApplicationNamesInfo.getInstance().fullProductName,
                            displayName,
                            "${BiomePackage.configName}.json")

                    val helpLabel = ContextHelpLabel.create(detectAutomaticallyHelpText)
                    helpLabel.border = JBUI.Borders.emptyLeft(UIUtil.DEFAULT_HGAP)
                    cell(helpLabel)
                }
                row {
                    manualConfiguration =
                        radioButton(JavaScriptBundle.message("settings.javascript.linters.autodetect.configure.manually",
                            displayName)).bindSelected(ConfigurationModeProperty(settings,
                            ConfigurationMode.MANUAL)).component
                }
            }

            // *********************
            // Manual configuration row
            // *********************
            panel {
                row(BiomeBundle.message("biome.path.executable")) {
                    textFieldWithBrowseButton(BiomeBundle.message("biome.path.executable")) { fileChosen(it) }.bindText(
                        settings::executablePath)
                        .comment(BiomeBundle.message("biome.path.executable.help"))
                }.visibleIf(manualConfiguration.selected)

                row(BiomeBundle.message("biome.config.path.label")) {
                    configPathField = textFieldWithBrowseButton(
                        BiomeBundle.message("biome.config.path.label"),
                        project,
                    ) { fileChosen(it) }.bindText(settings::configPath)
                        .validationOnInput { validateConfigPath(it) }
                        .validationOnApply { validateConfigPath(it) }
                        .comment(BiomeBundle.message("biome.config.path.help"))
                        .component
                }.visibleIf(manualConfiguration.selected)
            }

            // *********************
            // Supported file extensions row
            // *********************
            row(BiomeBundle.message("biome.supported.extensions.label")) {
                extensionsField = textField()
                    .align(AlignX.FILL)
                    .bindText({ settings.supportedExtensions.joinToString(",") }, { value ->
                        settings.supportedExtensions =
                            value.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
                    })
                    .validationOnInput { validateExtensions(it) }
                    .applyToComponent {
                        font = font.deriveFont(font.size2D - 2f) // Reduce font size by 2 points
                    }
                    .component

            }.enabledIf(!disabledConfiguration.selected)

            // Add help text with a "Reset" link below the field
            row {
                comment(BiomeBundle.message("biome.supported.extensions.comment") + " <a href=\"reset\">Reset to Defaults</a>")
                    .applyToComponent {
                        addHyperlinkListener { event ->
                            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED && event.description == "reset") {
                                extensionsField.text = BiomeSettingsState.DEFAULT_EXTENSION_LIST.joinToString(",")
                            }
                        }
                    }
            }
                .bottomGap(BottomGap.MEDIUM)
                .enabledIf(!disabledConfiguration.selected)

            // *********************
            // LSP row
            // *********************
            row {
                enableLspFormatCheckBox = checkBox(BiomeBundle.message("biome.enable.lsp.format.label")).bindSelected(
                    { settings.state.enableLspFormat },
                    { settings.enableLspFormat = it },
                ).component

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
                runFormatOnSaveCheckBox = checkBox(BiomeBundle.message("biome.run.format.on.save.label")).bindSelected(
                    { settings.state.formatOnSave },
                    { settings.formatOnSave = it },
                ).component

                val link = ActionsOnSaveConfigurable.createGoToActionsOnSavePageLink()
                cell(link)
            }.enabledIf(!disabledConfiguration.selected)

            // *********************
            // Apply safe fixes on save row
            // *********************
            row {
                runSafeFixesOnSaveCheckBox =
                    checkBox(BiomeBundle.message("biome.run.safe.fixes.on.save.label")).bindSelected(
                        { settings.state.applySafeFixesOnSave },
                        { settings.applySafeFixesOnSave = it },
                    ).component

                val link = ActionsOnSaveConfigurable.createGoToActionsOnSavePageLink()
                cell(link)
            }.enabledIf(!disabledConfiguration.selected)


            // *********************
            // Sort import on save row
            // *********************
            row {
                sortImportOnSaveCheckBox =
                    checkBox(BiomeBundle.message("biome.sort.import.on.save.label")).bindSelected(
                        { settings.state.sortImportOnSave },
                        { settings.sortImportOnSave = it },
                    ).component

                val link = ActionsOnSaveConfigurable.createGoToActionsOnSavePageLink()
                cell(link)
            }.enabledIf(!disabledConfiguration.selected)

            onApply {
                // The default project (Welcome screen / "Settings for New Projects") has no language servers to restart.
                if (project.isDefault) return@onApply

                project.service<BiomeSchemaAssociationService>().invalidate()
                biomeServerService.restartBiomeServer()
                biomeServerService.notifyRestart()
            }
        }
    }

    private fun validateExtensions(field: JBTextField): ValidationInfo? {
        val input = field.text
        val extensions = input.split(",").map { it.trim() }

        val invalidExtension = extensions.find { !it.matches(Regex("^\\.[a-zA-Z0-9]+$")) }
        return if (invalidExtension != null) {
            ValidationInfo("Invalid extension: $invalidExtension. Must start with '.' and contain only alphanumeric characters.",
                field)
        } else {
            null
        }
    }

    private fun validateConfigPath(field: TextFieldWithBrowseButton): ValidationInfo? {
        if (!manualConfiguration.isSelected || field.text.isBlank()) return null

        val valid = try {
            val path = Path(field.text)
            // A relative setting for future projects has no filesystem base to validate yet.
            if (!path.isAbsolute && project.isDefault) return null
            val paths = if (path.isAbsolute) listOf(path) else {
                project.getBaseDirectories().map { it.toNioPath().resolve(path) }
            }
            if (paths.isEmpty()) return null
            val configNames = listOf("biome.json", "biome.jsonc")
            paths.any { candidate ->
                when {
                    Files.isRegularFile(candidate) -> candidate.fileName.toString().let {
                        it.endsWith(".json") || it.endsWith(".jsonc")
                    }
                    Files.isDirectory(candidate) -> configNames.any { Files.isRegularFile(candidate.resolve(it)) }
                    else -> false
                }
            }
        } catch (_: InvalidPathException) {
            false
        }
        return if (valid) null else ValidationInfo(BiomeBundle.message("biome.configuration.file.not.found"), field)
    }

    private fun fileChosen(file: VirtualFile): String {
        return file.path
    }

    private class ConfigurationModeProperty(
        private val settings: BiomeSettings,
        private val mode: ConfigurationMode,
    ) : MutableProperty<Boolean> {
        override fun get(): Boolean = settings.configurationMode == mode

        override fun set(value: Boolean) {
            if (value) {
                settings.configurationMode = mode
            }
        }
    }

    companion object {
        const val CONFIGURABLE_ID = "Settings.Biome"
    }
}

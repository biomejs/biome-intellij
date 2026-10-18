package com.github.biomejs.intellijbiome.schema

import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.wslDistributionId
import com.github.biomejs.intellijbiome.configuration.BiomeConfigurationContext
import com.github.biomejs.intellijbiome.configuration.BiomeConfigurationResolution
import com.github.biomejs.intellijbiome.configuration.BiomeConfigurationResolver
import com.github.biomejs.intellijbiome.configuration.BiomeConfigurationSettings
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.ProjectTopics
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerManagerListener
import com.intellij.platform.lsp.api.LspServerState
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Resolves installed schemas outside UI/model locks and publishes immutable callback-only state. */
@Service(Service.Level.PROJECT)
internal class BiomeSchemaAssociationService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    @Volatile private var ready: Map<String, Association> = emptyMap()
    @Volatile private var published: Map<String, Association> = emptyMap()
    @Volatile private var watchedRoots: Set<String> = emptySet()
    @Volatile private var watchedConfigurations: Set<String> = emptySet()
    private var generation = 0L
    private var pending: Job? = null

    init {
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any(::affectsAssociation)) invalidate()
                }
            })
        project.messageBus.connect(scope).subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) { invalidate() }
        })
        NodeJsInterpreterManager.getInstance(project).addChangeListener({ invalidate() }, this)
        LspServerManager.getInstance(project).addLspServerManagerListener(object : LspServerManagerListener {
            override fun serverStateChanged(lspServer: LspServer) {
                if (lspServer.providerClass == BiomeLspServerSupportProvider::class.java) invalidate()
            }
        }, this)
    }

    fun schemaFor(config: VirtualFile): VirtualFile? = ready[configurationUrl(config)]?.let {
        it.schema.takeIf { schema -> schema.isValid && schema.modificationStamp == it.schemaStamp }
    }

    /** Clear eligibility immediately; coalesce editor, settings, package and server changes. */
    @Synchronized
    fun invalidate(): Job {
        val request = ++generation
        ready = emptyMap()
        pending?.cancel()
        return scope.launch {
            delay(50)
            val input = readAction { capture() } ?: return@launch
            val associations = withContext(Dispatchers.IO) { resolve(input) }
            withContext(Dispatchers.EDT) {
                currentCoroutineContext().ensureActive()
                val current = readAction { capture() == input }
                synchronized(this@BiomeSchemaAssociationService) {
                    if (project.isDisposed || request != generation || !current) return@synchronized
                    val changed = published != associations || ready != associations
                    ready = associations
                    if (changed) {
                        published = associations
                        JsonSchemaService.Impl.get(project).reset()
                    }
                }
            }
        }.also { pending = it }
    }

    private fun capture(): Input? {
        if (project.isDisposed || project.isDefault) return null
        val settings = BiomeConfigurationSettings.capture(project)
        val context = BiomeConfigurationResolver.captureContext(project, null)
        val files = FileEditorManager.getInstance(project).openFiles.filter { it.isValid }
        val servers = LspServerManager.getInstance(project)
            .getServersForProvider(BiomeLspServerSupportProvider::class.java).filter { it.state == LspServerState.Running }
            .mapNotNull { server ->
                val descriptor = server.descriptor as? BiomeLspServerDescriptor ?: return@mapNotNull null
                Selection(descriptor.roots.toList(), descriptor.selectedExecutable, descriptor.configurationPath,
                    server.initializeResult?.serverInfo?.version)
            }
        // Lexical watches also cover missing overrides and package replacement before a schema is ready.
        watchedConfigurations = context.bases.mapNotNull { pathFromBase(it, settings.configPath) }.toSet()
        watchedRoots = context.bases.map { schemaConfigurationPath(it.path) }.toSet() + published.values.map { schemaConfigurationPath(it.packagePath) } +
            context.bases.mapNotNull { base -> pathFromBase(base, settings.executablePath)?.let {
                Path.of(it).parent?.parent?.toString()?.let(::schemaConfigurationPath)
            } }
        return Input(settings, settings.interpreter?.referenceName,
            BiomeSettings.getInstance(project).supportedExtensions.toList(), context, files, servers)
    }

    private fun affectsAssociation(event: VFileEvent): Boolean {
        val paths = listOfNotNull(event.path, event.file?.path).map(::schemaConfigurationPath)
        if (paths.any { it in watchedConfigurations || "file://$it" in published }) return true
        val roots = watchedRoots + published.values.map { schemaConfigurationPath(it.packagePath) }
        if (paths.none { path -> roots.any { root -> path == root || path.startsWith("$root/") || root.startsWith("$path/") } }) return false
        // Ordinary source/data edits do not affect executable identity or schema contents.
        return event !is VFileContentChangeEvent || paths.any {
            it.substringAfterLast('/') in setOf("package.json", "configuration_schema.json") || it.contains("/bin/")
        }
    }

    private fun pathFromBase(base: VirtualFile, path: String): String? = try {
        path.takeIf { it.isNotBlank() }?.let { schemaConfigurationPath(base.toNioPath().resolve(it).normalize().toString()) }
    } catch (_: InvalidPathException) {
        null
    }

    private suspend fun resolve(input: Input): Map<String, Association> {
        if (input.settings.mode != ConfigurationMode.MANUAL || input.settings.configPath.isBlank()) return emptyMap()
        val candidates = linkedMapOf<String, MutableList<Association?>>()
        for (base in input.context.bases) {
            currentCoroutineContext().ensureActive()
            val context = input.context.copy(selectedFile = base, selectedPath = base.path, owningBase = base)
            val resolved = BiomeConfigurationResolver.resolve(context, input.settings) as? BiomeConfigurationResolution.Found
                ?: continue
            if (resolved.file.name in setOf("biome.json", "biome.jsonc")) continue
            val matching = input.servers.filter { server ->
                server.configPath?.let(::schemaConfigurationPath) == schemaConfigurationPath(input.settings.configPath) && server.roots.any { VfsUtilCore.isAncestor(base, it, false) }
            }
            val schemas = if (matching.isNotEmpty()) {
                matching.map { installedSchema(it.executable, base, it.version) }
            } else if (input.settings.executablePath.isNotBlank()) {
                listOf(installedSchema(input.settings.executablePath, base, null))
            } else {
                // A Manual override does not change launch selection: source files can select different
                // packages within one IDE base. Never discover relative to an external config's parent.
                val sources = listOf(base) + input.files.filter {
                    VfsUtilCore.isAncestor(base, it, false) && ".${it.extension}" in input.supportedExtensions
                }
                sources.map { source ->
                    val executable = BiomePackage(project).getPackage(source, input.settings.interpreter)?.getAbsolutePackagePathToRequire(project)
                        ?.let { Path.of(it, "bin", "biome").toString() }
                    executable?.let { installedSchema(it, base, null) }
                }
            }
            candidates.getOrPut(configurationUrl(resolved.file)) { mutableListOf() }.addAll(schemas)
        }
        return candidates.mapNotNull { (url, schemas) ->
            // Unknown identity is not permission to borrow another base's schema.
            if (schemas.any { it == null }) null else schemas.distinct().singleOrNull()?.let { url to it }
        }.toMap()
    }

    /** Prove the selected file is this package's declared bin, including npm symlinks. No process probes. */
    internal fun installedSchema(executable: String, base: VirtualFile, serverVersion: String?): Association? {
        check(!ApplicationManager.getApplication().isReadAccessAllowed)
        ApplicationManager.getApplication().assertIsNonDispatchThread()
        return try {
            val path = Path.of(executable)
            if (!path.isAbsolute && path.parent == null) return null // PATH lookup is not package identity.
            val realExecutable = base.toNioPath().resolve(path).toRealPath()
            val root = realExecutable.parent?.parent ?: return null
            val metadata = Json.parseToJsonElement(Files.readString(root.resolve("package.json"))) as? JsonObject ?: return null
            if ((metadata["name"] as? JsonPrimitive)?.contentOrNull != "@biomejs/biome") return null
            val version = (metadata["version"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            if (serverVersion != null && serverVersion != version) return null
            val bin = when (val value = metadata["bin"]) {
                is JsonPrimitive -> value.contentOrNull
                is JsonObject -> (value["biome"] as? JsonPrimitive)?.contentOrNull
                else -> null
            } ?: return null
            val declared = root.resolve(bin).normalize()
            if (!declared.startsWith(root) || declared.toRealPath() != realExecutable) return null
            val schemaPath = root.resolve("configuration_schema.json")
            if (!Files.isRegularFile(schemaPath)) return null
            val schema = LocalFileSystem.getInstance().findFileByNioFile(schemaPath) ?: return null
            Association(root.toString(), version, schema, schema.modificationStamp)
        } catch (_: IOException) {
            null
        } catch (_: InvalidPathException) {
            null
        } catch (_: SerializationException) {
            null
        }
    }

    override fun dispose() = Unit

    internal data class Association(val packagePath: String, val version: String, val schema: VirtualFile, val schemaStamp: Long)
    private data class Selection(val roots: List<VirtualFile>, val executable: String, val configPath: String?, val version: String?)
    private data class Input(val settings: BiomeConfigurationSettings, val interpreterName: String?,
        val supportedExtensions: List<String>, val context: BiomeConfigurationContext, val files: List<VirtualFile>, val servers: List<Selection>)
}

/** Initializes schema assistance on ordinary editor opens, including configs outside project content. */
class BiomeSchemaEditorListener : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val project = source.project
        val settings = BiomeSettings.getInstance(project)
        if (!project.isDefault && settings.configurationMode == ConfigurationMode.MANUAL && settings.configPath.isNotBlank()) {
            project.service<BiomeSchemaAssociationService>().invalidate()
        }
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        source.project.getServiceIfCreated(BiomeSchemaAssociationService::class.java)?.invalidate()
    }
}

/** WSL UNC aliases identify one Linux file; preserve the case-sensitive path below the distribution. */
internal fun schemaConfigurationPath(path: String): String {
    val normalized = path.replace('\\', '/')
    val distribution = wslDistributionId(normalized) ?: return normalized
    val suffix = normalized.removePrefix("//").substringAfter('/').substringAfter('/')
    return "//wsl.localhost/${distribution.lowercase()}/$suffix"
}

private fun configurationUrl(file: VirtualFile): String =
    if (wslDistributionId(file.path) == null) file.url else "file://${schemaConfigurationPath(file.path)}"

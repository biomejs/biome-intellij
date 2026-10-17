package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.BiomeConfig
import com.github.biomejs.intellijbiome.extensions.findNearestBiomeConfig
import com.github.biomejs.intellijbiome.extensions.isBiomeConfigFile
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerSupportProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun BiomeSettings.usesConfigDiscovery(): Boolean =
    configurationMode == ConfigurationMode.AUTOMATIC ||
        (configurationMode == ConfigurationMode.MANUAL && configPath.isEmpty())

/** Reconciles config ownership without reopening editors or restarting ordinary config edits. */
@Service(Service.Level.PROJECT)
class BiomeConfigDiscoveryService(private val project: Project, private val scope: CoroutineScope) {
    init {
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (project.isDisposed) return
                    val directories = events.flatMap(::configDirectories).distinct()
                    if (directories.isEmpty()) return
                    val request = project.service<BiomeLspLifecycleService>().requestStamp()
                    scope.launch { recoverOpenFiles(directories, request) }
                }
            })
    }

    private fun configDirectories(event: VFileEvent): List<String> {
        val file = if (event is VFileCopyEvent) event.findCreatedFile() else event.file
        val wasConfig = event.path.substringAfterLast('/') in configNames ||
            (event is VFilePropertyChangeEvent && event.propertyName == VirtualFile.PROP_NAME && (event.oldValue as? String)?.let { it in configNames } == true)
        if (file?.isBiomeConfigFile() != true && !wasConfig) return emptyList()
        // Deleted files are invalid, and moved/renamed configs also affect their former directory.
        return buildList {
            add(event.path.substringBeforeLast('/'))
            file?.parent?.path?.let(::add)
            if (event is VFileMoveEvent) add(event.oldParent.path)
        }
    }

    private suspend fun recoverOpenFiles(directories: List<String>, request: Long) {
        val lifecycle = project.service<BiomeLspLifecycleService>()
        if (!lifecycle.allowsRecovery(request)) return
        val initialStamp = lifecycle.requestStamp()
        if (!readAction { scan(directories, request) != null } && lifecycle.isCurrent(initialStamp)) return
        withContext(Dispatchers.EDT) {
            while (!project.isDisposed && lifecycle.allowsRecovery(request)) {
                if (!BiomeSettings.getInstance(project).usesConfigDiscovery()) return@withContext
                // Resolve again after the queued EDT boundary: settings, editors and executable
                // selection may have changed while the initial discovery read was pending.
                // Preserve the event's Stop intent while selecting a fresh process-start fence.
                val startRequest = lifecycle.requestStamp()
                val plan = readAction { createPlan(directories, request, startRequest) }
                if (!lifecycle.allowsRecovery(request)) return@withContext
                if (!lifecycle.isCurrent(startRequest)) continue
                if (plan == null) return@withContext
                val manager = LspServerManager.getInstance(project)
                if (settingsSnapshot() != plan.settings ||
                    FileEditorManager.getInstance(project).openFiles.toSet() != plan.openFiles ||
                    manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toSet() != plan.servers.toSet()) continue
                val stamp = if (plan.replaceOwners) {
                    // The public 253 API stops by provider. Replace these clients to clear
                    // permanently cached isSupportedFile(false) results before ownership changes.
                    lifecycle.replaceOwners().also {
                        manager.stopServers(BiomeLspServerSupportProvider::class.java)
                    }
                } else startRequest
                for (descriptor in plan.descriptors) {
                    val root = descriptor.roots.single()
                    if (!lifecycle.permits(root, stamp) || lifecycle.isStopped(root)) continue
                    descriptor.refreshStartRequest(stamp)
                    manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java, descriptor)
                }
                return@withContext
            }
        }
    }

    private fun scan(directories: List<String>, request: Long): Scan? {
        if (project.isDisposed || !BiomeSettings.getInstance(project).usesConfigDiscovery()) return null
        val settings = BiomeSettings.getInstance(project)
        val projectRoots = project.getBaseDirectories()
        val changed = directories.filter { directory ->
            projectRoots.any { directory == it.path || directory.startsWith(it.path + "/") }
        }
        if (changed.isEmpty()) return null
        val servers = LspServerManager.getInstance(project)
            .getServersForProvider(BiomeLspServerSupportProvider::class.java).toList()
        val serverRoots = servers.flatMap { it.descriptor.roots.toList() }
        val index = ProjectFileIndex.getInstance(project)
        val lifecycle = project.service<BiomeLspLifecycleService>()
        val openFiles = FileEditorManager.getInstance(project).openFiles.toSet()
        val ownership = openFiles.mapNotNull { file ->
            if (!file.isValid || !file.isInLocalFileSystem || !index.isInContent(file) || !settings.fileSupported(file)) return@mapNotNull null
            val projectRoot = projectRoots.firstOrNull { VfsUtilCore.isAncestor(it, file, true) } ?: return@mapNotNull null
            val established = serverRoots.filter { VfsUtilCore.isAncestor(it, file, true) }.maxByOrNull { it.path.length }
            val ownConfig = established?.children?.firstOrNull { it.isBiomeConfigFile() }
            val malformed = ownConfig != null && BiomeConfig.loadFromFile(ownConfig) == null
            val resolvedConfig = file.findNearestBiomeConfig(projectRoot)
            // The lookup falls back to nested non-root configs when no root is valid.
            // That fallback may bootstrap a workspace, but cannot replace an established owner.
            val resolved = resolvedConfig?.takeIf {
                established == null || BiomeConfig.loadFromFile(it)?.isRootConfig() == true
            }?.parent
            // A parse error is not a transfer. A missing root is retained unless a valid
            // ancestor can adopt its editor, preserving recovery when the lone config returns.
            val retainMalformed = malformed && (resolved == null || established == null ||
                !VfsUtilCore.isAncestor(established, resolved, true))
            val desired = if (retainMalformed) established else resolved ?: established
            if (desired == null) return@mapNotNull null
            if (!lifecycle.permitsRecovery(file, desired, established, request)) {
                // A newer real reopen may already have restored an unrelated owner. Preserve
                // that live owner during a provider-wide replacement without authorizing the
                // obsolete event to transfer any of its files.
                if (established == null || lifecycle.isStopped(established)) return@mapNotNull null
                return@mapNotNull Ownership(file, established, established, retainEstablished = true)
            }
            Ownership(file, established, desired, retainMalformed || resolved == null)
        }
        val affected = ownership.filter { item -> changed.any { item.file.path.startsWith(it + "/") } }
        if (affected.none { it.established != it.desired }) return null
        return Scan(servers, ownership, openFiles, affected.any { it.established != null && it.established != it.desired })
    }

    private fun createPlan(directories: List<String>, request: Long, startRequest: Long): Plan? {
        val scan = scan(directories, request) ?: return null
        val descriptors = linkedMapOf<VirtualFile, BiomeLspServerDescriptor>()
        val existing = scan.servers.associateBy { it.descriptor.roots.single() }
        val provider = BiomeLspServerSupportProvider()
        for (ownership in scan.ownership) {
            if (ownership.desired in descriptors || (!scan.replaceOwners && ownership.desired in existing)) continue
            if (ownership.retainEstablished) {
                (existing[ownership.desired]?.descriptor as? BiomeLspServerDescriptor)?.copyForStart(startRequest)?.let {
                    descriptors[ownership.desired] = it
                }
                continue
            }
            provider.discoverFile(project, ownership.file, object : LspServerSupportProvider.LspServerStarter {
                override fun ensureServerStarted(descriptor: LspServerDescriptor) {
                    if (descriptor.roots.single() == ownership.desired) {
                        descriptors[ownership.desired] = descriptor as BiomeLspServerDescriptor
                    }
                }
            }, startRequest, includeNestedRoots = false)
        }
        return Plan(scan.servers, scan.openFiles, scan.replaceOwners, descriptors.values.toList(), settingsSnapshot())
    }

    private fun settingsSnapshot(): List<Any> = BiomeSettings.getInstance(project).let {
        listOf(it.configurationMode, it.configPath, it.executablePath, it.supportedExtensions.toList())
    }

    private data class Ownership(val file: VirtualFile, val established: VirtualFile?, val desired: VirtualFile,
        val retainEstablished: Boolean)
    private data class Scan(val servers: List<LspServer>, val ownership: List<Ownership>, val openFiles: Set<VirtualFile>,
        val replaceOwners: Boolean)
    private data class Plan(val servers: List<LspServer>, val openFiles: Set<VirtualFile>, val replaceOwners: Boolean,
        val descriptors: List<BiomeLspServerDescriptor>, val settings: List<Any>)

    companion object {
        private val configNames = setOf("biome.json", "biome.jsonc")
    }
}

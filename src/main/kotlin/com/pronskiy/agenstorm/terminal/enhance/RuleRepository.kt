package com.pronskiy.agenstorm.terminal.enhance

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ide.actions.RevealFileAction
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.core.AgenstormSettings
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Steps I3.1 and I3.2. The rules the enhancer runs: the five built-ins, overridden and extended by the JSON
 * files in `<config>/agenstorm/terminal-rules/`. The folder is created on first use and watched — a file saved
 * there is reparsed a moment later (I3.2) — and a file that fails to parse produces one balloon naming it and
 * the error, is skipped, and leaves every other rule working: no rule file can make the terminal worse than
 * having the feature off.
 */
@Service(Service.Level.APP)
@OptIn(FlowPreview::class)
class RuleRepository(scope: CoroutineScope) : Disposable {

    val folder: Path = Path.of(PathManager.getConfigPath(), "agenstorm", "terminal-rules")

    private val builtIns: List<EnhancerRule> by lazy { BlockDetector.builtInRules() }
    private val loaded = AtomicBoolean(false)

    @Volatile
    private var catalog: RuleCatalog? = null
    private val reloadRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var watch: LocalFileSystem.WatchRequest? = null

    init {
        scope.launch(CoroutineName("Agenstorm terminal rules")) {
            reloadRequests.debounce(RELOAD_DEBOUNCE_MS).collectLatest {
                reload()
                resetProjects()
            }
        }
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val prefix = folder.toString()
                if (events.any { it.path.startsWith(prefix) }) reloadRequests.tryEmit(Unit)
            }
        })
    }

    /** Every rule, built-ins with the user's overrides applied. Reads the folder the first time it is asked. */
    val rules: List<EnhancerRule> get() = current().rules

    /** The files that did not parse at the last read. */
    val errors: List<RuleParseException> get() = current().errors

    /** The rules the detector runs: on in their file, and not switched off on the settings page. */
    fun activeRules(): List<EnhancerRule> = current().active(AgenstormSettings.getInstance().state.terminalEnhancerDisabledRules)

    /** Reads the folder now, creating it on first use, and says once about each file that failed. */
    fun reload(): RuleCatalog {
        ensureFolder()
        val read = RuleCatalog.load(folder, builtIns)
        catalog = read
        loaded.set(true)
        RuleFileNotice.report(read.errors, folder)
        return read
    }

    /** A reload a moment from now, collapsed with any other pending one; the terminals re-fold afterwards. */
    fun reloadLater() {
        reloadRequests.tryEmit(Unit)
    }

    /** Creates the folder and starts watching it; harmless to call again. */
    fun ensureFolder() {
        Files.createDirectories(folder)
        if (watch == null) {
            watch = LocalFileSystem.getInstance().addRootToWatch(folder.toString(), true)
            VirtualFileManager.getInstance().refreshAndFindFileByNioPath(folder)
        }
    }

    /** The ids of the rules that ship with the plugin, for "Copy a built-in rule…". */
    fun builtInIds(): List<String> = builtIns.map { it.id }

    /**
     * Writes a built-in rule's file into the folder, as a starting point for an override, and reads the folder
     * again. Null when [id] is not a built-in or the file is already there — a user's edits are never overwritten.
     */
    fun copyBuiltIn(id: String): Path? {
        if (id !in builtInIds()) return null
        ensureFolder()
        val target = folder.resolve("$id.json")
        if (Files.exists(target)) return null
        val text = BlockDetector::class.java.getResourceAsStream("/terminal/rules/$id.json")?.bufferedReader()?.use { it.readText() } ?: return null
        Files.writeString(target, text)
        reload()
        return target
    }

    /** Every open project's terminals re-fold with the current rules, on the EDT. */
    fun resetProjects() {
        ApplicationManager.getApplication().invokeLater({
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) TerminalEnhancerService.getInstance(project).resetAll()
            }
        }, ModalityState.any())
    }

    private fun current(): RuleCatalog {
        catalog?.let { if (loaded.get()) return it }
        return reload()
    }

    override fun dispose() {
        watch?.let { LocalFileSystem.getInstance().removeWatchedRoot(it) }
        watch = null
    }

    @TestOnly
    fun forgetForTest() {
        catalog = null
        loaded.set(false)
        RuleFileNotice.forgetForTest()
    }

    companion object {
        const val RELOAD_DEBOUNCE_MS = 300L
        fun getInstance(): RuleRepository = service()
    }
}

/**
 * One balloon per broken file and message. The key is remembered for the session, so a folder that is reparsed
 * on every save does not nag about the same mistake twice; a *different* mistake in the same file is news.
 */
object RuleFileNotice {
    private val LOG = logger<RuleFileNotice>()
    private val shown: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** The errors not reported before, in order; pure, for the tests. */
    fun unseen(errors: List<RuleParseException>): List<RuleParseException> = errors.filter { keyOf(it) !in shown }

    fun report(errors: List<RuleParseException>, folder: Path) {
        for (error in unseen(errors)) {
            shown += keyOf(error)
            LOG.warn("terminal rule skipped: ${error.message}")
            AgenstormNotifications.group()
                .createNotification(
                    AgenstormBundle.message("terminal.enhancer.rules.broken.title", error.source),
                    error.message ?: error.expected,
                    NotificationType.WARNING,
                )
                .addAction(NotificationAction.createSimple(AgenstormBundle.message("terminal.enhancer.rules.openFolder")) { RevealFileAction.openDirectory(folder) })
                .notify(null)
        }
    }

    private fun keyOf(error: RuleParseException) = error.source + "\u0000" + error.message

    @TestOnly
    fun forgetForTest() = shown.clear()
}

package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseHandler
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.QuietClose
import com.pronskiy.agenstorm.terminal.tmux.TmuxBackgroundPlan.Decided
import com.pronskiy.agenstorm.terminal.tmux.TmuxBackgroundPlan.Shown
import com.pronskiy.agenstorm.terminal.tmux.TmuxCloseDialog.Answer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Step U3.2 (decisions 91, 92). Closing a project or quitting with a tmux tab running something no other window shows
 * asks whether to keep it — as a *background terminal*, listed (U3.3) and back as a tab when its project opens (U3.1) —
 * or to stop it. A project close asks with the platform's process dialog ([TmuxCloseDialog.ask], *Disconnect* keeps); a
 * quit asks once for every window ([TmuxCloseDialog.askQuit]), after which the windows' own closes stay silent. A close
 * Agenstorm makes itself ([QuietClose]: offloading, a worktree switch, a removal) asks nothing and keeps. Asking and
 * acting are apart, because another listener can still cancel the close after ours said yes: the answer is acted on in
 * `projectClosing`, after every veto and with the tabs still attached — so a kept session has its `destroy-unattached`
 * off before its last client leaves — and only for the sessions that project's tabs show, a quit's answer included
 * (`beforeAppWillBeClosed`, the one moment a quit is certain, is internal API). An answer counts for a minute, as the
 * platform's own terminal check does: a close the user cancels elsewhere does not leave it asking twice.
 */
@Service(Service.Level.APP)
class TmuxCloseGuard : Disposable {

    /** An answer, and the sessions it is about that no closing project has acted on yet. */
    class Decision(val answer: Answer, sessions: List<Decided>, val at: Long = System.currentTimeMillis()) {
        private val pending = sessions.toMutableList()

        fun isRecent(now: Long = System.currentTimeMillis()): Boolean = now - at <= ANSWER_MS

        /** The pending sessions among [shown], taken: each is acted on once, by the first project closing that shows it. */
        @Synchronized
        fun take(shown: Set<String>): List<Decided> = pending.filter { it.session in shown }.also { pending.removeAll(it) }
    }

    @Volatile
    private var quit: Decision? = null
    private val installed = AtomicBoolean()

    /**
     * The quit prompt hangs on `ApplicationListener`, which has no declarative registration and whose start-up hook
     * (`AppLifecycleListener.appStarted`) is internal API; [TmuxReattach] installs it as each project opens, once a run.
     */
    fun install() {
        if (installed.compareAndSet(false, true)) ApplicationManager.getApplication().addApplicationListener(QuitListener(), this)
    }

    /** [ProjectCloseHandler]: on the EDT, before anything closes. */
    fun canClose(project: Project): Boolean {
        if (quit?.isRecent() == true) return true
        if (project.getUserData(DECISION)?.isRecent() == true) return true
        val shown = TmuxTabs.of(project).map { (tab, session) -> shownOf(tab, session) }
        if (shown.isEmpty()) return true
        val atStake = TmuxBackgroundPlan.atStake(read(), shown, project.basePath)
        if (atStake.isEmpty()) return true
        val answer = if (QuietClose.isQuiet(project)) Answer.KEEP
        else TmuxCloseDialog.ask(project, atStake.map { session -> shown.first { it.session == session.name }.title }, canKeep = true)
        if (answer == Answer.CANCEL) return false
        project.putUserData(DECISION, Decision(answer, atStake.map { session -> Decided(session.name, shown.first { it.session == session.name }.name) }))
        return true
    }

    /** [ProjectCloseListener]: the close — or a quit's close of this window — is going ahead, the tabs are still there. */
    fun projectClosing(project: Project) {
        val own = project.getUserData(DECISION)?.also { project.putUserData(DECISION, null) }?.takeIf { it.isRecent() }
        val decision = own ?: quit?.takeIf { it.isRecent() } ?: return
        val sessions = decision.take(TmuxTabs.of(project).map { it.second }.toSet())
        if (sessions.isNotEmpty()) apply(decision.answer, sessions, project.name)
    }

    /** `ApplicationListener.canExitApplication`: every window closes, so one question for all of them. */
    fun canQuit(): Boolean {
        val projects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }
        val shown = projects.flatMap { project -> TmuxTabs.of(project).map { (tab, session) -> shownOf(tab, session) } }
        if (shown.isEmpty()) return true
        val atStake = TmuxBackgroundPlan.atStake(read(), shown, project = null)
        if (atStake.isEmpty()) return true
        val labels = atStake.map { session ->
            TmuxBackgroundPlan.worktreeName(session)?.let { AgenstormBundle.message("terminal.tmux.quit.label", session.label, it) } ?: session.label
        }
        val answer = TmuxCloseDialog.askQuit(labels)
        if (answer == Answer.CANCEL) return false
        quit = Decision(answer, atStake.map { session -> Decided(session.name, shown.first { it.session == session.name }.name) })
        return true
    }

    private fun apply(answer: Answer, sessions: List<Decided>, where: String) {
        val tmux = Tmux.getInstance()
        for (session in sessions) {
            val command = if (answer == Answer.KEEP) TmuxBackgroundPlan.keep(session.session, session.tabName) else TmuxBackgroundPlan.stop(session.session)
            if (tmux.run(*command.toTypedArray()) == null) LOG.warn("Agenstorm: could not ${answer.name.lowercase()} tmux session ${session.session}")
        }
        LOG.info("Agenstorm: ${answer.name.lowercase()} ${sessions.joinToString { it.session }} as $where closes")
        BackgroundTerminals.getInstance().refresh()
    }

    private fun shownOf(tab: TerminalToolWindowTab, session: String): Shown {
        val title = tab.content.displayName
        val name = tab.view.title.userDefinedTitle ?: tab.content.getUserData(TmuxTitleMirror.ORIGINAL_NAME) ?: title
        return Shown(session, title, name)
    }

    /** The sessions now, read under a short modal progress: this runs on the EDT, and tmux answers in milliseconds. */
    private fun read(): List<TmuxSession> = ProgressManager.getInstance().runProcessWithProgressSynchronously(
        ThrowableComputable<List<TmuxSession>, RuntimeException> { TmuxSessions.read() },
        AgenstormBundle.message("terminal.tmux.checking"), false, null,
    )

    override fun dispose() = Unit

    private class QuitListener : ApplicationListener {
        override fun canExitApplication(): Boolean = getInstance().canQuit()
    }

    companion object {
        private val LOG = logger<TmuxCloseGuard>()
        private val DECISION = Key.create<Decision>("agenstorm.tmux.closeDecision")
        private const val ANSWER_MS = 60_000L

        fun getInstance(): TmuxCloseGuard = service()
    }
}

/** Step U3.2: `com.intellij.projectCloseHandler`, asked before a project closes. */
class TmuxProjectCloseHandler : ProjectCloseHandler {
    override fun canClose(project: Project): Boolean = TmuxCloseGuard.getInstance().canClose(project)
}

/** Step U3.2: acts on a project close's answer once the close is certain. */
class TmuxProjectClosing : ProjectCloseListener {
    override fun projectClosing(project: Project) = TmuxCloseGuard.getInstance().projectClosing(project)
}

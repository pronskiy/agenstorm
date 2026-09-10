package com.pronskiy.agenstorm.core

import com.intellij.ide.ui.customization.CustomActionsListener
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.diagnostic.logger

/**
 * One platform action id a feature borrows while it is on, and gives back when it is off.
 *
 * `ActionManager.replaceAction` keeps the action's place in every group it belongs to and its keymap shortcut,
 * because both hang off the id — so a feature can take over an action the IDE already ships without
 * re-registering anything. What it does not do is remember what it displaced, which is what this class is for:
 * the widget or action taken out is held as a plain [AnAction], never as its own type, so no feature has to name
 * a class the platform marked internal in order to fall back to it.
 *
 * `<action overrides="true">` cannot do this. It replaces the platform's action at load time and leaves nothing
 * to restore, which is why a feature built on it has to subclass the class it replaces.
 *
 * Fails soft: an IDE without [actionId] gets a warning, and the feature stays off.
 */
class ActionSlot(private val actionId: String, private val replacement: () -> AnAction) {

    private val log = logger<ActionSlot>()

    /** The action taken out of the slot, while ours is in it. */
    private var displaced: AnAction? = null

    /** The instance we put in, so "is the slot still ours?" is identity, not a type test. */
    private var installed: AnAction? = null

    /** Puts the slot in the state [enabled] asks for. Idempotent, so every entry point can just call it. */
    @Synchronized
    fun sync(enabled: Boolean) {
        if (enabled) take() else giveBack()
    }

    @Synchronized
    fun take() {
        val actionManager = ActionManager.getInstance()
        val current = actionManager.getAction(actionId)
        if (current == null) {
            log.warn("No $actionId action in this IDE; the feature that replaces it stays off")
            return
        }
        if (current === installed) return
        val ours = replacement()
        displaced = current
        installed = ours
        actionManager.replaceAction(actionId, ours)
        toolbarsAndMenusChanged()
    }

    /** Gives the slot back to the action taken out of it. A slot we never took is left alone. */
    @Synchronized
    fun giveBack() {
        val stock = displaced ?: return
        val actionManager = ActionManager.getInstance()
        if (actionManager.getAction(actionId) !== installed) {
            // Someone else owns the slot now; ours is already out of the way.
            displaced = null
            installed = null
            return
        }
        actionManager.replaceAction(actionId, stock)
        displaced = null
        installed = null
        toolbarsAndMenusChanged()
    }

    /** True while the slot holds the action this slot installed. */
    @Synchronized
    fun isTaken(): Boolean = installed != null && ActionManager.getInstance().getAction(actionId) === installed

    /** Toolbars and menus re-read their groups on the next update; this is the platform's own nudge to do it now. */
    private fun toolbarsAndMenusChanged() {
        CustomActionsListener.fireSchemaChanged()
    }
}

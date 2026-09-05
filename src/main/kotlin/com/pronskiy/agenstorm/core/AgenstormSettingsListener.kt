package com.pronskiy.agenstorm.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.Topic

/**
 * Fired on the application bus after the settings page applied a change that other features must react to at
 * once (for example the project-tabs options). Features subscribe with `messageBus.connect(disposable)`.
 */
fun interface AgenstormSettingsListener {

    fun settingsApplied(state: AgenstormSettings.State)

    companion object {
        val TOPIC: Topic<AgenstormSettingsListener> = Topic.create("Agenstorm settings applied", AgenstormSettingsListener::class.java)

        fun fire() {
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).settingsApplied(AgenstormSettings.getInstance().state)
        }
    }
}

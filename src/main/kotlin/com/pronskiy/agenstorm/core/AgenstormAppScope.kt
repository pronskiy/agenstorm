package com.pronskiy.agenstorm.core

import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope

/** Application-level coroutine scope for UI-triggered background work (settings "Test Connection"). */
@Service(Service.Level.APP)
class AgenstormAppScope(val scope: CoroutineScope)

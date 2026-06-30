package com.github.pronskiy.phptypehintsextra.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger

@Service(Service.Level.APP)
class MyApplicationService {
    init {
        thisLogger().info("PHP Typehints Extra loaded")
    }
}

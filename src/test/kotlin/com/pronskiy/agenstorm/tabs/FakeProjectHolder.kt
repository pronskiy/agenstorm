package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy

/** Distinct [Project] stand-ins for ordering tests: identity-only proxies that report a name and "not disposed". */
object FakeProjectHolder {

    fun another(template: Project, name: String): Project = Proxy.newProxyInstance(
        template.javaClass.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "isDisposed" -> false
            "getName" -> name
            "getBasePath" -> "/fake/$name"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "FakeProject($name)"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as Project
}

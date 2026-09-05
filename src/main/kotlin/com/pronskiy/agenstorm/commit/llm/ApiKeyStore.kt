package com.pronskiy.agenstorm.commit.llm

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * API keys live in the platform's PasswordSafe (keychain), never in `agenstorm.xml`. One entry per backend id.
 * [get] and [set] talk to the OS keychain and PasswordSafe asserts that they never run on the EDT (a
 * `SlowOperations` error otherwise): call them from a background thread, or use [load] and [store] from a
 * coroutine.
 */
object ApiKeyStore {

    fun get(backendId: String): String? = PasswordSafe.instance.getPassword(attributes(backendId))?.takeIf { it.isNotBlank() }

    fun set(backendId: String, key: String?) {
        PasswordSafe.instance.setPassword(attributes(backendId), key?.trim()?.takeIf { it.isNotEmpty() })
    }

    suspend fun load(backendId: String): String? = withContext(Dispatchers.IO) { get(backendId) }

    suspend fun store(backendId: String, key: String?) = withContext(Dispatchers.IO) { set(backendId, key) }

    private fun attributes(backendId: String) = CredentialAttributes(generateServiceName("Agenstorm", backendId))
}

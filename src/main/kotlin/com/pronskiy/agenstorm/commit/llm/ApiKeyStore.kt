package com.pronskiy.agenstorm.commit.llm

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * API keys live in the platform's PasswordSafe (keychain), never in `agenstorm.xml`. One entry per backend id.
 * Reads may block on the OS keychain: call from a background thread.
 */
object ApiKeyStore {

    fun get(backendId: String): String? = PasswordSafe.instance.getPassword(attributes(backendId))?.takeIf { it.isNotBlank() }

    fun set(backendId: String, key: String?) {
        PasswordSafe.instance.setPassword(attributes(backendId), key?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun attributes(backendId: String) = CredentialAttributes(generateServiceName("Agenstorm", backendId))
}

package com.pronskiy.agenstorm.worktrees.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * Step T2.5, pure but for [find]. The setup a repository asks a new worktree to run, in Cursor's format (decision 79),
 * so a repository set up for Cursor works unchanged: `.cursor/worktrees.json`, or `.agenstorm/worktrees.json` with the
 * same keys when there is none. `setup-worktree-unix` (or `-windows`) wins over `setup-worktree`; the value is a list
 * of commands, run in order and stopping at the first that fails, or the path of a script, relative to the file.
 */
object SetupConfig {

    val FILES = listOf(".cursor/worktrees.json", ".agenstorm/worktrees.json")

    sealed interface Setup {
        data class Commands(val commands: List<String>) : Setup
        data class Script(val path: Path) : Setup
    }

    /** The first config file the main checkout has. */
    fun find(main: Path): Path? = FILES.map(main::resolve).firstOrNull(Files::isRegularFile)

    /** Null when the file does not parse or names no setup for this platform. */
    fun parse(json: String, configDir: Path, windows: Boolean = false): Setup? {
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val value = root[if (windows) "setup-worktree-windows" else "setup-worktree-unix"] ?: root["setup-worktree"] ?: return null
        return when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                .takeIf { it.isNotEmpty() }?.let(Setup::Commands)
            is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.let { Setup.Script(configDir.resolve(it).normalize()) }
            else -> null
        }
    }
}

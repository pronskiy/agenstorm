package com.pronskiy.agenstorm.terminal.enhance

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Step I3.1. What reading the rules folder produced: every rule that parsed — the built-ins with the user's
 * overrides applied — and every file that did not, each with the file, the field and what was expected.
 */
class RuleCatalog(val rules: List<EnhancerRule>, val errors: List<RuleParseException>) {

    /** The rules the detector runs: on in their file, and not switched off in the settings. */
    fun active(disabledIds: Collection<String>): List<EnhancerRule> = rules.filter { it.enabled && it.id !in disabledIds }

    companion object {
        /**
         * A user rule with a built-in's id takes that built-in's place, so "edit a built-in" is "copy it and change
         * it"; the user's other rules follow in file-name order. Two files claiming one id: the later name wins.
         */
        fun merge(builtIns: List<EnhancerRule>, user: List<EnhancerRule>): List<EnhancerRule> {
            val byId = LinkedHashMap<String, EnhancerRule>()
            for (rule in user) byId[rule.id] = rule
            val replaced = builtIns.map { byId.remove(it.id) ?: it }
            return replaced + byId.values
        }

        /** Reads every `*.json` in [folder] (a missing folder is an empty one) and merges what parsed over [builtIns]. */
        fun load(folder: Path, builtIns: List<EnhancerRule>): RuleCatalog {
            val files = if (Files.isDirectory(folder)) {
                Files.list(folder).use { stream -> stream.filter { Files.isRegularFile(it) && it.extension == "json" }.sorted(compareBy { it.name }).toList() }
            } else {
                emptyList()
            }
            val parsed = ArrayList<EnhancerRule>()
            val errors = ArrayList<RuleParseException>()
            for (file in files) {
                try {
                    parsed += RuleParser.parse(file.readText(), file.name)
                } catch (e: RuleParseException) {
                    errors += e
                } catch (e: IOException) {
                    errors += RuleParseException(file.name, null, "a readable file (${e.message})")
                }
            }
            return RuleCatalog(merge(builtIns, parsed), errors)
        }
    }
}

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PhpStorm plugin (`php-typehints-extra`) built with Kotlin and Gradle. Adds extra
type-hinting capabilities to PHP code. Targets PhpStorm 2026.1+ (build 261+). This is
currently a minimal scaffold — the project was stripped down from a previous plugin and
is the starting point for new functionality.

## Build Commands

```bash
./gradlew build              # Full build (compile + test)
./gradlew runIde             # Launch PhpStorm sandbox with plugin loaded
./gradlew test               # Run unit tests
./gradlew buildPlugin        # Build distributable plugin ZIP
./gradlew verifyPlugin       # Verify plugin structure
./gradlew publishPlugin      # Publish to JetBrains Marketplace (requires PUBLISH_TOKEN)
```

## Architecture

### Plugin descriptor

`src/main/resources/META-INF/plugin.xml` — registers all extensions and dependencies. Plugin ID: `com.github.pronskiy.phptypehintsextra`. Depends on `com.intellij.modules.platform` and `com.jetbrains.php` (the bundled PhpStorm PHP plugin, giving access to PHP PSI and type inference).

### Source layout

All source lives under `src/main/kotlin/com/github/pronskiy/phptypehintsextra/`:

| File | Purpose |
|------|---------|
| `MyBundle.kt` | i18n resource bundle accessor (`MyBundle.message("key")`) |
| `services/MyApplicationService.kt` | Sample application-level `@Service` (no-op starting point) |

### Resource bundle

`src/main/resources/messages/MyBundle.properties` — message keys for the plugin UI.

### Tests

`src/test/kotlin/com/github/pronskiy/phptypehintsextra/MyPluginTest.kt` — extends `BasePlatformTestCase`, uses `myFixture` for PSI testing. Test data in `src/test/testData/`.

## Build Configuration

- **Kotlin 2.3.10**, JVM toolchain Java 21
- **Gradle 9.3.1** with Kotlin DSL (`build.gradle.kts`)
- Dependencies managed via version catalog: `gradle/libs.versions.toml`
- Plugin metadata (version, platform target, dependencies): `gradle.properties`
- Bundled plugin dependency: `platformBundledPlugins = com.jetbrains.php` in `gradle.properties`
- Kotlin stdlib is NOT bundled (`kotlin.stdlib.default.dependency = false`) — uses the one from the IntelliJ Platform
- Gradle configuration cache and build cache are enabled

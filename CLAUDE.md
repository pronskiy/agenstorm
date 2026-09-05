# CLAUDE.md

Project instructions for Claude Code working on **Agenstorm** — a PhpStorm plugin (Kotlin, IntelliJ Platform 2026.2) that makes the IDE friendlier for agent-driven development: clickable `path:line:col` locations everywhere, LLM commit messages without SDK bloat, a trimmed scratch-file popup, file-name-free window titles, project tabs inside the main toolbar, and an Obsidian-style live-markup mode for Markdown.

## Overview

Plugin id `com.pronskiy.agenstorm`, MIT, public on JetBrains Marketplace, target `since-build=262` (PhpStorm 2026.2). The repo holds a renamed IntelliJ Platform Plugin Template scaffold — Gradle build, `plugin.xml`, `AgenstormBundle`, one `BasePlatformTestCase` smoke test, GitHub Actions workflows — and no feature code yet.

**`SPEC.md` is the task list and source of truth.** Start at the **Current focus** pointer near the top of the spec — it names the next actionable step so you don't have to scan the whole file. Work the spec: implement that step's deliverable, update its status (🔲 → 🔄 → ✅) in the phase tracker, and advance the Current focus pointer. Don't skip ahead past a phase's exit guardrails — when you reach a phase boundary, verify the guardrail criteria, fill in the **Actual outcome** column, and only then move on.

Platform facts quoted in the spec were verified against IntelliJ Platform build 262 (2026.2). Paths like `platform/lang-impl/src/…` or `plugins/markdown/…` are relative to an `intellij-community` checkout. If the environment variable `INTELLIJ_COMMUNITY_SRC` points at such a checkout, grep there (`rg`) before guessing an API; otherwise use the platform sources the Gradle plugin attaches, or the decompiled classes in the sandbox IDE. Never invent an extension point or method name — if you cannot find it, mark the step ⏸️ and add an Open Question.

## Build

```bash
./gradlew build              # assemble + check
./gradlew check              # compile + test + Kover coverage — run before every commit
./gradlew test               # unit tests only
./gradlew runIde             # PhpStorm 2026.2 sandbox with the plugin loaded
./gradlew buildPlugin        # distributable ZIP in build/distributions/
./gradlew verifyPlugin       # IntelliJ Plugin Verifier against the recommended IDEs (downloads them; not part of check)
./gradlew publishPlugin      # JetBrains Marketplace (needs PUBLISH_TOKEN)
```

- **Toolchain:** Kotlin 2.3.10, JVM toolchain 21 (the Foojay resolver provisions a JDK if none matches), Gradle 9.3.1 with Kotlin DSL, IntelliJ Platform Gradle Plugin 2.x. Plugin and library versions live in `gradle/libs.versions.toml`.
- **`gradle.properties`** holds the plugin metadata (`pluginGroup`, `pluginName`, `pluginVersion`, `pluginSinceBuild`, `pluginUntilBuild`, `platformVersion`) and the platform dependencies (`platformBundledPlugins`: `com.jetbrains.php,org.intellij.plugins.markdown,Git4Idea`; `platformBundledModules`: the DVCS modules Git4Idea's classes extend, needed only to compile against `GitRepositoryManager`). `build.gradle.kts` reads everything through `providers.gradleProperty(...)` — change the properties, not the script.
- **Marketplace metadata:** the plugin description is extracted from `README.md` between the `<!-- Plugin description -->` markers (the build fails without them); change notes come from the `[Unreleased]` section of `CHANGELOG.md` (Keep a Changelog) via the Gradle Changelog Plugin.
- Kotlin stdlib is not bundled (`kotlin.stdlib.default.dependency = false`) — the platform's copy is used. Gradle configuration cache and build cache are on.
- **Pitfall:** after adding or removing a parameter of `AgenstormSettings.State` (or any data class whose default constructor tests call), run `./gradlew compileTestKotlin --rerun-tasks` once. Kotlin's incremental compiler does not recompile callers of the synthetic default constructor, the tests then fail with `NoSuchMethodError: State.<init>(...)`, and `clean` alone does not help because the stale test classes come back from the build cache.
- Sandbox IDE (`runIde`) lives in `.intellijPlatform/sandbox/agenstorm/PS-2026.2/` — logs in `log/idea.log`, persisted settings in `config/options/` (e.g. `agenstorm.xml`); tests use the sibling `*-test` dirs. Verifier reports: `build/reports/pluginVerifier/<IDE>/`.

## Code conventions

- **Directory structure (target):**
  ```
  agenstorm/
  ├── build.gradle.kts, settings.gradle.kts, gradle.properties, gradle/
  ├── src/main/kotlin/com/pronskiy/agenstorm/
  │   ├── core/        AgenstormSettings, AgenstormConfigurable, AgenstormBundle, notifications
  │   ├── links/       parser, resolver, Symbol-API + old-API references, markdown/, comments/, php/, CopyLocationLinkAction
  │   ├── scratch/     AllowlistScratchFilter
  │   ├── frame/       ProjectOnlyFrameTitleBuilder
  │   ├── commit/      GenerateCommitMessageAction, DiffCollector, PromptBuilder, MessagePostProcessor, llm/ (backends), context/
  │   ├── tabs/        ProjectTabsModel, ProjectTabsWidgetAction, NativeTabsRegistryGuard, ui/
  │   └── markdown/    MarkupRangeCollector, LiveMarkupController, LiveMarkupCustomizer, LiveMarkupAnnotator, ToggleLiveMarkupAction
  ├── src/main/resources/
  │   ├── META-INF/plugin.xml            core + always-on extensions
  │   ├── META-INF/agenstorm-markdown.xml  (optional dep: org.intellij.plugins.markdown)
  │   ├── META-INF/agenstorm-php.xml       (optional dep: com.jetbrains.php)
  │   ├── META-INF/agenstorm-git.xml       (optional dep: Git4Idea)
  │   ├── messages/AgenstormBundle.properties
  │   └── prompts/                        default system/user prompt templates
  ├── src/test/kotlin/com/pronskiy/agenstorm/…   mirrors main packages
  ├── src/test/testData/                          fixtures per feature (links/, commit/, markdown/, …)
  ├── .github/workflows/build.yml
  ├── SPEC.md, CLAUDE.md, README.md, CHANGELOG.md, LICENSE
  ```
  What exists today: `core/` (`AgenstormBundle`, `AgenstormSettings` light `@Service` → `agenstorm.xml`, `AgenstormConfigurable` Tools → Agenstorm); `links/` (`FileLocation` + `FileLocationMatch`, `FileLocationParser`, `FileLocationResolver`, Symbol API: `FileLocationSymbol` + `FileLocationNavigationTarget` + `FileLocationSymbolReference`; old API: `FileLocationPsiReference` + `FileLocationTarget`; `LocationGotoDeclarationHandler` for composite comments such as PHPDoc; `CopyLocationLinkAction`); `links/comments/CommentLocationReferenceProvider` (registered under `referenceProviderType key="commentsReferenceProvider"` in `plugin.xml`); `links/markdown/` (`MarkdownLocationReferenceProvider`, `LocationLinkInspectionSuppressor` in `agenstorm-markdown.xml`); `links/php/PhpStringLocationReferenceContributor` (in `agenstorm-php.xml`); `scratch/AllowlistScratchFilter` (in `agenstorm-scratch.xml`, `xi:include`d from `plugin.xml`, internal EP `scratchLanguageFilter`) with its allow-list editor and `parseAllowList`/`formatAllowList` in `core/`; `frame/ProjectOnlyFrameTitleBuilder` (service override in `plugin.xml`) + `frame/FrameTitleRefresher`; `commit/` (`llm/LlmBackend` + `LlmRequest` + `LlmException`, `llm/FakeBackend`, `llm/LlmBackends` registry, `DiffCollector`, `PromptBuilder` + `resources/prompts/{system,user}.txt`, `MessagePostProcessor`, `CommitGenerationService` (project-level coroutine scope), `GenerateCommitMessageAction` in `Vcs.MessageActionGroup`, notification group `Agenstorm`); `agenstorm-git.xml` is still empty. Tests mirror the packages under `src/test/kotlin/com/pronskiy/agenstorm/` with fixtures in `src/test/testData/links/{resolver,md}/`. Workflows: `.github/workflows/{build,release,run-ui-tests}.yml` (`build.yml` = buildPlugin, check, verifyPlugin, release draft; no Qodana/Codecov).
  One feature = one package = one optional `config-file` when it needs an optional plugin. Features never import each other; only `core/`.
- **Style:** Kotlin official code style (IntelliJ default). No wildcard imports. Prefer Kotlin UI DSL (`com.intellij.ui.dsl.builder`) for settings panels. Every user-visible string goes through `AgenstormBundle` (`messages/AgenstormBundle.properties`). Run `./gradlew check` before every commit.
- **Platform threading rules (non-negotiable):**
  - Actions declare `getActionUpdateThread() = ActionUpdateThread.BGT` and never touch Swing in `update()`.
  - PSI/VFS/index reads happen inside `readAction { }` / `ReadAction.nonBlocking(...)`; never on the EDT for anything heavier than a lookup.
  - Document writes go through `WriteCommandAction` / `CommandProcessor.executeCommand` on the EDT; use one command group id per user-visible operation so Undo is one step.
  - Long work runs in a coroutine scope injected into a `@Service` (`class Foo(val scope: CoroutineScope)`), with `withBackgroundProgress` for anything the user waits on. No `Thread`, no `ApplicationManager.executeOnPooledThread` in new code.
  - Fold-region mutations only inside `foldingModel.runBatchFoldingOperation { }` on the EDT.
- **Internal API:** allowed only where `SPEC.md` §2 lists it (`FrameTitleBuilder` override, `scratchLanguageFilter`, `commentsReferenceProvider`, `ProjectToolbarWidgetAction`, registry key `ide.mac.os.wintabs.version2`). Every such usage sits behind a settings toggle and must fail soft (log + no-op) if the hook is missing. Anything else internal → stop and ask.
- **Secrets:** API keys live in `PasswordSafe` only. Tests never hit the network — HTTP backends are tested against a local `com.sun.net.httpserver.HttpServer`; the CLI backend against a shell script in `testData/`.
- **Testing:** JUnit 4 + `BasePlatformTestCase` (`testFramework(TestFrameworkType.Platform)`). Every step whose deliverable is logic (parser, resolver, collector, prompt builder, post-processor, SSE reader, scratch filter) ships a test in the same commit. UI-only steps (tabs panel, title builder, live-markup feel) are verified through the guardrail checklists in the spec using `./gradlew runIde`; record what you saw in the **Actual outcome** column.
- **Commits:** Conventional Commits with the feature package as scope: `feat(links): resolve path:line:col in Markdown link destinations`, `test(commit): SSE reader edge cases`, `chore(build): …`. Reference the spec step in the body: `Implements A1.4`. One step per commit where practical; never mix spec-status edits with unrelated code.

## Workflow

- **Autonomous:** everything in Epics 0, A, B, C and D1/D2 — logic with clear deliverables and tests; settings UI wiring; docs; spec status updates; adding Open Questions.
- **Needs human input:** guardrail sign-offs that require running the IDE and looking (frame title, tabs strip, live markup feel) — prepare everything, run `./gradlew runIde`, describe exactly what to check, and wait; anything that adds a dependency or changes the license; anything that would use an internal API not listed in §2; decisions to record in §6 (propose the row, let Roman confirm); Marketplace publishing.
- **The loop:** read Current focus → pick the 🔲 step → implement the deliverable → write/run tests (`./gradlew test`, then `./gradlew check`) → update the status table → commit referencing the step → advance Current focus. At a phase boundary, stop and confirm the guardrails before continuing.
- **When blocked or ambiguous:** mark the step ⏸️ with a note, add an Open Question to `SPEC.md` §7, and surface it rather than guessing. This applies especially to platform API uncertainty — a wrong guess compiles against the sandbox and fails only at runtime.
- **Verification habits:** after `runIde`, always check `build/idea-sandbox/…/log/idea.log` for exceptions from `com.pronskiy.agenstorm` before declaring a guardrail passed.

## Goals

Near term: get Epic 0 done (buildable plugin, settings page, CI green), then Epic A through Phase A2 so `src/Foo.php:42:7` is clickable in Markdown, comments and PHP strings — that is the feature with the highest daily value and it de-risks the whole PSI/Symbol-API layer the rest builds on. Then B and C (an afternoon each), D (commit messages) for daily use, and only then the two UI-heavy epics E and F.

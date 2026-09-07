# CLAUDE.md

Project instructions for Claude Code working on **Agenstorm** — a PhpStorm plugin (Kotlin, IntelliJ Platform 2026.2) that makes the IDE friendlier for agent-driven development: clickable `path:line:col` locations everywhere, LLM commit messages without SDK bloat, a trimmed scratch-file popup, file-name-free window titles, project tabs inside the main toolbar, and an Obsidian-style live-markup mode for Markdown.

## Overview

Plugin id `com.pronskiy.agenstorm`, MIT, public on JetBrains Marketplace, target `since-build=262` (PhpStorm 2026.2). The repo started as a renamed IntelliJ Platform Plugin Template scaffold — Gradle build, `plugin.xml`, `AgenstormBundle`, GitHub Actions workflows — and now holds every feature of the 1.0 release: Epics 0–G and Epic H's Phase H1. Epic H's Phases H2 and H3 and the whole of Epic I are deferred to after 1.0 and have no code yet.

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
- **`gradle.properties`** holds the plugin metadata (`pluginGroup`, `pluginName`, `pluginVersion`, `pluginSinceBuild`, `pluginUntilBuild`, `platformVersion`) and the platform dependencies (`platformBundledPlugins`: `com.jetbrains.php,org.intellij.plugins.markdown,Git4Idea,org.jetbrains.plugins.terminal`; `platformBundledModules`: the DVCS modules Git4Idea's classes extend, needed only to compile against `GitRepositoryManager`). `build.gradle.kts` reads everything through `providers.gradleProperty(...)` — change the properties, not the script.
- **Marketplace metadata:** the plugin description is extracted from `README.md` between the `<!-- Plugin description -->` markers (the build fails without them); change notes come from the `[Unreleased]` section of `CHANGELOG.md` (Keep a Changelog) via the Gradle Changelog Plugin.
- Kotlin stdlib is not bundled (`kotlin.stdlib.default.dependency = false`) — the platform's copy is used. Gradle configuration cache and build cache are on.
- **Pitfall:** after adding or removing a parameter of `AgenstormSettings.State` (or any data class whose default constructor tests call), run `./gradlew compileTestKotlin --rerun-tasks` once. Kotlin's incremental compiler does not recompile callers of the synthetic default constructor, the tests then fail with `NoSuchMethodError: State.<init>(...)`, and `clean` alone does not help because the stale test classes come back from the build cache.
- Sandbox IDE (`runIde`) lives in `.intellijPlatform/sandbox/agenstorm/PS-2026.2/` — logs in `log/idea.log`, persisted settings in `config/options/` (e.g. `agenstorm.xml`); tests use the sibling `*-test` dirs. Verifier reports: `build/reports/pluginVerifier/<IDE>/`.

## Code conventions

- **Directory structure:**
  ```
  agenstorm/
  ├── build.gradle.kts, settings.gradle.kts, gradle.properties, gradle/
  ├── src/main/kotlin/com/pronskiy/agenstorm/
  │   ├── core/        AgenstormSettings, AgenstormConfigurable, AgenstormBundle, AgenstormAppScope, AgenstormSettingsListener, AgenstormNotifications, AllowListText
  │   ├── links/       parser, resolver, Symbol-API + old-API references, markdown/, comments/, php/, CopyLocationLinkAction
  │   ├── scratch/     AllowlistScratchFilter
  │   ├── frame/       ProjectOnlyFrameTitleBuilder
  │   ├── commit/      GenerateCommitMessageAction, DiffCollector, PromptBuilder, MessagePostProcessor, llm/ (backends), context/
  │   ├── tabs/        ProjectTabsModel, ProjectTabsWidgetAction, NativeTabsRegistryGuard, ui/
  │   ├── markdown/    MarkupRangeCollector, LiveMarkupController, LiveMarkupService (+ editor listener, startup activity), LiveMarkupAnnotator, LinkTextGotoDeclarationHandler, ToggleLiveMarkupAction, MarkdownBlockRenderer (Phase H1)
  │   └── terminal/    OpenRequestServer, OpenCommandRouter, OpenShimScriptHolder, TerminalOpenExecOptionsCustomizer, TerminalOpenNotice
  ├── src/main/resources/
  │   ├── META-INF/plugin.xml            core + always-on extensions
  │   ├── META-INF/agenstorm-markdown.xml  (optional dep: org.intellij.plugins.markdown)
  │   ├── META-INF/agenstorm-php.xml       (optional dep: com.jetbrains.php)
  │   ├── META-INF/agenstorm-git.xml       (optional dep: Git4Idea)
  │   ├── META-INF/agenstorm-terminal.xml  (optional dep: org.jetbrains.plugins.terminal)
  │   ├── META-INF/agenstorm-scratch.xml   (no optional dep; xi:included from plugin.xml)
  │   ├── messages/AgenstormBundle.properties
  │   ├── prompts/                        default system/user prompt templates
  │   └── terminal/                       open.sh shim template
  ├── src/test/kotlin/com/pronskiy/agenstorm/…   mirrors main packages
  ├── src/test/testData/                          fixtures per feature (links/, commit/, markdown/, …)
  ├── .github/workflows/            build.yml, release.yml, run-ui-tests.yml
  ├── SPEC.md, CLAUDE.md, README.md, CHANGELOG.md, LICENSE
  ```
  What exists today: `core/` (`AgenstormBundle`, `AgenstormSettings` light `@Service` → `agenstorm.xml`, `AgenstormConfigurable` Tools → Agenstorm); `links/` (`FileLocation` + `FileLocationMatch`, `FileLocationParser`, `FileLocationResolver`, Symbol API: `FileLocationSymbol` + `FileLocationNavigationTarget` + `FileLocationSymbolReference`; old API: `FileLocationPsiReference` + `FileLocationTarget`; `LocationGotoDeclarationHandler` for composite comments such as PHPDoc; `CopyLocationLinkAction`); `links/comments/CommentLocationReferenceProvider` (registered under `referenceProviderType key="commentsReferenceProvider"` in `plugin.xml`); `links/markdown/` (`MarkdownLocationReferenceProvider`, `LocationLinkInspectionSuppressor` in `agenstorm-markdown.xml`); `links/php/PhpStringLocationReferenceContributor` (in `agenstorm-php.xml`); `scratch/AllowlistScratchFilter` (in `agenstorm-scratch.xml`, `xi:include`d from `plugin.xml`, internal EP `scratchLanguageFilter`) with its allow-list editor and `parseAllowList`/`formatAllowList` in `core/`; `frame/ProjectOnlyFrameTitleBuilder` (service override in `plugin.xml`) + `frame/FrameTitleRefresher`; `commit/` (`llm/`: `LlmBackend` + `LlmRequest` + `LlmException`, `FakeBackend`, `AnthropicBackend`, `OpenAiCompatibleBackend`, `ClaudeCliBackend`, `HttpSseClient` + `SseReader`, `ApiKeyStore` (PasswordSafe), `LlmBackends` registry; `context/`: `CommitContextProvider` EP `com.pronskiy.agenstorm.commitContextProvider` + `GitCommitContextProvider` in `agenstorm-git.xml`; `DiffCollector`, `PromptBuilder` + `resources/prompts/{system,user,user-improve}.txt`, `MessagePostProcessor`, `CommitGenerationService` (project-level coroutine scope), `GenerateCommitMessageAction` in `Vcs.MessageActionGroup`, `CommitSettingsPanel` rendered by the configurable, notification group `Agenstorm`); `core/AgenstormAppScope` (app-level coroutine scope for settings actions), `core/AgenstormNotifications` (the one balloon group); `tabs/` (`NativeTabsRegistryGuard` + `TabsStartupActivity`/`TabsProjectCloseListener` (`postStartupActivity` + `applicationListeners`), `ProjectTabsModel` (`agenstorm-tabs.xml`), `ProjectTabsWidgetAction` overriding `main.toolbar.Project` (extends the internal `ProjectToolbarWidgetAction`), `ProjectTabActions` (switch/close/popups/reorder wiring), `ProjectTabNavigationActions` (three unbound actions), `ui/` (`SwitchingPanel`, `ProjectTabsPanel` with its own FULL/COMPACT/OVERFLOW layout and drag handler, `ProjectTabLabel`), `git/` (Phase E3, registered only in `agenstorm-git.xml`: `VcsToolbarGroup` overriding `MainToolbarVCSGroup`, `BranchStatusBarWidget` + factory, `BranchWidgetPlacement` + `BranchStartupActivity` putting the branch into the status bar's WEST box and hiding the bottom nav bar)); `core/AgenstormSettingsListener` (app-bus topic fired when the settings page applies tab options); `markdown/` (Epic F, registered only in `agenstorm-markdown.xml`: `MarkupRangeCollector` (PSI → `MarkupRange(kind, range, placeholder)`), `LiveMarkupController` (light fold regions per editor, debounced sync, caret policy, `FoldingListener` re-apply), `LiveMarkupService` (project service owning one controller per Markdown editor, per-editor override, `applySettings`) + `LiveMarkupEditorListener` (public `editorFactoryListener`; `TextEditorCustomizer` is internal, decision 20) + `LiveMarkupStartupActivity` (applies the settings topic), `LiveMarkupAnnotator` (bold/italic/strike/link styling only where the scheme lacks it, gated by a per-file counter), `LinkTextGotoDeclarationHandler` (Go to Declaration from inline-link text to the hidden destination, decision 22), `ToggleLiveMarkupAction` (`Agenstorm.ToggleLiveMarkup` in `Markdown.Toolbar.Right` and `Markdown.EditorContextMenuGroup`); settings `liveMarkupEnabled`, `liveMarkupCheckboxes`, `liveMarkupBullets`, `liveMarkupRevealScope` (`element` | `line`); the two markers of one element share a `FoldingGroup`, and a region on a range the platform restored from persisted folding state is replaced, decision 23), plus `MarkdownBlockRenderer` (Phase H1: the full-width card behind a fenced code block, a `LINES_IN_RANGE` range highlighter per block synced from the same collector run, setting `liveMarkupCodeBlocks`); `terminal/` (Epic G, registered only in `agenstorm-terminal.xml`: `OpenShimScriptHolder` (writes `resources/terminal/open.sh` into a per-run directory), `TerminalOpenExecOptionsCustomizer` (the `@Experimental` `shellExecOptionsCustomizer` EP putting that directory in front of a new terminal's PATH), `OpenRequestServer` (loopback `HttpServer` on a free port with a per-run token), `OpenCommandRouter` (claim-all-or-pass-through, navigation and project routing), `TerminalOpenNotice` (the one-time balloon); settings `terminalOpenEnabled`, `terminalOpenCommandNames`, `terminalOpenUnknownFileTypes`, `terminalOpenNoticeShown`). Tests mirror the packages under `src/test/kotlin/com/pronskiy/agenstorm/` with fixtures in `src/test/testData/links/{resolver,md}/` and `src/test/testData/markdown/`. Workflows: `.github/workflows/{build,release,run-ui-tests}.yml` (`build.yml` = buildPlugin, check, verifyPlugin, release draft; no Qodana/Codecov).
  One feature = one package = one optional `config-file` when it needs an optional plugin. Features never import each other; only `core/`.
- **Style:** Kotlin official code style (IntelliJ default). No wildcard imports. Prefer Kotlin UI DSL (`com.intellij.ui.dsl.builder`) for settings panels. Every user-visible string goes through `AgenstormBundle` (`messages/AgenstormBundle.properties`). Run `./gradlew check` before every commit.
- **Platform threading rules (non-negotiable):**
  - Actions declare `getActionUpdateThread() = ActionUpdateThread.BGT` and never touch Swing in `update()`.
  - PSI/VFS/index reads happen inside `readAction { }` / `ReadAction.nonBlocking(...)`; never on the EDT for anything heavier than a lookup.
  - Document writes go through `WriteCommandAction` / `CommandProcessor.executeCommand` on the EDT; use one command group id per user-visible operation so Undo is one step.
  - Long work runs in a coroutine scope injected into a `@Service` (`class Foo(val scope: CoroutineScope)`), with `withBackgroundProgress` for anything the user waits on. No `Thread`, no `ApplicationManager.executeOnPooledThread` in new code.
  - Fold-region mutations only inside `foldingModel.runBatchFoldingOperation { }` on the EDT.
- **Internal API:** allowed only where `SPEC.md` §2 lists it (`FrameTitleBuilder` override, `scratchLanguageFilter`, `commentsReferenceProvider`, `ProjectToolbarWidgetAction`, `GitBranchesTreePopupOnBackend.create`, registry key `ide.mac.os.wintabs.version2`). Every such usage sits behind a settings toggle and must fail soft (log + no-op) if the hook is missing. Anything else internal → stop and ask.
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

Near term: ship **Release 1.0** — Epics 0–G plus Epic H's Phase H1, all built. What is left is the release itself: the plugin icon, a green `verifyPlugin`, a hand-installed ZIP, the GitHub repo and the Marketplace listing, tracked as steps R1–R6 in the spec. After 1.0 come Epic H's Phases H2 and H3 (the rounded card with its language chip and copy action, then block quotes and thematic breaks) and Epic I (terminal output enhancers).

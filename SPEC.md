# Agenstorm — Technical Spec

**Author:** Roman Pronskiy · **Created:** 2026-09-05

> 📄 **This is a living document.** Status markers, decisions, and guardrail outcomes are meant to be updated as the work happens. See [How to Update This Document](#how-to-update-this-document) before editing.

### Changelog

| Date | Change | Author |
|------|--------|--------|
| 2026-09-05 | Initial spec created from the brainstorm + code walk of the IntelliJ Platform (build 262) | Roman Pronskiy |
| 2026-09-06 | Epic F: Phase F3 added (inline markup revealed per element, not per line) after the Epic F review; survey of other editors recorded there | Roman Pronskiy (decision), Claude (text) |
| 2026-09-06 | Epics G, H and I added *before* Release 1.0: `open path` interception in the terminal, Markdown block rendering (code fences, quotes, rules), and extensible terminal-output enhancers | Roman Pronskiy (decisions), Claude (text) |

### Status legend

🔲 Not started · 🔄 In progress · ✅ Done · ⏸️ Blocked · ❌ Cut

### Current focus

**Now on:** **Epic G** → step **G1.3** (`OpenShimScriptHolder`: the generated `open` script on disk).

Epics 0–F closed 2026-09-06, Phase F3 included — the MVP is complete. Release 1.0 was paused at R2: Roman added three more features to 1.0 on 2026-09-06, so the order is now **G → H → I → Release 1.0**, and R3 (hand-install tour) and R4 (Marketplace) wait until Epic I closes. Decisions 25–28 were confirmed by Roman on 2026-09-06, so G, H and I are cleared to build as written.

Carried over, all Roman's: the week-long **Daily-driver test** guardrails of Epic D (which also owes a live run of the Anthropic and OpenAI-compatible backends with real keys) and Epic E, and Marketplace publishing.

---

## 1. Executive summary

Agenstorm is an open-source (MIT) PhpStorm plugin that removes the friction an agent-heavy workflow hits in the IDE every day: it makes `path/to/file.php:42:7` locations clickable everywhere (Markdown, comments, PHP strings), generates commit messages with a modern LLM (streaming, HTTP or `claude -p`, no SDK bloat), trims the New Scratch File popup to the four languages that matter, keeps file names out of the window title and project tabs, renders project tabs inside the main toolbar so the window loses a row of chrome, and gives Markdown an Obsidian-style "live markup" mode where the syntax hides itself until the caret lands on the line. Three more features close the loop between the terminal and the editor: `open src/Foo.php:42` typed in an IDE terminal opens that file in that window instead of handing it to macOS, fenced code blocks render as cards in the Markdown editor, and noisy output like `var_dump()` collapses into a foldable summary through rules users can extend with their own files. It is built for one opinionated user first (the author) but ships on JetBrains Marketplace, so every feature is independently toggleable and degrades gracefully when a platform hook is missing.

---

## 2. Technical decisions

| Area | Decision | Rationale |
|------|----------|-----------|
| Language | Kotlin (JVM target as required by the 2026.2 platform — verify with the Gradle plugin; 21 as of 2025.x) | Platform is Kotlin-first; coroutines APIs (`readAction`, `Dispatchers.EDT`) are the modern way to do background work |
| Build | IntelliJ Platform Gradle Plugin 2.x, Gradle Kotlin DSL, `phpstorm("2026.2")` as the target | Standard toolchain; `verifyPlugin` and `runIde` come for free |
| Target IDE | PhpStorm 2026.2, `since-build="262"`, `until-build="262.*"` | Matches the platform branch the spec was researched against; every EP referenced exists there |
| Plugin id / package | `com.pronskiy.agenstorm` | Author's namespace |
| Module layout | One Gradle module, one plugin, feature packages `links`, `scratch`, `frame`, `commit`, `tabs`, `markdown`, `terminal`; optional dependencies wired through `<depends optional="true" config-file="…">` | Keeps a single artifact while letting the plugin load in IDEA/WebStorm without PHP/Markdown/Terminal |
| Dependencies | `com.intellij.modules.platform`, `com.intellij.modules.lang` (declares `referenceProviderType`), `com.intellij.modules.vcs`; optional: `com.jetbrains.php`, `org.intellij.plugins.markdown`, `Git4Idea`, `org.jetbrains.plugins.terminal` | Only what each feature needs; no third-party runtime libraries |
| HTTP / JSON | `java.net.http.HttpClient` (SSE via `BodyHandlers.ofLines()`); JSON via `kotlinx.serialization.json` bundled with the platform (`compileOnly`), Gson as fallback if the bundled artifact is unavailable | Zero extra jars; this is the whole reason not to use langchain4j |
| Secrets | `PasswordSafe` via `CredentialAttributes(generateServiceName("Agenstorm", backendId))` | Never put API keys into `PersistentStateComponent` XML |
| Settings | One app-level `AgenstormSettings : PersistentStateComponent` (`agenstorm.xml`) + one `Configurable` under Tools → Agenstorm with a group per feature and an on/off switch per feature | One place to find everything; each feature can be disabled by users who hit a conflict |
| Internal API usage | `FrameTitleBuilder` override (incl. the `IdeFrameEx.setFileTitle` refresh on toggle), `scratchLanguageFilter`, `commentsReferenceProvider`, registry write for macOS window tabs, subclassing `ProjectToolbarWidgetAction` for the `main.toolbar.Project` override, `GitBranchesTreePopupOnBackend.create` for the status-bar branch popup (Phase E3; the left-corner placement itself is plain Swing on the public status bar component) — all guarded by null checks / `Registry.is` / `LinkageError` catches and a feature toggle; `verifyPlugin` tolerates INTERNAL_API_USAGES on purpose. **Epics G, H and I add nothing to this list**: their hooks are `@ApiStatus.Experimental` (`ShellExecOptionsCustomizer`, `TerminalDataContextUtils.isReworkedTerminalEditor`) or plain public API, and experimental usage is a category the plugin already carries (28 of them at R2) | Public Marketplace plugin: a missing hook must log and no-op, never throw |
| Terminal command interception | A shell shim: a generated `open` script on a PATH entry prepended by a `ShellExecOptionsCustomizer`, talking back to a per-project loopback `com.sun.net.httpserver.HttpServer`. Not the IDE-side `TerminalShellCommandHandler` | The reworked terminal is the 2026.2 default and its only `TerminalShellCommandHandler` driver, `TerminalShellCommandHandlerHelper`, is constructed solely by the classic `ShellTerminalWidget` — the EP is dead under the default engine, and even in Classic it fires on the Run shortcut, not plain Enter. A shim works in every engine, on plain Enter, and for commands an agent runs. Decision 25 |
| Terminal output enhancement | `consoleFilterProvider` for highlighting and hyperlinks (the guaranteed floor, every engine); light fold regions on the reworked output editor for collapsing, gated by the I1.3 spike | `ConsoleFilterProvider` is stable public API and all three engines funnel through `ConsoleViewUtil.computeConsoleFilters`. The terminal itself never folds (zero `FoldingModel` references in its jars and no EP), but its output is a real editor reachable through the `@Experimental` `TerminalDataContextUtils.isReworkedTerminalEditor`, so Epic F's proven light-region technique applies with otherwise-public API. Decision 27 |
| Enhancer rule format | Declarative JSON files — regex plus a named built-in renderer. No user code is executed | A Marketplace plugin must not run arbitrary commands over terminal output by default. Rules stay hot-reloadable and safe; the external-command variant is an opt-in question in §7. Decision 28 |
| Markdown live markup mechanism | "Light" fold regions created manually via `FoldingModelEx.createFoldRegion` (not a `FoldingBuilder`) + a per-editor controller | Manual regions survive folding passes (`UpdateFoldRegionsOperation` keeps regions without `SIGNATURE`), can be 1 char long (builder regions < 2 chars are removed), and we own their lifecycle |
| Project tabs mechanism | Own toolbar widget in `MainToolbarLeft` replacing `main.toolbar.Project`; native macOS window tabs disabled via registry | Re-parenting the platform's `WindowTabsComponent` breaks `MacWinTabsHandlerV2` bookkeeping (`getTabsComponent` requires exactly one child) |
| Location link syntax | Bare `path:line[:col]` (GitHub/compiler style), resolved relative to file → project base → content roots → unique basename | It is what agents and tools already emit; no URL scheme, no Toolbox dependency |
| Distribution | JetBrains Marketplace, GitHub Actions (build, test, `verifyPlugin`), MIT | Public from day one |
| Tests | Platform test framework (`BasePlatformTestCase`, `testData/`) for references, scratch filter, settings, prompt builder, diff trimming; manual checklists for frame/tabs/live markup UI | UI-heavy epics are verified by guardrail checklists, logic by unit tests |

---

## 3. Architecture overview

```
                       ┌──────────────────────────────────────────────────────────┐
                       │                     Agenstorm plugin                     │
                       │                                                          │
   plugin.xml ───────▶ │  core/      AgenstormSettings (PersistentStateComponent) │
   (+ optional         │             AgenstormConfigurable (Tools → Agenstorm)    │
    config-files)      │             FeatureToggle, Logging                       │
                       │                                                          │
                       │  links/     FileLocationParser ──▶ FileLocationResolver  │
 IntelliJ EPs          │             ├─ Markdown: PsiSymbolReferenceProvider      │──▶ HyperlinkAnnotator
 ───────────────────▶  │             ├─ Comments: commentsReferenceProvider       │    (platform, free)
                       │             ├─ PHP strings: PsiReferenceContributor      │
                       │             └─ CopyLocationLinkAction                    │
                       │                                                          │
                       │  scratch/   AllowlistScratchFilter (scratchLanguageFilter)│
                       │                                                          │
                       │  frame/     ProjectOnlyFrameTitleBuilder (service override)│
                       │                                                          │
                       │  commit/    GenerateCommitMessageAction                  │
                       │             DiffCollector ──▶ PromptBuilder ──▶ LlmBackend│──▶ HTTP SSE / `claude -p`
                       │             backends: Anthropic | OpenAICompatible | ClaudeCli│
                       │             CommitMessageStreamer (writes editorField)   │
                       │                                                          │
                       │  tabs/      ProjectTabsModel (app service) ◀── ProjectManager│
                       │             ProjectTabsWidgetAction (MainToolbarLeft)    │
                       │             NativeTabsRegistryGuard                      │
                       │                                                          │
                       │  markdown/  LiveMarkupController (per TextEditor)        │
                       │             MarkupRangeCollector (PSI → ranges)          │
                       │             FoldingModelEx light regions + CaretListener │
                       │             MarkdownBlockRenderer (fences/quotes/rules)  │
                       │                                                          │
                       │  terminal/  TerminalOpenExecOptionsCustomizer ───────────│──▶ PATH + env of the shell
                       │             OpenShimScriptHolder (generates `open`)      │
                       │             OpenRequestServer ◀── loopback HTTP ─────────│◀── the shim (curl)
                       │             OpenCommandRouter (argv → open / fallback)   │
                       │             EnhancerRules + BlockDetector                │
                       │             TerminalEnhancerController (fold regions)    │
                       │             TerminalEnhancerFilterProvider ──────────────│──▶ consoleFilterProvider
                       └──────────────────────────────────────────────────────────┘
```

Every feature is a leaf: it registers its own extensions in its own optional `config-file`, reads `AgenstormSettings`, and never depends on another feature. `core/` is the only shared code. The links feature is pure PSI/Symbol API; commit is an action plus a streaming pipeline; tabs is Swing; markdown is editor-model manipulation; terminal is process environment on the way in and editor-model manipulation on the way out. `terminal/` is the one package holding two epics (G and I) — they share the optional dependency and the settings group but no code beyond the settings object.

---

## 4. Epics

Epics 0–F are the MVP; G, H and I were added on 2026-09-06 and ship in 1.0 as well. Recommended order: 0 → A → B → C → D → E → F → G → H → I (value per hour of work, riskiest last). G and I share the `terminal/` package and its optional dependency, so the knowledge carries over; H sits between them because it is independent of both and the cheapest of the three.

### Epic 0 — Scaffold, settings, CI  ·  MVP

**Goal:** A buildable, installable plugin with an empty settings page, feature toggles, CI, and the repo hygiene a Marketplace plugin needs.
**Success metrics:** `./gradlew buildPlugin verifyPlugin test` is green on CI; `runIde` opens PhpStorm 2026.2 with Tools → Agenstorm present.

#### Phase 01 — Project skeleton

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| 01.1 | Gradle project with IntelliJ Platform Gradle Plugin 2.x targeting PhpStorm 2026.2 | ✅ | Template scaffold renamed and building against PhpStorm 2026.2 with plugin id `com.pronskiy.agenstorm` (commits c6437b3, 8b2c590) |
| 01.2 | `plugin.xml` with id, MIT vendor block, `since/until-build`, optional depends and per-feature config files | ✅ | `since-build=262`, `until-build=262.*` patched from `gradle.properties`; PHP/Markdown/Git4Idea optional with empty `agenstorm-*.xml` config files |
| 01.3 | `AgenstormSettings` + `AgenstormConfigurable` with a toggle per feature | ✅ | Settings is a light `@Service(APP)` (no `applicationService` XML entry); six toggles default to on; `AgenstormSettingsTest` covers defaults, `@State` storage, skip-defaults XML shape and configurable apply/reset |
| 01.4 | Test framework wired: one `BasePlatformTestCase` smoke test | ✅ | `SettingsSmokeTest` checks descriptor + dependencies, service, configurable EP entry, bundle; template `MyPluginTest`/`rename/` removed; `testData/README.md` documents the per-feature layout |
| 01.5 | GitHub Actions: build, test, `verifyPlugin` on push/PR; `LICENSE` (MIT), `README.md`, `CHANGELOG.md` | ✅ | `build.yml`: buildPlugin → check + verifyPlugin → release draft (Qodana/Codecov jobs dropped, they need tokens); LICENSE already MIT © 2026; README has "Links for agents"; CHANGELOG Keep-a-Changelog with Epic 0 under Unreleased |

**Steps (detail):**

- **01.1 — Gradle skeleton.** Deliverable: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, wrapper. `./gradlew runIde` starts PhpStorm.
  ```kotlin
  // build.gradle.kts (sketch)
  plugins {
    id("org.jetbrains.kotlin.jvm") version "<latest 2.x>"
    id("org.jetbrains.intellij.platform") version "<latest 2.x>"
  }
  repositories { mavenCentral(); intellijPlatform { defaultRepositories() } }
  dependencies {
    intellijPlatform {
      phpstorm("2026.2")
      bundledPlugins("com.jetbrains.php", "org.intellij.plugins.markdown", "Git4Idea")
      testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
  }
  intellijPlatform {
    pluginConfiguration { ideaVersion { sinceBuild = "262"; untilBuild = "262.*" } }
    pluginVerification { ides { recommended() } }
  }
  ```
- **01.2 — plugin.xml.** Deliverable: `src/main/resources/META-INF/plugin.xml` plus `agenstorm-markdown.xml`, `agenstorm-php.xml`, `agenstorm-git.xml` for optional dependencies.
  ```xml
  <idea-plugin>
    <id>com.pronskiy.agenstorm</id>
    <name>Agenstorm</name>
    <vendor url="https://pronskiy.com">Roman Pronskiy</vendor>
    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.vcs</depends>
    <depends optional="true" config-file="agenstorm-markdown.xml">org.intellij.plugins.markdown</depends>
    <depends optional="true" config-file="agenstorm-php.xml">com.jetbrains.php</depends>
    <depends optional="true" config-file="agenstorm-git.xml">Git4Idea</depends>
    <resource-bundle>messages.AgenstormBundle</resource-bundle>
    <extensions defaultExtensionNs="com.intellij">
      <applicationService serviceImplementation="com.pronskiy.agenstorm.core.AgenstormSettings"/>
      <applicationConfigurable parentId="tools" instance="com.pronskiy.agenstorm.core.AgenstormConfigurable"
                               id="com.pronskiy.agenstorm" displayName="Agenstorm"/>
    </extensions>
  </idea-plugin>
  ```
- **01.3 — Settings.** Deliverable: `AgenstormSettings` (app-level, `@State(name="Agenstorm", storages=[Storage("agenstorm.xml")])`) with a `data class State` holding one `Boolean` per feature (`linksEnabled`, `scratchFilterEnabled`, `hideFileNameInTitle`, `commitEnabled`, `projectTabsEnabled`, `liveMarkupEnabled`) plus feature-specific fields added by later epics; `AgenstormConfigurable` built with Kotlin UI DSL (`panel { group("Links") { … } }`). Every later epic adds its own `group(...)`.
- **01.4 — Test wiring.** Deliverable: `src/test/kotlin/.../SettingsSmokeTest.kt` extending `BasePlatformTestCase`; `testData/` directory; `./gradlew test` green.
- **01.5 — CI + repo files.** Deliverable: `.github/workflows/build.yml` running `./gradlew build verifyPlugin`; `LICENSE` (MIT, © 2026 Roman Pronskiy); `README.md` with the feature list and a "Links for agents" section (the `path:line:col` convention, so users can paste it into their own `CLAUDE.md`); `CHANGELOG.md` in Keep-a-Changelog format.

**Exit guardrails — Phase 01 → Epic A**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Builds clean | `./gradlew buildPlugin` produces a zip; no deprecation errors from the Gradle plugin | ✅ | 2026-09-05: `./gradlew buildPlugin verifyPlugin --warning-mode all` → `build/distributions/agenstorm-0.0.1.zip`; no Gradle deprecation warnings (the only WARN lines are the verifier's "Layout component … nonexistent classPath" notes about the IDE distribution itself) |
| Verifier green | `verifyPlugin` reports no compatibility problems against PhpStorm 2026.2 | ✅ | Plugin Verifier 1.410: `com.pronskiy.agenstorm:0.0.1 against PS-262.10315.130` (PhpStorm 2026.2.2) → Compatible; "can probably be enabled or disabled without IDE restart" |
| Settings visible | `runIde` → Settings → Tools → Agenstorm shows six toggles; toggling persists across restart in `agenstorm.xml` | ✅ | `runIde` 2026-09-05: `idea.log` shows `Loaded custom plugins: Agenstorm (0.0.1)`, zero ERROR lines; Roman confirmed by screenshot that Tools → Agenstorm shows the six groups/toggles with the intro line. Persistence to `agenstorm.xml` is covered by `AgenstormSettingsTest` (skip-defaults XML round trip), not re-checked by eye |
| Loads without optional deps | Plugin loads in IntelliJ IDEA Community (no PHP plugin) without errors in `idea.log` | ✅ | IDEA Community has no 2026.x release (last: 2025.3), so the target is unified IntelliJ IDEA 2026.2.2 (`IU-262.10315.125`, no PHP plugin): `verifyPlugin` → Compatible, added permanently to `pluginVerification.ides`. Evidence is the verifier, not an IDEA sandbox `idea.log` (none launched) |

---

### Epic A — File location links (`path:line:col` everywhere)  ·  MVP

**Goal:** Any `path/to/file.ext:LINE[:COL]` token in a Markdown link destination, in a comment of any language, or in a PHP string literal is highlighted as a link, resolves to the file, and Ctrl/Cmd+click opens the file at that line and column. A "Copy Location Link" action produces such tokens.
**Success metrics:** Resolves in ≤ 1 read action without index access on the hot path (index only for the unique-basename fallback); zero false positives on `http://host:8080`, `12:30`, `Foo::bar`, `C:\path` in the test corpus; works in Markdown link destinations, comments of any language (PHP, JS, Kotlin, YAML, …) and PHP string literals. Windows drive paths are out of scope (macOS-first).

Platform facts the implementation relies on (verified against build 262):

- `HyperlinkAnnotator` (`platform/lang-impl/src/com/intellij/codeInsight/highlighting/HyperlinkAnnotator.java`) runs for all languages on `PsiExternalReferenceHost` / `ContributedReferenceHost` / `HintedReferenceHost` elements and highlights (a) any `PsiHighlightedReference` from the Symbol API and (b) any old-style `PsiReference` implementing `HighlightedReference`. No custom annotator is needed.
- `com.intellij.referenceProviderType` with `key="commentsReferenceProvider"` is a keyed collector (`ReferenceProviderType.java`): every provider registered under the key is applied to every `PsiComment` in every language (that is how issue-tracker links work, see `platform/vcs-impl/resources/META-INF/VcsExtensions.xml`). PHPDoc comments qualify: `PhpDocComment extends PsiDocCommentBase extends PsiComment`.
- `org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination` is a `PsiExternalReferenceHost`; the Markdown plugin resolves file paths and `#anchors` already (`HeaderAnchorSymbolReferenceProvider`), but `FileReferenceSet` fails on the `:42` suffix.
- PHP `StringLiteralExpression` is a `ContributedReferenceHost` (not a `PsiLiteralValue`), so it needs a PHP-specific `psi.referenceContributor`.
- `NavigatableSymbol.getNavigationTargets(project)` + `NavigationRequest.sourceNavigationRequest(project, file, offset)` (`platform/core-api/.../navigation/`) is the Symbol-API way to navigate to an offset.

#### Phase A1 — Parser, resolver, Markdown links

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| A1.1 | `FileLocation` model + `FileLocationParser` (regex, negative cases) with unit tests | ✅ | Plain JUnit `FileLocationParserTest`, 12 cases (full corpus + `https://example.com/a/b.php:42`, punctuation, 7-digit lines). Lookbehind also rejects a preceding `.` so URL paths never match; line/column 0 are dropped |
| A1.2 | `FileLocationResolver`: containing dir → project base → content roots → unique basename | ✅ | Absolute paths are tried first; directories never resolve; `toOffset` clamps. `FileLocationResolverTest` (9 cases) on the light fixture incl. basename fallback with three `Foo.php` candidates |
| A1.3 | `FileLocationSymbol` (`NavigatableSymbol`) + `FileLocationSymbolReference` (`PsiHighlightedReference`) | ✅ | Reference is built by `create(host, match)` only when the location resolves and carries the file; separate `FileLocationNavigationTarget` exposes `offset`. `navigationRequest()` must run off the EDT (platform assertion). `FileLocationSymbolReferenceTest`, 5 cases |
| A1.4 | `MarkdownLocationReferenceProvider` registered for `MarkdownLinkDestination` in `agenstorm-markdown.xml` | ✅ | Registration needs `referenceClass=FileLocationSymbolReference` or `HyperlinkAnnotator` never sees the reference; `highlightReference` must set `HIGHLIGHTED_REFERENCE` (default sets nothing). Added `LocationLinkInspectionSuppressor` (`lang.inspectionSuppressor`) because `MarkdownUnresolvedFileReference` warns on `Foo.php:3:5`. The Markdown plugin's `LineNumberPathReferenceProvider` only handles `#L10` anchors, no conflict. 6 tests incl. highlighting + suppression |
| A1.5 | Tests: resolve + navigation offset for `.md` fixtures | ✅ | `testData/links/md/` (`src/Foo.php`, `README.md`, `docs/plan.md`); `MarkdownLocationReferenceTest` (5 cases): resolution as written incl. `../`, offset vs. target document, one highlight per resolvable link, warning only on the broken link, heading anchors untouched |

**Steps (detail):**

- **A1.1 — Parser.** Deliverable: `links/FileLocationParser.kt` returning `List<FileLocationMatch(range, path, line, column?)>` for a `CharSequence`.
  ```kotlin
  data class FileLocation(val path: String, val line: Int, val column: Int?)          // 1-based, as written
  data class FileLocationMatch(val range: TextRange, val location: FileLocation)       // range covers "path:line[:col]"

  object FileLocationParser {
    // path: must contain '/' OR end with ".<ext>"; no spaces; may start with ./ ../ or /
    // rejects: "http://x:80", "12:30", "Foo::bar", "C:\\x", "::" anywhere in the token
    // A token is either "<something>.<ext>" (ext starts with a letter, ≤ 8 chars) or contains at least one '/'.
    private val PATTERN = Regex("""(?<![\w:/\\])((?:\.{1,2}/|/)?[\w.\-]+(?:/[\w.\-]+)*\.[A-Za-z][A-Za-z0-9]{0,7}|(?:\.{1,2}/|/)?[\w.\-]+(?:/[\w.\-]+)+):(\d{1,6})(?::(\d{1,5}))?(?![\w:/])""")
    fun parse(text: CharSequence): List<FileLocationMatch>
  }
  ```
  Negative test corpus (must produce zero matches): `http://localhost:8080/x`, `see 12:30`, `App\Foo::bar()`, `C:\Users\x`, `foo:bar`, `10:20:30`, `v1.2.3:4`, `Foo.php::42`. Positive: `src/Foo.php:42`, `src/Foo.php:42:7`, `./README.md:3`, `/abs/path/x.kt:1:1`, `tests/Unit/FooTest.php:12` inside backticks or parentheses, `Makefile:3` is *not* matched (no extension, no slash) — accepted limitation, documented.
- **A1.2 — Resolver.** Deliverable: `links/FileLocationResolver.kt`.
  ```kotlin
  class FileLocationResolver(private val project: Project) {
    /** Returns null if nothing matched. Never throws. Requires read access. */
    fun resolve(location: FileLocation, context: PsiFile?): VirtualFile? {
      val candidates = sequence {
        context?.virtualFile?.parent?.let { yield(it.findFileByRelativePath(location.path)) }
        project.guessProjectDir()?.let { yield(it.findFileByRelativePath(location.path)) }
        ProjectRootManager.getInstance(project).contentRoots.forEach { yield(it.findFileByRelativePath(location.path)) }
        if (location.path.startsWith("/")) yield(LocalFileSystem.getInstance().findFileByPath(location.path))
      }
      candidates.filterNotNull().firstOrNull()?.let { return it }
      // fallback: unique basename via FilenameIndex, only when not dumb
      if (DumbService.isDumb(project)) return null
      val byName = FilenameIndex.getVirtualFilesByName(location.path.substringAfterLast('/'), GlobalSearchScope.projectScope(project))
      return byName.singleOrNull { it.path.endsWith("/" + location.path.trimStart('.', '/')) } ?: byName.singleOrNull()
    }
    fun toOffset(file: VirtualFile, location: FileLocation): Int  // clamp line/col into the document; returns line start if col absent
  }
  ```
- **A1.3 — Symbol + reference.** Deliverable: `links/FileLocationSymbol.kt`, `links/FileLocationSymbolReference.kt`.
  ```kotlin
  class FileLocationSymbol(val file: VirtualFile, val location: FileLocation) : NavigatableSymbol, Pointer<FileLocationSymbol> {
    override fun createPointer() = this
    override fun getNavigationTargets(project: Project): Collection<NavigationTarget> = listOf(object : NavigationTarget {
      override fun createPointer() = Pointer.hardPointer(this)
      override fun computePresentation() = TargetPresentation.builder("${file.name}:${location.line}").icon(file.fileType.icon).presentation()
      override fun navigationRequest() = NavigationRequest.sourceNavigationRequest(project, file, FileLocationResolver(project).toOffset(file, location))
    })
  }
  class FileLocationSymbolReference(private val host: PsiElement, private val match: FileLocationMatch)
    : PsiSymbolReference, PsiHighlightedReference {
    override fun getElement() = host
    override fun getRangeInElement() = match.range
    override fun resolveReference(): Collection<Symbol> =
      FileLocationResolver(host.project).resolve(match.location, host.containingFile)?.let { listOf(FileLocationSymbol(it, match.location)) } ?: emptyList()
    override fun highlightReference(b: AnnotationBuilder) = b.textAttributes(DefaultLanguageHighlighterColors.HIGHLIGHTED_REFERENCE)
  }
  ```
  Unresolved matches return no reference (no red squiggles — agents write paths that do not exist yet; that is not an error).
- **A1.4 — Markdown provider.** Deliverable: `links/markdown/MarkdownLocationReferenceProvider.kt` implementing `PsiSymbolReferenceProvider` and returning `FileLocationSymbolReference`s for the destination text; registration in `agenstorm-markdown.xml`:
  ```xml
  <extensions defaultExtensionNs="com.intellij">
    <psi.symbolReferenceProvider hostLanguage="Markdown"
        hostElementClass="org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination"
        implementationClass="com.pronskiy.agenstorm.links.markdown.MarkdownLocationReferenceProvider"
        targetClass="com.pronskiy.agenstorm.links.FileLocationSymbol"/>
  </extensions>
  ```
  Only fire when the destination contains `:<digits>`; leave plain paths and `#anchors` to the Markdown plugin (they already work).
- **A1.5 — Tests.** Deliverable: `MarkdownLocationReferenceTest : BasePlatformTestCase` with `testData/links/md/`: fixture project containing `src/Foo.php`, a `notes.md` with `[x](src/Foo.php:3:5)`, `[y](../notes.md:1)`, `[z](missing.php:1)`; assert reference count, resolved file, and `toOffset` == document offset of line 3 col 5; assert `missing.php` yields no reference.

**Exit guardrails — Phase A1 → A2**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Parser corpus | 100% of positive/negative corpus cases pass in `FileLocationParserTest` | ✅ | 12/12 on 2026-09-05 (`./gradlew check`), corpus extended with a URL path and 7-digit line number |
| Markdown navigation | In `runIde`, Cmd+click on `[x](src/Foo.php:3:5)` opens `Foo.php` with caret at line 3 col 5; hover shows underline | ✅ | Offset and highlight covered by `MarkdownLocationReferenceTest`; Roman confirmed Cmd+click and hover underline in the sandbox on the demo project (2026-09-05) |
| No regressions | `[y](other.md#heading)` still resolves via the Markdown plugin; no duplicate highlight | ✅ | Automated: anchor reference still resolves and each destination gets exactly one `HIGHLIGHTED_REFERENCE`; the unresolved-file warning is suppressed only where a location resolves. Roman confirmed visually (2026-09-05) |

#### Phase A2 — Comments, PHP strings, Copy Location Link

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| A2.1 | `CommentLocationReferenceProvider` (`PsiReferenceProvider`) under `referenceProviderType key="commentsReferenceProvider"` | ✅ | Parse cached per element, bombed char sequence, 20 KB cap; references for every token, highlighted only when resolvable (A2.2). The EP lives in the lang module → `plugin.xml` also depends on `com.intellij.modules.lang`. **PHPDoc caveat:** `PhpDocCommentImpl.getReferences()` ignores the registry, so highlighting worked but Cmd+click did not; added `LocationGotoDeclarationHandler` (`gotoDeclarationHandler` EP) serving our references via `PsiReferenceService` only for hosts that do not expose them. `@see path:line` also carries PHP's own file reference (file top). `CommentLocationReferenceTest` (11 cases) incl. the real GotoDeclaration action |
| A2.2 | `FileLocationPsiReference` (old API): `PsiReferenceBase` + `HighlightedReference`, resolves to a `Navigatable` fake element | ✅ | Done before A2.1 (the provider needs the type). `isHighlightedWhenSoft() = resolve() != null`, so unresolved tokens are neither errors nor links, and no resolution result is cached. Target navigates via `OpenFileDescriptor(project, file, toOffset(...))`. `FileLocationPsiReferenceTest`, 4 cases incl. caret position after `navigate()` |
| A2.3 | `PhpStringLocationReferenceContributor` for `StringLiteralExpression` in `agenstorm-php.xml` | ✅ | Reuses `CommentLocationReferenceProvider` at `LOWER_PRIORITY`; `PhpStringLocationReferenceTest` (7 cases: quotes, heredoc, size cap, negatives, highlight + GotoDeclaration, toggle) |
| A2.4 | `CopyLocationLinkAction` (editor popup + gutter popup): copies `relpath:line[:col]`; with selection copies `relpath:line:col` of selection start | ✅ | Column 1 is omitted (`path:line`); gutter click uses `EditorGutterComponentEx.LOGICAL_LINE_AT_CURSOR` (not marked internal). Markdown flavor `text/markdown;class=java.lang.String`. `CopyLocationLinkActionTest` (5 cases) drives the registered action through the fixture |
| A2.5 | Tests for comments (PHP `//`, `/* */`, PHPDoc; Kotlin/JS comment in a plain-text-like fixture) and PHP strings | ✅ | Real JS (`//` + JSDoc) and YAML comments instead of a plain-text stand-in (both plugins load in the test IDE); dumb-mode resolver test; 5,000-line/200-comment timing smoke test. `CommentLocationReferenceTest` 14, `PhpStringLocationReferenceTest` 7, `FileLocationResolverTest` 10 |

**Steps (detail):**

- **A2.1 — Comment provider.** Deliverable: `links/comments/CommentLocationReferenceProvider.kt : PsiReferenceProvider`, `getReferencesByElement` runs the parser over `element.text` and returns `FileLocationPsiReference`s; registered in `plugin.xml`:
  ```xml
  <referenceProviderType key="commentsReferenceProvider"
                         implementationClass="com.pronskiy.agenstorm.links.comments.CommentLocationReferenceProvider"/>
  ```
  Guard: skip comments longer than 20 KB (`StringUtil.newBombedCharSequence` pattern from `UrlReferenceProvider`), and cache with `CachedValuesManager` keyed on the element.
- **A2.2 — Old-API reference.** Deliverable: `links/FileLocationPsiReference.kt`.
  ```kotlin
  class FileLocationPsiReference(host: PsiElement, private val match: FileLocationMatch)
    : PsiReferenceBase<PsiElement>(host, match.range, /* soft = */ true), HighlightedReference {
    override fun resolve(): PsiElement? {
      val file = FileLocationResolver(element.project).resolve(match.location, element.containingFile) ?: return null
      return FileLocationTarget(element.manager, file, match.location)   // FakePsiElement + Navigatable
    }
    override fun isHighlightedWhenSoft() = true
    override fun getVariants() = emptyArray<Any>()
  }
  class FileLocationTarget(manager: PsiManager, val file: VirtualFile, val location: FileLocation) : FakePsiElement(), Navigatable {
    override fun getParent() = manager.findFile(file)
    override fun navigate(requestFocus: Boolean) = OpenFileDescriptor(project, file, location.line - 1, (location.column ?: 1) - 1).navigate(requestFocus)
    override fun canNavigate() = true
    override fun getName() = "${file.name}:${location.line}"
  }
  ```
- **A2.3 — PHP strings.** Deliverable: `links/php/PhpStringLocationReferenceContributor.kt : PsiReferenceContributor` registering `CommentLocationReferenceProvider` (same provider, different host) on `psiElement(StringLiteralExpression::class.java)` with `PsiReferenceRegistrar.LOWER_PRIORITY`; registration `<psi.referenceContributor language="PHP" implementation="…"/>` in `agenstorm-php.xml`. Skip heredoc/nowdoc bodies > 20 KB.
- **A2.4 — Copy Location Link.** Deliverable: `links/CopyLocationLinkAction.kt` (`DumbAwareAction`, `ActionUpdateThread.BGT`), added to `EditorPopupMenu` and `EditorGutterPopupMenu`; text `Copy Location Link`; computes path relative to the content root (fallback: project base dir, fallback: absolute), line/col from caret (or selection start); also puts a Markdown flavour `[Foo.php:42](src/Foo.php:42:7)` on the clipboard as a second `DataFlavor` (plain text is the bare token). No default shortcut (Marketplace etiquette) — the README recommends one.
- **A2.5 — Tests.** Deliverable: `CommentLocationReferenceTest`, `PhpStringLocationReferenceTest` (`BasePlatformTestCase`, PHP plugin available through `bundledPlugins`): fixtures with `// see src/Foo.php:3`, `/** @see src/Foo.php:3:5 */`, `'src/Foo.php:3'`; assert `getReferences()` contains a `FileLocationPsiReference` resolving to `FileLocationTarget` with the right file/line; assert `http://x:80` in a comment yields none.

**Exit guardrails — Phase A2 → Epic B**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| All hosts | In `runIde`: link highlighted + navigable in a PHP `//` comment, a PHPDoc block, a PHP string, a JS comment, and a Markdown link | ✅ | All five hosts covered by tests (highlight + real GotoDeclaration for PHP `//`, PHPDoc, PHP string; references for JS/YAML; Markdown end-to-end); Roman confirmed by eye on the demo project (2026-09-05) |
| Performance | Opening a 5,000-line PHP file with 200 comments shows no `HyperlinkAnnotator` slow-annotator warning in `idea.log`; no freeze | ✅ | Automated: `testLargePhpFileWithManyCommentsHighlightsQuickly` highlights all 200 links (~12 s in the light test, dominated by PHP's own passes). Roman checked `big.php` in the sandbox: no slow-annotator warning, no freeze (2026-09-05) |
| Dumb mode | With indexing in progress, links resolve via the relative/base-dir paths; the basename fallback is skipped without exceptions | ✅ | `FileLocationResolverTest.testDumbModeKeepsDirectLookupsAndSkipsTheBasenameFallback` via `DumbModeTestUtils.runInDumbModeSynchronously` (2026-09-05) |
| Copy Location Link | Action visible in editor popup; clipboard content is `src/Foo.php:42:7` for caret at line 42 col 7 | ✅ | Clipboard content verified by `CopyLocationLinkActionTest` (caret, selection start, column 1 omitted, Markdown flavor); Roman confirmed the action in the editor and gutter popups (2026-09-05) |

---

### Epic B — New Scratch File allowlist  ·  MVP

**Goal:** The New Scratch File popup shows only Plain text, Markdown, PHP and JavaScript (configurable).
**Success metrics:** Popup shows exactly the allowlisted types; feature off → platform default list.

Platform fact: `com.intellij.scratchLanguageFilter` EP (`platform/lang-impl/src/com/intellij/ide/scratch/ScratchFileTypeFilter.kt`, added 2026-03 — `@ApiStatus.Internal`) is consulted in `ScratchImplUtil.buildLanguagesPopup` via `ScratchFileTypeFilter.isEnabled(fileType)`.

#### Phase B1 — Filter

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| B1.1 | `AllowlistScratchFilter : ScratchFileTypeFilter` reading `settings.scratchAllowedFileTypes` | ✅ | EP `com.intellij.scratchLanguageFilter` (interface `ScratchFileTypeFilter`, `@ApiStatus.Internal`, dynamic) confirmed in build 262; registered in `agenstorm-scratch.xml`, `xi:include`d from `plugin.xml`. Internal names `PLAIN_TEXT`, `Markdown`, `PHP`, `JavaScript` confirmed at runtime by the test |
| B1.2 | Settings UI: multi-line list of `FileType.name`s with an "Add current file's type" helper; defaults `PLAIN_TEXT, Markdown, PHP, JavaScript` | ✅ | Text area (one name per line) + "Add Current File's Type" + "Add File Type…" chooser over `FileTypeManager.registeredFileTypes`; parse drops blanks/duplicates and normalizes on apply. `AgenstormSettingsTest` covers XML list shape and edit/apply/reset |
| B1.3 | Test: `ScratchFileTypeFilter.isEnabled(PhpFileType)` true, `isEnabled(JsonFileType)` false, feature off → all true | ✅ | Shipped with B1.1: `AllowlistScratchFilterTest` (4 cases) goes through the platform's static `isEnabled`, so it also proves the registration |

**Steps (detail):**

- **B1.1 — Filter.** Deliverable: `scratch/AllowlistScratchFilter.kt`.
  ```kotlin
  class AllowlistScratchFilter : ScratchFileTypeFilter {
    override fun isProhibited(type: FileType): Boolean {
      val s = AgenstormSettings.getInstance().state
      if (!s.scratchFilterEnabled) return false
      return type.name !in s.scratchAllowedFileTypes
    }
  }
  ```
  `<scratchLanguageFilter implementation="com.pronskiy.agenstorm.scratch.AllowlistScratchFilter"/>` in `plugin.xml`. Because the EP is internal, wrap the registration in a dedicated `agenstorm-scratch.xml` that is included unconditionally but whose class references only that interface — if a future build drops the EP, the plugin verifier flags exactly this file.
- **B1.2 — Settings.** Deliverable: "Scratch files" group in `AgenstormConfigurable`; the list is stored as `List<String>` of `FileType.name` (internal names: `PLAIN_TEXT`, `Markdown`, `PHP`, `JavaScript` — confirm at runtime via `FileTypeManager.getInstance().registeredFileTypes`).
- **B1.3 — Test.** Deliverable: `AllowlistScratchFilterTest`.

**Exit guardrails — Epic B → Epic C**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Popup content | `runIde` → File → New → Scratch File shows exactly Text, Markdown, PHP, JavaScript (plus the "from selection" extra when applicable) | ✅ | Roman's check (2026-09-05): Text, Markdown, PHP, JavaScript **plus ActionScript and ECMAScript 6**. Those are JavaScript dialects whose `associatedFileType` is the JavaScript file type; the file-type-level EP cannot separate them. Accepted as a documented limitation (decision 14, README) |
| Toggle | Disabling the feature restores the full list without restart | ✅ | The filter reads the settings on every call (`testFeatureOffProhibitsNothing`), and the EP is dynamic; no restart involved (2026-09-05) |

---

### Epic C — Window title without file names  ·  MVP

**Goal:** The frame title (and therefore native macOS project tabs, Mission Control, Dock) shows only the project title, never the current file.
**Success metrics:** Title equals `FrameTitleBuilder.getProjectTitle(project)` output for any open editor; disabling the toggle restores the default on the next title update.

Platform facts: `FrameTitleBuilder` is an application service registered `open="true"` (`platform/platform-resources/src/META-INF/PlatformExtensions.xml`); `ProjectFrameHelper.updateTitle` concatenates project title, file title (from `EditorsSplitters.updateFrameTitle` → `getFileTitleAsync`) and `TitleInfoProvider`s, skipping blank parts (`appendTitlePart`). `FilenameToolbarWidgetAction` (header file-name widget) only shows when editor tabs are hidden, so returning `""` does not break it in the default layout.

#### Phase C1 — Service override

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| C1.1 | `ProjectOnlyFrameTitleBuilder : PlatformFrameTitleBuilder` returning `""` for file titles when enabled | ✅ | Both `getFileTitle` and the suspend `getFileTitleAsync`; `getProjectTitle` untouched |
| C1.2 | Register with `overrides="true"`; settings toggle "Hide file name in window title" (default on) | ✅ | `applicationService overrides="true"` in `plugin.xml`; the toggle's `onApply` calls `FrameTitleRefresher.refreshOpenFrames()` → `IdeFrameEx.setFileTitle(null, null)` (not `@Internal`, wrapped in a fail-soft catch) |
| C1.3 | Manual checklist + a unit test that `getFileTitle` returns `""`/default depending on the toggle | ✅ | `ProjectOnlyFrameTitleBuilderTest` (4 cases): service instance is ours, empty title on, platform title off (sync == async), project title unchanged. Checklist = the Epic C guardrails below |

**Steps (detail):**

- **C1.1 / C1.2 — Override.** Deliverable: `frame/ProjectOnlyFrameTitleBuilder.kt` and the registration:
  ```kotlin
  class ProjectOnlyFrameTitleBuilder : PlatformFrameTitleBuilder() {
    private val enabled get() = AgenstormSettings.getInstance().state.hideFileNameInTitle
    override fun getFileTitle(project: Project, file: VirtualFile) = if (enabled) "" else super.getFileTitle(project, file)
    override suspend fun getFileTitleAsync(project: Project, file: VirtualFile) = if (enabled) "" else super.getFileTitleAsync(project, file)
  }
  ```
  ```xml
  <applicationService serviceInterface="com.intellij.openapi.wm.impl.FrameTitleBuilder"
                      serviceImplementation="com.pronskiy.agenstorm.frame.ProjectOnlyFrameTitleBuilder"
                      overrides="true"/>
  ```
  On toggle change, force a refresh by calling `(WindowManager.getInstance().getFrame(project) as? IdeFrameEx)?.setFileTitle(null, null)` for every open project (the next editor switch restores the correct value) — good enough; do not chase perfection here.
- **C1.3 — Verification.** Deliverable: `ProjectOnlyFrameTitleBuilderTest` (light test: service instance is ours; toggle behaviour) + the guardrail checklist.

**Exit guardrails — Epic C → Epic D**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Title | With `Foo.php` open, the window title is the project name only; Mission Control shows the same | ✅ | Roman confirmed in the sandbox on the demo project (2026-09-05) |
| Native tabs | With native macOS project tabs still on (Epic E not yet applied), tabs show project names only | ✅ | Roman confirmed (2026-09-05) |
| No side effects | File-type icon in the title bar (`ide.show.fileType.icon.in.titleBar`) still works; "Show full paths in window header" setting still affects the project part | ✅ | Roman confirmed (2026-09-05); `getProjectTitle` is untouched by design |

---

### Epic D — AI commit messages  ·  MVP

**Goal:** One button (and one action id for a shortcut) in the commit toolbar that streams a subject + body commit message for the included changes into the message field, using the user's choice of Anthropic API, any OpenAI-compatible endpoint, or the local `claude` CLI — with no runtime dependencies beyond the JDK and the platform.
**Success metrics:** First streamed token < 3 s on an HTTP backend for a 5-file diff; total plugin jar growth for this epic < 300 KB; message respects subject ≤ 72 chars, blank line, wrapped body; the user's typed hint is honoured; Undo reverts the whole generation in one step.

Platform facts (verified against build 262; the same recipe the bundled AI Assistant uses):

- Add the action to `Vcs.MessageActionGroup` (`platform/vcs-impl/resources/META-INF/VcsActions.xml`) — that is the toolbar above the message field in both the commit tool window and the commit dialog.
- `e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)` → `CommitWorkflowUi` with `getIncludedChanges(): List<Change>` and `getIncludedUnversionedFiles(): List<FilePath>` (wrap the latter as `Change(null, CurrentContentRevision(filePath))`).
- `e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) as? CommitMessage` → `getText()`, `setText()`, `getEditorField(): EditorTextField`.
- Diff: `IdeaTextPatchBuilder.buildPatch(project, changes, basePath, /*reverse*/ false, /*honorExcludedFromCommit*/ true): List<FilePatch>` then `UnifiedDiffWriter.write(project, patches, writer, "\n", null)`; `IdeaTextPatchBuilder.isBinaryRevision(rev)` and `VirtualFile.isTooLarge()` to detect what to describe as a one-liner instead.
- Message history: `VcsConfiguration.getInstance(project).saveCommitMessage(text)` before overwriting, so the previous text is one click away in "Commit Message History".

#### Phase D1 — Pipeline core (action, diff, prompt, streaming into the field)

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| D1.1 | `LlmBackend` interface + `LlmRequest`/`LlmChunk` model; `FakeBackend` for tests | ✅ | Chunks are plain `String` deltas (no `LlmChunk` type needed). `FakeBackend` lives in main (id `fake`) so the D1 guardrail can select it; configurable chunks, delay and failure. `FakeBackendTest` (plain JUnit, 4 cases) |
| D1.2 | `DiffCollector`: changes → ranked, budgeted unified diff + stat | ✅ | Category by path/extension heuristics (not `FileType`); `VirtualFile.isTooLarge` does not exist in 262 → `FileSizeLimit.isTooLargeForContentLoading`. Per-file patch via `buildPatch(project, [change], basePath, false, true)` + `UnifiedDiffWriter.write`. `DiffCollectorTest` (7 cases incl. the 100 KB generated file budget case) |
| D1.3 | `PromptBuilder` with templates and `{diff} {stat} {branch} {hint} {language}` variables | ✅ | Templates in `resources/prompts/{system,user}.txt`; `{conventional}` too; empty hint/branch → `(none)`/`(unknown)`; unknown placeholders kept. `PromptBuilderTest` (6 cases) |
| D1.4 | `GenerateCommitMessageAction` in `Vcs.MessageActionGroup`: run/stop toggle, streaming into `CommitMessage`, single undo group | ✅ | Included changes are read in `actionPerformed` (the commit tree is EDT-only), so `update()` checks UI presence + backend id only. Chunks go through `Document.insertString` under one `CommandProcessor` group id. **Platform gotcha:** `UnifiedDiffWriter.write` with default PatchEPs runs `CharsetEP`, which refreshes the VFS synchronously and is forbidden under a read lock → use the overload with an empty `PatchEP` list. Settings fields added: `commitBackendId` (default `anthropic`), `commitModel`, `commitMaxDiffChars`, `commitConventionalCommits`, `commitBodyEnabled`, `commitLanguage`, `commitSystemPrompt`, `commitUserPrompt`. `CommitGenerationServiceTest` (4 cases: stream+post-process, one undo step, failure restores hint, cancel keeps partial text) |
| D1.5 | `MessagePostProcessor`: strip fences/prefixes, enforce subject length, wrap body at 72 | ✅ | Done before D1.2–D1.4 (the action depends on it). Subject length is *not* enforced (left to the platform inspection, as specified); also unquotes a single quoted line. **Body wrapping removed on 2026-09-05 (decision 15)**: body lines stay as the model wrote them, and the default system prompt asks for paragraphs without manual line breaks. `MessagePostProcessorTest` (9 cases) |
| D1.6 | Tests for D1.2, D1.3, D1.5 with `FakeBackend` | ✅ | `DiffCollectorTest` 7, `PromptBuilderTest` 6, `MessagePostProcessorTest` 9, `CommitGenerationServiceTest` 4 (incl. the single undo step) and `GenerateCommitMessageActionTest` 5 (proxied `CommitWorkflowUi`, real `CommitMessage`) |

**Steps (detail):**

- **D1.1 — Backend contract.** Deliverable: `commit/llm/LlmBackend.kt`.
  ```kotlin
  data class LlmRequest(val system: String, val user: String, val model: String?, val maxTokens: Int = 1024)
  interface LlmBackend {
    val id: String                       // "anthropic" | "openai" | "claude-cli"
    /** Emits text deltas in order; completes normally on end-of-stream; throws LlmException with a user-readable message. */
    fun stream(request: LlmRequest): Flow<String>
    suspend fun validate(): String?      // null = OK, otherwise a problem description for the settings page "Test" button
  }
  class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
  ```
- **D1.2 — DiffCollector.** Deliverable: `commit/DiffCollector.kt` producing `CollectedDiff(stat: String, diff: String, omittedFiles: List<String>)`.
  Ranking (highest first): project source files (by `FileType`/`Language` ≠ config/markup), tests (path contains `/tests?/` or name ends with `Test`), docs/config (`.md`, `.json`, `.yaml`, `.xml`, `.env*`), lock/generated always stat-only (`composer.lock`, `package-lock.json`, `yarn.lock`, `pnpm-lock.yaml`, `*.min.*`, `*.map`, `*.snap`, paths under `vendor/`, `node_modules/`, `dist/`, `build/`). Budget: `maxDiffChars` (default 60 000 ≈ 15k tokens); per-file cap 8 000 chars (truncated files end with `... [truncated N lines]`); every file always appears in `stat` (`M src/Foo.php (+12 -3)`). Binary/too-large → stat only. Run inside `readAction` / `runBlockingCancellable` on a background thread; never on EDT.
- **D1.3 — PromptBuilder.** Deliverable: `commit/PromptBuilder.kt` + default templates in `resources/prompts/`. Default system prompt (editable in settings):
  > You write git commit messages. Output only the message: a subject line of at most 72 characters in imperative mood, then a blank line, then a body explaining what changed and why, wrapped at 72 columns. No markdown, no code fences, no preamble. {conventional} {language}
  Where `{conventional}` expands to "Use Conventional Commits (`type(scope): subject`)." when enabled and `{language}` to "Write in {lang}." The user message: `Branch: {branch}\n\nHint from the author (follow it if present): {hint}\n\nFiles:\n{stat}\n\nDiff:\n{diff}`. Templates are plain strings with `{var}` substitution — no template engine.
- **D1.4 — Action.** Deliverable: `commit/GenerateCommitMessageAction.kt` (`DumbAwareAction`, `ActionUpdateThread.BGT`), icon `AllIcons.Actions.Lightning` (swap for `AllIcons.Run.Stop` while running), registered:
  ```xml
  <action id="Agenstorm.GenerateCommitMessage" class="com.pronskiy.agenstorm.commit.GenerateCommitMessageAction"
          icon="AllIcons.Actions.Lightning">
    <add-to-group group-id="Vcs.MessageActionGroup" anchor="first"/>
  </action>
  ```
  Behaviour: enabled iff commit UI present, ≥ 1 included change, backend configured. On invoke: if a generation is running → cancel it. Else: take current field text as `hint` (if non-blank), `saveCommitMessage(hint)`, clear the field, launch on a project-level `@Service(Service.Level.PROJECT) class CommitGenerationService(val scope: CoroutineScope)` with `withBackgroundProgress(project, "Generating commit message", cancellable = true)`; collect `backend.stream(request)`, appending each chunk on `Dispatchers.EDT` inside `CommandProcessor.executeCommand(project, { commitMessage.setText(commitMessage.text + chunk) }, "Generate Commit Message", GROUP_ID)` with one `GROUP_ID` per generation so a single Undo removes everything. On completion run `MessagePostProcessor` and replace the text once more (same group). Errors → `Notifications.Bus` balloon (group `Agenstorm`) with an "Open settings" action; restore the hint text.
- **D1.5 — Post-processor.** Deliverable: `commit/MessagePostProcessor.kt`: strip leading `Commit message:`-like prefixes and ``` fences; trim; if the subject > 72 chars and a body toggle is on, do not cut silently — leave as is but show a subtle inspection-free warning via the existing `Vcs.CommitMessageInspection` infrastructure (platform already warns on long subjects when enabled); hard-wrap body lines > 72 at word boundaries; ensure exactly one blank line between subject and body; when `bodyEnabled = false`, keep only the first paragraph.
- **D1.6 — Tests.** Deliverable: `DiffCollectorTest` (ranking/budget/lock-file rules on synthetic `Change`s with `SimpleContentRevision`), `PromptBuilderTest` (variable substitution, conventional flag), `MessagePostProcessorTest` (fence stripping, wrapping, body-off), and `GenerateCommitMessageActionTest` driving the action with `FakeBackend` emitting `["feat: a", "dd thing\n", "\nBody."]` and asserting the final field text and a single undo step.

**Exit guardrails — Phase D1 → D2**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Streaming works end-to-end | With `FakeBackend` selected in `runIde`, clicking the action streams text into the field and Undo removes it in one step | ✅ | Automated in `CommitGenerationServiceTest`/`GenerateCommitMessageActionTest`; Roman confirmed streaming + one-step Undo in the sandbox with `commitBackendId=fake` (2026-09-05) |
| Never blocks EDT | No "UI freeze" report in `idea.log` during diff collection of a 50-file change set | ✅ | Diff collection runs in `readAction` on the service's coroutine scope; Roman ran it on the 51-file demo change set, no freeze; sandbox `idea.log` has no UI-freeze report (2026-09-05) |
| Budget honoured | `DiffCollectorTest` proves output ≤ `maxDiffChars` with a lock file and a 100 KB generated file present | ✅ | `testBudgetIsHonouredWithALockFileAndAHugeGeneratedFilePresent` (2026-09-05) |

#### Phase D2 — Backends

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| D2.1 | `AnthropicBackend`: Messages API, SSE, `content_block_delta` text deltas, API key from `PasswordSafe` | ✅ | Default model `claude-sonnet-5` (free-text override); no `thinking` param sent, so the model's default applies and thinking deltas are ignored. `ApiKeyStore` = `PasswordSafe` + `generateServiceName("Agenstorm", backendId)`. `AnthropicBackendTest` (6 cases) against recorded SSE in `testData/commit/` |
| D2.2 | `OpenAiCompatibleBackend`: `POST {baseUrl}/chat/completions` with `stream:true`, SSE `choices[0].delta.content`, optional key | ✅ | Model required (no default OpenAI id guessed), no token-limit parameter (servers disagree on its name). Setting `commitOpenAiBaseUrl`. `OpenAiCompatibleBackendTest` (6 cases) |
| D2.3 | `ClaudeCliBackend`: `claude -p` subprocess, prompt on stdin, streamed output; executable discovery | ✅ | Flags checked against `claude --help` 2.1.261: `-p`, `--output-format stream-json --verbose --include-partial-messages` (streaming, added 2026-09-05 at Roman's request: each `content_block_delta`/`text_delta` event is emitted as it arrives, the `result` text is the fallback for a CLI without partial messages, `is_error` results become `LlmException`; stdout is read line by line through an `OSProcessHandler` listener), `--model`, `--system-prompt`, `--tools ""` (no tools), `--no-session-persistence`; the last two live in the `commitClaudeCliExtraArgs` setting. `~/.local/bin/claude` added to the discovery list (where this machine has it). Cancellation kills the process via a watcher coroutine (`runProcess` ignores interruption). Default model `haiku` (Roman, 2026-09-05; `--model haiku` and `sonnet[1m]` verified against the CLI), so the CLI's own default is never used; the Model field overrides it with any alias or id the CLI accepts, and `validate()` runs with the same model. Speed (2026-09-05): profiling with `--output-format stream-json` showed startup at 0.7–3 s but 20–47 s of `thinking_tokens` events — Claude Code enables extended thinking by default and `--effort low` does not change that; `MAX_THINKING_TOKENS=0` in the subprocess environment brings the call to ~3 s (decision 16), and `--strict-mcp-config` in the default extra args skips the user's MCP servers (2–4 s and ~120 tool definitions of input). `--safe-mode` (decision 17) also drops CLAUDE.md, plugins, skills and hooks: prompt ~6,000 → 775 tokens, $0.012 → $0.003 per call on Haiku, OAuth login still works (unlike `--bare`). `ClaudeCliBackendTest` (15 cases) with `testData/commit/fake-claude.sh`, which replays the CLI's stream-json events |
| D2.4 | Shared `SseReader` over `HttpClient.sendAsync(..., BodyHandlers.ofLines())` with cancellation | ✅ | Split into `SseReader` (pure parser) and `HttpSseClient` (`channelFlow`, `invokeOnClose` cancels the future and closes the body stream; `error.message` extraction with bundled `kotlinx.serialization.json`). `SseReaderTest` 5, `HttpSseClientTest` 4 (incl. cancellation under 5 s against a hanging server) |
| D2.5 | Backend tests against a local `com.sun.net.httpserver.HttpServer` serving canned SSE; CLI test with a fake `claude` script | ✅ | Shipped with each step: `SseReaderTest` 5, `HttpSseClientTest` 4, `AnthropicBackendTest` 6, `OpenAiCompatibleBackendTest` 6, `ClaudeCliBackendTest` 15; fixtures in `testData/commit/` |

**Steps (detail):**

- **D2.4 (do first) — SseReader.** Deliverable: `commit/llm/SseReader.kt`: turns `Flow<String>` of raw lines into `Flow<SseEvent(event: String?, data: String)>`; handles `data:` continuation lines, `[DONE]`, blank-line delimiters; propagates HTTP ≥ 400 as `LlmException` with the response body's `error.message` when present. Cancellation of the collecting coroutine must cancel the `CompletableFuture` from `sendAsync`.
- **D2.1 — Anthropic.** Deliverable: `commit/llm/AnthropicBackend.kt`. `POST https://api.anthropic.com/v1/messages`, headers `x-api-key`, `anthropic-version: 2023-06-01`, body `{model, max_tokens, system, messages:[{role:"user", content}], stream:true}`; emit `delta.text` from `content_block_delta` events; stop on `message_stop`. Default model: the current Sonnet at implementation time (free-text setting, do not hard-code a list). JSON via `kotlinx.serialization.json` `JsonObject` building/parsing (no data-class schema needed).
- **D2.2 — OpenAI-compatible.** Deliverable: `commit/llm/OpenAiCompatibleBackend.kt`. `baseUrl` default `https://api.openai.com/v1`; works unchanged for Ollama (`http://localhost:11434/v1`), LM Studio, OpenRouter, Groq. Body `{model, stream:true, messages:[{role:"system"},{role:"user"}]}`; emit `choices[0].delta.content`; `Authorization: Bearer` only if a key is stored.
- **D2.3 — Claude CLI.** Deliverable: `commit/llm/ClaudeCliBackend.kt`. Discovery order: settings path → `PATH` (`PathEnvironmentVariableUtil.findInPath("claude")`) → `~/.claude/local/claude`, `/opt/homebrew/bin/claude`, `/usr/local/bin/claude`. Run with `GeneralCommandLine(exe, "-p", "--output-format", "text", *extraArgs)` (`--model` appended when set), working directory = project base path, prompt (system + user concatenated) written to stdin, stdout captured via `CapturingProcessHandler`/`OSProcessHandler` with a `ProcessListener`; emit stdout as one chunk on exit 0 (the CLI does not stream in text mode), non-zero exit → `LlmException(stderr)`. Timeout 120 s. Verify flags against `claude --help` on the machine during implementation and expose an "Extra CLI arguments" setting so flag drift is fixable without a release. Follow-up done 2026-09-05: `--output-format stream-json --verbose --include-partial-messages` for true streaming.
- **D2.5 — Tests.** Deliverable: `SseReaderTest`, `AnthropicBackendTest` and `OpenAiCompatibleBackendTest` using `HttpServer` on an ephemeral port serving recorded SSE fixtures from `testData/commit/`; `ClaudeCliBackendTest` pointing the executable at `testData/commit/fake-claude.sh` (echoes a fixed message).

**Exit guardrails — Phase D2 → D3**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Three backends live | Each backend produces a real commit message in `runIde` against a real endpoint/CLI (author's keys); recorded in Notes | ✅ | Roman signed off 2026-09-05 ("all good, continue with Epic E"). Claude CLI: exercised repeatedly in the sandbox that day (haiku, safe mode, streamed output). Anthropic and OpenAI-compatible: verified against recorded SSE in `AnthropicBackendTest`/`OpenAiCompatibleBackendTest` and via the Test Connection code path; no live key was entered in the sandbox, so a live run of those two is still owed by the Daily-driver test |
| Cancellation | Clicking Stop mid-stream stops appending within 200 ms and the HTTP connection/process is closed (checked via logging) | ✅ | Covered by `HttpSseClientTest` (connection closed on cancel) and `ClaudeCliBackendTest.cancellationKillsTheProcessPromptly` (process gone < 5 s, measured ~1 s); signed off with the rest of D2 on 2026-09-05 |
| No secrets on disk | `agenstorm.xml` contains no API key; key lives in `PasswordSafe` | ✅ | `CommitSettingsPanelTest.testApiKeysGoToPasswordSafeAndNeverIntoTheState`: keys round-trip through `PasswordSafe` and the serialized `State` never contains them (2026-09-05) |
| Error UX | Wrong key → balloon with the API's error message and "Open settings"; field text restored | ✅ | Balloon + "Open Settings…" + hint restore implemented in `CommitGenerationService` and covered by `CommitGenerationServiceTest`; the CLI's own error text (e.g. unknown model) surfaces through `is_error` results; signed off 2026-09-05 |

#### Phase D3 — Settings UI and polish

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| D3.1 | "Commit messages" group: backend selector, per-backend fields, "Test connection", model, prompts (system/user) with "Reset to default", max diff chars, Conventional Commits, body, language | ✅ | Taken before the D2 guardrails (key entry needed). `commit/CommitSettingsPanel` rendered inside the Commit messages group; per-backend rows via `selectedValueMatches`; keys via `ApiKeyStore`, loaded in a background coroutine after the panel is built and written off the EDT on apply (PasswordSafe asserts `SlowOperations` for both `get` and `set` on the EDT; the sandbox logged a SEVERE from `render` on 2026-09-05, fixed the same day); Test Connection validates the typed values on `AgenstormAppScope`, including the Model field for the CLI backend (fixed 2026-09-05). `CommitSettingsPanelTest` (7 cases incl. keys never in the serialized state, background key loading and a Test Connection run against the fake CLI) |
| D3.2 | Git context provider (optional `Git4Idea`): current branch name into `{branch}` via a tiny internal EP `com.pronskiy.agenstorm.commitContextProvider` | ✅ | As specified: EP declared (`dynamic`), `GitCommitContextProvider` in `agenstorm-git.xml` (`GitRepositoryManager`, repo containing the project dir preferred). Compiling against Git4Idea needs `platformBundledModules = intellij.platform.vcs.dvcs,intellij.platform.vcs.dvcs.impl` (its supertypes). `CommitContextTest` (3 cases) |
| D3.3 | Hint handling polish: keep hint on failure; when hint looks like a full message (multi-line), ask the model to improve rather than replace (prompt variant) | ✅ | Built-in `prompts/user-improve.txt` used when the hint has ≥ 2 non-blank lines (`PromptBuilder.isDraft`); not exposed in the settings UI (only system/user templates are). Hint restore on failure since D1.4. `PromptBuilderTest` (8 cases) |
| D3.4 | README section: setup per backend, prompt variables, recommended shortcut | ✅ | README "AI commit messages" section (three backends, Test Connection, prompt variables, exclusions, `Ctrl+Alt+Shift+G` suggestion); CHANGELOG now lists Epics A–D under Unreleased |

**Steps (detail):**

- **D3.1 — Settings.** Deliverable: `commit/CommitSettingsPanel.kt` (Kotlin UI DSL) plugged into `AgenstormConfigurable`; API key field bound to `PasswordSafe` (read on panel open, write on apply); "Test" button calls `backend.validate()` in a background task and shows the result inline.
- **D3.2 — Branch name.** Deliverable: `commit/context/CommitContextProvider.kt` (`interface { fun branchName(project): String? }`, EP declared in `plugin.xml`) and `commit/context/GitCommitContextProvider.kt` in `agenstorm-git.xml` using `GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.currentBranchName`. Without Git4Idea `{branch}` is empty.
- **D3.3 — Hint polish.** Deliverable: second user-prompt template `improve` used when the existing text has ≥ 2 lines; otherwise `generate` with the hint.
- **D3.4 — Docs.** Deliverable: README section + `CHANGELOG.md` entry.

**Exit guardrails — Epic D → Epic E**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Daily-driver test | Author uses the feature for 10 real commits across two projects; ≥ 8 accepted with ≤ 1 line edited | 🔲 | |
| Size | Plugin zip grew < 300 KB since Epic C | ✅ | `agenstorm-0.0.1.zip`: 75,260 bytes at the end of Epic C (commit 3a255ca, rebuilt in a worktree) → 231,078 bytes after D3 = +152 KB, no third-party jars (2026-09-05) |
| Verifier | `verifyPlugin` still clean (no new internal API usages beyond the documented ones) | ✅ | 2026-09-05: Compatible against PS-262.10315.130 and IU-262.10315.125. 4 internal usages, all documented: `ScratchFileTypeFilter`/`isProhibited` (Epic B) and `IdeFrameEx`/`setFileTitle` (Epic C refresh). 20 experimental usages (Symbol API, `PsiHighlightedReference`, UI DSL `textFieldWithBrowseButton`), tolerated. The Gradle task's default failure level failed on the internal usages, so `pluginVerification.failureLevel` is now COMPATIBILITY_PROBLEMS + INVALID_PLUGIN + MISSING_DEPENDENCIES |

---

### Epic E — Project tabs in the main toolbar (single header row)  ·  MVP

**Goal:** On macOS, open projects appear as tabsinside the main toolbar row (left slot, where the project widget is), so the window has one header row instead of two; tabs show project names only; clicking switches windows; native macOS project tabs are turned off.
**Success metrics:** Header height with 3 projects open equals header height with 1 project; switch latency < 100 ms; tabs stay consistent across all frames when projects open/close.

Platform facts (verified against build 262):

- Native tabs are `WindowTabsComponent` driven by `MacWinTabsHandlerV2` (`platform/platform-impl/src/com/intellij/ui/mac/`), living in their own 28 px row above the toolbar (`IdeRootPane.CustomHeaderRootLayout`). They are enabled by registry `ide.mac.os.wintabs.version2` (restart required) and gated by `ide.mac.bigsur.window.with.tabs.enabled`. Re-parenting them is not viable (see §2).
- The main toolbar's left group is `MainToolbarLeft` containing `main.toolbar.Project` (`ProjectToolbarWidgetAction`) — `platform/platform-resources/src/idea/PlatformActions.xml`. Actions can be replaced with `<action id="…" class="…" overrides="true"/>`.
- `ProjectUtil.focusProjectWindow(project, stealFocusIfAppInactive)`; `ProjectManagerEx.getInstanceEx().closeAndDispose(project)`; `RecentProjectsManagerBase.getInstanceEx().getProjectIcon(path, isProjectValid, unscaledIconSize)`; `RecentProjectListActionProvider.getInstance().getActions()` for the recent-projects popup; `ProjectWidget.Actions` group holds the static entries of the stock widget popup.

#### Phase E1 — Native tabs off, model, and a static widget

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| E1.1 | `NativeTabsRegistryGuard` (`ProjectActivity`): when feature on & macOS & registry key true → set `ide.mac.os.wintabs.version2=false`, notify "Restart to apply"; when feature off → restore `true` | ✅ | Key verified in `misc/registry.properties` of build 262 (`=true`, `restartRequired=true`). Deviation: feature off restores `true` only when the new `State.nativeTabsDisabledByAgenstorm` flag says Agenstorm flipped it, so a user who disabled native tabs themselves keeps that. Balloon has "Restart Now" (`Application.restart()`) when `isRestartCapable`. Wired as `postStartupActivity` + `onApply` of the tabs toggle. `core/AgenstormNotifications` holds the group id. `NativeTabsRegistryGuardTest` (6 cases: on/off, user's own choice, flag-only, non-mac, missing key). The activity also runs in the test IDE, so `SettingsSmokeTest` now resets the state first |
| E1.2 | `ProjectTabsModel` app service: ordered list of open projects (persisted order by base path), listeners; fed by `ProjectActivity` + `ProjectCloseListener` | ✅ | `tabs/ProjectTabsModel` (`agenstorm-tabs.xml`, key = base path, `MAX_REMEMBERED` 100 with closed keys forgotten first); `TabsStartupActivity` (shared with E1.1) + `TabsProjectCloseListener` under `<applicationListeners>`; listeners fire on the EDT (`invokeLater(ModalityState.any())` off it). `ProjectTabsModelTest` (6 cases: storage, order/append, remember/cap, move, listener dispose, EDT delivery) |
| E1.3 | `ProjectTabsWidgetAction` replacing `main.toolbar.Project` via `overrides="true"`, extending the stock `ProjectToolbarWidgetAction` so that feature-off = stock behaviour; `ProjectTabsPanel` rendering one `ProjectTabLabel` per project + "+" button | ✅ | Stock action verified in 262: `ProjectToolbarWidgetAction : ExpandableComboAction`, `@ApiStatus.Internal`, BGT; declared under `MainToolbarLeft` both in `PlatformActions.xml` and PhpStorm's own `PhpStormPlugin.xml`; its `updateCustomComponent` casts to `AbstractToolbarCombo`, hence the inner stock button is what `super` receives. `tabs/ui/SwitchingPanel` (CardLayout, sizes follow the visible card), `ProjectTabsPanel` (subscribes in `addNotify`/`removeNotify`, callbacks for E1.4), `ProjectTabLabel` (icon via `RecentProjectsManagerBase.getProjectIcon(path, true, 16, name)`, colours from `MainToolbar.Dropdown.*` named keys with `ActionButton` fallbacks — the spec's `JBUI.CurrentTheme.MainToolbar.Dropdown.hoverBackground()` does not exist in 262, only `hoverArc()`). `ProjectTabsWidgetActionTest` (3) + `ProjectTabsPanelTest` (3). Guardrail-run feedback 2026-09-05: the × made tabs change width on hover; it now keeps its slot (same-size `EmptyIcon`), always shown on the active tab and on hover elsewhere, like editor tabs (`ProjectTabLabelTest`, 2) |
| E1.4 | Click → switch (`focusProjectWindow`, optional bounds mirroring); middle-click / × → close; "+" → popup (recent projects + `ProjectWidget.Actions`) | ✅ | `tabs/ProjectTabActions` wired from `ProjectTabsWidgetAction.createCustomComponent`. Switch: `ProjectUtil.focusProjectWindow(target, true)` after mirroring `JFrame.bounds` unless `ProjectFrameHelper.getFrameHelper(frame).isInFullScreen` (new `State.tabsMirrorWindowBounds`, UI in E2.4). Close: `ProjectManager.closeAndDispose` via `invokeLater` (the click comes from the frame being closed), neighbour = next tab else previous. "+": `RecentProjectListActionProvider.getActions(project)` (the stock widget's list) + separator + `ProjectWidget.Actions`, `JBPopupFactory.createActionGroupPopup(...).showUnderneathOf(button)`. Right click: Close / Close Others / Copy Path. `ProjectTabActionsTest` (5) with `FakeProjectHolder` proxies for ordering |
| E1.5 | Verifier check of the override; if extending the internal `ProjectToolbarWidgetAction` is rejected, fall back to a standalone `CustomComponentAction` with a minimal own "project dropdown" for the feature-off state (record decision) | ✅ | `verifyPlugin` 2026-09-05: Compatible against PS-262.10315.130 and IU-262.10315.125; internal usages 4 → 13, all from subclassing `ProjectToolbarWidgetAction` (class reference, constructor, `update`/`createCustomComponent`/`updateCustomComponent` overrides) plus the four sanctioned earlier. `RecentProjectListActionProvider.getActions(Project)` turned out to be `@Internal` too and was replaced by the public boolean overload. No fallback needed (decision 18). Re-run after Phase E2 (2026-09-05): still Compatible on both IDEs, still 13 internal usages |

**Steps (detail):**

- **E1.1 — Registry guard.** Deliverable: `tabs/NativeTabsRegistryGuard.kt`. Uses `Registry.get("ide.mac.os.wintabs.version2")`; `RegistryValue.setValue(false)` persists across restarts. Notification group `Agenstorm` with action "Restart now" → `ApplicationManager.getApplication().restart()`. Also listen to the settings toggle to flip the key back and notify. `SystemInfo.isMac` guard; on other OSes the widget still works (there are no native tabs to disable).
- **E1.2 — Model.** Deliverable: `tabs/ProjectTabsModel.kt` — `@Service(Service.Level.APP)`, `@State(name="AgenstormProjectTabs", storages=[Storage("agenstorm-tabs.xml")])` holding `order: MutableList<String>` (project base paths). API: `fun tabs(): List<Project>` (open projects sorted by stored order, unknown ones appended), `fun moveTab(project, index)`, `fun addListener(listener, disposable)`. Feeds: `class TabsProjectActivity : ProjectActivity` (project opened) and `ProjectCloseListener.projectClosed` on the app message bus. Fires listeners on EDT.
- **E1.3 — Widget.** Deliverable: `tabs/ProjectTabsWidgetAction.kt` and `tabs/ui/ProjectTabsPanel.kt`, `tabs/ui/ProjectTabLabel.kt`.
  ```kotlin
  // Registered as <action id="main.toolbar.Project" class="…ProjectTabsWidgetAction" overrides="true"/>
  // ProjectToolbarWidgetAction is `open` but @ApiStatus.Internal — accepted (see §2, Internal API usage); E1.5 has the fallback.
  class ProjectTabsWidgetAction : ProjectToolbarWidgetAction() {
    private val enabled get() = AgenstormSettings.getInstance().state.projectTabsEnabled
    override fun update(e: AnActionEvent) {
      super.update(e)
      e.presentation.putClientProperty(PROJECT_KEY, e.project)
      e.presentation.putClientProperty(TABS_MODE_KEY, enabled)
    }
    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
      SwitchingPanel(stock = super.createCustomComponent(presentation, place), tabs = ProjectTabsPanel())   // CardLayout
    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
      val panel = component as SwitchingPanel
      panel.showTabs(presentation.getClientProperty(TABS_MODE_KEY) == true)
      panel.tabs.ownerProject = presentation.getClientProperty(PROJECT_KEY)
      if (!panel.isShowingTabs) super.updateCustomComponent(panel.stock, presentation)
    }
  }
  ```
  `ProjectTabsPanel` (JPanel, horizontal box layout, opaque = false) subscribes to `ProjectTabsModel`; `ownerProject` is the tab rendered as active (each frame's panel highlights the project of its own frame — no global "active" tracking needed). `ProjectTabLabel`: 16 px project icon (`RecentProjectsManagerBase.getInstanceEx().getProjectIcon(basePath, true, 16)`), name (`project.name`), close icon on hover (`AllIcons.Actions.Close` / `CloseHovered`), tooltip = base path, preferred height = toolbar height, min width 72 px, max 220 px with ellipsis, colors from named UI colors with fallbacks (`MainToolbar.Dropdown.hoverBackground`, `MainWindow.Tab.selectedBackground` → fallback `JBUI.CurrentTheme.MainToolbar.Dropdown.hoverBackground()`), separators between tabs. "+" button: `AllIcons.General.Add`.
- **E1.4 — Interactions.** Deliverable: handlers in `ProjectTabsPanel`.
  - Left click: `switchTo(target)`: if `mirrorWindowBounds` (default on) and neither frame is fullscreen (`ProjectFrameHelper.getFrameHelper(frame)?.isInFullScreen != true`), set `targetFrame.bounds = currentFrame.bounds`; then `ProjectUtil.focusProjectWindow(target, true)`.
  - Middle click or ×: `ProjectManagerEx.getInstanceEx().closeAndDispose(target)` on EDT; if `target` is the owner project and other tabs exist, `switchTo(neighbour)` first so focus does not fall to the desktop.
  - "+": `JBPopupFactory.getInstance().createActionGroupPopup(null, DefaultActionGroup(RecentProjectListActionProvider.getInstance().getActions(addClearListItem = false) + Separator + ActionManager.getInstance().getAction("ProjectWidget.Actions")), dataContext, …)`.
  - Right click on a tab: small popup — Close, Close Others, Copy Path.
- **E1.5 — Installation strategy check.** Deliverable: `verifyPlugin` output reviewed and a §6 decision row. Extending `ProjectToolbarWidgetAction` produces an "internal API usage" *warning*, which Marketplace tolerates; if it ever becomes an error, switch to a standalone `AnAction + CustomComponentAction` registered under the same overriding id, whose feature-off state is a minimal own dropdown (project name + the "+" popup from E1.4). Do not use `ActionConfigurationCustomizer` (internal, discouraged by its own KDoc).

**Exit guardrails — Phase E1 → E2**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Single row | Screenshot: with 3 projects open after restart, header is one row; the 28 px native tab strip is gone | ✅ | Roman, 2026-09-05 sandbox run ("all good, continue with E2"): registry key flipped on first start, native strip gone after restart, tabs in the toolbar row |
| Consistency | Opening/closing a project updates the tab strip in every open frame within one EDT cycle; no stale tabs | ✅ | Roman, 2026-09-05 |
| Switching | Click switches focus < 100 ms; bounds mirrored when both windows are normal; nothing weird in fullscreen (documented as unsupported if needed) | ✅ | Roman, 2026-09-05. Feedback fixed the same day: the × changed the tab width on hover; it now keeps its slot (always on the active tab, on hover elsewhere) |
| Recovery | Turning the feature off restores the stock project widget and (after restart) native tabs | ✅ | Roman, 2026-09-05. Note: Restart Now inside a Gradle `runIde` sandbox only exits the IDE (the restarter cannot relaunch it); a real installation restarts normally |
| Log clean | No exceptions in `idea.log` while opening 4 projects, closing 2, reopening 1 | ✅ | 2026-09-05 sandbox sessions 21:32–21:57: nothing thrown by `com.pronskiy.agenstorm`. One SEVERE blamed on the plugin only because Restart Now was the last action: `LspIntentionActionService.dispose` threw a CancellationException during the platform's own shutdown |

#### Phase E2 — Overflow, order, keyboard

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| E2.1 | Overflow: shrink to icon-only when width is insufficient; then first N + chevron popup listing the rest | ✅ | `ProjectTabsPanel` lays itself out (no BoxLayout): `plan(available)` picks FULL / COMPACT (32 px icon tabs, name in tooltip, no ×) / OVERFLOW (first N compact + `AllIcons.Actions.MoreHorizontal` chevron, owner tab swapped in when it would hide); `available` = **window** width / 2 via `availableWidthProvider` (unlimited until the window is sized). Deviation from the step text: measuring against the parent toolbar looped — the left `MainToolbar` group is sized from its children, so the cap shrank with the strip and tabs were always icon-only (found in Roman's review 2026-09-05, fixed the same day). Chevron popup = `ProjectTabActions.overflowGroup`. `ProjectTabsOverflowTest` (5) with `FakeProjectHolder` proxies |
| E2.2 | Drag-to-reorder tabs within the strip; order persisted in `ProjectTabsModel` | ✅ | `ProjectTabsPanel.dragHandler` (press/drag/release on the tab surface via `ProjectTabLabel.addDragListener`; 4 px threshold; `insertionIndex(x)` against visible tab centres; focus-coloured marker painted in `paint`); `onReorder` → `ProjectTabsModel.moveTab`. `ProjectTabsReorderTest` (4) |
| E2.3 | Actions `Agenstorm.NextProjectTab` / `Agenstorm.PrevProjectTab` (cyclic), unbound by default; `Agenstorm.CloseProjectTab` | ✅ | `tabs/ProjectTabNavigationActions.kt` (BGT `DumbAwareAction`s; next/prev enabled with 2+ tabs; close reuses `ProjectTabActions.close`); registered in `plugin.xml` without shortcuts, README suggests `Ctrl+Alt+Shift+]`/`[`. `ProjectTabNavigationActionsTest` (3) |
| E2.4 | Settings group "Project tabs": enable, mirror bounds, show icons, max tab width | ✅ | `State.tabsMirrorWindowBounds` / `tabsShowIcons` / `tabsMaxWidth` (72–600, default 220); rows in the tabs `featureGroup` named `tabs.mirrorBounds`, `tabs.showIcons`, `tabs.maxWidth`; `ProjectTabsModel.refresh()` re-renders every strip; per-cell `onApply` because the DSL runs a cell's apply callback only when that cell changed. `TabsSettingsPanelTest` (2), `ProjectTabLabelTest` +1 |

**Steps (detail):**

- **E2.1 — Overflow.** Deliverable: `ProjectTabsPanel.doLayout()` measuring available width from the parent toolbar (the toolbar gives the custom component its preferred size; cap `preferredSize.width` at `parent.width * 0.5` and switch modes when the sum of tab widths exceeds it).
- **E2.2 — Reorder.** Deliverable: mouse-drag within the panel swapping indices; `ProjectTabsModel.moveTab`. Keep it simple (no cross-window DnD).
- **E2.3 — Actions.** Deliverable: three `DumbAwareAction`s registered in `plugin.xml`; README suggests shortcuts; no defaults to avoid keymap conflicts.
- **E2.4 — Settings.** Deliverable: fields in `AgenstormSettings.State` + UI group.

#### Phase E3 — Git branch out of the toolbar, into the status bar

**Why:** with the project tabs in the toolbar's left slot, the Git widget right next to them competes for the same row and repeats what the tab already says about the project. Bottom-left, where the navigation bar (breadcrumbs) sits by default in the new UI, is where a branch belongs in an agent-driven workflow: always visible, never in the way. Requested by Roman during the Epic E review (2026-09-05).

Platform facts (verified against build 262):

- The toolbar's VCS slot is the group `MainToolbarVCSGroup`, declared by the Git plugin with `add-to-group MainToolbarLeft anchor="before" relative-to-action="MainToolbarGeneralActionsGroup"`; its children are `main.toolbar.git.Branches` (`com.intellij.vcs.git.frontend.widget.GitToolbarWidgetAction`, a frontend-module class), `main.toolbar.git.MergeRebase` and `Vcs.ToolbarWidget.CreateRepository`. Groups can be replaced with `<group id="…" class="…" overrides="true"/>`; whether children other plugins added survive the replacement is checked by a test (fallback: re-resolve the three ids).
- The stock status-bar branch widget `git4idea.ui.branch.GitBranchWidget$Factory` (id `git`) is unavailable in the new UI while the main toolbar is visible: `isAvailable` = `(!isNewUI || isEnabledByDefault) && repositories non-empty`, `isEnabledByDefault` = `!(showNewMainToolbar && ToolbarSettings visible && available)`. An own widget is therefore needed: `StatusBarWidgetFactory` EP (public) with a `StatusBarWidget.MultipleTextValuesPresentation` (`getSelectedValue`, `getIcon`, `getTooltipText`, `getPopup`/`getClickConsumer`).
- Branch data (public git4idea API): `GitRepositoryManager.getInstance(project).repositories`, `GitBranchUtil.guessWidgetRepository(project, file)`, `GitRepository.currentBranch?.name`, project-bus topic `GitRepository.GIT_REPO_CHANGE` (`GitRepositoryChangeListener.repositoryChanged`), plus `FileEditorManagerListener` for the current file.
- Popup: `GitBranchesTreePopupOnBackend.create(project, repository): JBPopup` (public static, but the class carries `@ApiStatus.Internal`) is what the `Git.Branches` action shows via `showCenteredInCurrentWindow`. Preferred: the same popup shown under the widget; fallback when the class is missing: invoke the public action `Git.Branches`.
- Bottom navigation bar: `UISettings.showNavigationBar` / `navBarLocation` (`NavBarLocation.BOTTOM`), `UISettings.fireUISettingsChanged()`. `IdeStatusBarImpl` is a `BorderLayout`: WEST = a lazily created horizontal box that holds the navigation bar (filled through the internal `addWidgetToLeft`), CENTER = the info/progress panel (`setCentralWidget` goes there — not the left corner), EAST = the ordinary widgets. The left corner is reached with plain Swing on `StatusBar.getComponent()`: add to the front of the WEST box, or create the box. Fallback when the layout differs: the label shows inside the widget's own component among the ordinary widgets.

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| E3.1 | `VcsToolbarGroup` overriding `MainToolbarVCSGroup` (in `agenstorm-git.xml`): hidden while project tabs and "branch in status bar" are on; stock children preserved (or re-resolved by id) so feature-off = stock toolbar | ✅ | `tabs/git/VcsToolbarGroup` (`DefaultActionGroup`, BGT). `VcsToolbarGroupTest` proves the override is in place and `main.toolbar.git.Branches` is still a child in the test IDE, so the platform keeps `add-to-group` children across a replacement; the by-id fallback stays as a safety net |
| E3.2 | `BranchStatusBarWidget` + `Factory` (id `agenstorm.branch`, `agenstorm-git.xml`): current branch of the repository for the focused file (else the project's first), `AllIcons.Vcs.Branch`, tooltip = repository root; updates on `GIT_REPO_CHANGE` and editor switches; click → branches popup under the widget | ✅ | `CustomStatusBarWidget` (owns its `JBLabel`, so the same component can move into the central slot); text = branch, else 8-char revision, else "no branch"; popup via `GitBranchesTreePopupOnBackend.create(...).showUnderneathOf(label)` with `Git.Branches` as `LinkageError` fallback. `BranchStatusBarWidgetTest` (4) |
| E3.3 | Placement: hide the bottom navigation bar (`State.navBarHiddenByAgenstorm` remembers it was us, restored on feature-off like the registry key) and install the widget as the status bar's central widget; fail-soft to ordinary placement | ✅ | Deviation found in Roman's review (2026-09-05): `IdeStatusBarImpl.setCentralWidget` fills the CENTER info panel, not the left corner, and the widgets manager creates widgets asynchronously, so the first attempt left the branch among the right-hand widgets. The status bar (`StatusBar.getComponent()`, public) is a `BorderLayout` — WEST = the navigation bar's horizontal box, CENTER = info/progress, EAST = ordinary widgets — so `BranchWidgetPlacement.attach` now moves the widget's label to the front of the WEST box (creating the box when the platform has none) with plain Swing, from the widget's own `install()` and after UI-settings changes; the platform holds an invisible host component instead. No internal API left in this step. `syncNavBar` + `StatusBarWidgetsManager.updateWidget` unchanged; `BranchStartupActivity` drives it (project open, `VCS_REPOSITORY_MAPPING_UPDATED`, `AgenstormSettingsListener.TOPIC`). `BranchWidgetPlacementTest` (7) |
| E3.4 | Setting "Show the Git branch in the status bar instead of the toolbar" (default on) in the Project tabs group; README + CHANGELOG | ✅ | `State.branchInStatusBar`, checkbox `tabs.branchInStatusBar`; the tabs toggle and this checkbox fire `AgenstormSettingsListener` (new `core/AgenstormSettingsListener`, app bus). `TabsSettingsPanelTest` +1 |

**Steps (detail):**

- **E3.1 — Toolbar.** Deliverable: `tabs/git/VcsToolbarGroup.kt`, a `DefaultActionGroup` whose `update` sets the presentation invisible while `projectTabsEnabled && branchInStatusBar`; `getActionUpdateThread = BGT`. Registered as `<group id="MainToolbarVCSGroup" class="…" overrides="true"/>` inside `agenstorm-git.xml` so it only exists when Git4Idea is present. Test: `ActionManager.getAction("MainToolbarVCSGroup")` is ours and still lists `main.toolbar.git.Branches`; if the platform drops the children on replacement, `getChildren` re-resolves the three known ids.
- **E3.2 — Widget.** Deliverable: `tabs/git/BranchStatusBarWidget.kt` (`StatusBarWidget` + `MultipleTextValuesPresentation`), `BranchStatusBarWidgetFactory` (`isAvailable` = feature on && Git repositories present; `isEnabledByDefault` = true). Text: `currentBranch?.name`, else the short revision or "no branch"; tooltip = repository root path. Refresh: `statusBar.updateWidget(ID)` on `GIT_REPO_CHANGE` (any repository) and on `FileEditorManagerListener.selectionChanged`. Click: `GitBranchesTreePopupOnBackend.create(project, repo).show(RelativePoint above the widget)` inside a `LinkageError` guard, else `ActionUtil.invokeAction(Git.Branches, component, place, event, null)`.
- **E3.3 — Placement.** Deliverable: `tabs/git/BranchWidgetPlacement.kt` run from `TabsStartupActivity` and the settings hook. With the feature on: if `UISettings.showNavigationBar && navBarLocation == BOTTOM`, set `showNavigationBar = false`, mark `navBarHiddenByAgenstorm`, `fireUISettingsChanged()`; then `(WindowManager.getStatusBar(project) as? IdeStatusBarImpl)?.setCentralWidget("agenstorm.branch", widget.component)`. Feature off: remove the central widget, restore `showNavigationBar` only when the flag is set. Any `LinkageError`/cast failure → log once, keep the widget where the platform put it.
- **E3.4 — Setting.** `State.branchInStatusBar = true`; checkbox in the Project tabs group named `tabs.branchInStatusBar` with an apply hook that re-runs the placement for every open project and refreshes the toolbar (`ActionToolbarImpl.updateAllToolbarsImmediately()`).

**Exit guardrails — Phase E3**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Branch bottom-left | With the feature on, the current branch shows at the left end of the status bar where the breadcrumbs were; the toolbar has no VCS widget | ✅ | Roman, 2026-09-05 sandbox run on `agenstorm-demo` ("all good, it looks great", screenshot). Follow-up the same day: icon aligned to the tool window stripe's right edge (measured at runtime from the stripe buttons, 40 px fallback) |
| Live | Checking out another branch (popup or terminal) updates the text within a second; switching editors between two repositories switches the branch shown | ✅ | Signed off with the same run (2026-09-05); wired to `GIT_REPO_CHANGE` and editor selection |
| Popup | Clicking the branch opens the branches popup anchored to the widget (or centered, if the fallback path is in use — recorded) | ✅ | Signed off with the same run (2026-09-05); `GitBranchesTreePopupOnBackend` path, shown under the widget |
| Recovery | Feature off → toolbar VCS widget and the bottom navigation bar are back without restart | ✅ | Signed off with the same run (2026-09-05) |
| Log clean + verifier | No exceptions from the plugin; `verifyPlugin` Compatible, new internal usages listed in §2 | ✅ | Verifier 2026-09-05: Compatible on PS/IU 262.10315; internal usages 13 → 15 (`GitBranchesTreePopupOnBackend` class + `create`, both guarded); 2 deprecated usages remain: `StatusBarWidget.getPresentation(PlatformType)` bridged by the Kotlin compiler for the `CustomStatusBarWidget` implementation. Log: every sandbox session that day (22:47, 23:08–23:14) clean, nothing blamed on the plugin |

**Exit guardrails — Epic E → Epic F**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Narrow window | At 1200 px window width with 6 projects, the strip degrades gracefully (icons, then chevron) and the run/VCS widgets remain visible | ✅ | Roman closed Epic E on 2026-09-05 ("ok good") after the E2/E3 review sessions; the cap is half the window width (the toolbar-based cap looped and was fixed during review), `ProjectTabsOverflowTest` covers the three modes |
| Order survives restart | Reordered tabs come back in the same order after restart | ✅ | Closed with Epic E on 2026-09-05; `agenstorm-tabs.xml` kept three projects across that day's restarts, `ProjectTabsModelTest` covers `moveKey`/`sortKeys` |
| Daily-driver test | Author uses the strip for a week without re-enabling native tabs | 🔲 | |

---

### Epic F — Markdown live markup (Obsidian-style)  ·  MVP

**Goal:** In the normal Markdown text editor, inline markup characters (`**`, `*`, `_`, `~~`, backticks, `#` prefixes, `[`…`](url)`) are hidden and the text is styled (bold/italic/strike/code/link), except on the line(s) where the caret is — there the raw Markdown shows so you can edit it. Checkboxes render as ☐/☑ and toggle on click. Toggleable per editor and by default in settings. Heading font sizes are explicitly out of scope (single line height in the IntelliJ editor).
**Success metrics:** Typing latency unchanged (no fold operations on every keystroke — debounced); a 3,000-line `.md` re-syncs in < 50 ms after edits stop; caret navigation with arrow keys never "jumps over" hidden markers on the caret line; copying always copies raw Markdown.

Platform facts (verified against build 262):

- The editor has one line height and no per-range font size in `TextAttributes` → hiding markers requires folding; styling uses the Markdown colour scheme keys (`MarkdownHighlighterColors.BOLD/ITALIC/STRIKE_THROUGH/CODE_SPAN/…`).
- Fold regions created manually with `FoldingModelEx.createFoldRegion(start, end, placeholder, group, neverExpands)` inside `runBatchFoldingOperation` are "light" regions (no `SIGNATURE` user data): `UpdateFoldRegionsOperation.shouldRemoveRegion` keeps them across folding passes, they may be 1 character long, and an empty placeholder is accepted (`FoldingModelImpl.createFoldRegion` only rejects `start >= end`, character-pair splits and tree-invalid ranges). `FoldingBuilder`-created regions, by contrast, are removed when `range.length < 2` — this is why the live markup does **not** use a `FoldingBuilder`.
- `com.intellij.textEditorCustomizer` (`TextEditorCustomizer.customize(textEditor, coroutineScope)`) is how the Markdown plugin itself attaches per-editor behaviour (`MarkdownCharacterGridCustomizer`). The interface is `@ApiStatus.Internal` (checked in 262), so the plugin uses the public `editorFactoryListener` instead (decision 20); the Markdown plugin registers one of those too (`MarkdownCharacterGridEditorFactoryListener`).
- PSI: `MarkdownElementTypes.STRONG / EMPH / STRIKETHROUGH / CODE_SPAN / INLINE_LINK / LINK_TEXT / LINK_DESTINATION / IMAGE / ATX_1…ATX_6`; tokens `MarkdownTokenTypes.EMPH` (marker chars), `ATX_HEADER`, `BACKTICK`, `CHECK_BOX`.
- Toolbar groups for a toggle: `Markdown.Toolbar.Right` (editor-with-preview toolbar), `Markdown.EditorContextMenuGroup`.
- Persisted folding state (`DocumentFoldingInfo`, package-private): on close, every collapsed region of an initialised editor is saved as `<marker signature="start:end" ph="…"/>`, ours included (no `SIGNATURE`, not `TRANSIENT_KEY`, both keys private), and restored on reopen through `FoldingModel.addFoldRegion` as plain regions; a second region on the same range is rejected by the fold tree. Decision 23.

#### Phase F1 — Range collection and light fold regions

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| F1.1 | `MarkupRangeCollector`: PSI → `List<MarkupRange(kind, range, placeholder)>`, skipping code fences/blocks/HTML | ✅ | PSI shape verified with a tree dump: heading space sits inside `ATX_CONTENT` (taken from the document text), `CHECK_BOX` token is `[ ] ` with a trailing space (only `[ ]` folded), `~~` is two `TILDE` tokens, nested `***x***` keeps markers as direct children so ranges never overlap. Closing `##` of a heading is hidden too. Empty headings and empty link text stay raw |
| F1.2 | `LiveMarkupController` per `TextEditor` (via `textEditorCustomizer`): create/refresh light fold regions, debounce on document change, dispose with editor | ✅ | Attached through the public `editorFactoryListener` (`LiveMarkupEditorListener` + project service `LiveMarkupService`) instead of `textEditorCustomizer`, which is `@ApiStatus.Internal` in 262 — decision 20. Regions start expanded when created, so the controller collapses them; `UNTYPED` editors are accepted next to `MAIN_EDITOR` (plain `EditorFactory.createEditor` over a file, test fixtures) |
| F1.3 | Caret policy: regions intersecting caret line(s)/selection expanded, all others collapsed; applied on caret/selection change and after re-sync | ✅ | Whole caret lines plus selection ranges, union over all carets; overlap is strict (a region touching the selection edge stays hidden). Re-applied via a coalesced `invokeLater` on line change / caret add-remove / selection change, never on moves within a line (test counts zero batch operations) |
| F1.4 | Spike result recorded: regions survive Markdown plugin's own folding pass, `Fold All`/`Expand All`, and typing at region borders | ✅ | Decision 21. All four cases hold, one fix needed: a `FoldingListener` re-applies the caret policy after foreign batches |
| F1.5 | Tests: collector on fixtures; controller in `BasePlatformTestCase` with `EditorTestUtil` (regions exist, caret line expanded) | ✅ | Shipped with each step: `MarkupRangeCollectorTest` (8), `LiveMarkupControllerTest` (12), `LiveMarkupPerformanceTest` (1). No `EditorTestUtil` needed — caret model, `CodeFoldingManager` and editor action handlers drive the editor directly |

**Steps (detail):**

- **F1.1 — Collector.** Deliverable: `markdown/MarkupRangeCollector.kt`.
  ```kotlin
  enum class MarkupKind { STRONG, EMPH, STRIKE, CODE, HEADING, LINK_OPEN, LINK_TAIL, CHECKBOX_OFF, CHECKBOX_ON }
  data class MarkupRange(val kind: MarkupKind, val range: TextRange, val placeholder: String)

  object MarkupRangeCollector {
    /** Read action required. Deterministic order by startOffset. */
    fun collect(file: PsiFile): List<MarkupRange> {
      // Walk PSI; for each element:
      //  STRONG        -> first 2 chars and last 2 chars, placeholder ""
      //  EMPH          -> first 1 char and last 1 char, placeholder ""
      //  STRIKETHROUGH -> "~~" both sides
      //  CODE_SPAN     -> every BACKTICK token range, placeholder ""
      //  ATX_1..6      -> ATX_HEADER token + following single space, placeholder ""
      //  INLINE_LINK   -> "[" (LINK_OPEN, placeholder "") and from "]" to the end of the link (LINK_TAIL, placeholder "")
      //  CHECK_BOX     -> "[ ]" -> "☐", "[x]"/"[X]" -> "☑"
      // Skip anything inside CODE_FENCE, CODE_BLOCK, HTML_BLOCK, and inside LINK_DESTINATION/IMAGE (v1 leaves images raw).
    }
  }
  ```
- **F1.2 — Controller.** Deliverable: `markdown/LiveMarkupController.kt`, `markdown/LiveMarkupCustomizer.kt : TextEditorCustomizer` (registered in `agenstorm-markdown.xml`; only for files whose type is Markdown and when enabled globally or per editor).
  ```kotlin
  class LiveMarkupController(private val editor: EditorEx, private val project: Project, scope: CoroutineScope) : Disposable {
    private val regions = HashMap<TextRange, FoldRegion>()          // our regions only
    private val ourKey = Key.create<MarkupKind>("agenstorm.liveMarkup")
    private val resync = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
      editor.document.addDocumentListener(object : DocumentListener { override fun documentChanged(e: DocumentEvent) { resync.tryEmit(Unit) } }, this)
      editor.caretModel.addCaretListener(object : CaretListener { override fun caretPositionChanged(e: CaretEvent) { if (lineSetChanged(e)) applyCaretPolicy() } }, this)
      editor.selectionModel.addSelectionListener({ applyCaretPolicy() }, this)
      scope.launch { resync.debounce(200).collectLatest { sync() } }
      resync.tryEmit(Unit)
    }
    private suspend fun sync() {
      val wanted = readAction { PsiDocumentManager.getInstance(project).getPsiFile(editor.document)?.let(MarkupRangeCollector::collect) } ?: return
      withContext(Dispatchers.EDT) {
        val model = editor.foldingModel as FoldingModelEx
        model.runBatchFoldingOperation({
          // remove regions whose range/kind no longer exists; create missing ones collapsed; keep the rest
          // createFoldRegion(start, end, placeholder, group = null, neverExpands = false)?.also { it.putUserData(ourKey, kind); it.setGutterMarkEnabledForSingleLine(false) }
        }, /* allowMovingCaret */ false, /* keepRelativeCaretPosition */ true)
        applyCaretPolicy()
      }
    }
    fun applyCaretPolicy() { /* expand regions intersecting any caret line or the selection; collapse the rest; one batch op */ }
    fun removeAll() { /* batch: remove every region with ourKey */ }
    override fun dispose() = removeAll()
  }
  ```
  Notes for the implementer: use `FoldRegion.setGutterMarkEnabledForSingleLine(false)` so the gutter does not fill with fold marks; compare regions by `TextRange` + kind after edits (fold regions are range markers and move with text); never touch regions without `ourKey` — the Markdown plugin's header/list folding must keep working; `EditorEx.foldingModel` cast to `FoldingModelEx` is fine (`FoldingModelImpl` implements it).
- **F1.3 — Caret policy.** Deliverable: `applyCaretPolicy()` as above, plus `lineSetChanged` (avoid re-running on every keystroke within the same line). Multi-caret: union of caret lines. Selection: regions intersecting `[selectionStart, selectionEnd]` expanded so what you see is what you copy.
- **F1.4 — Spike record.** Deliverable: a §6 decision row with the observed behaviour: regions after `Fold All`/`Expand All` (expected: `Expand All` expands ours — re-apply policy on `FoldingListener.onFoldProcessingEnd`), after the Markdown plugin's folding update, after `Reformat`, after undo. If any of these kills our regions, the `resync` on `FoldingListener` events is the fix.
- **F1.5 — Tests.** Deliverable: `MarkupRangeCollectorTest` (fixtures in `testData/markdown/`: bold/italic/nested/code span with backticks inside fences/links/checkboxes/headings) and `LiveMarkupControllerTest` using `myFixture.configureByFile` + `EditorTestUtil.buildInitialFoldingsInBackground`; assert `foldingModel.allFoldRegions` contains ours collapsed except on the caret line; move caret with `EditorTestUtil` and re-assert.

**Exit guardrails — Phase F1 → F2**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Hide/reveal | In `runIde`, `**bold**` shows as `bold`; placing the caret on that line reveals `**`; moving away hides them | ✅ | Automated in `LiveMarkupControllerTest` (caret following, selection, multi-caret). Roman checked `agenstorm-demo/docs/live-markup.md` in the 2026-09-06 00:24 sandbox session: "all good, continue"; idea.log clean |
| Coexistence | Markdown plugin heading/list folding still works; `Fold All`/`Expand All` do not break live markup after the next caret move | ✅ | Automated: the plugin's heading/list/fence/table regions appear next to ours and both actions are corrected on the next event-loop turn (decision 21). Roman signed off 2026-09-06 |
| Copy fidelity | Select-all + copy yields raw Markdown | ✅ | Fold regions never change the document, and a selection reveals what it covers (F1.3). Roman signed off 2026-09-06 |
| Perf | 3,000-line file: no visible lag while typing; sync after edits < 50 ms (log timing at debug level) | 🔄 | Measured in `LiveMarkupPerformanceTest` on 3,000 lines / 7,500 regions: first sync 179 ms (creates every region), no-op sync 16 ms, sync after an edit 14 ms. Timings also logged at debug level (`#com.pronskiy.agenstorm.markdown.LiveMarkupController`). Typing feel checked by Roman on `docs/big.md`, signed off 2026-09-06 |

#### Phase F2 — Styling, links, checkboxes, toggle

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| F2.1 | `LiveMarkupAnnotator` (only when live mode is on for that file): bold/italic/strike font attributes when the scheme lacks them; link text underlined + link colour | ✅ | The bundled 262 scheme already renders all four (`MARKDOWN_LINK_TEXT` falls back to `HYPERLINK_ATTRIBUTES`, `MARKDOWN_STRIKE_THROUGH` to `DEPRECATED_ATTRIBUTES`; bold/italic are defined outright), so the annotator is a no-op there and only matters for schemes that dropped them. Per-file gate: a counter on the `VirtualFile` maintained by `LiveMarkupService.attach/detach`, with a daemon restart when it flips |
| F2.2 | Checkbox toggle: click on a ☐/☑ placeholder flips `[ ]`↔`[x]` in a write command | ✅ | `EditorMouseListener.mousePressed` + `EditorMouseEvent.collapsedFoldRegion`; only the middle character is replaced so the fold region (a range marker) survives, then placeholder and kind are swapped in place; the event is consumed (no caret move, no expand). One undo step; hand cursor on hover |
| F2.3 | Link navigation from the visible link text: Cmd/Ctrl+click on `LINK_TEXT` navigates via the (hidden) destination's references; URLs open in browser | ✅ | Decision 22: a `gotoDeclarationHandler` instead of editor mouse listeners, so Ctrl+B and the hover underline come along and no internal navigation service is needed. Tests navigate through the real Go to Declaration action: file link, `path:line:col` (caret lands on line and column), heading anchor in the same and in another file, markup inside link text; URL yields a `WebReference` target; reference-style links, missing files and the feature toggle give nothing |
| F2.4 | `ToggleLiveMarkupAction` in `Markdown.Toolbar.Right` and `Markdown.EditorContextMenuGroup`; per-editor state; settings default + group "Markdown live markup" | ✅ | Icon `AllIcons.Actions.ToggleVisibility`; the editor comes from `CommonDataKeys.EDITOR` or the split editor's `TextEditorWithPreview`. Per-editor override stored in editor user data (null = follow the setting). Settings: `liveMarkupCheckboxes`, `liveMarkupBullets`; the page (in `core/`) fires `AgenstormSettingsListener` and `LiveMarkupStartupActivity` applies it, so `core/` never references the optional Markdown plugin |
| F2.5 | Bullets `- `/`* ` rendered as `• ` (placeholder), optional setting | ✅ | Only the marker character of `LIST_BULLET` (`-`, `*`, `+`) is replaced; the space stays, ordered lists untouched; `liveMarkupBullets` (default on) |

**Steps (detail):**

- **F2.1 — Annotator.** Deliverable: `markdown/LiveMarkupAnnotator.kt` registered `<annotator language="Markdown" …/>` in `agenstorm-markdown.xml`; it checks a per-file flag (set by the controller via `PsiFile.putUserData`/editor user data) and adds `enforcedTextAttributes` for `STRONG` (bold), `EMPH` (italic), `STRIKETHROUGH` (strike), `LINK_TEXT` (underline + `DefaultLanguageHighlighterColors.HIGHLIGHTED_REFERENCE`-like colour). Prefer the existing scheme keys: if `EditorColorsManager.getInstance().globalScheme.getAttributes(MarkdownHighlighterColors.BOLD).fontType` already has BOLD, do nothing for that kind.
- **F2.2 — Checkbox toggle.** Deliverable: `EditorMouseListener` in the controller: on click, `editor.foldingModel.getCollapsedRegionAtOffset(offset)` with `ourKey == CHECKBOX_*` → `WriteCommandAction.runWriteCommandAction(project) { document.replaceString(...) }`; the sync then swaps the placeholder.
- **F2.3 — Link navigation.** Deliverable: `EditorMouseListener` + `EditorMouseMotionListener` in the controller: with Cmd/Ctrl held over a `LINK_TEXT` range (looked up from the collector output), show hand cursor; on click find the sibling `LINK_DESTINATION`, collect `PsiSymbolReferenceService.getService().getReferences(destination)` + `destination.references`, navigate the first resolvable target (`NavigationTarget.navigationRequest()` via `NavigationService`, or `Navigatable.navigate`), else if it is a URL → `BrowserUtil.browse`. This also makes Epic A's `path:line:col` links work from the visible text.
- **F2.4 — Toggle + settings.** Deliverable: `markdown/ToggleLiveMarkupAction.kt` (`ToggleAction`, icon `AllIcons.Actions.PreviewDetails` or a custom 16 px SVG), per-editor state in `editor.putUserData(LIVE_MARKUP_ENABLED, …)` respected by the customizer/controller (`removeAll()` on off); settings: `liveMarkupEnabled` (default on), `liveMarkupBullets`, `liveMarkupCheckboxes`.
- **F2.5 — Bullets.** Deliverable: collector emits `BULLET` ranges (`LIST_BULLET` token's `-`/`*`/`+` char → placeholder `•`) when the setting is on.

**Exit guardrails — Epic F → release 1.0**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Obsidian parity (scoped) | Side-by-side with Obsidian on `testData/markdown/parity.md`: bold, italic, strike, code, headings (markers hidden), links (text only), checkboxes, bullets match in what is shown/hidden; heading size difference accepted | ✅ | `parity.md` committed; the same constructs are rendered by `MarkupRangeCollectorTest`. Roman signed off 2026-09-06 after the second review round ("all good"), with one difference noted for follow-up: Obsidian reveals inline elements one at a time, Agenstorm reveals the whole caret line (see §7) |
| Editing safety | 30-minute editing session of a real plan file: no lost characters, no caret jumps, undo/redo correct | ✅ | Automated: typing at region borders, Reformat, Undo and checkbox toggles keep regions and text consistent (`LiveMarkupControllerTest`). Roman's sessions of 2026-09-06 (three sandbox runs) signed off |
| Off switch | Toggling off restores a plain Markdown editor instantly (no leftover folds) | ✅ | Automated: `ToggleLiveMarkupActionTest` (no region of ours left after toggling off; back on restores them; settings apply reaches every editor). Roman signed off 2026-09-06 |
| Agent-written files | Opening a file while an agent rewrites it (external change → reload) keeps live markup consistent after reload | ✅ | Automated: an outside document edit at a collapsed region's border re-syncs cleanly; a VFS reload is a document replace and goes through the same debounced sync. First review round (2026-09-06, 08:25 and 12:04 sessions): markers never unfolded — persisted folding state, fixed, decision 23; second round (12:49 session, fixed build on the same workspace) signed off. The one SEVERE of that round is PhpStorm's own: `FrameworkCommandsConfigurable` (PHP plugin) reads the VFS on the EDT while the Settings tree renders; no Agenstorm frame |

#### Phase F3 — Reveal inline markup per element (post-MVP)

**Goal:** On the caret line, only the inline element the caret is in (or touches) shows its raw Markdown; the other bold, italic, strike, code and link elements on that line stay rendered. Block-level markers (heading hashes, bullets, checkboxes) keep revealing per line. Requested by Roman at the Epic F sign-off: "inline elements like bold unfold along with all other elements in the line; fine for headers, not perfect for inline elements like links".

**What other editors do** (checked 2026-09-06):

- **Obsidian** (Live Preview) reveals the syntax of the element the cursor is in: moving the cursor into the word *bold* shows the `**` around that word and nothing else on the line; touching the edge of an element counts as being in it, enough so that a community plugin exists to require the cursor strictly inside a link. Heading hashes and list markers are revealed per line. Sources: [Obsidian help, Edit and preview Markdown](https://ryn-cx.github.io/obsidian-theme-previews/Obsidian%20Help/Editing%20and%20formatting/Edit%20and%20preview%20Markdown.html), [Actually Useful Obsidian: Formatting](https://dandylyons.net/posts/actually-useful-obsidian-formatting/), [Link Hover Reveal plugin](https://www.obsidianstats.com/plugins/link-hover-reveal).
- **Typora** expands a span element into its Markdown source when the cursor moves into the middle of it; a preference turns that off for pure WYSIWYG. Sources: [Typora Markdown Reference](https://support.typora.io/Markdown-Reference/), [typora-issues #1317](https://github.com/typora/typora-issues/issues/1317).
- **Bear 2** hides markup by default and shows it around the element being edited; the public docs describe the hiding, not the exact cursor rule. Source: [Bear 2.0 release notes](https://alternativeto.net/news/2023/7/bear-2-0-is-here-the-next-generation-for-the-markdown-note-taking-app-with-bunch-of-features).
- **org-appear** (Emacs) toggles hidden element parts on entering and leaving an element: emphasis markers by default, links and entities optionally. Source: [org-appear](https://github.com/awth13/org-appear).
- **render-markdown.nvim** (Neovim) reveals everything on the cursor row, which is what Agenstorm does after Phase F2; a consequence of Vim's line-based conceal rather than a design goal. Source: [render-markdown.nvim](https://github.com/MeanderingProgrammer/render-markdown.nvim).

Accepted behaviour, therefore: inline elements one at a time, block markers per line. Decision 24.

Platform facts (verified against build 262):

- `FoldingGroup.newGroup(String)` + `FoldingModelEx.createFoldRegion(start, end, placeholder, group, neverExpands)` and `FoldingModelEx.getGroupedRegions(group)`: the two marker regions of one element can share a group, which also makes the platform treat them as one unit when a placeholder is clicked.
- `FoldingModelEx.getRegionsOverlappingWith(start, end)`: the regions near the old and new caret positions, so a per-move policy does not scan the whole file.
- A caret cannot sit inside a collapsed region; the platform moves it past the placeholder. An element must therefore open the moment a caret reaches its edge, or arrow keys skip the opening marker once.

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| F3.1 | Collector: every `MarkupRange` carries the span of its element (both markers of `**bold**` share one span; heading, bullet and checkbox spans are their line). Controller: one `FoldingGroup` per element; the caret policy reveals a group when a caret offset is inside or touching its span, or a selection overlaps it, and runs on every caret move over the regions near the old and new position only | ✅ | Two findings changed the plan. (1) A zero-width placeholder has no caret position of its own: `EditorRight` from the character before a collapsed `**` lands after it, so an inline element opens one character early on each side (within its line) — the arrow-key walk test needs it. (2) The full scan on every caret move costs 6 ms on 7,500 regions (`LiveMarkupPerformanceTest`), so the nearby-regions optimisation was not built. Keep rule: a region survives a sync only while every marker of its group is still wanted with the same span (single-marker elements included). Nested elements open together because the inner one is at most one step away |
| F3.2 | Setting `liveMarkupRevealScope` (`element`, default, or `line` for the Phase F2 behaviour) in the Markdown group; applied through the settings topic like the other options | ✅ | Combo box `markdown.revealScope`; the controller reads the scope on every policy evaluation, so a settings apply changes open editors in place |
| F3.3 | Tests: caret inside, touching either edge, outside on the same line; nested `***both***` and bold inside link text; selection across two elements; arrow-key walk across a collapsed element never skips a marker; the `line` scope reproduces the Phase F2 expectations | ✅ | Shipped with F3.1 and F3.2 in `LiveMarkupControllerTest` (7 new cases, the arrow walk in both directions) plus a collector span test and the policy timing in `LiveMarkupPerformanceTest` |

**Steps (detail):**

- **F3.1 — Spans and groups.** `MarkupRange(kind, range, placeholder, span: TextRange)`. The controller keys groups by span: regions with the same span get the same `FoldingGroup`; the `RegionKey` gains the span so a changed element is recreated. `applyCaretPolicy()` computes, per group, `revealed = carets.any { it.offset in span.startOffset..span.endOffset } || selections.any { it overlaps span }`; block kinds use the line span exactly as today. The caret listener drops the line-change test and instead schedules the policy on every position change; the policy looks only at `getRegionsOverlappingWith` over the lines of the old and new positions, so a keystroke inside an element costs one small scan and no fold operation.
- **F3.2 — Setting.** `AgenstormSettings.State.liveMarkupRevealScope: String = "element"`; combo box in the Markdown group; the controller reads it in `revealedSpans()`.
- **F3.3 — Tests.** Extend `LiveMarkupControllerTest`; the existing line-based tests become the `line` scope cases.

**Exit guardrails — Phase F3**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| One at a time | On a line with two bold spans and a link, the caret in the first bold shows only its `**`; the link keeps its text-only form | ✅ | Automated (`testOnlyTheElementAtTheCaretIsRevealedOnItsLine`). Roman signed off 2026-09-06 (13:35 sandbox session): "all good" |
| Keyboard walk | Arrow keys from plain text through `**bold**` and out again never skip a character; the markers appear when the caret touches the element | ✅ | Automated with the real `EditorRight`/`EditorLeft` handlers; the element opens one character early. Roman signed off 2026-09-06 |
| Block markers | Heading hashes, bullets and checkboxes still reveal for the whole caret line | ✅ | Automated (`isBlock` kinds use the line span). Roman signed off 2026-09-06 |
| Copy fidelity | A selection across two elements reveals both; copy yields raw Markdown | ✅ | Automated (`testASelectionAcrossTwoElementsRevealsBoth`). Roman signed off 2026-09-06 |
| Scope setting | Switching to `line` restores the Phase F2 behaviour in open editors without reopening them | ✅ | Automated (`testLineScopeRevealsTheWholeCaretLineAndAppliesToOpenEditors`). Roman signed off 2026-09-06. Review-round note: the one SEVERE of the day blamed on Agenstorm (`Cannot create listener TabsProjectCloseListener`, caused by a `ZipException`) came from a second `runIde` rewriting the sandbox jar under the instance the first one had started; not a plugin bug — one launcher per review from now on |

### Epic G — `open path` in the terminal opens in this IDE  ·  1.0

**Goal:** In an IDE terminal, `open src/Foo.php:42:7` opens that file in *this* project window with the caret on line 42, column 7; `open ../other-project` opens or focuses that project; everything else `open` normally does — `-a`, `-R`, URLs, missing paths, no arguments — still reaches `/usr/bin/open` untouched. The point is that agents and tools already print `path:line:col` all day (Epic A made those clickable everywhere except where they are most often typed), and `open` is the verb everyone's fingers already know.
**Success metrics:** round trip from Enter to caret < 150 ms; zero behaviour change for arguments the IDE does not claim; works in zsh, bash and fish under both the reworked and the classic engine; turning the feature off leaves a new terminal's PATH untouched.

New package `terminal/` and a new optional descriptor `agenstorm-terminal.xml` (`<depends optional="true" config-file="agenstorm-terminal.xml">org.jetbrains.plugins.terminal</depends>`), shared with Epic I. **Before G1.1 compiles**, `platformBundledPlugins` in `gradle.properties` has to gain `org.jetbrains.plugins.terminal` — that is the whole build change, and per CLAUDE.md it belongs in `gradle.properties`, not in `build.gradle.kts`.

Platform facts (verified against build 262):

- **The reworked (Gen2) terminal is the default in 2026.2.** `TerminalOptionsProvider$State.<init>` assigns `TerminalEngine.REWORKED`; `CLASSIC` (JediTerm) and `NEW_TERMINAL` (the deprecated 2024 block terminal) are opt-in from Settings → Tools → Terminal → Terminal Engine. The registry keys `terminal.new.ui` and `terminal.new.ui.reworked` still exist but their own descriptions call them no-ops.
- **`TerminalShellCommandHandler` is not usable for this.** The EP `com.intellij.terminal.shellCommandHandler` exists (interface `com.intellij.terminal.TerminalShellCommandHandler` in `lib/intellij.platform.execution.impl.jar`, gated by the `terminal.shell.command.handling` *experimental feature* at 100 %, not a registry key), and the platform registers `OpenFileShellCommandHandler` and `RunAnythingTerminalBridge` on it. But its only driver, `TerminalShellCommandHandlerHelper`, is constructed solely by `ShellTerminalWidget` and highlights through `com.jediterm.terminal.model.TerminalLineIntervalHighlighting` — so the EP is dead under the default engine, and in Classic it fires from `matchedExecutor(KeyEvent)`, i.e. the Run/Debug shortcut, not plain Enter. Decision 25.
- **The hook that does work:** EP `org.jetbrains.plugins.terminal.shellExecOptionsCustomizer` → `org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer` (`@ApiStatus.Experimental`, `@RequiresBackgroundThread`, `@RequiresReadLockAbsence`), one method `customizeExecOptions(Project, MutableShellExecOptions)`. `MutableShellExecOptions` exposes exactly what is needed: `prependEntryToPATH(Path)`, `setEnvironmentVariable(String, String)`, `getEelDescriptor()`, `getEnvs()`. It supersedes `LocalTerminalCustomizer`, whose `customizeCommandAndEnvironment` overloads are `@Deprecated`.
- **The PATH entry survives the user's rc files.** `MutableShellExecOptionsImpl` implements `prependEntryToPATH` through `_INTELLIJ_FORCE_PREPEND_PATH`, which `shell-integrations/{zsh,bash,fish,powershell}` apply *after* `.zshenv`/`.zprofile`/`.zshrc`/`.zlogin` have run — a plain `PATH=` in the env map would be clobbered by a user who rebuilds PATH in their rc file, this is not.
- **Precedent for a generated script + env injection:** remote dev ships `com.jetbrains.rdserver.unattendedHost.browser.UnattendedHostOpenLinkScriptHolder`, which writes `remote-dev-browser.sh` to disk at runtime and points `BROWSER` at it, with `UnattendedHostOpenLinkTerminalEnvCustomizer` (a `LocalTerminalCustomizer`) doing the injection. Same shape as G1.3 + G1.4.
- `com.sun.net.httpserver.HttpServer` is in the JBR (module `jdk.httpserver`) and already used by this project's tests, so the endpoint needs no third-party library — same rationale as decision 6.
- `com.intellij.ide.impl.ProjectUtil` offers `findAndFocusExistingProjectForPath(Path)`, `openOrImportAsync(Path, OpenProjectTask)`, `focusProjectWindow(Project, Boolean)` and `isSameProject(Path, Project)`. **Its ApiStatus is not settled:** `@ApiStatus.Internal` appears in the class file but attaches to `openExistingDir`/`FolderOpeningMode`, and the class-level annotation is `kotlin.Metadata`. G2.2 re-checks this before using it — see the Open Question in §7.

#### Phase G1 — The shim and the endpoint

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| G1.1 | `OpenRequestServer`: per-project loopback `HttpServer`, token-checked `POST /open` | ✅ | `OpenRequestHandler` seam; G1.2 installs the router |
| G1.2 | `OpenCommandRouter`: argv + cwd → open files / open project / fall back. Pure logic, all the tests | ✅ | Claiming is all-or-nothing per command line; a loose peel next to `FileLocationParser` covers paths with spaces |
| G1.3 | `OpenShimScriptHolder`: generates the `open` script on disk, 0755, regenerated per plugin version | 🔲 | |
| G1.4 | `TerminalOpenExecOptionsCustomizer : ShellExecOptionsCustomizer` — PATH entry plus port and token | 🔲 | |

**Steps (detail):**

- **G1.1 — Endpoint.** Deliverable: `terminal/OpenRequestServer.kt`, a project `@Service(PROJECT)` taking a `CoroutineScope`. Binds `HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)` so the port is free-chosen and unreachable from outside the machine; generates a 32-hex token per IDE run. One context, `POST /open`: the body is a NUL-separated UTF-8 list whose first field is the shell's `$PWD` and whose rest is the original argv. **A NUL-separated body, not a query string**, because paths may contain spaces, quotes and even newlines, and this way the shim needs no URL encoding at all. The token travels in an `X-Agenstorm-Token` header — never in argv, so it cannot be read out of `ps`. Compared with `MessageDigest.isEqual` (constant time). Replies `204` when the request was handled, `409` when the router decided the IDE should not claim it, `403` on a bad token. Disposed with the project; nothing is bound while the feature is off.
  ```kotlin
  @Service(Service.Level.PROJECT)
  class OpenRequestServer(private val project: Project, private val scope: CoroutineScope) : Disposable {
    val token: String = randomHex(32)
    val port: Int get() = server?.address?.port ?: -1
    // handler: read body -> split(' ') -> cwd + argv -> OpenCommandRouter.route(...)
    //          Decision.Fallback -> 409; otherwise perform on the EDT and answer 204
  }
  ```
- **G1.2 — Router.** Deliverable: `terminal/OpenCommandRouter.kt`, pure and IDE-free apart from a `Project` for path resolution, so it carries the whole test suite. `route(cwd: Path, argv: List<String>): Decision`, where `Decision` is `OpenFiles(List<FileTarget>)`, `OpenProject(Path)` or `Fallback(reason)`. Rules: empty argv, any argument starting with `-`, and any argument matching `scheme://` ⇒ `Fallback`; a trailing `:line[:col]` is peeled off with `FileLocationParser` and the remainder resolved against `cwd` first, then through `FileLocationResolver`; a path that resolves to nothing ⇒ `Fallback` (so `open nope.txt` still produces macOS's own error); a directory ⇒ `OpenProject`; a file whose type is binary ⇒ `Fallback` unless `terminalOpenUnknownFileTypes` says otherwise. Several paths in one command produce several `FileTarget`s.
- **G1.3 — Shim.** Deliverable: `terminal/OpenShimScriptHolder.kt`, an app `@Service`, and the script template in `resources/terminal/open.sh`. Writes each configured command name into `PathManager.getSystemPath()/agenstorm/bin/`, `POSIX_FILE_PERMISSIONS` 0755, rewritten when `pluginVersion` changes so a stale script never survives an update. POSIX `sh` only — no bashisms, because the same file is on PATH for zsh, bash and fish.
  ```sh
  #!/bin/sh
  # Generated by Agenstorm. Anything this script does not claim goes to the real `open`.
  real=/usr/bin/open
  [ -x "$real" ] || real=$(command -v xdg-open 2>/dev/null) || real=
  fallback() { [ -n "$real" ] && exec "$real" "$@"; echo "open: command not found" >&2; exit 127; }

  [ -n "$AGENSTORM_OPEN_PORT" ] || fallback "$@"      # not an Agenstorm terminal
  [ $# -gt 0 ] || fallback "$@"                        # no arguments: macOS prints usage
  case "$1" in -*|*://*) fallback "$@" ;; esac         # flags and URLs are macOS's job
  command -v curl >/dev/null 2>&1 || fallback "$@"

  printf '%s\0' "$PWD" "$@" |
    curl -fsS -m 2 -X POST --data-binary @- \
         -H "X-Agenstorm-Token: $AGENSTORM_OPEN_TOKEN" \
         "http://127.0.0.1:$AGENSTORM_OPEN_PORT/open" >/dev/null 2>&1 && exit 0
  fallback "$@"                                        # 409, 403, timeout, IDE gone
  ```
  `curl -f` makes any 4xx a non-zero exit, so a `409` from the router and a dead IDE take the same path. Scope: macOS first, Linux for free (`xdg-open`); **Windows is out of scope for 1.0** — the customizer no-ops there rather than generating an `open.cmd`, matching Epic A's macOS-first stance.
- **G1.4 — Injection.** Deliverable: `terminal/TerminalOpenExecOptionsCustomizer.kt` registered as `<shellExecOptionsCustomizer implementation="…"/>` in `agenstorm-terminal.xml`. `customizeExecOptions` returns immediately when `terminalOpenEnabled` is off or `getEelDescriptor()` is not the local one (v1 does not shim shells running over Eel — a WSL or SSH shell cannot reach the IDE's loopback port). Otherwise: `prependEntryToPATH(binDir)`, `setEnvironmentVariable("AGENSTORM_OPEN_PORT", port)`, `setEnvironmentVariable("AGENSTORM_OPEN_TOKEN", token)`. The method is `@RequiresBackgroundThread` and `@RequiresReadLockAbsence`, so the script is written here, off the EDT, not in a startup activity.

**Exit guardrails — Phase G1 → G2**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Shim reaches the IDE | In `runIde`, `echo $AGENSTORM_OPEN_PORT` is non-empty and `command -v open` resolves to the generated script in all three of zsh, bash and fish | 🔲 | |
| PATH survives rc files | A `.zshrc` that does `export PATH=/usr/bin:/bin` still leaves the shim first (this is what `_INTELLIJ_FORCE_PREPEND_PATH` buys) | 🔲 | |
| No token leak | `ps aux` during an `open` never shows the token; it is only ever a header | 🔲 | |
| Router correctness | `OpenCommandRouterTest` green on the corpus: flags, URLs, no args, missing path, `path:42`, `path:42:7`, several paths, a directory, a path with spaces and one with a newline | ✅ | 21 cases green, whole corpus covered plus `.`, a colon in a file name, an absolute missing path, and the binary-file rule |

#### Phase G2 — Opening, settings, first run

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| G2.1 | Open files at `line:col` in this project window and focus it | 🔲 | |
| G2.2 | Open or focus a project for a directory argument | 🔲 | |
| G2.3 | Settings group "Terminal" and the three options | 🔲 | |
| G2.4 | First-run balloon and README section | 🔲 | |

**Steps (detail):**

- **G2.1 — Files.** Deliverable: the `OpenFiles` branch of `OpenRequestServer`. On the EDT, for each target `OpenFileDescriptor(project, file, line - 1, column - 1).navigate(true)`, then `ProjectUtil.focusProjectWindow(project, true)` once. Offsets are computed the way Epic A already does it (`FileLocationResolver.toOffset` clamps a line beyond EOF), so `open Foo.php:99999` lands on the last line instead of failing.
- **G2.2 — Projects.** Deliverable: the `OpenProject` branch. A directory that is inside the current project's content roots is not a project at all — select it in the Project view and focus the window. Otherwise `ProjectUtil.findAndFocusExistingProjectForPath(path)` first; if that returns nothing, `ProjectUtil.openOrImportAsync(path, OpenProjectTask { forceOpenInNewFrame = true })` from the service scope, which with Epic E on means the new window arrives as a new project tab. **Before writing this step, confirm `ProjectUtil`'s ApiStatus** (see the platform facts above): if the class turns out to be `@ApiStatus.Internal`, mark the step ⏸️, add the Open Question, and do not guess a replacement.
- **G2.3 — Settings.** Deliverable: a "Terminal" group in `AgenstormConfigurable` and three fields on `AgenstormSettings.State`: `terminalOpenEnabled` (default on), `terminalOpenCommandNames` (default `open`; a comma-separated list, so a user can add `e` or `edit` without losing `open`), `terminalOpenUnknownFileTypes` (default off — a PDF or a PNG goes to macOS, not to the IDE). Changing any of them fires `AgenstormSettingsListener`; existing terminals keep their environment until they are restarted, and the settings page says so.
- **G2.4 — First run.** Deliverable: a one-shot balloon in the `Agenstorm` notification group the first time the shim is installed, saying that `open` is shadowed *inside IDE terminals only* and linking to the setting; a `terminalOpenNoticeShown` flag on the settings state so it never repeats. README gains an "Opening files from the terminal" section covering the fallback rules and the `path:line:col` form.

**Exit guardrails — Epic G → Epic H**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| The happy path | `open src/Foo.php:42:7` puts the caret at 42:7 in the window whose terminal it was typed in, in under 150 ms by feel | 🔲 | |
| Fallback fidelity | `open -a Preview doc.pdf`, `open https://jetbrains.com`, `open nope.txt`, `open` with no arguments, and `open .` inside the project behave exactly as they did before the plugin | 🔲 | |
| Both engines | Works with Terminal Engine set to Reworked **and** to Classic | 🔲 | |
| Projects | `open ~/projects/other` opens that project; running it again focuses the existing window rather than opening a second one | 🔲 | |
| Off switch | Turning `terminalOpenEnabled` off and opening a new terminal: `command -v open` is `/usr/bin/open` again, and no `AGENSTORM_OPEN_*` variables are set | 🔲 | |
| Log clean | `idea.log` has no `com.pronskiy.agenstorm` frames after a session of use | 🔲 | |

---

### Epic H — Markdown block rendering: code fences, quotes, rules  ·  1.0

**Goal:** In live-markup mode a fenced code block renders as a rounded card carrying its language and a copy action, with the ``` lines hidden and the syntax highlighting inside untouched; block quotes lose their `>` markers and gain a left rail; thematic breaks render as a drawn line. Epic F's caret policy still governs everything — putting the caret in a block reveals its raw markers. Indented code blocks and images stay out of scope (§7).
**Success metrics:** a 3,000-line file with 200 fences re-syncs in < 50 ms after edits stop — the Epic F budget, unchanged; the card survives a theme switch, soft wrap, and the Markdown plugin's own `CODE_FENCE` fold region; a selection across a card still copies the raw fence, backticks included.

Platform facts (verified against build 262):

- PSI: `MarkdownElementTypes.CODE_FENCE` and `.BLOCK_QUOTE`; tokens `MarkdownTokenTypes.CODE_FENCE_START`, `.CODE_FENCE_END`, `.FENCE_LANG`, `.CODE_FENCE_CONTENT`, `.BLOCK_QUOTE` (the `>` marker) and `.HORIZONTAL_RULE`; the set `MarkdownTokenTypeSets.CODE_FENCE_ITEMS`. The PSI class is `org.intellij.plugins.markdown.lang.psi.impl.MarkdownCodeFence` (extends the abstract `MarkdownCodeFenceImpl`) with `getFenceLanguage()` returning the info string. None of these carry an ApiStatus annotation.
- **Syntax highlighting inside a fence already works in the plain editor.** `org.intellij.plugins.markdown.fenceInjection.CodeFenceInjector` — a `MultiHostInjector` in the `intellij.markdown.fenceInjection` module — injects the language guessed by `CodeFenceLanguageGuesser` over the whole body with a single `addPlace`, so the ordinary injected-highlighting pass colours it. **Do not re-highlight it and do not fold the body**; the epic only hides the fence lines and paints around them.
- **The Markdown plugin already folds fences and quotes.** `MarkdownFoldingBuilder` (a `CustomFoldingBuilder`, registered `lang.foldingBuilder language="Markdown"`) emits one `FoldingDescriptor` over the whole multi-line `CODE_FENCE`, and also folds `BLOCK_QUOTE`, lists, tables and link destinations. Collapsed by default only when `MarkdownCodeFoldingSettings.State.collapseCodeFences` is on (it defaults to off). Our light regions must nest strictly *inside* its range — the fold tree allows nesting, rejects crossing, and rejects a duplicate range (this is the same trap decision 23 records).
- **A `LINES_IN_RANGE` highlighter paints to the right edge of the viewport.** `MarkupModel.addRangeHighlighter(start, end, layer, TextAttributes, HighlighterTargetArea.LINES_IN_RANGE)`: `RangeHighlighterImpl.getAffectedAreaStartOffset/EndOffset` expand such a highlighter to line bounds, `IterationState` deliberately skips `EXACT_RANGE` highlighters when computing the past-line-end attributes so only `LINES_IN_RANGE` survives there, and `EditorPainter.paintAfterLineEnd` fills from the text to `clip.x + clip.width`. `EXACT_RANGE` stops under the text. `HighlighterLayer` has `SYNTAX = 1000` … `ADDITIONAL_SYNTAX = 3000`, low enough that selection and the injected highlighting still win.
- **Rounded corners are a custom renderer, not a different highlighter.** `RangeHighlighter.setCustomRenderer(CustomHighlighterRenderer)`; the interface and `paint(Editor, RangeHighlighter, Graphics)` are public and un-annotated in 262, and run after the background and before the text at the default order. `CustomHighlighterOrder` and `getOrder()` *are* `@ApiStatus.Experimental` — leave them unoverridden.
- **Inlays for the chip.** `InlayModel.addAfterLineEndElement(offset, InlayProperties, EditorCustomElementRenderer)`; `InlayModel`, `InlayProperties`, `Inlay` and `EditorCustomElementRenderer` carry no ApiStatus in 262. `InlayProperties.showWhenFolded(true)` keeps the chip visible if the fence is folded by the Markdown plugin, and `EditorCustomElementRenderer.getContextMenuGroup(Inlay)` gives the copy action a home.
- **Do not use `FoldingModel.addCustomLinesFolding` / `CustomFoldRegion`.** Both are `@Experimental`; the region is created already collapsed and its `CustomFoldRegionRenderer` replaces the text wholesale, which would throw away the injected highlighting the epic is trying to preserve; and `FoldingModelImpl.addCustomLinesFolding` returns `null` when the range intersects an existing region — which `MarkdownFoldingBuilder`'s `CODE_FENCE` descriptor always does. That mechanism is how "render documentation comments in the editor" works (`DocRenderItemImpl` + `DocRenderer` + `DocRenderPassFactory`); it is the closest platform analogue and the wrong fit here.
- Colour keys to derive the palette from: `MarkdownHighlighterColors.CODE_FENCE`, `.CODE_FENCE_MARKER`, `.CODE_FENCE_LANGUAGE`, `.BLOCK_QUOTE`, `.BLOCK_QUOTE_MARKER`, `.HRULE`.

#### Phase H1 — Fences: markers hidden, block background

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| H1.1 | Collector emits `FENCE_OPEN` / `FENCE_CLOSE` ranges and a block list carrying the span and the language | 🔲 | |
| H1.2 | `MarkdownBlockRenderer`: one `LINES_IN_RANGE` highlighter per block, owned by the controller's sync | 🔲 | |
| H1.3 | Caret policy and coexistence with the Markdown plugin's own `CODE_FENCE` region | 🔲 | |
| H1.4 | Tests: `fences.md` fixture and a controller test | 🔲 | |

**Steps (detail):**

- **H1.1 — Collector.** Deliverable: `MarkdownElementTypes.CODE_FENCE` leaves `MarkupRangeCollector.SKIPPED`, and the walker gains a `codeFence` branch emitting two `MarkupRange`s plus a `MarkdownBlock(kind, span, language)` on a second output list. The opening line folds `CODE_FENCE_START` together with `FENCE_LANG` and the spaces between them **but not its EOL**, so the card keeps a header row for the chip. The closing line folds from the end of the last content line *through* `CODE_FENCE_END`, newline included, so the line disappears entirely. `CODE_FENCE_CONTENT` is never touched. New kinds `FENCE_OPEN` and `FENCE_CLOSE` are `isBlock` (whole-line reveal, decision 24). Cases to get right: no info string, an unknown info string, `~~~` fences, a fence indented inside a list item or a block quote, and an unterminated fence at end of file (no `CODE_FENCE_END` token — emit the opener only).
- **H1.2 — Background.** Deliverable: `markdown/MarkdownBlockRenderer.kt`, created and disposed by `LiveMarkupController` alongside its fold regions and keyed the same way, so one sync updates both. Per block: `editor.markupModel.addRangeHighlighter(span.start, span.end, HighlighterLayer.ADDITIONAL_SYNTAX, attributes, HighlighterTargetArea.LINES_IN_RANGE)`. Attributes come from `MarkdownHighlighterColors.CODE_FENCE`; when the scheme defines no background there (many do not), fall back to `ColorUtil.mix(editor.colorsScheme.defaultBackground, …)` the way `DocRenderer` does, so the card is visible in both Light and Dark without hard-coding a colour.
- **H1.3 — Caret and coexistence.** Deliverable: nothing new in the caret policy — `FENCE_OPEN`/`FENCE_CLOSE` being `isBlock` is enough — plus a test asserting that our two regions and `MarkdownFoldingBuilder`'s whole-fence region coexist, that ours are strictly nested, and that the background highlighter stays while the markers are revealed. The `FoldingListener` re-apply from F1.4 already covers `Expand All` and foreign batches.
- **H1.4 — Tests.** Deliverable: `testData/markdown/fences.md` covering the six cases from H1.1, `MarkupRangeCollectorTest` additions, and a `LiveMarkupControllerTest` case asserting one highlighter per block, none left after toggling live markup off, and none created when `liveMarkupCodeBlocks` is off.

**Exit guardrails — Phase H1 → H2**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Fences hidden | In `runIde`, a ```` ```php ```` block shows no backticks; the closing line is gone; the caret on either line brings them back | 🔲 | |
| Injection intact | Syntax highlighting inside the fence is identical with live markup on and off, and Cmd+click inside it still navigates | 🔲 | |
| Coexistence | The Markdown plugin's own fence folding still collapses the block; `Fold All` / `Expand All` recover on the next caret move | 🔲 | |
| Background | The block background reaches the right edge of the editor, not just the end of each line | 🔲 | |

#### Phase H2 — The card: rounded painting, language chip, copy

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| H2.1 | `CodeBlockHighlighterRenderer : CustomHighlighterRenderer` — rounded rect, insets, hairline border | 🔲 | |
| H2.2 | Language chip and copy action as an after-line-end inlay on the header row | 🔲 | |
| H2.3 | Settings: `liveMarkupCodeBlocks`, `liveMarkupCodeBlockCard`, `liveMarkupCodeBlockCopy` | 🔲 | |
| H2.4 | Tests plus the soft-wrap and theme-switch check | 🔲 | |

**Steps (detail):**

- **H2.1 — Card.** Deliverable: `markdown/CodeBlockHighlighterRenderer.kt`, attached with `setCustomRenderer` on the H1.2 highlighter. Paints a `fillRoundRect` inset from the left gutter and stopping short of the right edge, plus a one-pixel border, with antialiasing on — the `DocRenderer.paint` shape. Colours are read from the scheme every paint (no caching across theme changes). `getOrder()` is deliberately not overridden so the plugin stays off the `@Experimental` `CustomHighlighterOrder`. When `liveMarkupCodeBlockCard` is off the renderer is simply not attached and H1's flat full-width background remains.
- **H2.2 — Chip and copy.** Deliverable: an after-line-end inlay on the header row rendering the language name in `CODE_FENCE_LANGUAGE` colours and a copy glyph; `getContextMenuGroup` exposes a "Copy code block" action; a click on the glyph copies the fence body **without** the backticks in one command. The inlay is created and disposed with the block, carries `showWhenFolded(true)`, and is skipped when the fence has no info string and `liveMarkupCodeBlockCopy` is off (nothing to show).
- **H2.3 — Settings.** Deliverable: three fields under the existing "Markdown live markup" group — `liveMarkupCodeBlocks` (on), `liveMarkupCodeBlockCard` (on), `liveMarkupCodeBlockCopy` (on) — applied to open editors through `AgenstormSettingsListener`, the way `liveMarkupBullets` already is. Remember the `compileTestKotlin --rerun-tasks` pitfall from CLAUDE.md after adding fields to `State`.
- **H2.4 — Tests.** Deliverable: renderer geometry asserted headlessly where possible (inset arithmetic, colour resolution against a scheme with and without a `CODE_FENCE` background); the painting itself and the soft-wrap/theme behaviour go to the guardrail checklist.

#### Phase H3 — Block quotes and thematic breaks

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| H3.1 | Collector emits `QUOTE_MARKER` ranges for each `>` plus its space | 🔲 | |
| H3.2 | Left rail per quote, one per nesting level | 🔲 | |
| H3.3 | `HORIZONTAL_RULE` folded and drawn as a full-width line | 🔲 | |
| H3.4 | Settings `liveMarkupQuotes`, `liveMarkupRules`; tests | 🔲 | |

**Steps (detail):**

- **H3.1 — Quote markers.** Deliverable: a `MarkdownTokenTypes.BLOCK_QUOTE` branch emitting one `QUOTE_MARKER` range per marker token plus the single space after it, with the enclosing `MarkdownElementTypes.BLOCK_QUOTE` element as span. `isBlock`, so a caret anywhere on the line brings the `>` back and the quote stays editable.
- **H3.2 — Rail.** Deliverable: a `LINES_IN_RANGE` highlighter over the quote element whose custom renderer draws a two-pixel rail at the text's left inset in `BLOCK_QUOTE_MARKER` colours. Nested quotes produce nested elements, hence one rail per level, offset by the indent the hidden markers used to occupy.
- **H3.3 — Rules.** Deliverable: the `HORIZONTAL_RULE` token folded to an empty placeholder, with a `LINES_IN_RANGE` highlighter on its line whose renderer draws a full-width one-pixel line in `HRULE` colours. `---` directly under a paragraph is a setext heading, not a rule — the parser already distinguishes them, so keying off the token is enough.
- **H3.4 — Settings and tests.** Deliverable: `liveMarkupQuotes` and `liveMarkupRules` (both on) and fixture coverage: nested quotes, a quote containing a fence, a quote containing a list, `---` / `***` / `___`, and a setext heading that must *not* be treated as a rule.

**Exit guardrails — Epic H → Epic I**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Obsidian parity (scoped) | `testData/markdown/parity.md` grows a fences / quotes / rules section; side-by-side with Obsidian the same things are hidden and drawn; the heading-size difference stays accepted | 🔲 | |
| Injection intact | A `php` fence highlights and navigates identically with the feature on and off | 🔲 | |
| Coexistence | The Markdown plugin's fence and quote folding still work; `Fold All` / `Expand All` recover on the next caret move | 🔲 | |
| Copy fidelity | Selecting across a card and copying yields the raw fence with its backticks; the copy glyph yields the body without them | 🔲 | |
| Themes and wrap | Card, rails and rules render correctly in Light and Dark and with soft wrap on | 🔲 | |
| Perf | 3,000-line file with 200 fences: sync after an edit < 50 ms, no visible lag while scrolling | 🔲 | |

---

### Epic I — Terminal output enhancers  ·  1.0

**Goal:** Output worth reading — `var_dump`, `print_r`, `var_export`, JSON, PHP stack traces — collapses to a one-line summary that expands on click, with a structured tree viewer for the nested ones, and users add their own enhancements as JSON rule files from the settings page. The engine is generic; the five built-ins are just the rules that ship with it.
**Success metrics:** a 10,000-line output scrolls with no visible lag; a malformed or catastrophic user regex can never hang the EDT nor corrupt plain output; turning the feature off restores a terminal indistinguishable from stock.

Same `terminal/` package and `agenstorm-terminal.xml` as Epic G.

Platform facts (verified against build 262):

- **`consoleFilterProvider` is the supported way to decorate terminal output, in every engine.** The EP `com.intellij.consoleFilterProvider` → `com.intellij.execution.filters.ConsoleFilterProvider` is stable public API declared in `lib/intellij.platform.ide.impl.jar!/META-INF/LangExtensionPoints.xml`. All three terminals funnel through `ConsoleViewUtil.computeConsoleFilters`; the reworked one does it in `org.jetbrains.plugins.terminal.hyperlinks.filter.CompositeFilterWrapper`, which also calls `ExtensionPointName.addChangeListener`, so filters can appear and disappear at runtime. The terminal registers its own `TerminalGenericFileFilterProvider` this way. Two constraints: the `ConsoleView` passed to providers is **`null`** (so `ConsoleDependentFilterProvider` is useless — implement plain `ConsoleFilterProvider`), and in split/remote-dev the filter runs on the backend.
- **A `Filter.ResultItem` can carry more than a link.** In the reworked terminal `HyperlinkProcessor` maps results to `TerminalHyperlinkInfo` (a `HyperlinkInfo` plus `TextAttributes` for normal, hovered and followed states), `TerminalHighlightingInfo` (attributes only, no click target) or `TerminalInlayInfo` (an `InlayProvider`).
- **The terminal never folds anything.** A scan of `terminal.jar` and all six `lib/modules/*.jar` finds zero references to `com.intellij.openapi.editor.FoldingModel`, and there is no folding extension point. The one output-highlighting EP, `org.jetbrains.plugins.terminal.exp.commandBlockHighlighterProvider`, is `@ApiStatus.Internal` and only wired into the deprecated 2024 block terminal.
- **But the reworked output is a real editor.** `com.intellij.terminal.frontend.view.impl.TerminalEditorFactory` (internal) creates an `EditorImpl` over a real `Document`, so it has a full `FoldingModel`, `MarkupModel` and `InlayModel`. The sanctioned way to recognise one is `org.jetbrains.plugins.terminal.block.util.TerminalDataContextUtils.isReworkedTerminalEditor(Editor)`. That class carries a class-level `@ApiStatus.Experimental` and **no** class-level `@ApiStatus.Internal`; several of its *other* members (`IS_PROMPT_EDITOR_KEY`, `IS_OUTPUT_MODEL_EDITOR_KEY`, `getOutputController`, `getPromptController`) are `@Internal`, but the `is*Editor` predicates are not — so using only those keeps Epic I off the §2 internal list. Reached from a plain `editorFactoryListener`, that gives Epic F's light-fold technique a target with no internal API at all. The risk is behavioural — the terminal trims its document as scrollback overflows (`new.terminal.output.capacity.kb`, default 1024) and runs its own `EditorTextDecorationApplier` — which is why I1.3 is a spike before anything is built on it.
- **Command lifecycle, if the detector ever needs it.** `org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandExecutionListener` (`@Experimental`) has `commandStarted` / `commandFinished`, whose `TerminalCommandBlock` carries `executedCommand`, `workingDirectory`, `exitCode`, `outputStartOffset` and `endOffset`. It is registered per session through `TerminalShellIntegration.addCommandExecutionListener`, reached via the topic `com.intellij.terminal.frontend.toolwindow.TerminalTabsManagerListener.TOPIC` on `TerminalView.getShellIntegrationDeferred()`. This needs `<module name="intellij.terminal.frontend"/>` in the descriptor's `<dependencies>`. Shipped precedent: `plugins/mcpserver/lib/modules/intellij.mcpserver.terminal.frontend.jar` registers a `projectListener` on exactly that topic. **Not used in v1** — the debounced document scan of I2.1 is enough, and this would add a second experimental surface; recorded here so the next reader does not have to find it again.
- `ConsoleFolding` (`com.intellij.execution.ConsoleFolding`, EP `com.intellij.console.folding`) exists and is public, but it is keyed on `isEnabledForConsole(ConsoleView)` and consumed by `ConsoleViewImpl` — the terminal is not a `ConsoleView`, so it does not apply here.

#### Phase I1 — Rules, detection, and the folding spike

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| I1.1 | `EnhancerRule` + `RuleParser`: JSON in, validated rules out, precise errors | 🔲 | |
| I1.2 | `BlockDetector`: text + rules → blocks, off the EDT, with a per-rule time budget | 🔲 | |
| I1.3 | **Spike** — can light fold regions live in a reworked-terminal output editor? Gates I2.1 | 🔲 | |

**Steps (detail):**

- **I1.1 — Rules.** Deliverable: `terminal/enhance/EnhancerRule.kt` and `RuleParser.kt`. A rule is `id`, `start` (regex matching the first line of a block), optional `end` (regex closing it; absent means the block is the single matched line), `render` (`fold` | `tree` | `json`), `summary` (a template over the `start` capture groups, e.g. `array({1}) …`), `enabled`, `maxLines` (hard cap, default 500). Parsed with the bundled `kotlinx.serialization.json` (decision 6). Every failure names the file, the field and what was expected — these are files humans hand-write.
  ```json
  { "id": "php-var-dump", "start": "^(array|object)\\((\\d+)\\)\\s*\\{$",
    "end": "^\\}$", "render": "tree", "summary": "{1}({2}) …", "maxLines": 500 }
  ```
- **I1.2 — Detector.** Deliverable: `terminal/enhance/BlockDetector.kt`: `detect(text: CharSequence, rules: List<EnhancerRule>, from: Int): List<EnhancedBlock>` where `EnhancedBlock` is `(ruleId, range, summary, payload)`. Runs off the EDT. **Regex safety is a first-class requirement, not a polish item:** the rules are user-written, so matching runs against a `CharSequence` whose `charAt` checks a deadline and throws, giving every rule a per-invocation time budget (the standard `Pattern` interruption idiom); a rule that blows its budget is disabled for the session and reported once. `from` lets the controller rescan only appended text. Fixtures for all five built-ins plus a deliberate catastrophic-backtracking rule.
- **I1.3 — Spike.** Deliverable: a §6 decision row, the way F1.4 produced decision 21. Attach to a reworked-terminal editor through `editorFactoryListener` filtered by `TerminalDataContextUtils.isReworkedTerminalEditor`, create a light fold region over a multi-line block, and record what happens on: further output appended below and inside, scrollback trimming past the region, `Clear` (Cmd+K), the terminal's own decoration pass, resizing the window, and switching to the alternate buffer (`less`, `vim`). Also record whether the placeholder is clickable and whether the region survives a `commandFinished`. If the answer is no, I2.1 is cut to ❌ and I2.3 becomes the whole rendering story — the epic still delivers, with a hyperlink instead of a fold.

**Exit guardrails — Phase I1 → I2**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Detector correctness | `BlockDetectorTest` green on all five built-in fixtures, including nested `var_dump` and a truncated block at end of output | 🔲 | |
| Regex safety | The catastrophic-backtracking fixture is cancelled inside its budget; the test asserts the detector returns and the rule is marked disabled | 🔲 | |
| Spike recorded | A decision row in §6 states plainly whether fold regions survive in the reworked terminal, with the observed behaviour per case | 🔲 | |

#### Phase I2 — Rendering

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| I2.1 | `TerminalEnhancerController`: light fold regions collapsed to the summary, per output editor | 🔲 | Gated by I1.3 |
| I2.2 | Structured tree viewer for `tree` and `json` renders | 🔲 | |
| I2.3 | `TerminalEnhancerFilterProvider : ConsoleFilterProvider` — the engine-independent floor | 🔲 | |
| I2.4 | The five built-in rules as plugin resources | 🔲 | |

**Steps (detail):**

- **I2.1 — Controller.** Deliverable: `terminal/enhance/TerminalEnhancerController.kt`, deliberately shaped like `LiveMarkupController`: attached per editor, debounced document listener, scan of the appended tail only, one light fold region per block created collapsed with the rule's summary as placeholder, a gutter icon, and the same `FoldingListener` re-apply. Differences from Epic F that the terminal forces: the document is append-only and trimmed, so regions below the trim point are dropped rather than recreated; and there is no caret policy — a terminal caret is the shell prompt, so regions expand only on an explicit click.
- **I2.2 — Viewer.** Deliverable: a tree popup rendering the parsed payload for `tree` and `json` rules — nested arrays and objects as expandable nodes, scalars with their type. Opened from the fold placeholder or the gutter icon, with copy-node and copy-subtree in its context menu.
- **I2.3 — Filter.** Deliverable: `terminal/enhance/TerminalEnhancerFilterProvider.kt` registered `<consoleFilterProvider …/>` in `agenstorm-terminal.xml`, implementing plain `ConsoleFilterProvider` (the `ConsoleView` is null). Its `Filter` highlights the first line of a matched block and attaches a `HyperlinkInfo` opening the I2.2 viewer. **Always on, in every engine** — this is what makes the epic degrade rather than fail in the classic terminal or if I1.3 came back negative. Its per-line nature is a real constraint: the filter recognises a block *opener* and hands the offset to the viewer, which reads the surrounding text itself.
- **I2.4 — Built-ins.** Deliverable: `resources/terminal/rules/*.json` — `php-var-dump`, `php-print-r`, `php-var-export`, `json-line`, `php-stack-trace` — loaded first and overridable by a user rule with the same `id`, so "edit a built-in" means "copy it and change it" without patching anything.

#### Phase I3 — User rules from the settings

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| I3.1 | Rule directory and the settings list | 🔲 | |
| I3.2 | Hot reload; a broken file is skipped, never fatal | 🔲 | |
| I3.3 | README section documenting the format | 🔲 | |

**Steps (detail):**

- **I3.1 — Directory and UI.** Deliverable: rules read from `PathManager.getConfigPath()/agenstorm/terminal-rules/*.json`, created on first use. The "Terminal" settings group gains a table of rules — id, source (built-in or file), enabled — with "Open folder", "Reload" and "Copy a built-in rule…" buttons. Disabled ids persist in `terminalEnhancerDisabledRules`.
- **I3.2 — Reload and failure.** Deliverable: a file watcher on the directory that reparses on change; a file that fails to parse produces exactly one balloon in the `Agenstorm` group naming the file and the error, is skipped, and leaves every other rule working. The bar is that no rule file can ever make the terminal worse than having the feature off.
- **I3.3 — Docs.** Deliverable: a README section with the field reference and two worked examples, plus the note that rules are regex-only by design and cannot run commands (§7 tracks the opt-in variant).

**Exit guardrails — Epic I → Release 1.0**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| It reads better | A real `var_dump()` of a nested array collapses to one line and expands to a readable tree | 🔲 | |
| Perf | 10,000 lines of output containing 200 matches: scrolling and typing stay smooth; the tail scan after each chunk stays off the EDT | 🔲 | |
| Hostile rule | A deliberately catastrophic regex dropped into the rules folder is cancelled, the rule disabled, one balloon shown, the terminal unaffected | 🔲 | |
| Broken file | A rule file with a syntax error is skipped with one balloon; the other rules still work | 🔲 | |
| Both engines | Classic engine degrades to the I2.3 filter path with no errors | 🔲 | |
| Off switch | Feature off: no regions, no highlights, no gutter icons, and the filter is not registered | 🔲 | |
| Log clean | `idea.log` has no `com.pronskiy.agenstorm` frames after a session of use | 🔲 | |

---

### Release 1.0  ·  after Epics G, H and I

**Goal:** A Marketplace-ready 1.0.0 built from `main`: version and change notes set, the verifier green on PhpStorm and IntelliJ IDEA 2026.2, the ZIP installed by hand once. Publishing itself is Roman's.

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| R1 | `pluginVersion = 1.0.0`; `CHANGELOG.md` `[Unreleased]` reviewed (it becomes the 1.0.0 section through `patchChangelog` at publish time); README plugin-description block reviewed against what shipped; CLAUDE.md inventory current | 🔄 | Done 2026-09-06 for the Epics 0–F build. To redo once G, H and I have landed: the changelog, the README description block and the CLAUDE.md inventory all gain the three new features |
| R2 | `./gradlew verifyPlugin` against the recommended PhpStorm and IntelliJ IDEA 2026.2 builds: Compatible on both; internal-API usages limited to the §2 list | 🔄 | Done 2026-09-06 for the Epics 0–F build; must be re-run after G, H and I (new optional dependency on the Terminal plugin, three new `@Experimental` usages, no new internal ones expected). The 2026-09-06 result, plugin 1.0.0: Compatible on PS-262.10315.130 and IU-262.10315.125. 15 internal usages, all from the §2 list (`ProjectToolbarWidgetAction` subclassing and overrides, `IdeFrameEx.setFileTitle`, `ScratchFileTypeFilter`, `GitBranchesTreePopupOnBackend.create`); 28 experimental usages (Symbol API, `PsiHighlightedReference`, the Markdown plugin's `MarkdownInlineLink` / `MarkdownLinkText`); 2 deprecated usages, both the Kotlin bridge for `StatusBarWidget.getPresentation(PlatformType)`. A third deprecated usage, `DaemonCodeAnalyzer.restart(PsiFile)`, was replaced by the overload with a reason. Reports in `build/reports/pluginVerifier/`; the ZIP in `build/distributions/agenstorm-1.0.0.zip` |
| R3 | `./gradlew buildPlugin`, install `build/distributions/agenstorm-1.0.0.zip` into a real PhpStorm 2026.2 from disk, open a project with Markdown, Git and PHP: every feature toggle visible in Settings, no SEVERE in `idea.log` after a short tour | 🔲 | Roman's, by hand |
| R4 | Marketplace: upload the ZIP (or `publishPlugin` with `PUBLISH_TOKEN`), release notes from the changelog, tag `v1.0.0` | 🔲 | Roman's |

**Exit guardrails — Release 1.0**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Epics closed | Epics G, H and I closed, their exit guardrails filled in, decisions 25–28 confirmed by Roman | 🔲 | |
| Verifier | Compatible on PS-262 and IU-262; no new internal usages beyond §2 | 🔄 | Was ✅ on 2026-09-06 for the Epics 0–F build (see R2); must be re-run once G, H and I have landed, since they add an optional dependency on `org.jetbrains.plugins.terminal` and three `@Experimental` usages |
| Fresh install | R3 tour clean; the plugin loads in IntelliJ IDEA without the PHP plugin (no PHP-only features shown), and in an IDE where the Terminal plugin is disabled | 🔲 | |
| Daily driver | Epics D and E daily-driver guardrails closed by Roman | 🔲 | |

---

## 5. Risk register

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Internal API drift (`FrameTitleBuilder` override, `scratchLanguageFilter`, `commentsReferenceProvider`, `ProjectToolbarWidgetAction`) breaks a feature in 2026.3+ | Med | Med | Each feature isolated behind a toggle and an optional config file; `verifyPlugin` against EAP in CI; fallbacks documented per epic (B1.1, E1.5) |
| Light fold regions interfere with the Markdown plugin's folding or with other plugins' regions (fold-tree crossing) | Med | High | F1.4 spike before F2; our regions are tiny and nested; re-sync on `FoldingListener` events; kill-switch per editor |
| Live markup makes typing feel laggy | Med | High | Debounced sync (200 ms), caret policy only on line-set change, `ReadAction.nonBlocking`-style collection off EDT, perf guardrail |
| Project-tabs widget starves other toolbar widgets of width | Med | Med | E2.1 overflow modes; width cap at 50% of toolbar |
| macOS fullscreen: separate windows live in separate Spaces, switching animates | High | Low–Med | Document as a limitation; skip bounds mirroring in fullscreen; recommend maximized non-fullscreen windows |
| LLM output format drift (fences, preambles, over-long subjects) | High | Low | `MessagePostProcessor` + strict system prompt; user-editable prompts |
| `claude` CLI flags change | Med | Low | "Extra CLI arguments" setting; verify `--help` at implementation; error surface shows stderr |
| Diff too large / too slow to build on huge change sets | Med | Med | Budget + ranking in `DiffCollector`; background thread; cancellable progress |
| False-positive location links (`10:20:30`, URLs with ports) | Med | Low | Parser corpus tests; soft references (no error highlighting) |
| Marketplace rejection for internal API usage | Low | Med | Warnings are tolerated; keep the list in §2 short and each usage guarded |
| The two `@Experimental` terminal hooks (`ShellExecOptionsCustomizer`, `TerminalDataContextUtils.isReworkedTerminalEditor`) change shape in 263 | High | Med | Both sit behind the Epic G / Epic I toggles and a `LinkageError` catch; Epic I already ships the `consoleFilterProvider` path (stable API) as its floor, so only the folding refinement is exposed. The reworked terminal is new and JetBrains is still moving it — expect to re-verify at every platform bump |
| Shadowing `open` surprises a user whose script depends on macOS `open` | Med | Med | The shim claims only bare existing paths: flags, URLs, missing paths and no-argument calls exec the real binary, and the router can decline with `409` so the real binary still runs. Off by one toggle, and a first-run balloon says so. Only inside IDE terminals — the user's own shells are untouched |
| A user's enhancer regex makes the terminal crawl | Med | Med | Matching runs off the EDT against a deadline-checking `CharSequence`, so a catastrophic pattern is cancelled rather than survived; the offending rule is disabled for the session with one balloon. Guardrail I2 covers it with a deliberately hostile fixture |

---

## 6. Decision log

| # | Date | Decision | Context | Decided by |
|---|------|----------|---------|------------|
| 1 | 2026-09-05 | Build one plugin, `com.pronskiy.agenstorm`, public on JetBrains Marketplace, MIT | Personal tooling first, but nothing here is proprietary; public forces discipline about feature toggles and fallbacks | Roman |
| 2 | 2026-09-05 | Target PhpStorm 2026.2 (build 262) only for 1.0 | Matches the platform branch the spec was researched on; widen later if trivial | Roman |
| 3 | 2026-09-05 | All six features are MVP; order 0 → A → B → C → D → E → F | Value per hour, riskiest last | Roman |
| 4 | 2026-09-05 | Location links use bare `path:line[:col]`; no custom URL scheme, no Toolbox dependency | `jetbrains://` is registered by Toolbox, not the IDE; agents already emit the bare form; must work in the current project without extra tooling | Roman |
| 5 | 2026-09-05 | Commit messages: subject + body; backends Anthropic, OpenAI-compatible, `claude -p`; Apple Foundation Models postponed | On-device model is limited to a 4k context; PCC via macOS 27 `fm --model pcc` is interesting but not shipped yet; `claude -p` gives a frontier model with zero setup | Roman |
| 6 | 2026-09-05 | No LLM SDKs or third-party HTTP/JSON libraries | The bloat and slowness of the existing ai-commits plugin come from langchain4j; JDK `HttpClient` + bundled `kotlinx.serialization` suffice | Roman |
| 7 | 2026-09-05 | File names removed from the window title regardless of Epic E | Epic E may fail; Epic C is 20 lines | Roman |
| 8 | 2026-09-05 | Project tabs: own widget in `MainToolbarLeft`, native macOS window tabs disabled via registry; not re-parenting `WindowTabsComponent` | `MacWinTabsHandlerV2` requires its component to stay the single child of its container; hiding the toolbar does not remove the tab row either | Roman |
| 9 | 2026-09-05 | Markdown live markup: hide markers + style text; heading font sizes out of scope | Editor has one line height; block-inlay headings would be a different, much larger project | Roman |
| 10 | 2026-09-05 | Live markup uses manually created light fold regions, not a `FoldingBuilder` | Builder regions shorter than 2 chars are dropped by `UpdateFoldRegionsOperation`; light regions survive folding passes and can be 1 char | Roman (from code walk) |
| 11 | 2026-09-05 | Extending internal `ProjectToolbarWidgetAction` via `overrides="true"` is acceptable for 1.0 | Gives feature-off = stock widget without restart; verifier warning tolerated; fallback documented in E1.5 | Roman |
| 12 | 2026-09-05 | No default keyboard shortcuts for new actions | Marketplace etiquette; README recommends bindings | Roman |
| 13 | 2026-09-05 | "Loads without optional deps" is verified against the unified IntelliJ IDEA 2026.2 (no PHP plugin) through `verifyPlugin`, which is now a permanent verifier target | IntelliJ IDEA Community's last release is 2025.3, so no 262 build exists; the unified IDEA also lacks the PHP plugin and exercises the same optional-dependency path | Roman (confirmed after the Phase 01 report) |
| 14 | 2026-09-05 | Scratch allow-list keeps the platform's file-type-level filter; JavaScript dialects (ActionScript, ECMAScript 6) that share the JavaScript file type stay visible and this is documented | `scratchLanguageFilter` receives a `FileType`, not a `Language`; the only alternative is replacing the `NewScratchFile` action (package-private stock class, own popup, no LRU order, no selection-based language detection), judged not worth it | Roman |
| 15 | 2026-09-05 | Generated commit message bodies are not hard-wrapped; paragraphs stay on one line each | Roman's request after the first real generations; wrapped bodies read badly in the commit UI and in tools that reflow text. The default system prompt asks for unbroken paragraphs; the post-processor no longer wraps | Roman |
| 16 | 2026-09-05 | The Claude CLI backend disables extended thinking through the `MAX_THINKING_TOKENS=0` environment variable rather than a flag, and skips MCP servers with `--strict-mcp-config` in the default extra args | Roman found generation "quite slow": a one-line diff took 20–50 s on Haiku, almost all of it thinking. No CLI flag turns thinking off (`--effort low` had no effect; `--settings '{"alwaysThinkingEnabled":false}'` works but needs JSON quoting inside the extra-args field), so the backend sets the documented env var, overridable through its `environment` parameter. Result: ~3 s end to end | Claude, confirmed by Roman's report |
| 17 | 2026-09-05 | The Claude CLI backend runs with `--safe-mode` by default, so the user's CLAUDE.md, plugins, skills, hooks and MCP servers stay out of commit-message generation | Measured per call with the plugin's prompt: ~6,000 prompt tokens (CLAUDE.md files, a plugin's session hook, 66 skills) versus 775 in safe mode, $0.012 versus $0.003 on Haiku, same ~3 s. Message style now comes only from the plugin's prompt and settings, which makes output identical across machines; users who want their CLAUDE.md rules applied remove the flag in the Extra arguments field. `--bare` was rejected because it refuses OAuth logins; `--disable-slash-commands` and `--setting-sources local` remove only part of the context | Roman |
| 18 | 2026-09-05 | Project tabs keep extending the internal `ProjectToolbarWidgetAction` (no standalone fallback) | The verifier reports the subclassing as internal-API usage but stays Compatible on PhpStorm and IntelliJ IDEA 2026.2; Marketplace tolerates warnings of that kind, and `failureLevel` excludes them on purpose. Extending the stock action is what makes feature-off identical to stock. The E1.5 fallback (own `CustomComponentAction` + minimal dropdown) stays documented for the day the class becomes final or the usage becomes an error | Claude (proposed), confirmed by Roman 2026-09-05 |
| 19 | 2026-09-05 | With project tabs on, the Git branch leaves the main toolbar and takes the bottom-left of the status bar, replacing the navigation bar (breadcrumbs) | Roman, reviewing Epic E: the toolbar branch widget "does not make sense" next to project tabs, "where it does make sense is in the bottom toolbar, left corner, instead of breadcrumbs". The stock status-bar widget is unavailable while the new toolbar is shown, so the plugin ships its own (Phase E3); the toolbar VCS group is hidden by overriding it; both flips are reversible from the same toggle | Roman |
| 20 | 2026-09-06 | Live markup attaches to editors through the public `EditorFactoryListener` (`com.intellij.editorFactoryListener`), not the `textEditorCustomizer` named in the Epic F plan | `TextEditorCustomizer` turned out to be `@ApiStatus.Internal` (and `@OverrideOnly`) in build 262 and is not on the §2 list. The listener gives the same per-editor lifecycle (`editorCreated`/`editorReleased`), the Markdown plugin itself uses one, and it keeps Epic F free of internal API. Eligibility: this project's editor, kind `MAIN_EDITOR` or `UNTYPED`, Markdown file type, feature on; consoles, diff viewers and previews are excluded by kind | Claude (proposed), confirmed by Roman 2026-09-06 with the Phase F1 sign-off |
| 21 | 2026-09-06 | Live-markup fold regions need no defence beyond a `FoldingListener` that re-applies the caret policy after foreign batches | F1.4 spike, all observed in `LiveMarkupControllerTest` against build 262. (a) The Markdown plugin's folding pass (`CodeFoldingManager.updateFoldRegions`) and the highlighting passes leave all 36 light regions of the fixture untouched, valid and collapsed, and its own heading/list/fence/table regions appear alongside. (b) `Expand All` expands our regions too and `Collapse All` collapses the caret line's — both are foreign batches, so `onFoldProcessingEnd` re-applies the policy and the file looks right again on the next event loop turn; foreign regions keep whatever the action did to them. (c) Typing at a region border, an outside edit at a collapsed region's border, `Reformat` and two `Undo`s all leave the regions equal to the collector's output with the caret line open. (d) `beforeFoldRegionRemoved` on one of ours (a model rebuild) asks for a re-sync. No region ever had to be recreated defensively | Claude (spike), confirmed by Roman 2026-09-06 with the Phase F1 sign-off |
| 22 | 2026-09-06 | Link navigation from visible link text is a `GotoDeclarationHandler` on inline-link text, not an editor mouse handler; heading anchors are matched by `MarkdownHeader.anchorText` | `MarkdownLinkText` is neither a `PsiExternalReferenceHost` nor a `ContributedReferenceHost`, so no reference of either API can sit on it, and the platform's `NavigationService` / `SymbolNavigationService` / `SourceNavigationRequest` are all `@ApiStatus.Internal`. The handler EP is public (Epic A uses it already) and plugs into the platform's Ctrl+click, Ctrl+B and Ctrl-hover underline. For the hidden destination it returns: a `WebReference` target for URLs (browser), Epic A's `FileLocationSymbol` (which now also implements the public `Navigatable`, opening the file in the project that owns it), the `MarkdownHeader` whose anchor matches `path#anchor` (the plugin resolves anchors to header symbols only its internal service can open), and the Markdown plugin's own file references restricted to the one reaching the end of the path | Claude (proposed), confirmed by Roman 2026-09-06 with the Epic F sign-off |
| 23 | 2026-09-06 | The live-markup sync replaces any foreign fold region that sits exactly on a range it wants | Found by Roman in the Epic F review ("folded elements in markdown don't unfold ever"): the second sandbox session started with the first one's persisted folding state, so every marker came back as an ordinary collapsed region without our user data, which nothing expanded, and our own region for the range was rejected as a duplicate. The sandbox workspace files held 266 such `<marker>` entries, all with an empty placeholder. There is no public way to keep a region out of the persisted state (`TRANSIENT_KEY` and `SIGNATURE` are private, `DocumentFoldingInfo` is package-private), so the controller heals instead: a foreign region on a wanted range is removed before ours is created, and a restore landing on existing regions only collapses them, which the caret policy undoes on the next event-loop turn. Covered by two tests that save and restore the state through `CodeFoldingManager` | Claude (fix), confirmed by Roman 2026-09-06 (second review round) |
| 24 | 2026-09-06 | Inline markup is revealed per element (caret inside or touching the element, or a selection over it); heading, bullet and checkbox markers stay per line; the Phase F2 whole-line behaviour remains available as a setting | Roman at the Epic F sign-off: per-line reveal "is fine for headers, because they take the entire line, but for inline elements like links it's not perfect". The survey in Phase F3 shows per-element reveal is what Obsidian, Typora, Bear and org-appear do; only Neovim's render-markdown reveals the cursor row, for lack of a better primitive. Touching counts because a caret cannot enter a collapsed fold region: opening on contact is what keeps arrow keys from skipping the opening marker | Roman |
| 25 | 2026-09-06 | `open` in the terminal is intercepted by a generated shell shim on a PATH entry injected through `ShellExecOptionsCustomizer`, not by the IDE-side `TerminalShellCommandHandler` | The reworked terminal is the 2026.2 default (`TerminalOptionsProvider$State` initialises `TerminalEngine.REWORKED`) and the EP's only driver, `TerminalShellCommandHandlerHelper`, is constructed solely by the classic `ShellTerminalWidget` and highlights via JediTerm's `TerminalLineIntervalHighlighting` — the EP is dead under the default engine. Even in Classic it fires from `matchedExecutor(KeyEvent)`, i.e. the Run/Debug shortcut, so plain `open foo.php` + Enter would still reach macOS. A shim is engine-independent, works on plain Enter, and also catches `open` run by an agent inside the terminal. `MutableShellExecOptions.prependEntryToPATH` routes through `_INTELLIJ_FORCE_PREPEND_PATH`, so the entry survives a user's `.zshrc` rebuilding PATH. JetBrains does the same thing in remote dev (`UnattendedHostOpenLinkScriptHolder`) | Claude (proposed), confirmed by Roman 2026-09-06 |
| 26 | 2026-09-06 | The shim talks to a per-project loopback `com.sun.net.httpserver.HttpServer` over `POST /open` with a NUL-separated body, not to the IDE's built-in web server and not over a query string | A per-project endpoint is what binds a terminal to *its* window, which is the whole feature; the built-in server is global and would need the project resolved from the request. `com.sun.net.httpserver` is in the JBR and already used by this project's tests, so nothing is added to the dependency list (decision 6). A NUL-separated body removes URL encoding from a POSIX `sh` script entirely and survives paths with spaces, quotes and newlines; the token goes in a header so it never appears in `ps`. Transport is `curl`, which macOS ships; no `curl` means the shim execs the real `open` | Claude (proposed), confirmed by Roman 2026-09-06 |
| 27 | 2026-09-06 | Terminal output enhancement is built in two layers: `consoleFilterProvider` for highlighting and hyperlinks (always on, every engine) and light fold regions on the reworked output editor for collapsing (gated by the I1.3 spike) | `ConsoleFilterProvider` is stable public API and all three engines funnel through `ConsoleViewUtil.computeConsoleFilters`, so the floor works everywhere and cannot regress. Collapsing is what the feature is actually for, but the terminal has no folding at all — zero `FoldingModel` references across `terminal.jar` and its six module jars, and no EP — so it has to be done from outside, through `TerminalDataContextUtils.isReworkedTerminalEditor` (`@Experimental`, not internal) plus the fully public `editor.foldingModel`. That is legal but unproven against the terminal's document trimming and its own decoration pass, hence a spike before anything is built on it, exactly like F1.4 | Claude (proposed), confirmed by Roman 2026-09-06 |
| 28 | 2026-09-06 | Enhancer rules are declarative JSON — a regex plus a named built-in renderer — and never execute user code | Agenstorm is a public Marketplace plugin, and a rule format that shells out would run arbitrary commands over whatever happens to be printed in a terminal. Declarative rules stay safe to hot-reload, safe to share, and testable without an IDE. The cost is expressiveness, which is why the external-command variant is kept as an opt-in question in §7 rather than being dropped. The safety burden that remains is regex cost, handled by the per-rule deadline in I1.2 | Claude (proposed), confirmed by Roman 2026-09-06 |

---

## 7. Open questions

- [ ] Default models per backend at implementation time (Anthropic and OpenAI model ids change; pick the current mid-tier default and keep it a free-text setting).
- [ ] Should `DiffCollector` honour a project-level ignore file (e.g. `.aiignore` / `.agenstormignore`) for files that must never leave the machine? Default: no, keep v1 simple; revisit after daily use.
- [ ] Does `claude -p` on the author's machine accept `--max-turns 1` / `--tools ""` style flags to guarantee a tool-free single response? Verify during D2.3; otherwise rely on the prompt.
- [x] ~~**Live markup, reveal granularity:** the caret policy reveals every hidden marker on the caret line; Obsidian and the other hybrid editors reveal inline elements one at a time.~~ Resolved 2026-09-06: Phase F3, decision 24.
- [ ] Should live markup also render images (`![alt](src)`) — as `🖼 alt` placeholder or as a block inlay? Deferred; not in 1.0. Epic H deliberately left this out when it took on fences, quotes and rules.
- [ ] Should Epic H also give indented code blocks (`MarkdownElementTypes.CODE_BLOCK`) the card treatment? They need no markers hidden, so it is only the background — cheap once H1 exists. Left out of 1.0 on purpose; revisit after use.
- [ ] **Epic I, external-command rules:** should a rule be allowed to name a command that the matched block is piped through, its output replacing the block? Rejected for 1.0 by decision 28 (arbitrary execution over terminal output). If it comes back, it needs an explicit opt-in toggle that is off by default, a per-rule confirmation on first use, and a note in the Marketplace description.
- [ ] **Epic G, `ProjectUtil` API status:** `com.intellij.ide.impl.ProjectUtil` carries an `@ApiStatus.Internal` in its class file, but it appears to attach to `openExistingDir` / `FolderOpeningMode` rather than the class. G2.2 must settle this before using `openOrImportAsync`; if the class really is internal, the step is ⏸️ and the alternative (or dropping directory support) becomes a decision, not a guess.
- [ ] **Epic G on Windows:** the shim is POSIX `sh`, so `TerminalOpenExecOptionsCustomizer` no-ops on Windows. Worth an `open.cmd` / PowerShell function later? Default: no, macOS-first (same stance as Epic A's drive paths).
- [ ] **Epic G outside the IDE:** should the plugin offer to install the shim into the user's own shell profile, so `open path:line` works in iTerm too? It would need a stable port or a discovery file, and it takes the "only inside IDE terminals" safety argument away. Default: no.
- [ ] Epic E on Windows/Linux: the widget works, but is it wanted there (native tabs do not exist)? Default: available, off by default outside macOS.
- [ ] Should `Copy Location Link` also offer `path:line:col` relative to the *repository* root vs. content root when they differ (monorepos)? Default: content root; decide after use.
- [x] ~~**Epic B, dialects:** the scratch popup lists *languages*, but `scratchLanguageFilter` filters by *file type*; JavaScript dialects map to the JavaScript file type and stay whenever JavaScript is allowed.~~ Resolved 2026-09-05: accept and document (decision 14).

---

## How to Update This Document

This spec is the source of truth for the build. Keep it current as work happens:

- **Status markers.** Update a step's status in its tracker table as you go: 🔲 → 🔄 → ✅. Use ⏸️ for blocked (note why in Notes) and ❌ for cut (leave the row; the strikethrough of history is useful).
- **Current focus.** Keep the pointer at the top aimed at the next actionable 🔲 step. Update it the moment you finish a step or cross a phase boundary — a stale pointer is worse than none, since it sends the next reader to the wrong place.
- **Guardrails.** When you hit a phase boundary, fill the **Actual outcome** column with what really happened and set the guardrail status. Don't advance to the next phase until its entry guardrails pass — or log a decision explaining why you're proceeding anyway.
- **Decisions.** Any non-trivial choice made during the build gets a new row in the Decision Log (§6). It's append-only — reversals are new rows, not edits. If the choice changes the architecture, also update the Technical Decisions snapshot (§2).
- **Spec changes.** Structural changes (new epic, re-scoped phase) get a Changelog row at the top. Keep the executive summary honest if the project's shape shifts.
- **Open questions.** When one resolves, strike it from §7 and log the decision in §6.

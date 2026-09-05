# Agenstorm — Technical Spec

**Author:** Roman Pronskiy · **Created:** 2026-09-05

> 📄 **This is a living document.** Status markers, decisions, and guardrail outcomes are meant to be updated as the work happens. See [How to Update This Document](#how-to-update-this-document) before editing.

### Changelog

| Date | Change | Author |
|------|--------|--------|
| 2026-09-05 | Initial spec created from the brainstorm + code walk of the IntelliJ Platform (build 262) | Roman Pronskiy |

### Status legend

🔲 Not started · 🔄 In progress · ✅ Done · ⏸️ Blocked · ❌ Cut

### Current focus

**Now on:** Epic 0 → Phase 01 exit guardrails — verify Builds clean / Verifier green / Loads without optional deps, then the human check of Settings visible in `runIde`; Epic A → Phase A1 → step A1.1 follows.

---

## 1. Executive summary

Agenstorm is an open-source (MIT) PhpStorm plugin that removes the friction an agent-heavy workflow hits in the IDE every day: it makes `path/to/file.php:42:7` locations clickable everywhere (Markdown, comments, PHP strings), generates commit messages with a modern LLM (streaming, HTTP or `claude -p`, no SDK bloat), trims the New Scratch File popup to the four languages that matter, keeps file names out of the window title and project tabs, renders project tabs inside the main toolbar so the window loses a row of chrome, and gives Markdown an Obsidian-style "live markup" mode where the syntax hides itself until the caret lands on the line. It is built for one opinionated user first (the author) but ships on JetBrains Marketplace, so every feature is independently toggleable and degrades gracefully when a platform hook is missing.

---

## 2. Technical decisions

| Area | Decision | Rationale |
|------|----------|-----------|
| Language | Kotlin (JVM target as required by the 2026.2 platform — verify with the Gradle plugin; 21 as of 2025.x) | Platform is Kotlin-first; coroutines APIs (`readAction`, `Dispatchers.EDT`) are the modern way to do background work |
| Build | IntelliJ Platform Gradle Plugin 2.x, Gradle Kotlin DSL, `phpstorm("2026.2")` as the target | Standard toolchain; `verifyPlugin` and `runIde` come for free |
| Target IDE | PhpStorm 2026.2, `since-build="262"`, `until-build="262.*"` | Matches the platform branch the spec was researched against; every EP referenced exists there |
| Plugin id / package | `com.pronskiy.agenstorm` | Author's namespace |
| Module layout | One Gradle module, one plugin, feature packages `links`, `scratch`, `frame`, `commit`, `tabs`, `markdown`; optional dependencies wired through `<depends optional="true" config-file="…">` | Keeps a single artifact while letting the plugin load in IDEA/WebStorm without PHP/Markdown |
| Dependencies | `com.intellij.modules.platform`, `com.intellij.modules.vcs`; optional: `com.jetbrains.php`, `org.intellij.plugins.markdown`, `Git4Idea` | Only what each feature needs; no third-party runtime libraries |
| HTTP / JSON | `java.net.http.HttpClient` (SSE via `BodyHandlers.ofLines()`); JSON via `kotlinx.serialization.json` bundled with the platform (`compileOnly`), Gson as fallback if the bundled artifact is unavailable | Zero extra jars; this is the whole reason not to use langchain4j |
| Secrets | `PasswordSafe` via `CredentialAttributes(generateServiceName("Agenstorm", backendId))` | Never put API keys into `PersistentStateComponent` XML |
| Settings | One app-level `AgenstormSettings : PersistentStateComponent` (`agenstorm.xml`) + one `Configurable` under Tools → Agenstorm with a group per feature and an on/off switch per feature | One place to find everything; each feature can be disabled by users who hit a conflict |
| Internal API usage | `FrameTitleBuilder` override, `scratchLanguageFilter`, `commentsReferenceProvider`, registry write for macOS window tabs — all guarded by null checks / `Registry.is` and a feature toggle | Public Marketplace plugin: a missing hook must log and no-op, never throw |
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
                       └──────────────────────────────────────────────────────────┘
```

Every feature is a leaf: it registers its own extensions in its own optional `config-file`, reads `AgenstormSettings`, and never depends on another feature. `core/` is the only shared code. The links feature is pure PSI/Symbol API; commit is an action plus a streaming pipeline; tabs is Swing; markdown is editor-model manipulation.

---

## 4. Epics

All epics are MVP. Recommended order: 0 → A → B → C → D → E → F (value per hour of work, and the last two are the riskiest).

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
| Builds clean | `./gradlew buildPlugin` produces a zip; no deprecation errors from the Gradle plugin | 🔲 | |
| Verifier green | `verifyPlugin` reports no compatibility problems against PhpStorm 2026.2 | 🔲 | |
| Settings visible | `runIde` → Settings → Tools → Agenstorm shows six toggles; toggling persists across restart in `agenstorm.xml` | 🔲 | |
| Loads without optional deps | Plugin loads in IntelliJ IDEA Community (no PHP plugin) without errors in `idea.log` | 🔲 | |

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
| A1.1 | `FileLocation` model + `FileLocationParser` (regex, negative cases) with unit tests | 🔲 | |
| A1.2 | `FileLocationResolver`: containing dir → project base → content roots → unique basename | 🔲 | |
| A1.3 | `FileLocationSymbol` (`NavigatableSymbol`) + `FileLocationSymbolReference` (`PsiHighlightedReference`) | 🔲 | |
| A1.4 | `MarkdownLocationReferenceProvider` registered for `MarkdownLinkDestination` in `agenstorm-markdown.xml` | 🔲 | |
| A1.5 | Tests: resolve + navigation offset for `.md` fixtures | 🔲 | |

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
| Parser corpus | 100% of positive/negative corpus cases pass in `FileLocationParserTest` | 🔲 | |
| Markdown navigation | In `runIde`, Cmd+click on `[x](src/Foo.php:3:5)` opens `Foo.php` with caret at line 3 col 5; hover shows underline | 🔲 | |
| No regressions | `[y](other.md#heading)` still resolves via the Markdown plugin; no duplicate highlight | 🔲 | |

#### Phase A2 — Comments, PHP strings, Copy Location Link

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| A2.1 | `CommentLocationReferenceProvider` (`PsiReferenceProvider`) under `referenceProviderType key="commentsReferenceProvider"` | 🔲 | |
| A2.2 | `FileLocationPsiReference` (old API): `PsiReferenceBase` + `HighlightedReference`, resolves to a `Navigatable` fake element | 🔲 | |
| A2.3 | `PhpStringLocationReferenceContributor` for `StringLiteralExpression` in `agenstorm-php.xml` | 🔲 | |
| A2.4 | `CopyLocationLinkAction` (editor popup + gutter popup): copies `relpath:line[:col]`; with selection copies `relpath:line:col` of selection start | 🔲 | |
| A2.5 | Tests for comments (PHP `//`, `/* */`, PHPDoc; Kotlin/JS comment in a plain-text-like fixture) and PHP strings | 🔲 | |

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
| All hosts | In `runIde`: link highlighted + navigable in a PHP `//` comment, a PHPDoc block, a PHP string, a JS comment, and a Markdown link | 🔲 | |
| Performance | Opening a 5,000-line PHP file with 200 comments shows no `HyperlinkAnnotator` slow-annotator warning in `idea.log`; no freeze | 🔲 | |
| Dumb mode | With indexing in progress, links resolve via the relative/base-dir paths; the basename fallback is skipped without exceptions | 🔲 | |
| Copy Location Link | Action visible in editor popup; clipboard content is `src/Foo.php:42:7` for caret at line 42 col 7 | 🔲 | |

---

### Epic B — New Scratch File allowlist  ·  MVP

**Goal:** The New Scratch File popup shows only Plain text, Markdown, PHP and JavaScript (configurable).
**Success metrics:** Popup shows exactly the allowlisted types; feature off → platform default list.

Platform fact: `com.intellij.scratchLanguageFilter` EP (`platform/lang-impl/src/com/intellij/ide/scratch/ScratchFileTypeFilter.kt`, added 2026-03 — `@ApiStatus.Internal`) is consulted in `ScratchImplUtil.buildLanguagesPopup` via `ScratchFileTypeFilter.isEnabled(fileType)`.

#### Phase B1 — Filter

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| B1.1 | `AllowlistScratchFilter : ScratchFileTypeFilter` reading `settings.scratchAllowedFileTypes` | 🔲 | |
| B1.2 | Settings UI: multi-line list of `FileType.name`s with an "Add current file's type" helper; defaults `PLAIN_TEXT, Markdown, PHP, JavaScript` | 🔲 | |
| B1.3 | Test: `ScratchFileTypeFilter.isEnabled(PhpFileType)` true, `isEnabled(JsonFileType)` false, feature off → all true | 🔲 | |

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
| Popup content | `runIde` → File → New → Scratch File shows exactly Text, Markdown, PHP, JavaScript (plus the "from selection" extra when applicable) | 🔲 | |
| Toggle | Disabling the feature restores the full list without restart | 🔲 | |

---

### Epic C — Window title without file names  ·  MVP

**Goal:** The frame title (and therefore native macOS project tabs, Mission Control, Dock) shows only the project title, never the current file.
**Success metrics:** Title equals `FrameTitleBuilder.getProjectTitle(project)` output for any open editor; disabling the toggle restores the default on the next title update.

Platform facts: `FrameTitleBuilder` is an application service registered `open="true"` (`platform/platform-resources/src/META-INF/PlatformExtensions.xml`); `ProjectFrameHelper.updateTitle` concatenates project title, file title (from `EditorsSplitters.updateFrameTitle` → `getFileTitleAsync`) and `TitleInfoProvider`s, skipping blank parts (`appendTitlePart`). `FilenameToolbarWidgetAction` (header file-name widget) only shows when editor tabs are hidden, so returning `""` does not break it in the default layout.

#### Phase C1 — Service override

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| C1.1 | `ProjectOnlyFrameTitleBuilder : PlatformFrameTitleBuilder` returning `""` for file titles when enabled | 🔲 | |
| C1.2 | Register with `overrides="true"`; settings toggle "Hide file name in window title" (default on) | 🔲 | |
| C1.3 | Manual checklist + a unit test that `getFileTitle` returns `""`/default depending on the toggle | 🔲 | |

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
| Title | With `Foo.php` open, the window title is the project name only; Mission Control shows the same | 🔲 | |
| Native tabs | With native macOS project tabs still on (Epic E not yet applied), tabs show project names only | 🔲 | |
| No side effects | File-type icon in the title bar (`ide.show.fileType.icon.in.titleBar`) still works; "Show full paths in window header" setting still affects the project part | 🔲 | |

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
| D1.1 | `LlmBackend` interface + `LlmRequest`/`LlmChunk` model; `FakeBackend` for tests | 🔲 | |
| D1.2 | `DiffCollector`: changes → ranked, budgeted unified diff + stat | 🔲 | |
| D1.3 | `PromptBuilder` with templates and `{diff} {stat} {branch} {hint} {language}` variables | 🔲 | |
| D1.4 | `GenerateCommitMessageAction` in `Vcs.MessageActionGroup`: run/stop toggle, streaming into `CommitMessage`, single undo group | 🔲 | |
| D1.5 | `MessagePostProcessor`: strip fences/prefixes, enforce subject length, wrap body at 72 | 🔲 | |
| D1.6 | Tests for D1.2, D1.3, D1.5 with `FakeBackend` | 🔲 | |

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
| Streaming works end-to-end | With `FakeBackend` selected in `runIde`, clicking the action streams text into the field and Undo removes it in one step | 🔲 | |
| Never blocks EDT | No "UI freeze" report in `idea.log` during diff collection of a 50-file change set | 🔲 | |
| Budget honoured | `DiffCollectorTest` proves output ≤ `maxDiffChars` with a lock file and a 100 KB generated file present | 🔲 | |

#### Phase D2 — Backends

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| D2.1 | `AnthropicBackend`: Messages API, SSE, `content_block_delta` text deltas, API key from `PasswordSafe` | 🔲 | |
| D2.2 | `OpenAiCompatibleBackend`: `POST {baseUrl}/chat/completions` with `stream:true`, SSE `choices[0].delta.content`, optional key | 🔲 | |
| D2.3 | `ClaudeCliBackend`: `claude -p` subprocess, prompt on stdin, text output; executable discovery | 🔲 | |
| D2.4 | Shared `SseReader` over `HttpClient.sendAsync(..., BodyHandlers.ofLines())` with cancellation | 🔲 | |
| D2.5 | Backend tests against a local `com.sun.net.httpserver.HttpServer` serving canned SSE; CLI test with a fake `claude` script | 🔲 | |

**Steps (detail):**

- **D2.4 (do first) — SseReader.** Deliverable: `commit/llm/SseReader.kt`: turns `Flow<String>` of raw lines into `Flow<SseEvent(event: String?, data: String)>`; handles `data:` continuation lines, `[DONE]`, blank-line delimiters; propagates HTTP ≥ 400 as `LlmException` with the response body's `error.message` when present. Cancellation of the collecting coroutine must cancel the `CompletableFuture` from `sendAsync`.
- **D2.1 — Anthropic.** Deliverable: `commit/llm/AnthropicBackend.kt`. `POST https://api.anthropic.com/v1/messages`, headers `x-api-key`, `anthropic-version: 2023-06-01`, body `{model, max_tokens, system, messages:[{role:"user", content}], stream:true}`; emit `delta.text` from `content_block_delta` events; stop on `message_stop`. Default model: the current Sonnet at implementation time (free-text setting, do not hard-code a list). JSON via `kotlinx.serialization.json` `JsonObject` building/parsing (no data-class schema needed).
- **D2.2 — OpenAI-compatible.** Deliverable: `commit/llm/OpenAiCompatibleBackend.kt`. `baseUrl` default `https://api.openai.com/v1`; works unchanged for Ollama (`http://localhost:11434/v1`), LM Studio, OpenRouter, Groq. Body `{model, stream:true, messages:[{role:"system"},{role:"user"}]}`; emit `choices[0].delta.content`; `Authorization: Bearer` only if a key is stored.
- **D2.3 — Claude CLI.** Deliverable: `commit/llm/ClaudeCliBackend.kt`. Discovery order: settings path → `PATH` (`PathEnvironmentVariableUtil.findInPath("claude")`) → `~/.claude/local/claude`, `/opt/homebrew/bin/claude`, `/usr/local/bin/claude`. Run with `GeneralCommandLine(exe, "-p", "--output-format", "text", *extraArgs)` (`--model` appended when set), working directory = project base path, prompt (system + user concatenated) written to stdin, stdout captured via `CapturingProcessHandler`/`OSProcessHandler` with a `ProcessListener`; emit stdout as one chunk on exit 0 (the CLI does not stream in text mode), non-zero exit → `LlmException(stderr)`. Timeout 120 s. Verify flags against `claude --help` on the machine during implementation and expose an "Extra CLI arguments" setting so flag drift is fixable without a release. Optional follow-up (not required for the guardrail): `--output-format stream-json --verbose --include-partial-messages` for true streaming.
- **D2.5 — Tests.** Deliverable: `SseReaderTest`, `AnthropicBackendTest` and `OpenAiCompatibleBackendTest` using `HttpServer` on an ephemeral port serving recorded SSE fixtures from `testData/commit/`; `ClaudeCliBackendTest` pointing the executable at `testData/commit/fake-claude.sh` (echoes a fixed message).

**Exit guardrails — Phase D2 → D3**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Three backends live | Each backend produces a real commit message in `runIde` against a real endpoint/CLI (author's keys); recorded in Notes | 🔲 | |
| Cancellation | Clicking Stop mid-stream stops appending within 200 ms and the HTTP connection/process is closed (checked via logging) | 🔲 | |
| No secrets on disk | `agenstorm.xml` contains no API key; key lives in `PasswordSafe` | 🔲 | |
| Error UX | Wrong key → balloon with the API's error message and "Open settings"; field text restored | 🔲 | |

#### Phase D3 — Settings UI and polish

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| D3.1 | "Commit messages" group: backend selector, per-backend fields, "Test connection", model, prompts (system/user) with "Reset to default", max diff chars, Conventional Commits, body, language | 🔲 | |
| D3.2 | Git context provider (optional `Git4Idea`): current branch name into `{branch}` via a tiny internal EP `com.pronskiy.agenstorm.commitContextProvider` | 🔲 | |
| D3.3 | Hint handling polish: keep hint on failure; when hint looks like a full message (multi-line), ask the model to improve rather than replace (prompt variant) | 🔲 | |
| D3.4 | README section: setup per backend, prompt variables, recommended shortcut | 🔲 | |

**Steps (detail):**

- **D3.1 — Settings.** Deliverable: `commit/CommitSettingsPanel.kt` (Kotlin UI DSL) plugged into `AgenstormConfigurable`; API key field bound to `PasswordSafe` (read on panel open, write on apply); "Test" button calls `backend.validate()` in a background task and shows the result inline.
- **D3.2 — Branch name.** Deliverable: `commit/context/CommitContextProvider.kt` (`interface { fun branchName(project): String? }`, EP declared in `plugin.xml`) and `commit/context/GitCommitContextProvider.kt` in `agenstorm-git.xml` using `GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.currentBranchName`. Without Git4Idea `{branch}` is empty.
- **D3.3 — Hint polish.** Deliverable: second user-prompt template `improve` used when the existing text has ≥ 2 lines; otherwise `generate` with the hint.
- **D3.4 — Docs.** Deliverable: README section + `CHANGELOG.md` entry.

**Exit guardrails — Epic D → Epic E**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Daily-driver test | Author uses the feature for 10 real commits across two projects; ≥ 8 accepted with ≤ 1 line edited | 🔲 | |
| Size | Plugin zip grew < 300 KB since Epic C | 🔲 | |
| Verifier | `verifyPlugin` still clean (no new internal API usages beyond the documented ones) | 🔲 | |

---

### Epic E — Project tabs in the main toolbar (single header row)  ·  MVP

**Goal:** On macOS, open projects appear as tabs inside the main toolbar row (left slot, where the project widget is), so the window has one header row instead of two; tabs show project names only; clicking switches windows; native macOS project tabs are turned off.
**Success metrics:** Header height with 3 projects open equals header height with 1 project; switch latency < 100 ms; tabs stay consistent across all frames when projects open/close.

Platform facts (verified against build 262):

- Native tabs are `WindowTabsComponent` driven by `MacWinTabsHandlerV2` (`platform/platform-impl/src/com/intellij/ui/mac/`), living in their own 28 px row above the toolbar (`IdeRootPane.CustomHeaderRootLayout`). They are enabled by registry `ide.mac.os.wintabs.version2` (restart required) and gated by `ide.mac.bigsur.window.with.tabs.enabled`. Re-parenting them is not viable (see §2).
- The main toolbar's left group is `MainToolbarLeft` containing `main.toolbar.Project` (`ProjectToolbarWidgetAction`) — `platform/platform-resources/src/idea/PlatformActions.xml`. Actions can be replaced with `<action id="…" class="…" overrides="true"/>`.
- `ProjectUtil.focusProjectWindow(project, stealFocusIfAppInactive)`; `ProjectManagerEx.getInstanceEx().closeAndDispose(project)`; `RecentProjectsManagerBase.getInstanceEx().getProjectIcon(path, isProjectValid, unscaledIconSize)`; `RecentProjectListActionProvider.getInstance().getActions()` for the recent-projects popup; `ProjectWidget.Actions` group holds the static entries of the stock widget popup.

#### Phase E1 — Native tabs off, model, and a static widget

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| E1.1 | `NativeTabsRegistryGuard` (`ProjectActivity`): when feature on & macOS & registry key true → set `ide.mac.os.wintabs.version2=false`, notify "Restart to apply"; when feature off → restore `true` | 🔲 | |
| E1.2 | `ProjectTabsModel` app service: ordered list of open projects (persisted order by base path), listeners; fed by `ProjectActivity` + `ProjectCloseListener` | 🔲 | |
| E1.3 | `ProjectTabsWidgetAction` replacing `main.toolbar.Project` via `overrides="true"`, extending the stock `ProjectToolbarWidgetAction` so that feature-off = stock behaviour; `ProjectTabsPanel` rendering one `ProjectTabLabel` per project + "+" button | 🔲 | |
| E1.4 | Click → switch (`focusProjectWindow`, optional bounds mirroring); middle-click / × → close; "+" → popup (recent projects + `ProjectWidget.Actions`) | 🔲 | |
| E1.5 | Verifier check of the override; if extending the internal `ProjectToolbarWidgetAction` is rejected, fall back to a standalone `CustomComponentAction` with a minimal own "project dropdown" for the feature-off state (record decision) | 🔲 | |

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
| Single row | Screenshot: with 3 projects open after restart, header is one row; the 28 px native tab strip is gone | 🔲 | |
| Consistency | Opening/closing a project updates the tab strip in every open frame within one EDT cycle; no stale tabs | 🔲 | |
| Switching | Click switches focus < 100 ms; bounds mirrored when both windows are normal; nothing weird in fullscreen (documented as unsupported if needed) | 🔲 | |
| Recovery | Turning the feature off restores the stock project widget and (after restart) native tabs | 🔲 | |
| Log clean | No exceptions in `idea.log` while opening 4 projects, closing 2, reopening 1 | 🔲 | |

#### Phase E2 — Overflow, order, keyboard

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| E2.1 | Overflow: shrink to icon-only when width is insufficient; then first N + chevron popup listing the rest | 🔲 | |
| E2.2 | Drag-to-reorder tabs within the strip; order persisted in `ProjectTabsModel` | 🔲 | |
| E2.3 | Actions `Agenstorm.NextProjectTab` / `Agenstorm.PrevProjectTab` (cyclic), unbound by default; `Agenstorm.CloseProjectTab` | 🔲 | |
| E2.4 | Settings group "Project tabs": enable, mirror bounds, show icons, max tab width | 🔲 | |

**Steps (detail):**

- **E2.1 — Overflow.** Deliverable: `ProjectTabsPanel.doLayout()` measuring available width from the parent toolbar (the toolbar gives the custom component its preferred size; cap `preferredSize.width` at `parent.width * 0.5` and switch modes when the sum of tab widths exceeds it).
- **E2.2 — Reorder.** Deliverable: mouse-drag within the panel swapping indices; `ProjectTabsModel.moveTab`. Keep it simple (no cross-window DnD).
- **E2.3 — Actions.** Deliverable: three `DumbAwareAction`s registered in `plugin.xml`; README suggests shortcuts; no defaults to avoid keymap conflicts.
- **E2.4 — Settings.** Deliverable: fields in `AgenstormSettings.State` + UI group.

**Exit guardrails — Epic E → Epic F**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Narrow window | At 1200 px window width with 6 projects, the strip degrades gracefully (icons, then chevron) and the run/VCS widgets remain visible | 🔲 | |
| Order survives restart | Reordered tabs come back in the same order after restart | 🔲 | |
| Daily-driver test | Author uses the strip for a week without re-enabling native tabs | 🔲 | |

---

### Epic F — Markdown live markup (Obsidian-style)  ·  MVP

**Goal:** In the normal Markdown text editor, inline markup characters (`**`, `*`, `_`, `~~`, backticks, `#` prefixes, `[`…`](url)`) are hidden and the text is styled (bold/italic/strike/code/link), except on the line(s) where the caret is — there the raw Markdown shows so you can edit it. Checkboxes render as ☐/☑ and toggle on click. Toggleable per editor and by default in settings. Heading font sizes are explicitly out of scope (single line height in the IntelliJ editor).
**Success metrics:** Typing latency unchanged (no fold operations on every keystroke — debounced); a 3,000-line `.md` re-syncs in < 50 ms after edits stop; caret navigation with arrow keys never "jumps over" hidden markers on the caret line; copying always copies raw Markdown.

Platform facts (verified against build 262):

- The editor has one line height and no per-range font size in `TextAttributes` → hiding markers requires folding; styling uses the Markdown colour scheme keys (`MarkdownHighlighterColors.BOLD/ITALIC/STRIKE_THROUGH/CODE_SPAN/…`).
- Fold regions created manually with `FoldingModelEx.createFoldRegion(start, end, placeholder, group, neverExpands)` inside `runBatchFoldingOperation` are "light" regions (no `SIGNATURE` user data): `UpdateFoldRegionsOperation.shouldRemoveRegion` keeps them across folding passes, they may be 1 character long, and an empty placeholder is accepted (`FoldingModelImpl.createFoldRegion` only rejects `start >= end`, character-pair splits and tree-invalid ranges). `FoldingBuilder`-created regions, by contrast, are removed when `range.length < 2` — this is why the live markup does **not** use a `FoldingBuilder`.
- `com.intellij.textEditorCustomizer` (`TextEditorCustomizer.customize(textEditor, coroutineScope)`) is how the Markdown plugin itself attaches per-editor behaviour (`MarkdownCharacterGridCustomizer`).
- PSI: `MarkdownElementTypes.STRONG / EMPH / STRIKETHROUGH / CODE_SPAN / INLINE_LINK / LINK_TEXT / LINK_DESTINATION / IMAGE / ATX_1…ATX_6`; tokens `MarkdownTokenTypes.EMPH` (marker chars), `ATX_HEADER`, `BACKTICK`, `CHECK_BOX`.
- Toolbar groups for a toggle: `Markdown.Toolbar.Right` (editor-with-preview toolbar), `Markdown.EditorContextMenuGroup`.

#### Phase F1 — Range collection and light fold regions

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| F1.1 | `MarkupRangeCollector`: PSI → `List<MarkupRange(kind, range, placeholder)>`, skipping code fences/blocks/HTML | 🔲 | |
| F1.2 | `LiveMarkupController` per `TextEditor` (via `textEditorCustomizer`): create/refresh light fold regions, debounce on document change, dispose with editor | 🔲 | |
| F1.3 | Caret policy: regions intersecting caret line(s)/selection expanded, all others collapsed; applied on caret/selection change and after re-sync | 🔲 | |
| F1.4 | Spike result recorded: regions survive Markdown plugin's own folding pass, `Fold All`/`Expand All`, and typing at region borders | 🔲 | |
| F1.5 | Tests: collector on fixtures; controller in `BasePlatformTestCase` with `EditorTestUtil` (regions exist, caret line expanded) | 🔲 | |

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
| Hide/reveal | In `runIde`, `**bold**` shows as `bold`; placing the caret on that line reveals `**`; moving away hides them | 🔲 | |
| Coexistence | Markdown plugin heading/list folding still works; `Fold All`/`Expand All` do not break live markup after the next caret move | 🔲 | |
| Copy fidelity | Select-all + copy yields raw Markdown | 🔲 | |
| Perf | 3,000-line file: no visible lag while typing; sync after edits < 50 ms (log timing at debug level) | 🔲 | |

#### Phase F2 — Styling, links, checkboxes, toggle

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| F2.1 | `LiveMarkupAnnotator` (only when live mode is on for that file): bold/italic/strike font attributes when the scheme lacks them; link text underlined + link colour | 🔲 | |
| F2.2 | Checkbox toggle: click on a ☐/☑ placeholder flips `[ ]`↔`[x]` in a write command | 🔲 | |
| F2.3 | Link navigation from the visible link text: Cmd/Ctrl+click on `LINK_TEXT` navigates via the (hidden) destination's references; URLs open in browser | 🔲 | |
| F2.4 | `ToggleLiveMarkupAction` in `Markdown.Toolbar.Right` and `Markdown.EditorContextMenuGroup`; per-editor state; settings default + group "Markdown live markup" | 🔲 | |
| F2.5 | Bullets `- `/`* ` rendered as `• ` (placeholder), optional setting | 🔲 | |

**Steps (detail):**

- **F2.1 — Annotator.** Deliverable: `markdown/LiveMarkupAnnotator.kt` registered `<annotator language="Markdown" …/>` in `agenstorm-markdown.xml`; it checks a per-file flag (set by the controller via `PsiFile.putUserData`/editor user data) and adds `enforcedTextAttributes` for `STRONG` (bold), `EMPH` (italic), `STRIKETHROUGH` (strike), `LINK_TEXT` (underline + `DefaultLanguageHighlighterColors.HIGHLIGHTED_REFERENCE`-like colour). Prefer the existing scheme keys: if `EditorColorsManager.getInstance().globalScheme.getAttributes(MarkdownHighlighterColors.BOLD).fontType` already has BOLD, do nothing for that kind.
- **F2.2 — Checkbox toggle.** Deliverable: `EditorMouseListener` in the controller: on click, `editor.foldingModel.getCollapsedRegionAtOffset(offset)` with `ourKey == CHECKBOX_*` → `WriteCommandAction.runWriteCommandAction(project) { document.replaceString(...) }`; the sync then swaps the placeholder.
- **F2.3 — Link navigation.** Deliverable: `EditorMouseListener` + `EditorMouseMotionListener` in the controller: with Cmd/Ctrl held over a `LINK_TEXT` range (looked up from the collector output), show hand cursor; on click find the sibling `LINK_DESTINATION`, collect `PsiSymbolReferenceService.getService().getReferences(destination)` + `destination.references`, navigate the first resolvable target (`NavigationTarget.navigationRequest()` via `NavigationService`, or `Navigatable.navigate`), else if it is a URL → `BrowserUtil.browse`. This also makes Epic A's `path:line:col` links work from the visible text.
- **F2.4 — Toggle + settings.** Deliverable: `markdown/ToggleLiveMarkupAction.kt` (`ToggleAction`, icon `AllIcons.Actions.PreviewDetails` or a custom 16 px SVG), per-editor state in `editor.putUserData(LIVE_MARKUP_ENABLED, …)` respected by the customizer/controller (`removeAll()` on off); settings: `liveMarkupEnabled` (default on), `liveMarkupBullets`, `liveMarkupCheckboxes`.
- **F2.5 — Bullets.** Deliverable: collector emits `BULLET` ranges (`LIST_BULLET` token's `-`/`*`/`+` char → placeholder `•`) when the setting is on.

**Exit guardrails — Epic F → release 1.0**

| Guardrail | Criteria (pass/fail) | Status | Actual outcome |
|-----------|----------------------|--------|----------------|
| Obsidian parity (scoped) | Side-by-side with Obsidian on `testData/markdown/parity.md`: bold, italic, strike, code, headings (markers hidden), links (text only), checkboxes, bullets match in what is shown/hidden; heading size difference accepted | 🔲 | |
| Editing safety | 30-minute editing session of a real plan file: no lost characters, no caret jumps, undo/redo correct | 🔲 | |
| Off switch | Toggling off restores a plain Markdown editor instantly (no leftover folds) | 🔲 | |
| Agent-written files | Opening a file while an agent rewrites it (external change → reload) keeps live markup consistent after reload | 🔲 | |

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

---

## 7. Open questions

- [ ] Default models per backend at implementation time (Anthropic and OpenAI model ids change; pick the current mid-tier default and keep it a free-text setting).
- [ ] Should `DiffCollector` honour a project-level ignore file (e.g. `.aiignore` / `.agenstormignore`) for files that must never leave the machine? Default: no, keep v1 simple; revisit after daily use.
- [ ] Does `claude -p` on the author's machine accept `--max-turns 1` / `--tools ""` style flags to guarantee a tool-free single response? Verify during D2.3; otherwise rely on the prompt.
- [ ] Should live markup also render images (`![alt](src)`) — as `🖼 alt` placeholder or as a block inlay? Deferred; not in 1.0.
- [ ] Epic E on Windows/Linux: the widget works, but is it wanted there (native tabs do not exist)? Default: available, off by default outside macOS.
- [ ] Should `Copy Location Link` also offer `path:line:col` relative to the *repository* root vs. content root when they differ (monorepos)? Default: content root; decide after use.

---

## How to Update This Document

This spec is the source of truth for the build. Keep it current as work happens:

- **Status markers.** Update a step's status in its tracker table as you go: 🔲 → 🔄 → ✅. Use ⏸️ for blocked (note why in Notes) and ❌ for cut (leave the row; the strikethrough of history is useful).
- **Current focus.** Keep the pointer at the top aimed at the next actionable 🔲 step. Update it the moment you finish a step or cross a phase boundary — a stale pointer is worse than none, since it sends the next reader to the wrong place.
- **Guardrails.** When you hit a phase boundary, fill the **Actual outcome** column with what really happened and set the guardrail status. Don't advance to the next phase until its entry guardrails pass — or log a decision explaining why you're proceeding anyway.
- **Decisions.** Any non-trivial choice made during the build gets a new row in the Decision Log (§6). It's append-only — reversals are new rows, not edits. If the choice changes the architecture, also update the Technical Decisions snapshot (§2).
- **Spec changes.** Structural changes (new epic, re-scoped phase) get a Changelog row at the top. Keep the executive summary honest if the project's shape shifts.
- **Open questions.** When one resolves, strike it from §7 and log the decision in §6.

<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Agenstorm Changelog

## [Unreleased]

### Added

- **The IDE as your terminal's editor.** `$EDITOR` and `$VISUAL` in an IDE terminal point at this IDE, so Claude Code's <kbd>Ctrl</kbd>+<kbd>G</kbd> opens the prompt — or the plan — in the window whose terminal asked for it, and closing the tab hands the edited text back. `git commit`, `crontab -e` and everything else that reaches for an editor behave the same way, and the command stays blocked until the last tab for that file is closed, with what you typed on disk before it is let go. Only one existing, writable file is ever claimed: an invocation carrying options, a missing path, a directory or a read-only file goes to the editor your profile set, or to `vi`. Both variables are also set through the terminal's `_INTELLIJ_FORCE_SET_*` mechanism, which runs after your rc files, so a profile that exports its own `EDITOR` no longer disables the bridge — that editor becomes the fallback instead. Inside IDE terminals only, announced once in a balloon, and switchable under Tools → Agenstorm → Terminal editor
- **Maximize Terminal** (⌘⌥M on macOS, ⌥⇧F12 elsewhere, and a button in the terminal's title bar): the terminal fills the editor's area, and the same key hides it again and returns the caret to the editor. Other tool windows keep their place and their full height, the terminal opens first if it was closed, and the height you dragged it to is what comes back. Confining the terminal to the editor's column is what the IDE's Widescreen tool window layout does, so Agenstorm turns that on the first time you use the toggle — never on install — explains it once in a balloon, and puts your layout back when the feature is switched off. A widescreen layout you chose yourself is never claimed. This is the first Agenstorm action to ship a default binding — both keystrokes are free in every bundled keymap — and it can be switched off, button and all, under Tools → Agenstorm → Terminal size

## [1.2.0] - 2026-09-10

### Changed

- The plugin uses no internal platform API at all: the JetBrains Marketplace review rejected 1.1.0 over fifteen usages of it, and every one of them is gone. The window title refreshes through a UI settings change instead of `IdeFrameEx`; the status-bar branch widget extends the Git plugin's own widget instead of calling the internal branches popup; the project tabs take the main toolbar's project slot through `ActionManager.replaceAction` instead of subclassing the stock widget; and the New Scratch File popup is Agenstorm's own action rather than a filter plugged into the platform's. What each feature does is unchanged
- The New Scratch File popup now filters **languages** rather than file types, so allowing JavaScript no longer also offers ActionScript and ECMAScript 6 — the limitation 1.0 had to document. The allow-list is a list of language names — Plain text, Markdown, PHP, JavaScript and HTML by default — keeps the order you write it in, and ends with an "All Languages…" entry that opens the full list when you need something you did not list. A list customized under 1.0 or 1.1 carries over as it is: a file type name still resolves to its language
- The branch in the status bar now carries the Git plugin's own branch icon, so a branch with unpushed or unfetched commits shows the incoming/outgoing arrows

## [1.1.0] - 2026-09-09

### Added

- Block quotes and GitHub alerts (`> [!NOTE]`, `> [!IMPORTANT]`, …) render in Markdown live markup: every `>` is folded to a single space, so no column moves and the markers come back at the caret without the line shifting, and the quote gets a full-width card with an accent bar down the column its markers left behind. Nesting reads as indent, one card and one bar per quote. A caret anywhere in the quote brings back every `>` of the block at once, and the bar steps aside while they show. An alert's `[!…]` title is left as written, so the Markdown plugin keeps styling it and showing its gutter icon. New option under Tools → Agenstorm → Markdown live markup
- Thematic breaks (`---`, `***`, `___`, `- - -`) render as a full-width rule across the row their characters left empty, with no card behind it. The caret on that line brings the characters back and the rule steps aside. New option alongside the others

### Fixed

- Markdown live markup no longer paints bullets and task boxes as folded code. The •, ☐ and ☑ are fold placeholders, and the editor paints every collapsed placeholder with the colour scheme's folded-text attributes — in Catppuccin Mocha a background plus a `BOXED` border, which put each bullet in a little box. That value is per editor with no per-region hook, so live markup now overrides it for the editors it owns and the markers are painted with the editor's default text attributes. A genuinely folded heading or code fence in such an editor shows its `...` as plain text too
- Project tabs share one window again. `ide.mac.os.wintabs.version2` is not only the look of the macOS window tabs: under the New UI it is what `JdkEx.getTabbingModeInvocator()` reads, so switching it off (as 1.0 did, to be rid of the extra tab row) meant no `NSWindowTabGroup` was ever formed and every tab was its own window. The key is now left on — and turned back on once for installs 1.0 disabled it in, with a balloon offering the restart it needs — while the platform's tab row is hidden per frame instead, which keeps the header at one line. Switching a tab no longer mirrors window bounds on macOS, since there is only one window to move

## [1.0.0] - 2026-09-07

### Added

- Settings page under Tools → Agenstorm with an on/off switch per feature
- Plugin loads in IDEs without the PHP, Markdown, Git or Terminal plugin: every feature that needs one switches itself off when it is missing
- Clickable `path/to/file.php:42:7` locations in Markdown link destinations, comments of every language and PHP strings, plus a Copy Location Link action in the editor and gutter menus
- New Scratch File popup limited to an allow-list of file types (Text, Markdown, PHP, JavaScript by default)
- Window title shows the project only, never the current file
- Generate Commit Message action streaming a subject and body from the Anthropic API, any OpenAI-compatible endpoint or the local `claude` CLI, with one-step Undo, editable prompts and a hint/draft mode
- Project tabs inside the main toolbar: one tab per open project, click to switch, middle-click or × to close, "+" for the recent-projects popup, right-click menu, drag to reorder (order remembered), icon-only and "…" overflow modes for narrow toolbars, Next/Previous/Close Project Tab actions without default shortcuts, options for window-bounds mirroring, icons and max tab width; native macOS project tabs are switched off while the feature is on; the Git branch moves from the toolbar to the status bar's left corner, replacing the navigation bar
- `open` in an IDE terminal opens in the IDE: `open src/Foo.php:42:7` puts the caret on line 42, column 7 in the window the terminal belongs to, several paths open several files, a file that belongs to another already-open project opens in that project's window, a directory opens or focuses that project and a directory inside the current one is shown in the Project view. Flags, URLs, missing paths, binary files and anything else reach the real `open` untouched. A generated POSIX `sh` shim goes in front of the PATH of IDE terminals only, talking to a loopback endpoint bound to a free port with a per-run token; settings for the switch, the command names and binary file types under Tools → Agenstorm → Terminal
- Markdown live markup: Obsidian-style hiding of `**`, `*`, `~~`, backticks, `#` and link syntax until the caret reaches the line, ☐ / ☑ task checkboxes that toggle on click, • bullets, Ctrl/Cmd+click on link text following the destination (files, `path:line:col`, headings, URLs), a per-editor Live Markup toggle in the Markdown toolbar and context menu, settings for checkboxes and bullets, and hidden syntax revealed for the element at the caret (or, by setting, the whole caret line)
- Fenced code blocks in Markdown live markup render as a full-width card: the ``` lines lose their markers and stay as the card's empty header and footer rows, the syntax highlighting inside the fence is untouched, and the caret on either fence line brings both markers back

[Unreleased]: https://github.com/pronskiy/agenstorm/compare/1.2.0...HEAD
[1.2.0]: https://github.com/pronskiy/agenstorm/compare/1.1.0...1.2.0
[1.1.0]: https://github.com/pronskiy/agenstorm/compare/1.0.0...1.1.0
[1.0.0]: https://github.com/pronskiy/agenstorm/commits/1.0.0

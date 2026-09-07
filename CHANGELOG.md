<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Agenstorm Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/pronskiy/agenstorm/compare/1.0.0...HEAD
[1.0.0]: https://github.com/pronskiy/agenstorm/commits/1.0.0

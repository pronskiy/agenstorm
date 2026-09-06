<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Agenstorm Changelog

## [Unreleased]

### Added

- Project scaffold targeting PhpStorm 2026.2 (build 262)
- Settings page under Tools → Agenstorm with an on/off switch per feature
- Plugin loads in IDEs without the PHP, Markdown or Git plugin (optional dependencies)
- Clickable `path/to/file.php:42:7` locations in Markdown link destinations, comments of every language and PHP strings, plus a Copy Location Link action in the editor and gutter menus
- New Scratch File popup limited to an allow-list of file types (Text, Markdown, PHP, JavaScript by default)
- Window title shows the project only, never the current file
- Generate Commit Message action streaming a subject and body from the Anthropic API, any OpenAI-compatible endpoint or the local `claude` CLI, with one-step Undo, editable prompts and a hint/draft mode
- Project tabs inside the main toolbar: one tab per open project, click to switch, middle-click or × to close, "+" for the recent-projects popup, right-click menu, drag to reorder (order remembered), icon-only and "…" overflow modes for narrow toolbars, Next/Previous/Close Project Tab actions without default shortcuts, options for window-bounds mirroring, icons and max tab width; native macOS project tabs are switched off while the feature is on; the Git branch moves from the toolbar to the status bar's left corner, replacing the navigation bar
- Markdown live markup: Obsidian-style hiding of `**`, `*`, `~~`, backticks, `#` and link syntax until the caret reaches the line, ☐ / ☑ task checkboxes that toggle on click, • bullets, Ctrl/Cmd+click on link text following the destination (files, `path:line:col`, headings, URLs), a per-editor Live Markup toggle in the Markdown toolbar and context menu, settings for checkboxes and bullets, and hidden syntax revealed for the element at the caret (or, by setting, the whole caret line)

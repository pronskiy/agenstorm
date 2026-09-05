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
- Project tabs inside the main toolbar: one tab per open project, click to switch, middle-click or × to close, "+" for the recent-projects popup, right-click menu; native macOS project tabs are switched off while the feature is on

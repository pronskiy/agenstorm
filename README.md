# Agenstorm

![Build](https://github.com/pronskiy/agenstorm/workflows/Build/badge.svg)

<!-- Plugin description -->
A PhpStorm plugin that makes the IDE friendlier for agent-driven development:

- clickable `path/to/file.php:42:7` locations in Markdown, comments and PHP strings
- LLM-generated commit messages (streaming; Anthropic, OpenAI-compatible or `claude -p`) with no SDK bloat
- a New Scratch File popup trimmed to the languages you actually use
- window titles and project tabs without file names
- project tabs inside the main toolbar, so the window loses a row of chrome
- an Obsidian-style live-markup mode for Markdown

Every feature is toggleable on its own under Settings → Tools → Agenstorm.
<!-- Plugin description end -->

Status: early development. `SPEC.md` is the plan and the task list.

## Compatibility

PhpStorm 2026.2 and later.

## Installation

- Manually:

  Download the [latest release](https://github.com/pronskiy/agenstorm/releases/latest) and install it using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

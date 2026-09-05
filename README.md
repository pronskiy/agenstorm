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

Status: early development. `SPEC.md` is the plan and the task list; features land in the order
listed there (location links first).

## Compatibility

PhpStorm 2026.2 (build 262). The plugin also loads in other IntelliJ-based IDEs of the same
version; features that need the PHP, Markdown or Git plugin switch themselves off when that
plugin is missing.

## Installation

- From JetBrains Marketplace (once published):

  <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > search for "Agenstorm" > <kbd>Install</kbd>

- Manually:

  Download the [latest release](https://github.com/pronskiy/agenstorm/releases/latest) and install it using
  <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Settings

<kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>Agenstorm</kbd> has one group per feature with an on/off
switch. Everything is on by default. Settings are stored in `agenstorm.xml` in the IDE config
directory, so they roam with Settings Sync.

## Links for agents

Agenstorm makes bare file locations clickable wherever they appear: Markdown link destinations,
comments in any language, and PHP string literals. The syntax is the one compilers, test runners
and coding agents already emit:

```
path/to/file.ext:LINE
path/to/file.ext:LINE:COLUMN
```

- `LINE` and `COLUMN` are 1-based.
- Paths resolve relative to the file that contains the link, then the project root, then every
  content root; an absolute path works too. As a last resort a unique file name anywhere in the
  project matches.
- Locations that do not resolve stay plain text. Nothing is flagged as an error, because agents
  routinely refer to files they are about to create.

To get agents to produce these links, add a line like this to your `CLAUDE.md`, `AGENTS.md` or
system prompt:

> When you refer to code, write the location as `path/from/project/root/File.php:LINE:COLUMN`
> (1-based) so it is clickable in the IDE. In Markdown, use it as the link destination:
> `[Foo::bar()](src/Foo.php:42:7)`.

The editor context menu offers **Copy Location Link** to produce such a token for the caret or
selection.

## Development

```bash
./gradlew check          # compile, tests, coverage — run before every commit
./gradlew runIde         # PhpStorm 2026.2 sandbox with the plugin
./gradlew buildPlugin    # ZIP in build/distributions/
./gradlew verifyPlugin   # IntelliJ Plugin Verifier
```

See `CLAUDE.md` for the project conventions and `SPEC.md` for the roadmap.

## License

[MIT](LICENSE) © 2026 Roman Pronskiy

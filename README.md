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

The **Scratch files** group also holds the allow-list for the New Scratch File popup: one internal
file type name per line (`PLAIN_TEXT`, `Markdown`, `PHP` and `JavaScript` by default), with buttons
to add the current file's type or pick from every registered type.
Known limitation: the platform filters that popup by *file type*, so language dialects that share an
allowed file type stay visible too. With `JavaScript` allowed, ActionScript and ECMAScript 6 remain
in the list.

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

The editor and gutter context menus offer **Copy Location Link**, which puts such a token for the
caret (or the selection start) on the clipboard, plus a Markdown-link flavor
(`[Foo.php:42](src/Foo.php:42:7)`) for apps that accept it. The action ships without a shortcut;
`Ctrl+Alt+Shift+L` is free in the default keymaps and works well (Settings → Keymap, search for
"Copy Location Link").

## AI commit messages

The lightning button above the commit message field (commit tool window and commit dialog) streams a
subject and body for the included changes into the field. Click it again to stop. Undo removes the whole
generation in one step. Text already in the field is treated as a hint; a multi-line draft is improved
rather than replaced, and it is saved to the commit message history before being overwritten.

Configure the backend under Settings → Tools → Agenstorm → Commit messages:

- **Anthropic API**: paste an API key (stored in the IDE password safe, never in `agenstorm.xml`). The
  default model is `claude-sonnet-5`; any model id works in the Model field.
- **OpenAI-compatible API**: works for OpenAI (`https://api.openai.com/v1`), Ollama
  (`http://localhost:11434/v1`, no key), LM Studio, OpenRouter and Groq. A model id is required; the key
  is optional and sent as a bearer token only when set.
- **Claude CLI**: runs `claude -p --output-format text` with the prompt on stdin, so it uses your Claude
  Code login and needs no key. Leave the executable empty to find `claude` on the PATH or in the usual
  install locations. Flags that may change between CLI versions live in the "Extra arguments" field.

**Test Connection** validates the values as typed. The prompt templates are editable: `{diff}`, `{stat}`,
`{branch}`, `{hint}`, `{language}` and `{conventional}` are substituted, unknown placeholders are kept, and
"Reset to Default" restores the built-in text. Lock files and generated output (`vendor/`, `node_modules/`,
`dist/`, `build/`, `*.min.*`, `*.map`) never enter the diff; every file still appears in the stat.

The action has no default shortcut; `Ctrl+Alt+Shift+G` is free in the default keymaps (Settings → Keymap,
search for "Generate Commit Message").

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

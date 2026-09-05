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
- **Claude CLI**: runs `claude -p` with the prompt on stdin, so it uses your Claude Code login and needs no
  key; the message streams into the field word by word, like the HTTP backends. The default model is `haiku`; the Model field takes any alias the CLI
  accepts (`sonnet`, `opus`, `sonnet[1m]`) or a full model id. Leave the executable empty to find `claude`
  on the PATH or in the usual install locations. The call runs with extended thinking off and in the CLI's
  safe mode, so your CLAUDE.md, plugins, skills, hooks and MCP servers stay out of it: about 3 s instead of
  20–50 s, and roughly 800 prompt tokens instead of 6,000. Remove `--safe-mode` from the "Extra arguments"
  field if you want your CLAUDE.md rules to apply to generated messages; flags that change between CLI
  versions live in the same field.

**Test Connection** validates the values as typed. The prompt templates are editable: `{diff}`, `{stat}`,
`{branch}`, `{hint}`, `{language}` and `{conventional}` are substituted, unknown placeholders are kept, and
"Reset to Default" restores the built-in text. Lock files and generated output (`vendor/`, `node_modules/`,
`dist/`, `build/`, `*.min.*`, `*.map`) never enter the diff; every file still appears in the stat.

The action has no default shortcut; `Ctrl+Alt+Shift+G` is free in the default keymaps (Settings → Keymap,
search for "Generate Commit Message").

## Project tabs

On macOS, open projects appear as tabs inside the main toolbar, in the slot where the project widget
normally sits, so the window has one header row instead of two. Each tab shows the project icon and
name only; the frame's own project is highlighted. Click a tab to switch to that project's window (the
target window first moves onto the current window's bounds, unless one of them is in full screen),
middle-click or use the × to close it, and the "+" button opens the usual recent-projects popup with
New Project, Open and Clone. Right-click a tab for Close, Close Others and Copy Path.

The native macOS project tabs are switched off while this feature is on (a balloon offers to restart
the IDE, which the change needs). Turning the feature off restores them after another restart, but
only if Agenstorm was the one that switched them off. Tab order is remembered per project in
`agenstorm-tabs.xml`; drag a tab to reorder. When the toolbar gets narrow the tabs shrink to icons, and
when even those do not fit the first ones stay and a "…" button lists the rest. Off macOS the strip works
the same; there are simply no native tabs to replace.

With the tabs on, the Git branch moves out of the toolbar too: the VCS widget next to the tabs is hidden
and the current branch shows bottom-left in the status bar, where the navigation bar (breadcrumbs) was.
Click it for the branches popup. The navigation bar is hidden for that, and comes back when the option is
turned off, provided Agenstorm was the one that hid it.

The **Project tabs** settings group holds the switch plus four options: the Git branch in the status bar,
moving the other window onto this one's position and size when switching, project icons, and the maximum
tab width before names are shortened (220 px by default). All are on by default.

Three actions come without a shortcut so they never collide with your keymap: **Next Project Tab**,
**Previous Project Tab** (both wrap around) and **Close Project Tab**. Assign them under Settings → Keymap
by searching for "Project Tab"; `Ctrl+Alt+Shift+]` and `Ctrl+Alt+Shift+[` are a natural pair for next and
previous.

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

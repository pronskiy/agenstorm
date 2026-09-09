# Agenstorm

![Build](https://github.com/pronskiy/agenstorm/workflows/Build/badge.svg)

<!-- Plugin description -->
**Agenstorm removes the friction an agent-heavy workflow hits in the IDE every day.**

Coding agents changed what a working day looks like, and the IDE was not built for that
rhythm. Agenstorm closes the gaps, one small feature at a time.

### Markdown live markup

Obsidian-style editing: `**bold**`, headings, links, `- [ ]` task boxes and bullets show their
result until the caret reaches them, and fenced code blocks sit on a full-width card with their
highlighting intact. It is folding only — the file on disk never changes, and copying always
copies raw Markdown.

### One header row instead of two

Open projects become tabs inside the main toolbar, in the slot the project widget normally
occupies, so the window loses a row of chrome; the Git branch moves down to the status bar.
Window titles name the project, not whichever file happens to be open.

### Clickable file locations

`src/Foo.php:42:7` becomes a link — in Markdown, in comments of every language, and in PHP
strings. It is the format compilers, test runners and agents already emit, so nothing has to
change on the other side. Paths resolve against the containing file, the project root, the
content roots, and finally a unique file name anywhere in the project. **Copy Location Link**
puts a token for the caret on the clipboard, plain or as a Markdown link, ready to paste back
to an agent.

### `open` in the IDE terminal

`open src/Foo.php:42` opens that file in the window the terminal belongs to, caret on line 42,
instead of handing it to the OS. Several paths open several files; a file belonging to another
open project lands in that project's window; a directory opens or focuses it. Everything the
IDE does not claim — flags, URLs, missing paths, binaries — reaches the real `open` untouched.
The shim shadows `open` inside IDE terminals only; every other shell on the machine is
unaffected.

### AI commit messages

A subject and body streamed into the commit message field from the Anthropic API, any
OpenAI-compatible endpoint (OpenAI, Ollama, LM Studio, OpenRouter, Groq), or the local `claude`
CLI, which needs no API key at all. No SDK and no bundled jars — just the platform's own HTTP
client. Undo removes the whole generation in one step, text already in the field is treated as
a hint, and every prompt is editable.

### A shorter New Scratch File popup

Only the file types on your allow-list.

---

Every feature has its own on/off switch under **Settings → Tools → Agenstorm**, and the ones
that need the PHP, Markdown, Git or Terminal plugin switch themselves off when it is missing —
so the plugin is at home in any IntelliJ-based IDE of the same version.

Free and open source, MIT: https://github.com/pronskiy/agenstorm
<!-- Plugin description end -->

`SPEC.md` is the plan and the task list; `CHANGELOG.md` records what each version shipped.

## Compatibility

PhpStorm 2026.2 (build 262). The plugin also loads in other IntelliJ-based IDEs of the same
version; features that need the PHP, Markdown, Git or Terminal plugin switch themselves off when
that plugin is missing.

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
name only; the frame's own project is highlighted. Click a tab to switch to that project,
middle-click or use the × to close it, and the "+" button opens the usual recent-projects popup with
New Project, Open and Clone. Right-click a tab for Close, Close Others and Copy Path.

The projects share **one window**. That is the platform's own macOS window tabs doing the work — Agenstorm
leaves them on and hides only their separate row, so switching a tab does not raise a second window and the
header stays a single line. (Agenstorm 1.0 switched those window tabs off, which is why every tab was its own
window; installs left that way are put right on first start, and a balloon offers the restart the change needs.)
Tab order is remembered per project in `agenstorm-tabs.xml`; drag a tab to reorder. When the toolbar gets
narrow the tabs shrink to icons, and when even those do not fit the first ones stay and a "…" button lists
the rest.

Windows and Linux have no window merging to build on, so there the strip switches between windows instead;
the "move the other window onto this one's position and size" option exists for exactly that case.

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

## Markdown live markup

Obsidian-style editing for Markdown files: the syntax hides itself until the caret reaches the line.

- `**bold**`, `*italic*`, `~~strike~~` and `` `code` `` show only their text; the markers come back for the element
  the caret is in (or touches) and under a selection, so what you see is what you copy; a setting switches to
  revealing the whole caret line instead
- `#` heading marks are hidden; `- [ ]` / `- [x]` become ☐ / ☑ and toggle on click; `-`, `*` and `+` bullets show as •. These three are painted as ordinary text: the colour scheme's folded-text styling is switched off for editors live markup owns, so a folded heading or fence there shows its `...` without a background or border too
- Block quotes and GitHub alerts (`> [!NOTE]`, `> [!WARNING]`, …) lose their `>` markers and get a card with an accent bar; each marker folds to a space, so nesting still reads as indent and nothing moves when the caret brings the markers back. A caret anywhere in the quote reveals the whole block, and the bar steps aside while the markers show. An alert's `[!…]` title stays as written, so the Markdown plugin keeps styling it
- `[text](destination)` shows the text; Ctrl/Cmd+click or Ctrl+B on it follows the destination: files,
  `path:line:col` locations, `#headings` and URLs
- The **Live Markup** button in the Markdown editor toolbar (also in the editor context menu) turns it off or on for
  one editor; Settings → Tools → Agenstorm → Markdown live markup has the global switch and the checkbox and bullet
  options
- Fenced code blocks lose their ``` lines and sit on a full-width card; the two fence lines stay as the card's
  empty header and footer rows, the highlighting inside the fence is untouched, and the caret on either of them
  brings the backticks back
- Copying always copies raw Markdown; the file is never changed by the folding; the Markdown plugin's own folding,
  Expand All and Collapse All keep working

Limitations: heading sizes stay at the editor's single line height; images and reference-style links stay raw;
the code-block card has no rounded corners, language chip or copy button, and block quotes and thematic breaks
render as written.

## Opening files from the terminal

Inside an IDE terminal, `open` opens files in the window the terminal belongs to:

```
open src/Foo.php            # opens the file
open src/Foo.php:42         # caret on line 42
open src/Foo.php:42:7       # caret on line 42, column 7
open src/Foo.php docs/a.md  # several files at once
open ../other-project       # focuses that project's window, or opens it
open .                      # shows the directory in the Project view
```

Paths resolve against the shell's working directory first, then the way location links resolve
everywhere else: the project base, the content roots, and finally a unique file name anywhere in the
project. A line past the end of the file lands on the last line.

A file opens in the project it belongs to. `open ../other-project/src/Bar.php` goes to that project's
window when it is already open, focusing it; when it is not, the file opens here, in the window you typed
in. A file the current project holds always stays here, so a command never jumps out of its own window.

Everything the IDE does not claim reaches the real `open` untouched, with its own behaviour and its own
error messages: flags (`open -a Preview doc.pdf`, `open -R file`), URLs, no arguments at all, paths that
do not exist, files the IDE treats as binary, and any command line mixing files with directories. If a
single argument cannot be claimed, the whole command is passed through — a half-claimed command would
swallow the error for the rest of it.

How it works: the IDE writes a small POSIX `sh` script and puts its directory at the front of the PATH of
every terminal it starts, so `open` is shadowed **inside IDE terminals only** — every other shell on the
machine is untouched. The script posts the working directory and the arguments to a loopback endpoint that
is bound to `127.0.0.1` on a free port, with a token generated per IDE run; nothing outside the machine can
reach it, and the token is never a command-line argument.

A project that is already open is simply focused. One that is not follows the IDE's own
<kbd>Settings</kbd> > <kbd>Appearance & Behavior</kbd> > <kbd>System Settings</kbd> > **Open project in**
preference — new window, the current window, or ask — so `open` behaves like every other way of opening a
project.

Settings → Tools → Agenstorm → **Terminal** has the switch, the command names (comma-separated, so `e` or
`edit` can shadow as well) and an option to let the IDE claim files it treats as binary. Terminals that are
already running keep the environment they started with, so a change takes effect in the next terminal.
Shells running over WSL or SSH are never shimmed; Windows is not supported yet.

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

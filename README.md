# Agenstorm

![Build](https://github.com/pronskiy/agenstorm/workflows/Build/badge.svg)

<!-- Plugin description -->
**Agenstorm removes the friction an agent-heavy workflow hits in the IDE every day.**

Coding agents changed what a working day looks like, and the IDE was not built for that rhythm.
Agenstorm closes the gaps, one small feature at a time. Every feature has its own on/off switch.

### Markdown live markup

Obsidian-style editing: `**bold**`, headings, links, `- [ ]` task boxes and bullets show their
result until the caret reaches them. Fenced code blocks and block quotes sit on a card. It is
folding only: the file on disk never changes, and copying always copies raw Markdown. An optional
switch also hides the editor/preview layout buttons.

### Clickable file locations

`src/Foo.php:42:7` becomes a link in Markdown, in comments of every language, and in PHP strings.
It is the format compilers, test runners and agents already emit, so nothing has to change on the
other side. **Copy Location Link** puts such a token on the clipboard, ready to paste back to an agent.

### `open` in the IDE terminal

`open src/Foo.php:42` opens that file in the window the terminal belongs to, caret on line 42,
instead of handing it to the OS. Anything the IDE does not claim (flags, URLs, missing paths,
binaries) reaches the real `open` untouched.

### The IDE as your terminal's editor

Press <kbd>Ctrl</kbd>+<kbd>G</kbd> in Claude Code and the prompt opens in this project's window
instead of vim. Close the tab and the edited text goes back to it. `git commit` and everything else
that reaches for `$EDITOR` behaves the same way.

### AI commit messages

A subject and body streamed into the commit message field from the Anthropic API, any
OpenAI-compatible endpoint (OpenAI, Ollama, LM Studio, OpenRouter, Groq), or the local `claude` CLI,
which needs no API key. No SDK and no bundled jars: just the platform's own HTTP client.

### One header row instead of two

Open projects become tabs inside the main toolbar, in the slot the project widget normally occupies,
so the window loses a row of chrome. The Git branch moves down to the status bar, and window titles
name the project rather than whichever file is open.

### A terminal that fills the window

One key fills the editor's area with the terminal, and the same key gives the editor back. Other
tool windows keep their place.

### Notification popups that hide themselves

Balloons fade after a few seconds instead of the IDE's 10 seconds, or 5 minutes for sticky ones.
The countdown pauses while the IDE is in the background, and everything stays in the Notifications
tool window; only the popup goes.

### Less chrome, on request

A switch that is **off** until you ask for it: clear the right tool window bar so the editor
reaches the window edge.

### Names typed in the project tree

New File, New Directory, Rename and Duplicate open an editable row in the tree instead of a dialog.
Type, press Enter, done. `src/Http/Client.php` creates the folders on the way.

### A shorter New Scratch File popup

Only the languages on your allow-list, in your order, with "All Languages…" at the bottom.

### Works anywhere

Features that need the PHP, Markdown, Git or Terminal plugin switch themselves off when it is
missing, so the plugin is at home in any IntelliJ-based IDE of the same version.

Free and open source, MIT: https://github.com/pronskiy/agenstorm
<!-- Plugin description end -->

## All features at a glance

| Feature | Default | Settings group |
|---|---|---|
| [Markdown live markup](#markdown-live-markup-1) | on | Markdown live markup |
| [Clickable file locations](#clickable-file-locations-1) | on | Location links |
| [`open` in the terminal](#opening-files-from-the-terminal) | on | Terminal |
| [IDE as `$EDITOR`](#the-ide-as-your-terminals-editor-1) | on | Terminal editor |
| [AI commit messages](#ai-commit-messages-1) | on | Commit messages |
| [Project tabs in the toolbar](#project-tabs) | on | Project tabs |
| [Window title without file names](#window-title) | on | Window title |
| [Terminal fills the window](#filling-the-window-with-the-terminal) | on | Terminal size |
| [Self-hiding notifications](#notifications) | on | Notifications |
| [Names typed in the project tree](#project-tree) | on | Project tree |
| [Shorter New Scratch File popup](#scratch-files) | on | Scratch files |
| [Hide the right tool window bar](#tool-windows) | **off** | Tool windows |

`SPEC.md` is the plan and the task list; `CHANGELOG.md` records what each version shipped.

## Compatibility

**PhpStorm 2026.2 (build 262.\*).** The plugin also loads in other IntelliJ-based IDEs of the same
version. Features that need the PHP, Markdown, Git or Terminal plugin switch themselves off when
that plugin is missing.

## Installation

**From JetBrains Marketplace**

<kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd> → search "Agenstorm" → <kbd>Install</kbd>

**From disk**

Download the [latest release](https://github.com/pronskiy/agenstorm/releases/latest), then
<kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>⚙️</kbd> → <kbd>Install plugin from disk…</kbd>

## Settings

Everything lives under <kbd>Settings</kbd> → <kbd>Tools</kbd> → <kbd>Agenstorm</kbd>, one group per
feature. Most features are on by default; the two marked **off** in the table above are not.

Settings are stored in `agenstorm.xml` in the IDE config directory, so they roam with Settings Sync.

---

## Markdown live markup

Obsidian-style editing: the syntax hides itself until the caret reaches the line.

- **Inline markers.** `**bold**`, `*italic*`, `~~strike~~`, `` `code` `` show only their text. Markers
  return for the element the caret touches and under a selection, so what you see is what you copy.
  A setting switches to revealing the whole caret line instead.
- **Headings, task boxes, bullets.** `#` marks are hidden; `- [ ]` / `- [x]` become ☐ / ☑ and toggle on
  click; `-`, `*`, `+` show as •.
- **Block quotes and GitHub alerts** (`> [!NOTE]`, `> [!WARNING]`, …) lose their `>` markers and get a
  card with an accent bar. Nesting still reads as indent. An alert's `[!…]` title stays as written.
- **Thematic breaks** (`---`, `***`, `___`) become a full-width rule.
- **Fenced code blocks** lose their ``` lines and sit on a full-width card; highlighting inside is
  untouched.
- **Links.** `[text](destination)` shows the text; Ctrl/Cmd+click or Ctrl+B follows the destination:
  files, `path:line:col` locations, `#headings` and URLs.
- **Layout buttons, if you want them gone.** A separate switch, off by default under Settings → Tools →
  Agenstorm → Markdown editor, hides the editor/preview buttons in Markdown editors. While it is on the
  preview cannot be opened at all, Find Action included.

### Turning it off

The **Live Markup** entry in the editor context menu turns it off or on for one editor, overriding
the global switch for that tab. Settings → Tools → Agenstorm → Markdown live markup has the global
switch and the per-element options.

### Good to know

Nothing is ever written to the file. It is folding: copying always copies raw Markdown, and the
Markdown plugin's own folding, Expand All and Collapse All keep working.

Limitations:

- Heading sizes stay at the editor's single line height.
- Images and reference-style links stay raw.
- The code-block card has no rounded corners, language chip or copy button.

---

## Clickable file locations

Bare file locations become links wherever they appear: Markdown link destinations, comments in any
language, and PHP string literals.

```
path/to/file.ext:LINE
path/to/file.ext:LINE:COLUMN
```

- Line and column are 1-based, like every compiler and test runner.
- Paths resolve relative to the containing file, then the project root, then every content root.
  Absolute paths work, and as a last resort a unique file name anywhere in the project matches.
- Locations that do not resolve stay plain text. Nothing is flagged as an error, because agents
  routinely refer to files they are about to create.

### Getting agents to emit them

Add a line like this to your `CLAUDE.md`, `AGENTS.md` or system prompt:

> When you refer to code, write the location as `path/from/project/root/File.php:LINE:COLUMN`
> (1-based) so it is clickable in the IDE. In Markdown, use it as the link destination:
> `[Foo::bar()](src/Foo.php:42:7)`.

### Copy Location Link

In the editor and gutter context menus. Puts a token for the caret (or selection start) on the
clipboard, plus a Markdown-link flavor (`[Foo.php:42](src/Foo.php:42:7)`).

No default shortcut. `Ctrl+Alt+Shift+L` is free in the default keymaps (Settings → Keymap, search
"Copy Location Link").

---

## AI commit messages

The lightning button above the commit message field streams a subject and body for the included
changes. Click again to stop.

- Undo removes the whole generation in one step.
- Text already in the field is treated as a hint: a multi-line draft is improved rather than
  replaced, and saved to the commit message history first.
- No default shortcut; `Ctrl+Alt+Shift+G` is free (Settings → Keymap, "Generate Commit Message").

### Backends

Settings → Tools → Agenstorm → Commit messages.

| Backend | Key needed | Notes |
|---|---|---|
| **Anthropic API** | yes | Stored in the IDE password safe, never in `agenstorm.xml`. Default model `claude-sonnet-5` |
| **OpenAI-compatible** | optional | OpenAI, Ollama (`http://localhost:11434/v1`, no key), LM Studio, OpenRouter, Groq. Model id required |
| **Claude CLI** | no | Runs `claude -p` with your Claude Code login. Default model `haiku` |

The CLI backend runs with extended thinking off and in safe mode, so your CLAUDE.md, plugins,
skills, hooks and MCP servers stay out of it: about 3 s instead of 20–50 s, and roughly 800 prompt
tokens instead of 6,000. Remove `--safe-mode` from "Extra arguments" if you want your
CLAUDE.md rules applied.

### Prompts and diffs

- Test Connection validates the values as typed.
- Templates are editable: `{diff}`, `{stat}`, `{branch}`, `{hint}`, `{language}` and `{conventional}`
  are substituted, unknown placeholders are kept, and "Reset to Default" restores the built-in text.
- Lock files and generated output never enter the diff (`vendor/`, `node_modules/`, `dist/`,
  `build/`, `*.min.*`, `*.map`). Every file still appears in the stat.

---

## Project tree

**Type names in the tree, not in a dialog.** On by default, under Settings → Tools → Agenstorm →
Project tree.

New File, New Directory and Rename put an editable row in the project tree and let you type the name
there. Enter commits, Escape cancels, clicking elsewhere accepts what you typed, the way a file
manager behaves.

- The row appears directly below the row you clicked, indented to where the file or folder will land.
- `src/Http/Client.php` creates the two folders on the way, the same as the dialog does.
- Renaming runs the real refactoring, so a PHP class file's usages are still updated, in one undo step.
  Nothing asks along the way: when `Client.php` holds class `Client`, the class is renamed with the file,
  exactly as pressing OK in PhpStorm's own "rename the class too?" dialog would. A rename that reaches into
  comments or plain text still shows you the list first.
- The new file is empty, exactly as `New | File` makes it today. The `<?php` still comes from
  PhpStorm's own PHP File entry, which this does not touch. Neither are PHP Class, Interface and
  Trait, whose dialogs carry a namespace a single field cannot hold.
- Anything the row cannot handle keeps the dialog it has today: a module root, a library, the project
  folder, and a folder that is a PSR namespace, where PhpStorm has namespace work to offer.
- The New menu's typed entries go inline too: **PHP File**, **HTML File**, **JavaScript File**,
  **TypeScript File**. The row opens on the extension with the caret in front of it, so New | PHP File
  shows `.php` waiting for a name, and the file arrives with its template body. An entry with no template of its own name, such as
  composer.json File, keeps its dialog. PHP Class, Interface and Trait keep theirs too, because a namespace
  does not fit in one field. The plain **File** entry still makes an empty file, exactly as it does today.
- **Duplicate** in the same menu copies a file or folder next to itself, opening the row on `Client 2.php`
  with `Client 2` selected. It has no shortcut out of the box, because ⌘D already means Duplicate Line
  everywhere; bind one under Settings → Keymap if you want it.
- Copy File… (F5) is untouched and keeps its target-directory browser. ⌘C / ⌘V in the tree are untouched too.
- Switching it off gives New File, New Directory and Shift+F6 straight back, without a restart, and takes
  Duplicate out of the menu.

## Tool windows

**Hide the right tool window bar.** Off by default, under Settings → Tools → Agenstorm → Tool windows.

Clears the strip of icons down the right edge: the tool windows anchored there move to the left bar,
and the IDE hides a bar with nothing on it, so the editor reaches the window edge.

- The windows keep their content and every way in: View | Tool Windows, their own shortcuts, the ⋯
  button on the left bar.
- They now open on the left, alongside Project, instead of on the right. That is the trade, and it is
  why the setting is off until you ask for it.
- Switching it off moves back exactly the windows Agenstorm moved. One you already had on the left
  stays there, one you re-docked yourself is left where you put it, and uninstalling the plugin puts
  them back too.

---

## Project tabs

On macOS, open projects appear as tabs inside the main toolbar, where the project widget normally
sits, so the window has one header row instead of two.

- Click a tab to switch, middle-click or × to close.
- "+" opens the usual recent-projects popup (New Project, Open, Clone).
- Right-click for Close, Close Others, Copy Path.
- Drag to reorder; order is remembered per project in `agenstorm-tabs.xml`.
- When the toolbar gets narrow, tabs shrink to icons, then a "…" button lists the rest.

### One window, not many

That is the platform's own macOS window tabs doing the work: Agenstorm leaves them on and hides only
their separate row, so switching a tab does not raise a second window.

> Agenstorm 1.0 switched those window tabs off, which is why every tab was its own window. Installs
> left that way are put right on first start, with a balloon offering the restart it needs.

Windows and Linux have no window merging to build on, so there the strip switches between windows
instead; the "move the other window onto this one's position and size" option exists for that case.

### The Git branch

With tabs on, the VCS widget next to them is hidden and the branch shows bottom-left in the status
bar, where the navigation bar was. Click it for the branches popup. The navigation bar comes back
when the option is turned off, provided Agenstorm was the one that hid it.

### Options and shortcuts

The **Project tabs** group holds the switch plus four options, all on by default: branch in the status
bar, mirror window position/size when switching, project icons, and max tab width (220 px).

Three actions ship without shortcuts so they never collide with your keymap: **Next Project Tab**,
**Previous Project Tab** (both wrap) and **Close Project Tab**. Settings → Keymap, search "Project
Tab". `Ctrl+Alt+Shift+]` and `Ctrl+Alt+Shift+[` are a natural pair.

---

## Window title

Window titles name the project, not whichever file happens to be open. On by default, Settings →
Tools → Agenstorm → Window title.

---

## Opening files from the terminal

Inside an IDE terminal, `open` opens files in the window the terminal belongs to:

```
open src/Foo.php            # opens the file
open src/Foo.php:42         # caret on line 42
open src/Foo.php:42:7       # caret on line 42, column 7
open src/A.php src/B.php    # opens both
open .                      # opens or focuses the directory
```

- Inside IDE terminals only; every other shell on the machine is unaffected.
- Flags, URLs, missing paths and binaries reach the real `open` untouched.
- A file belonging to another open project lands in that project's window.
- The shim announces itself once in a balloon.

Settings → Tools → Agenstorm → Terminal. You can change which command names the shim installs under,
and whether the IDE claims file types it treats as binary.

---

## The IDE as your terminal's editor

`$EDITOR` and `$VISUAL` in an IDE terminal point at this IDE, so anything reaching for an editor opens
a tab here and waits.

- Claude Code's <kbd>Ctrl</kbd>+<kbd>G</kbd>, `git commit` and `crontab -e` all behave the same way.
- The command stays blocked until the last tab for that file is closed, with what you typed on disk
  before it is let go.
- A maximized terminal steps aside for the file and takes its place back afterwards.
- Only one existing, writable file is ever claimed. An invocation with options, a missing path, a
  directory or a read-only file goes to your own editor, or to `vi`.
- Your rc file cannot break it: both variables are also set through the terminal's
  `_INTELLIJ_FORCE_SET_*` mechanism, which runs after your rc files, so the editor you exported
  becomes the fallback instead.

Settings → Tools → Agenstorm → Terminal editor.

---

## Filling the window with the terminal

<kbd>⌘</kbd><kbd>⌥</kbd><kbd>M</kbd> on macOS, <kbd>⌥</kbd><kbd>⇧</kbd><kbd>F12</kbd> elsewhere, or the
button in the terminal's title bar.

- The terminal fills the editor's area; the same key gives the editor back and returns the caret.
- Other tool windows keep their place and full height.
- The terminal opens first if it was closed, and the height you dragged it to is what comes back.

### Two things it changes, and gives back

**Widescreen layout.** Confining the terminal to the editor's column is what the IDE's Widescreen
tool window layout does, so Agenstorm turns it on the first time you *use* the toggle (never on
install), says so once in a balloon, and puts your layout back when the feature is switched off. A
widescreen layout you chose yourself is never claimed.

**⌘⌥M is Extract Method** in the macOS keymaps. While this feature is on, Maximize Terminal wins that
keystroke. Nothing in your keymap is changed to arrange it, a balloon says so the first time, and
rebinding either action or switching the feature off gives it back.

Settings → Tools → Agenstorm → Terminal size.

---

## Notifications

Balloons fade after 5 seconds instead of the IDE's own 10 seconds (Balloon groups) or 5 minutes
(Sticky balloon groups).

- The countdown pauses while the IDE is in the background, so nothing that arrived while you were
  elsewhere is gone before you look.
- Every notification stays in the Notifications tool window; only the popup goes.
- Errors keep the five-minute timer unless you opt them in.

Settings → Tools → Agenstorm → Notifications holds the delay and both switches.

---

## Scratch files

The New Scratch File popup lists only the languages on your allow-list, in your order, ending with an
"All Languages…" entry, so a language you left out is one click further away rather than out of reach.

Default list: `Plain text`, `Markdown`, `PHP`, `JavaScript`, `HTML`. Buttons add the current file's
language or pick from every registered one.

> Entries may also name a **file type**, which is what allow-lists written for 1.0 and 1.1 hold. Those
> keep working unchanged.

---

## Development

```bash
./gradlew check        # compile, test, coverage — run before every commit
./gradlew runIde       # PhpStorm 2026.2 sandbox with the plugin loaded
./gradlew buildPlugin  # distributable ZIP in build/distributions/
./gradlew verifyPlugin # IntelliJ Plugin Verifier
```

See `CLAUDE.md` for conventions and `SPEC.md` for the task list.

## License

MIT — see [LICENSE](LICENSE).

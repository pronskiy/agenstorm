#!/bin/sh
# Stand-in for the `claude` CLI in ClaudeCliBackendTest: records its arguments and stdin, then prints a fixed
# message. Environment knobs: FAKE_CLAUDE_ARGS_FILE, FAKE_CLAUDE_STDIN_FILE, FAKE_CLAUDE_FAIL (exit 3 with
# stderr), FAKE_CLAUDE_SLEEP (seconds before answering).
if [ -n "$FAKE_CLAUDE_ARGS_FILE" ]; then printf '%s\n' "$@" > "$FAKE_CLAUDE_ARGS_FILE"; fi
if [ -n "$FAKE_CLAUDE_STDIN_FILE" ]; then cat > "$FAKE_CLAUDE_STDIN_FILE"; else cat > /dev/null; fi
if [ -n "$FAKE_CLAUDE_FAIL" ]; then echo "boom: not logged in" >&2; exit 3; fi
if [ -n "$FAKE_CLAUDE_SLEEP" ]; then sleep "$FAKE_CLAUDE_SLEEP"; fi
printf 'feat: add thing\n\nBody from fake claude.\n'

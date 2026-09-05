#!/bin/sh
# Stand-in for the `claude` CLI in ClaudeCliBackendTest: records its arguments, stdin and environment, then
# answers. With `--output-format stream-json` among the arguments it prints one JSON event per line the way
# `claude -p --output-format stream-json --verbose --include-partial-messages` does; otherwise plain text.
# Environment knobs: FAKE_CLAUDE_ARGS_FILE, FAKE_CLAUDE_STDIN_FILE, FAKE_CLAUDE_ENV_FILE (records
# MAX_THINKING_TOKENS), FAKE_CLAUDE_FAIL (exit 3 with stderr before any output), FAKE_CLAUDE_SLEEP (seconds
# before answering), FAKE_CLAUDE_DELTA_SLEEP (seconds between the two text deltas), FAKE_CLAUDE_MODE:
# "deltas" (default: two text_delta events, then the assistant message and the result), "no-deltas" (an older
# CLI: assistant message and result only), "error-result" (result with is_error=true and exit 1).
if [ -n "$FAKE_CLAUDE_ARGS_FILE" ]; then printf '%s\n' "$@" > "$FAKE_CLAUDE_ARGS_FILE"; fi
if [ -n "$FAKE_CLAUDE_ENV_FILE" ]; then printf 'MAX_THINKING_TOKENS=%s\n' "${MAX_THINKING_TOKENS-unset}" > "$FAKE_CLAUDE_ENV_FILE"; fi
if [ -n "$FAKE_CLAUDE_STDIN_FILE" ]; then cat > "$FAKE_CLAUDE_STDIN_FILE"; else cat > /dev/null; fi
if [ -n "$FAKE_CLAUDE_FAIL" ]; then echo "boom: not logged in" >&2; exit 3; fi
if [ -n "$FAKE_CLAUDE_SLEEP" ]; then sleep "$FAKE_CLAUDE_SLEEP"; fi

stream_json=""
for arg in "$@"; do if [ "$arg" = "stream-json" ]; then stream_json=1; fi; done
if [ -z "$stream_json" ]; then
  printf 'feat: add thing\n\nBody from fake claude.\n'
  exit 0
fi

printf '%s\n' '{"type":"system","subtype":"init","model":"fake-model","tools":[],"mcp_servers":[]}'
case "${FAKE_CLAUDE_MODE:-deltas}" in
  error-result)
    printf '%s\n' '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"There is an issue with the selected model (bogus)."}],"error":"model_not_found"}}'
    printf '%s\n' '{"type":"result","subtype":"success","is_error":true,"result":"There is an issue with the selected model (bogus).","errors":null}'
    echo "[claude-code:unrecognized_model] {\"model\":\"bogus\"}" >&2
    exit 1
    ;;
  no-deltas)
    ;;
  *)
    printf '%s\n' '{"type":"stream_event","event":{"type":"message_start","message":{"role":"assistant","content":[]}}}'
    printf '%s\n' '{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}'
    printf '%s\n' '{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"feat: add thing"}}}'
    echo "not json: a stray diagnostic line the backend must ignore"
    if [ -n "$FAKE_CLAUDE_DELTA_SLEEP" ]; then sleep "$FAKE_CLAUDE_DELTA_SLEEP"; fi
    printf '%s\n' '{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"\n\nBody from fake claude."}}}'
    printf '%s\n' '{"type":"stream_event","event":{"type":"content_block_stop","index":0}}'
    printf '%s\n' '{"type":"stream_event","event":{"type":"message_stop"}}'
    ;;
esac
printf '%s\n' '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"feat: add thing\n\nBody from fake claude."}]}}'
printf '%s\n' '{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}'
printf '%s\n' '{"type":"result","subtype":"success","is_error":false,"result":"feat: add thing\n\nBody from fake claude.","errors":null,"num_turns":1}'

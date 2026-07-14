# Replay fixtures

`iex-session.jsonl` goes here — 30–45 minutes of raw Alpaca WebSocket frames
captured during a live US session (SETUP.md §5). It is deliberately
**committed** (see the `.gitignore` exception): it's test data, not a secret.

Capture it once, during market hours:

```bash
wscat -c wss://stream.data.alpaca.markets/v2/iex \
  -x '{"action":"auth","key":"'"$ALPACA_KEY_ID"'","secret":"'"$ALPACA_SECRET_KEY"'"}' \
  -w 1 \
  | tee src/test/resources/fixtures/iex-session.jsonl
```

then subscribe to a mix of liquid and thin names (include `SIRI` — its bar
gaps are the case the alert engine must handle):

```
{"action":"subscribe","bars":["AAPL","MSFT","SPY","TSLA","F","SIRI"],"trades":["AAPL","TSLA"]}
```

Verify before committing:

```bash
wc -l iex-session.jsonl            # want >1000 lines
grep -c '"T":"b"' iex-session.jsonl   # want >100 bar messages
```

Every downstream step (4–7) tests against this file via the `replay` profile.
**Do not start Step 4 without it.**

## `synthetic-sample.jsonl`

A hand-written 8-line fixture in the same wire format, used only by
`ReplayStreamClientTest`/`AlpacaFrameParserTest` to exercise the parser
(bar, updated-bar, trade, trade cancellation) without a live session. It is
**not** a substitute for `iex-session.jsonl` — it has no volume, no gaps in
a thin symbol, and doesn't satisfy the Step 0 acceptance gate. Once the real
capture exists, only `iex-session.jsonl` is used by the `replay` profile
default (`alpaca.replay-file` in `application.yml`); this file stays
test-only.

# Step 0 — Spike Notes

> Deliverable of Step 0 in `stock-tracker-implementation-plan.md`.
> Paste **actual response payloads** under each heading. If a section can't be
> filled in, Step 0 isn't done.

## 1. REST snapshot (`feed=iex`)

Command run, and response for `AAPL,MSFT`:

```json
TODO
```

## 2. SIP rejection on free tier (expected error 42210000 — this is a PASS)

```json
TODO
```

## 3. Market clock + calendar

`GET /v2/clock`:

```json
TODO
```

## 4. WebSocket bars stream

- Connected at (UTC / MYT): TODO
- Bars arrived every ~60s for ≥ 30 min: TODO
- Sample `b` message:

```json
TODO
```

## 5. Trade-channel cap (31st symbol rejected)

Error received on subscribing 31 trade symbols:

```json
TODO
```

## 6. Connection-limit check (validates dev/prod account split)

- 2nd WS on the **same** key → expected `406 connection limit exceeded`: TODO
- 1st WS on the **other** account's key → expected success: TODO

## 7. Decision log

- Is IEX-only pricing (C5, ~2% of volume) acceptable for v1? **TODO**
- Replay fixture captured and committed (`src/test/resources/fixtures/iex-session.jsonl`)? **TODO**

# US Stock Tracker — Step-by-Step Implementation Plan

**Stack decision (verified July 2026):** Java 25 (LTS) · Spring Boot 4.1.x · PostgreSQL 16 · Gradle 9.x · Alpaca Market Data API v2 · **no vendor SDK**

---

## Compatibility Register

Read this before Step 0. These constraints are load-bearing — the design falls out of them.

| # | Constraint | Verified fact | Design consequence |
|---|---|---|---|
| C1 | No official Java SDK | Alpaca ships Python / Go / NodeJS / C# SDKs only | Hand-roll the client with `RestClient` + `WebSocketClient` |
| C2 | Community SDK is split-brain | `alpaca-java` v11 = REST + SSE, **no WebSocket** (WIP). v10.0.1 = WebSocket, but OkHttp + Gson | Don't depend on it. If you must, use it for REST only |
| C3 | **1 concurrent WS connection per account** | True even on paid Algo Trader Plus. 2nd connect → `406 connection limit exceeded` | Single leader-elected ingestor. Dev and prod need **separate Alpaca accounts** |
| C4 | Free WS: 30 symbols for trades/quotes | **No cap on the `bars` channel** | Build the alert engine on minute bars. Reserve 30 trade slots for a hot-list |
| C5 | Free feed = IEX only | IEX ≈ 2% of US market volume | "Last price" for illiquid tickers can be stale/divergent. Must be disclosed in UI |
| C6 | Free REST + SIP = 15-min delayed | `latest`/`snapshot` endpoints reject `feed=sip` without subscription; historical SIP needs `end` ≥ 15 min old | Free tier: use `feed=iex` explicitly. Never let the default silently pick a feed |
| C7 | Rate limit 200 req/min (free) | Algo Trader Plus ($99/mo) → SIP + up to 10,000 rpm | Client-side rate limiter from day one, not bolted on later |
| C8 | Spring Boot 3.5 is EOL | OSS support ended 2026-06-30 | Start on 4.1.x. Note: Boot 4 defaults to **Jackson 3** (`tools.jackson.*`) |
| C9 | Timezone | Market = `America/New_York`, you = `Asia/Kuala_Lumpur` (UTC+8) | US open is **21:30–04:00 MYT** (Mar–Nov) / **22:30–05:00 MYT** (Nov–Mar). See Step 0 |
| C10 | Redistribution licensing | Market data licences typically restrict redistribution to third parties | Personal/internal use is fine. **Public multi-user product needs a licence review** |

**Cost model:** $0 to build and demo. $99/mo the moment you need real consolidated prices.

---

## Step 0 — Feasibility Spike & Account Setup

**Goal:** Prove the vendor works from Malaysia before writing a line of Spring. Kill the project early if it doesn't.
**Time:** half a day. **Do not skip.**

### Tasks
1. Create **two** Alpaca accounts (see C3): `dev@…` and `prod@…`. Generate paper-trading API keys for each. Store as env vars `ALPACA_KEY_ID` / `ALPACA_SECRET_KEY` — never in `application.yml`.
2. `curl` the REST snapshot from your machine, explicitly pinning the feed:
   ```
   curl -H "APCA-API-KEY-ID: $K" -H "APCA-API-SECRET-KEY: $S" \
     "https://data.alpaca.markets/v2/stocks/snapshots?symbols=AAPL,MSFT&feed=iex"
   ```
   Confirm you get data and are not geo-blocked.
3. Confirm C6 by deliberately requesting `feed=sip` on a `latest` endpoint. You should get error `42210000`. **Seeing this error is a passing test** — it proves you understand your own tier.
4. `wscat -c wss://stream.data.alpaca.markets/v2/iex`, authenticate, then `{"action":"subscribe","bars":["AAPL"]}`. Leave it running through a US session and confirm bars arrive every 60s.
5. Subscribe to `"trades":[…31 symbols…]` and confirm you get rejected at 31. **Verify C4 yourself** — don't trust this doc.
6. Open a second WS with the *same* key. Confirm `406`. Then open one with the *other account's* key and confirm it succeeds. **This validates the dev/prod split.**
7. Hit `GET /v2/clock` and `GET /v2/calendar`. These give you market open/close and the holiday calendar — you will use them instead of hardcoding anything.

### Deliverables
- `docs/spike-notes.md` with actual response payloads pasted in
- Two working key pairs
- A decision on whether IEX-only pricing (C5) is acceptable for v1

### Acceptance criteria
- [ ] Bars stream for ≥ 30 min without disconnect
- [ ] 31st trade subscription rejected
- [ ] 2nd connection on same key rejected, 1st connection on 2nd key accepted

### Limitations discovered here
> **L0.1** — Testing against live data requires you to be awake 21:30–04:00 MYT. **Mitigation:** capture 30 minutes of raw WS frames to a JSONL file during the spike. This becomes your replay fixture for every subsequent step, so you can develop at 10am.

---

## Step 1 — Gateway Skeleton + REST Quotes

**Goal:** A `MarketDataProvider` interface, an Alpaca implementation behind it, and a working `/api/quotes` endpoint. Nothing else in the app knows Alpaca exists.
**Time:** 2–3 days.

### Design
```java
public interface MarketDataProvider {
    Quote getQuote(String symbol);
    Map<String, Quote> getQuotes(Set<String> symbols);   // batched — see L1.1
    String feedName();                                    // "iex" | "sip" — surfaced to UI (C5)
}

public record Quote(
    String symbol,
    BigDecimal lastPrice,
    BigDecimal dailyChange,
    BigDecimal dailyChangePct,
    long volume,
    Instant asOf,          // vendor timestamp, NOT System.now()
    String feed,           // provenance
    boolean stale          // asOf older than threshold
) {}
```

`asOf` and `stale` are not optional polish. With an IEX-only feed (C5) a thinly-traded stock may not have printed for an hour, and a UI that shows a stale number as if it were live is actively misleading.

### Tasks
1. Gradle project, Spring Boot 4.1.x, Java 25 toolchain. Modules as packages: `gateway`, `quote`, `alert`, `notify`, `api`.
2. `AlpacaProperties` — `@ConfigurationProperties`, binds key/secret/base-url/feed.
3. `AlpacaMarketDataProvider implements MarketDataProvider` using Spring `RestClient`.
   - Always send `feed=iex` explicitly (C6).
   - Map Alpaca's snapshot JSON → your `Quote` record. **Never leak Alpaca's DTOs past this class.**
   - Prefer `/v2/stocks/snapshots?symbols=A,B,C` (batched) over N single calls.
4. Resilience4j: `RateLimiter` at 180 rpm (10% headroom under the 200 cap, C7), `Retry` with exponential backoff on 429/5xx, `CircuitBreaker` that opens after 5 consecutive failures.
5. `GET /api/quotes?symbols=AAPL,MSFT` → returns `List<Quote>`.
6. WireMock tests: happy path, 429, 500, malformed JSON, empty symbol, unknown symbol.

### Acceptance criteria
- [ ] `/api/quotes?symbols=AAPL,MSFT,TSLA` returns 3 quotes in one upstream call
- [ ] Firing 300 requests in 60s never produces a 429 from Alpaca (rate limiter absorbs it)
- [ ] Circuit breaker opens and returns a cached/degraded response, not a 500
- [ ] Zero Alpaca types are importable from outside the `gateway` package (enforce with ArchUnit)

### Limitations discovered here
> **L1.1** — The snapshots endpoint has a URL-length ceiling on the `symbols` param. Chunk requests at ~100 symbols. Build this into `getQuotes()` now.
> **L1.2** — Alpaca returns prices as JSON numbers. Parse into `BigDecimal` via `DecimalFormat`/string, **not** `double`. Binary floats will silently corrupt threshold comparisons like `price >= 150.00`.
> **L1.3** — Outside market hours the snapshot returns the previous close. Your `stale` flag and `asOf` are the only way to distinguish "market closed" from "vendor is broken."

---

## Step 2 — Symbol Universe & US-Only Enforcement

**Goal:** Requirement #3 — only US market. Enforce it at the boundary, not with a comment.
**Time:** 1–2 days.

### Tasks
1. On startup, call `GET /v2/assets?status=active&asset_class=us_equity`. This returns every tradable US symbol with `exchange` and `tradable` fields.
2. Persist to a `symbols` table: `symbol`, `name`, `exchange` (NYSE/NASDAQ/AMEX/ARCA), `tradable`, `fractionable`, `last_synced_at`.
3. Load into an in-memory `Set<String>` at boot. Refresh nightly via `@Scheduled` (after US close, so ~05:30 MYT — or just run it at 06:00 MYT).
4. `SymbolValidator` — rejects anything not in the set with a 400 and a helpful message. Every symbol entering the system (watchlist add, alert create, quote query) passes through it.
5. `GET /api/symbols/search?q=appl` — typeahead against the local table. Zero API calls.

### Acceptance criteria
- [ ] `GET /api/quotes?symbols=7113.KL` → 400, not an upstream call
- [ ] `GET /api/quotes?symbols=SPY` → 200
- [ ] Symbol table populated with several thousand rows at boot
- [ ] Search returns results with no network I/O

### Limitations discovered here
> **L2.1** — OTC symbols appear in the asset list but frequently have **no IEX data**. Filter to `exchange IN ('NYSE','NASDAQ','AMEX','ARCA','BATS')` for v1 and flag OTC as unsupported, rather than letting users create alerts that will never fire.
> **L2.2** — Ticker symbols get **reused** after delisting (a new company gets the old ticker). Store your internal FK against the Alpaca `asset_id` UUID, not the ticker string. This will bite you eventually if you don't.
> **L2.3** — The universe changes daily (IPOs, delistings, ticker changes from M&A). Your nightly sync must handle *removals* — decide now whether a delisted symbol disables the user's alert or deletes it. **Recommendation: disable + notify the user.** Never silently delete.

---

## Step 3 — Persistence, Users, Watchlists

**Goal:** The boring foundation. Get it right and Steps 5–6 become easy.
**Time:** 2–3 days.

### Schema (Flyway `V1__init.sql`)
```
users        (id, email, tz, created_at)
symbols      (asset_id PK, symbol, name, exchange, tradable, last_synced_at)
watchlists   (id, user_id, name)
watchlist_items (watchlist_id, asset_id)
alerts       (id, user_id, asset_id, condition, threshold, status,
              cooldown_seconds, last_fired_at, armed, created_at)
notifications(id, alert_id, channel, status, payload, attempt, sent_at)
```

### Tasks
1. Flyway + PostgreSQL via Testcontainers in the test suite.
2. JPA entities + repositories. `BigDecimal` for every price/threshold column, `NUMERIC(18,6)` in SQL. **No `double` anywhere in the schema.**
3. Watchlist CRUD endpoints.
4. `GET /api/watchlists/{id}/quotes` — joins the watchlist to `MarketDataProvider.getQuotes()`.
5. Caffeine cache in front of the provider: 5s TTL during market hours, 60s TTL when closed (read `/v2/clock` to know which).

### Acceptance criteria
- [ ] Testcontainers suite green from a clean DB
- [ ] Loading a 50-symbol watchlist makes **1** upstream call, not 50
- [ ] Second load within 5s makes **0** upstream calls

### Limitations discovered here
> **L3.1** — Caching and alerting have opposite requirements. Cache staleness that's invisible in a UI is a *missed alert* in the alert engine. **The alert engine must never read through this cache** — it reads the stream directly (Step 4). Keep the two paths separate.

---

## Step 4 — Streaming Ingestor (the hard step)

**Goal:** One process, one WebSocket, minute bars for every symbol anyone cares about, fanned out on an internal event bus.
**Time:** 4–5 days. **This is the riskiest step.**

### Design
```
Alpaca WS (1 conn, leader only)
   → BarMessage
     → SymbolPriceBus  (Spring ApplicationEventPublisher for v1)
       → AlertEvaluator      (Step 5)
       → QuoteCache updater  (feeds the UI)
       → UI push             (Step 7)
```

### Tasks
1. **Leader election.** ShedLock on Postgres, or a DB advisory lock. Only the leader opens the WS (C3). Followers serve HTTP and consume the bus. Even if you deploy one instance today, build this now — it is *very* painful to retrofit and rolling deploys will bite you the first time two instances overlap.
2. **`AlpacaStreamClient`** using Spring's `WebSocketClient`:
   - Connect → auth within the 10-second window → subscribe.
   - Handle every message type: `success`, `error`, `subscription`, `b` (bar), `t` (trade), `q` (quote).
   - **Reconnect with exponential backoff + jitter.** Re-subscribe on reconnect — subscriptions do not survive a drop.
   - Heartbeat/idle-timeout detection: no message for 90s during market hours ⇒ assume dead, reconnect.
3. **Dynamic subscription manager.** The subscription set = `distinct(symbols with active alerts) ∪ distinct(symbols in open UI sessions)`. When it changes, send an incremental `subscribe`/`unsubscribe`. Never resend the full set.
4. **Channel strategy (C4):**
   - `bars` for **all** watched symbols — unlimited, ~60s latency. This is the primary alert input.
   - `trades` for up to **30** hot symbols — sub-second. Rank by `count(active_alerts)` and re-rank every 15 minutes.
5. **Replay mode.** A profile that reads your Step-0 JSONL fixture and feeds it into the bus at 100× speed. Every downstream test runs against this.
6. **Market-hours gate.** Use `/v2/clock` and `/v2/calendar` (Step 0.7). Do not hardcode 9:30–16:00, do not hardcode holidays, do not compute DST yourself.

### Acceptance criteria
- [ ] Kill the network for 60s → client reconnects and re-subscribes automatically
- [ ] Start a 2nd instance → it does **not** open a WS; logs "not leader"
- [ ] Kill the leader → a follower acquires the lock and connects within 30s
- [ ] Adding an alert on a new symbol triggers an incremental `subscribe` within 1s
- [ ] Replay profile drives a full alert cycle with zero network access

### Limitations discovered here
> **L4.1** — **Alert latency floor is ~60s on the free tier** for any symbol outside the 30-symbol hot list. This is a hard product constraint. Say it out loud in your UI: *"Alerts evaluate on 1-minute bars."* Don't promise real-time and deliver 60s.
> **L4.2** — The single-connection limit means **your local dev instance and your deployed instance cannot both stream on the same account.** This is why Step 0 created two accounts. It also means you cannot horizontally scale the ingestor — ever. If you outgrow one process, you need a second Alpaca account, which is a licensing question, not an engineering one.
> **L4.3** — Alpaca sends **trade corrections and cancellations** (`c` / `x` message types) on the trade channel. A trade you alerted on can be cancelled after the fact. For v1: ignore corrections, but log them, and put a footnote in the UI. Do not build a financial-grade system on the assumption that prints are final.
> **L4.4** — Bars are emitted just after each minute mark, and a *late* trade can produce an `updatedBars` message revising a bar you already processed. Decide: ignore `updatedBars` (simple, slightly wrong) or make your evaluator idempotent per `(symbol, bar_timestamp)` (correct). **Recommendation: idempotent.** It's cheap now and impossible later.
> **L4.5** — The WS negotiates permessage-deflate compression (RFC-7692). Spring's default client won't negotiate it, so you'll receive clear text. Fine — but if you later swap HTTP clients, bandwidth will change and you may hit buffer limits. Set an explicit max frame size.

---

## Step 5 — Alert Engine

**Goal:** Requirement #2. Correctness over latency.
**Time:** 3–4 days.

### Model
```java
enum Condition { PRICE_ABOVE, PRICE_BELOW, PCT_CHANGE_UP, PCT_CHANGE_DOWN, VOLUME_ABOVE }

record Alert(UUID id, UUID userId, UUID assetId, Condition condition,
             BigDecimal threshold, Duration cooldown,
             boolean armed, Instant lastFiredAt, Status status) {}
```

### The three things everyone gets wrong

1. **Re-arm hysteresis.** A naive `if (price > threshold) fire()` sends 400 emails when a stock oscillates around $150.00. Use a state machine:
   - `ARMED` → price crosses threshold → **fire**, transition to `TRIGGERED`
   - `TRIGGERED` → only returns to `ARMED` when price moves back **through a re-arm band** (e.g. 0.5% below the threshold), *and* the cooldown has elapsed
   - Store `armed` in the DB, not in memory — it must survive a restart

2. **Cooldown.** Even with hysteresis, cap at one notification per alert per `cooldown_seconds` (default 3600).

3. **Market-hours gating.** Never fire on a pre-market/after-hours print from a 2%-of-volume feed unless the user explicitly opted into extended hours. This is where false alerts come from.

### Tasks
1. `AlertEvaluator` — subscribes to `SymbolPriceBus`. In-memory index `Map<AssetId, List<Alert>>`, rebuilt on alert CRUD.
2. Idempotency key per evaluation: `(alert_id, bar_timestamp)`. Dedupes L4.4 revised bars.
3. On fire: write a `notifications` row with status `PENDING` inside the same transaction that flips the alert to `TRIGGERED`. **Do not send the email here.** (Outbox pattern — Step 6 picks it up.)
4. Alert CRUD API. Validate: symbol exists (Step 2), threshold > 0, cooldown ≥ 60s.
5. Property-based tests: generate random price walks, assert the number of fires never exceeds `duration / cooldown`.

### Acceptance criteria
- [ ] Price oscillating ±0.1% around the threshold for an hour produces exactly **1** notification
- [ ] Restart mid-cycle → `armed` state is preserved, no duplicate fire
- [ ] Replaying the same bar twice produces 1 notification
- [ ] Alert on a symbol with no active alerts unsubscribes it from the WS

### Limitations discovered here
> **L5.1** — With minute bars you see OHLC, not every tick. A stock that spikes to $151 and back to $149 *within* one minute will show `high=151` on the bar. **Decide: evaluate against `close` (conservative, misses intra-minute spikes) or `high`/`low` (catches spikes, but you're alerting on a price the user could never have acted on).** Recommendation: evaluate `high`/`low` but label the notification with the bar window, so the user knows it wasn't a close.
> **L5.2** — On the IEX feed (C5), an illiquid stock may produce **no bar at all** for a given minute. Absence of a bar is not absence of price movement. For thin symbols, supplement the stream with a periodic REST snapshot poll. Budget this against C7's 200 rpm.
> **L5.3** — `PCT_CHANGE` needs a baseline. "Percent change from what?" — previous close, session open, or alert-creation time? These are three different products. Pick one, name it in the UI, and don't let it be ambiguous.

---

## Step 6 — Notification Delivery

**Goal:** The alert actually reaches a human. Exactly once, ideally.
**Time:** 2 days.

### Tasks
1. **Outbox poller.** `@Scheduled(fixedDelay=1000)` picks up `PENDING` notifications, `SELECT … FOR UPDATE SKIP LOCKED` so multiple instances don't double-send.
2. Channel abstraction: `NotificationChannel { send(Notification) }`. Implementations: `EmailChannel` (SendGrid/SES), `WebhookChannel`, later `PushChannel` (FCM).
3. Retry with backoff, max 3 attempts, then `DEAD` + surface in the UI. **Never retry forever.**
4. Rate-limit per user (e.g. max 20 notifications/hour) as a safety net against a bug in Step 5.
5. Templated email: symbol, condition, threshold, actual price, bar timestamp **in the user's timezone**, and a link to the chart.

### Acceptance criteria
- [ ] Kill the app mid-send → notification is redelivered, not lost
- [ ] Two instances running → no duplicate emails (SKIP LOCKED verified under load)
- [ ] SendGrid returns 500 → 3 retries, then DEAD, visible in `/api/notifications`

### Limitations discovered here
> **L6.1** — Outbox + retry gives you **at-least-once**, not exactly-once. A user may occasionally get a duplicate email. This is the correct trade-off (losing an alert is worse than duplicating one), but include a dedupe key in the email so the client can collapse them.
> **L6.2** — Email deliverability is its own project. Alert emails from a new domain will land in spam. Budget time for SPF/DKIM/DMARC. This is not a Java problem and it will take longer than you think.

---

## Step 7 — API + Live UI

**Goal:** Requirement #1 — show current prices, updating live.
**Time:** 3–4 days.

### Tasks
1. Spring WebSocket + STOMP. Client subscribes to `/topic/quotes/{symbol}`.
2. `QuoteBroadcaster` listens on the internal `SymbolPriceBus` and pushes to STOMP topics. **Throttle to max 1 update/symbol/second** — no UI needs 50 updates a second and your browser will thank you.
3. REST fallback for initial page load (snapshot) then upgrade to WS.
4. Frontend: React or plain HTML+JS. Watchlist table, sparkline, alert management.
5. **Disclose the feed.** A persistent badge: `IEX feed · ~2% of market volume · not a consolidated last price` (C5). Non-negotiable — this is the difference between a demo and a lie.
6. Show `asOf` age on every row. Grey out anything older than 60s.

### Acceptance criteria
- [ ] Open the UI during market hours → prices tick without a refresh
- [ ] Open 10 browser tabs → still exactly 1 Alpaca WS connection
- [ ] Disconnect wifi → UI shows "disconnected", reconnects and backfills on return

### Limitations discovered here
> **L7.1** — Every open browser tab wants a symbol subscribed upstream. Ref-count subscriptions and unsubscribe on the last viewer leaving, or your subscription set grows monotonically until the WS chokes.

---

## Step 8 — Hardening

**Time:** ongoing.

1. **Metrics** (Micrometer → Prometheus): upstream call count vs. the 200 rpm budget, WS connection uptime, reconnect count, bar-to-alert latency (p50/p99), notification send latency, alerts fired/suppressed-by-cooldown.
2. **Alert on your alerting.** If the WS has been down for > 5 minutes during market hours, page yourself. A silently dead ingestor means silently missed alerts — the worst possible failure mode, because it looks exactly like "no alerts triggered."
3. **Reconciliation job.** Every 15 minutes, REST-poll a sample of symbols with active alerts and compare against what the stream last delivered. Discrepancy ⇒ the stream is lying. Log loudly.
4. Structured logging with `symbol` + `alert_id` in MDC.
5. Secrets in a vault, not env vars, once this leaves your laptop.

---

## Revised Limitations Summary

Consolidated, ranked by how much they should worry you:

| Severity | Limitation | Where |
|---|---|---|
| 🔴 **Blocker for a real product** | Free feed is IEX-only (~2% of volume). Prices are not the consolidated last price. | C5, L5.2 |
| 🔴 | Redistributing real-time data to other users likely requires a licence. Personal use only until reviewed. | C10 |
| 🟠 **Architectural** | 1 WebSocket connection per account. Ingestor cannot scale horizontally. | C3, L4.2 |
| 🟠 | Alert latency floor ≈ 60s (minute bars) outside a 30-symbol hot list. | C4, L4.1 |
| 🟠 | Trades can be corrected/cancelled after you've alerted on them. | L4.3 |
| 🟡 **Engineering** | No official Java SDK; community SDK's WebSocket support is in flux. | C1, C2 |
| 🟡 | 200 req/min ceiling on free tier. | C7 |
| 🟡 | At-least-once notification delivery; duplicates possible. | L6.1 |
| 🟡 | You're 12–13 hours offset from the market you're tracking. | C9, L0.1 |

**The $99/month question:** Algo Trader Plus upgrades you to the full SIP feed (100% of market volume) and 10,000 rpm. It does **not** lift the 1-connection limit. So it fixes 🔴 C5 and 🟡 C7, and nothing else. Everything architectural in this plan survives the upgrade unchanged — which is the point of designing against the constraints rather than around them.

---

## Suggested Sequencing

| Week | Steps |
|---|---|
| 1 | Step 0 + Step 1 |
| 2 | Step 2 + Step 3 |
| 3–4 | **Step 4** (budget the most time here) |
| 5 | Step 5 |
| 6 | Step 6 + Step 7 |
| 7+ | Step 8, then decide on the paid tier |

Do not start Step 4 until Step 0's replay fixture exists. Everything after it depends on being able to test without a live market.

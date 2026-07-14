# Stock Tracker (US)

A US stock tracker: live watchlist prices, price/volume alerts, and email/webhook
notifications — built against the Alpaca Market Data API on the free (IEX) tier.

**Stack:** Java 25 · Spring Boot 4.1 · PostgreSQL 17 · Gradle 9 · Alpaca Market Data API v2 (no vendor SDK)

**Status:** Steps 1–8 of the implementation plan are written, compiled, and covered by 42
passing tests — but **Step 0 was never run** (no real Alpaca credentials or live market
session exist in the environment that built this). See [`HANDOFF.md`](HANDOFF.md) before
you trust any of it.

## Read this first

| Doc | What it is |
|---|---|
| [`HANDOFF.md`](HANDOFF.md) | **Start here if code already exists.** What's implemented, what's stubbed, what needs your intervention, and every bug/API surprise found along the way. |
| [`SETUP.md`](SETUP.md) | Environment setup, Alpaca accounts, connectivity checks, and the verification gate you must pass before Step 0. |
| [`docs/stock-tracker-implementation-plan.md`](docs/stock-tracker-implementation-plan.md) | The full Step 0 → Step 8 implementation plan, including the compatibility register (C1–C10) the whole design hangs on. |
| [`docs/spike-notes.md`](docs/spike-notes.md) | Step 0 deliverable — still a template. Fill in real API payloads as you run the feasibility spike. |
| [`src/test/resources/fixtures/README.md`](src/test/resources/fixtures/README.md) | How to capture the real WebSocket replay fixture — the committed `synthetic-sample.jsonl` is test-only, not a substitute. |

## What's already done vs. what you must do on your own machine

Steps 1–8 are implemented: gateway/REST quotes, symbol universe sync, persistence,
the streaming ingestor with leader election, the alert engine, notification outbox,
a live STOMP-backed UI, and basic hardening (metrics, health checks, reconciliation).
Full accounting in [`HANDOFF.md`](HANDOFF.md), including every bug that turned up
from actually compiling against real dependency versions instead of guessing.

None of that changes what only a human at a real desk can do — this environment
can't install software, hold secrets, or sit through a live US trading session:

- [ ] Install JDK 25, Docker, and Node.js 20+ (`SETUP.md` §0–1)
- [ ] Create the **two** Alpaca paper-trading accounts (dev + prod) and generate API keys
      — this requires signing up with two real email addresses (`SETUP.md` §3)
- [ ] Store the keys in `~/.stocktracker/{dev,prod}.env` on your machine, `chmod 600`
      — **never** commit or paste these anywhere (`SETUP.md` §3)
- [ ] Run the four connectivity checks against your own network/IP (`SETUP.md` §4)
- [ ] Run the Step 0 feasibility spike and fill in real payloads in
      [`docs/spike-notes.md`](docs/spike-notes.md) — **still a template**, not done
- [ ] Stay up once during a live US session (21:30–04:00 MYT) to capture the real
      WebSocket replay fixture (`SETUP.md` §5) — the committed `synthetic-sample.jsonl`
      is 8 hand-written lines for unit tests only, not a substitute
- [ ] Tick every box in the `SETUP.md` §10 verification gate
- [ ] Run `./gradlew build` on your machine — this sandbox's network policy blocks
      the Gradle distribution download, so the real Gradle 9.6.1/JDK 25 combination
      has never actually built this repo (see `HANDOFF.md` for what *was* verified)
- [ ] Wire a real email provider — `EmailChannel` is currently a logging stub

## Quick start

### 1. Prerequisites

- **JDK 25** (Temurin). The Gradle build declares a Java 25 toolchain and will
  auto-download one if missing (via the Foojay resolver), but a local install is faster:
  `sdk install java 25-tem`
- **Docker** — required for local Postgres *and* the Testcontainers test suite.
- **Node.js 20+** — only for `wscat` (`npm i -g wscat`), used in the spike.
- Nothing else: Gradle comes from the wrapper, Postgres runs in Docker.

### 2. Start the database

```bash
docker compose up -d
docker compose ps   # postgres must show "healthy"
```

### 3. Alpaca credentials

Create **two** free paper-trading accounts (dev + prod — Alpaca allows only **one**
concurrent WebSocket connection per account; see C3 in the plan). Store keys outside
the repo per [`SETUP.md §3`](SETUP.md), then:

```bash
source ~/.stocktracker/dev.env
```

`*.env` and `.stocktracker/` are already git-ignored. **Never** put keys in `application.yml`.

### 4. Build and test

```bash
./gradlew build
```

First run downloads Gradle 9.6.1 (and JDK 25 if needed). The test suite includes a
Testcontainers smoke test, so the Docker daemon must be running.

### 5. Run

```bash
./gradlew bootRun                                          # real config, needs ALPACA_* env vars
./gradlew bootRun --args='--spring.profiles.active=test-stream'   # Alpaca 24/7 synthetic stream (FAKEPACA)
./gradlew bootRun --args='--spring.profiles.active=replay'        # committed WS fixture, zero network
```

Actuator: <http://localhost:8080/actuator/health> · metrics at `/actuator/prometheus`.

### Profiles

| Profile | Data source | When to use |
|---|---|---|
| *(default)* | Live Alpaca IEX feed | Real session testing only — US market hours are 21:30–04:00 MYT |
| `test-stream` | `wss://…/v2/test`, symbol `FAKEPACA`, runs 24/7 | Daytime development of the stream path |
| `replay` | `src/test/resources/fixtures/iex-session.jsonl` | All automated tests; offline dev |

## Project layout

```
com.stocktracker
├── gateway/    # ONLY package that knows Alpaca exists (REST client, WS stream client,
│               # leader election, resilience, metrics) — Alpaca DTOs live in gateway.alpaca
│               # and never leave it, enforced by an ArchUnit test
├── stream/     # Vendor-neutral BarEvent/TradeEvent + the internal price bus
├── quote/      # Quote record, UI-side caching (never read by the alert engine — L3.1)
├── symbol/     # US symbol universe sync, validation, typeahead search
├── user/       # Minimal user accounts (email, timezone, webhook URL)
├── watchlist/  # Watchlist CRUD, joined to live/cached quotes
├── alert/      # Alert engine: state machine, hysteresis, cooldown, reconciliation
├── notify/     # Outbox poller, notification channels (webhook real, email stub), retries
└── api/        # REST controllers, STOMP config + broadcaster, static UI
```

Flyway owns the schema (`src/main/resources/db/migration`); JPA runs with
`ddl-auto: validate`. Prices are `BigDecimal`/`NUMERIC` everywhere — never `double`.

## Constraints worth knowing before you touch anything

Full detail in the plan's compatibility register; the load-bearing ones:

- **1 WebSocket connection per Alpaca account** — hence two accounts (dev/prod) and a
  single leader-elected stream ingestor.
- **Free feed is IEX only (~2% of US volume)** — prices can be stale/divergent and the UI
  must disclose it. Alerts evaluate on 1-minute bars (unlimited channel); the 30-symbol
  trade channel is reserved for a hot-list.
- **200 requests/min REST ceiling** — client-side rate limiter at 180 rpm from day one.
- **Resilience4j core modules only, used functionally** — do *not* add
  `resilience4j-spring-boot3`; it's incompatible with Boot 4 (SETUP.md §7).
- **Boot 4 defaults to Jackson 3** (`tools.jackson.*`) — check before adding any
  Jackson-2-based library.

## Where to begin

1. Read [`HANDOFF.md`](HANDOFF.md) — what's implemented, what's stubbed, what to check first.
2. Work through [`SETUP.md`](SETUP.md) top to bottom and tick every box in §10.
3. Run the Step 0 spike; record results in [`docs/spike-notes.md`](docs/spike-notes.md).
4. Capture the real replay fixture during one live US session.
5. Run `./gradlew build` for real — Docker-dependent tests (`PostgresSmokeTest`,
   `WatchlistQuoteBatchingIT`) and the Gradle 9.6.1/JDK 25 toolchain itself have
   never executed outside this session's workarounds.
6. From there: review the code against Steps 1–8 in the plan, wire a real email
   provider, and decide on the paid Alpaca tier per the plan's cost model.

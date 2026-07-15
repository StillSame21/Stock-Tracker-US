# Implementation Handoff

Steps 1–8 of `docs/stock-tracker-implementation-plan.md` are implemented and
committed on this branch. This document is the honest accounting of what
that means, what was skipped, and exactly what you need to do before any of
it touches real money or real users.

## What's actually done

Real, compiling, tested code for every step:

| Step | What's there |
|---|---|
| 1 — Gateway + REST quotes | `MarketDataProvider`/`Quote`, Alpaca REST client with rate limiter + circuit breaker + retry, ArchUnit boundary test, WireMock tests |
| 2 — Symbol universe | Nightly + startup asset sync, `SymbolValidator`, exchange filtering, disable-not-delete on delisting |
| 3 — Persistence | Flyway schema, watchlist CRUD, Caffeine cache (5s/60s TTL), batching |
| 4 — Streaming ingestor | ShedLock leader election, `AlpacaStreamClient` (reconnect/backoff/idle-watchdog), `SubscriptionManager` (bars unlimited, trades top-30), replay mode |
| 5 — Alert engine | Re-arm hysteresis, cooldown, market-hours gating, idempotent bar handling, jqwik property test |
| 6 — Notifications | Outbox poller (`SKIP LOCKED`, retry+backoff, rate limit), real `WebhookChannel`, stub `EmailChannel` |
| 7 — Live UI | STOMP broadcaster (throttled), ref-counted UI subscriptions, minimal frontend with feed disclosure |
| 8 — Hardening | Prometheus metrics, stream health indicator, 15-min reconciliation job, MDC logging |

**42 tests pass** in this sandbox (everything not gated on a Docker daemon).
Every dependency version and every non-obvious API call was checked against
the actual published jar before being used — this caught several real bugs
along the way (see "Bugs found and fixed" below), not just typos.

## What you must do before this is usable

These aren't polish items — the software is genuinely incomplete without them:

- [ ] **Run `SETUP.md` end to end and pass Step 0.** Nothing in this repo
      has touched a real Alpaca account. `docs/spike-notes.md` is still a
      template.
- [ ] **Capture the real replay fixture** (`src/test/resources/fixtures/iex-session.jsonl`)
      during a live US session. `synthetic-sample.jsonl` (8 hand-written
      lines) only proves the parser works — it has no volume, no gaps in a
      thin symbol, and doesn't satisfy the Step 0 gate.
- [ ] **Wire a real email provider.** `EmailChannel` is a logging stub —
      every "EMAIL" notification is marked `SENT` without a human ever
      receiving anything. See the class Javadoc for exactly what to
      replace and where the template data already lives.
- [ ] **Run the Testcontainers/Docker-dependent tests** (`PostgresSmokeTest`,
      `WatchlistQuoteBatchingIT`) — this sandbox has no Docker daemon, so
      they've never actually executed, only compiled.
- [ ] **Run `./gradlew build` end to end** — this sandbox's network policy
      blocks `downloads.gradle.org`, so the Gradle 9.6.1 wrapper distribution
      itself was never downloaded here. Every compile/test cycle in this
      session used a workaround (system Gradle 8.14 + JDK 21 with the
      toolchain temporarily relaxed, then restored before each commit —
      check `git diff` on `build.gradle.kts`/`settings.gradle.kts` right now
      if you want to confirm they're back to Java 25 + the Foojay resolver).
      The real Gradle 9.6.1/JDK 25 combination has never run this build.
- [ ] **Wire actual paging** on top of `StreamHealthIndicator` and the
      `ReconciliationService` warning logs — both are detection-only.
- [ ] **Move secrets to a real vault** before this leaves your laptop
      (SETUP.md's env-var pattern is what's implemented now).
- [ ] **Open a browser against the live STOMP endpoint.** The UI, STOMP
      config, and broadcaster all compile and have unit coverage, but
      nothing in this session actually loaded `index.html` and watched
      prices tick — there's no running Postgres or Alpaca account here to
      boot the full app against.

## Scope cuts, documented in code where they live

- **VOLUME_ABOVE** evaluates a single bar's volume, not cumulative daily
  volume — accurate daily accumulation needs session-boundary reset logic
  that didn't fit this pass. (`AlertEvaluator`)
- **Re-arm band for VOLUME_ABOVE** is "re-arm as soon as cooldown allows" —
  volume isn't mean-reverting around a threshold the way price is, so no
  band is defined. (`AlertEvaluator`)
- **PCT_CHANGE baseline** is previous close, cached per symbol per day via
  one REST call, cleared on the same nightly schedule as the symbol sync.
  (`AlertEvaluator`)
- **Replay cadence** is a fixed 10ms delay between lines, not a
  reconstruction of the original capture's timing. (`ReplayStreamClient`)

## Bugs found and fixed by actually compiling and running tests

Every one of these would have shipped silently if this had been written
without checking against real jars and running the test suite:

1. `spring-boot-starter-aop` doesn't exist for Boot 4.1.0 (confirmed 404 on
   Maven Central) — removed; not needed since retry uses resilience4j core
   functionally rather than a Spring Framework 7 annotation.
2. `resilience4j-all`'s `Decorators` helper isn't available from the core
   modules alone — rate limiter/circuit breaker/retry are composed by hand.
3. Testcontainers 2.x renamed `org.testcontainers:postgresql` to
   `testcontainers-postgresql` (and needs `testcontainers-junit-jupiter`
   explicitly), and `PostgreSQLContainer` dropped its generic type parameter.
4. Jackson 3's `MissingNode.decimalValue()` throws instead of defaulting to
   zero (Jackson 2's behavior) — fixed with `decimalValueOpt()`.
   `JsonNode.asText()`/`asText(default)` are deprecated in favor of
   `asString()`/`asString(default)`.
5. `Alert.id` is `@GeneratedValue`, so directly-constructed test entities
   have a null id — `AlertEvaluator`'s idempotency map is a plain
   `ConcurrentHashMap`, which rejects null keys. Doesn't affect production
   (every `Alert` the evaluator sees was already loaded from the database).
6. Boot 4 moved health-check support out of `spring-boot-actuator` into a
   new `spring-boot-health` module; `HealthIndicator`/`Health` live at
   `org.springframework.boot.health.contributor`, not
   `org.springframework.boot.actuate.health`.

## Judgment calls worth knowing about

- **Retry uses resilience4j core, not Spring Boot 4's native `@Retryable`.**
  SETUP.md §7 mentions the native annotation, but its exact package for
  Spring Framework 7 couldn't be verified without live docs access. Using
  the same core-module approach as the rate limiter/circuit breaker
  sidesteps the risk of shipping an import that doesn't compile.
- **`ReconciliationService` lives in the `alert` package, not `gateway`.**
  It needs both alert data and market data; putting it in `gateway` would
  have created a circular package dependency (`gateway` already has no
  reason to depend on `alert`, and several `alert` classes legitimately
  depend on `gateway`).

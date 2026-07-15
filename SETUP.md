# SETUP.md

Complete every section here **before** starting Step 0 of `stock-tracker-implementation-plan.md`.
Each section ends with a **verify** command. If it doesn't pass, stop and fix it — do not proceed.

**Estimated time:** 2–3 hours, most of it waiting for downloads.

---

## 0. Prerequisites Checklist

| Tool | Version | Why this version |
|---|---|---|
| JDK | **25** (LTS, Temurin) | Spring Boot 4.1 supports 17–26. 25 is the current LTS. |
| Gradle | **9.x** | 9.6 runs on JDK 17–26. Use the wrapper, not a system install. |
| Docker | any recent | Postgres + Testcontainers. **Non-negotiable** — the test suite won't run without it. |
| Node.js | 20+ | Only for `wscat`. Not used by the app. |
| PostgreSQL | 17 (via Docker) | Don't install it natively. |
| IDE | IntelliJ IDEA 2025.2+ | Older versions don't understand Java 25 syntax. |

---

## 1. Install the JDK

Use SDKMAN so you can switch versions per-project.

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

sdk install java 25-tem
sdk use java 25-tem
```

**Verify:**
```bash
java -version   # must print 25.x
```

> ⚠️ If you have Java 17 or 21 lingering in `JAVA_HOME`, Gradle will silently use *that* instead. Check `echo $JAVA_HOME`.

---

## 2. Docker + PostgreSQL

Create `docker-compose.yml` in the project root:

```yaml
services:
  postgres:
    image: postgres:17-alpine
    container_name: tracker-db
    environment:
      POSTGRES_DB: stocktracker
      POSTGRES_USER: tracker
      POSTGRES_PASSWORD: tracker_local_only
    ports:
      - "5432:5432"
    volumes:
      - tracker-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U tracker -d stocktracker"]
      interval: 5s
      retries: 5

volumes:
  tracker-pgdata:
```

**Verify:**
```bash
docker compose up -d
docker compose ps          # postgres must show "healthy"
docker exec -it tracker-db psql -U tracker -d stocktracker -c "SELECT version();"
```

> Testcontainers needs the Docker **daemon**, not just the CLI. On macOS/Windows make sure Docker Desktop is actually running, not just installed.

---

## 3. Alpaca Accounts — create TWO

This is not paranoia. Alpaca permits **one concurrent WebSocket connection per account**. If you use the same key on your laptop and on your deployed instance, one of them gets `406 connection limit exceeded` — and it will be whichever one connected second, which is non-deterministic and maddening to debug.

1. Sign up at `alpaca.markets` → **Paper Trading** account. Call it `dev`.
2. Sign up again with a different email → second Paper account. Call it `prod`.
3. In each dashboard: **Generate API Key**. You see the secret **once**. Copy it.

Both accounts sit on the **Basic (free)** market-data plan by default — IEX feed, 200 req/min, 30-symbol cap on trade/quote channels, no cap on bars.

**Store the keys** in `~/.stocktracker/dev.env` (mode `600`, and `.gitignore` it):

```bash
mkdir -p ~/.stocktracker && chmod 700 ~/.stocktracker

cat > ~/.stocktracker/dev.env <<'EOF'
export ALPACA_KEY_ID="PK..."
export ALPACA_SECRET_KEY="..."
export ALPACA_DATA_URL="https://data.alpaca.markets"
export ALPACA_TRADING_URL="https://paper-api.alpaca.markets"
export ALPACA_STREAM_URL="wss://stream.data.alpaca.markets/v2/iex"
export ALPACA_FEED="iex"
EOF

chmod 600 ~/.stocktracker/dev.env
source ~/.stocktracker/dev.env
```

Do the same for `prod.env` with the second account's keys.

> 🔒 **Never** put these in `application.yml`, and never commit them. Add `*.env` and `.stocktracker/` to `.gitignore` now, before you forget.

---

## 4. Verify Alpaca Connectivity

Four checks. **All four must pass**, including the two that are *supposed* to fail.

### 4.1 REST works from Malaysia
```bash
curl -s -H "APCA-API-KEY-ID: $ALPACA_KEY_ID" \
        -H "APCA-API-SECRET-KEY: $ALPACA_SECRET_KEY" \
  "$ALPACA_DATA_URL/v2/stocks/snapshots?symbols=AAPL,MSFT&feed=iex" | jq .
```
✅ Expect JSON with `latestTrade`, `dailyBar`, `prevDailyBar` per symbol.
❌ 403 → key is wrong. Empty → you're outside market hours (fine, you'll still get `prevDailyBar`).

### 4.2 Confirm your tier's ceiling (this failure is the point)
```bash
curl -s -H "APCA-API-KEY-ID: $ALPACA_KEY_ID" \
        -H "APCA-API-SECRET-KEY: $ALPACA_SECRET_KEY" \
  "$ALPACA_DATA_URL/v2/stocks/AAPL/trades/latest?feed=sip" | jq .
```
✅ Expect `{"code":42210000,"message":"subscription does not permit querying recent SIP data"}`

**Seeing this error is a pass.** It proves you understand your own subscription. If you *don't* see it, you're on a paid plan and should re-read the cost assumptions in the plan.

### 4.3 Market clock + calendar
```bash
curl -s -H "APCA-API-KEY-ID: $ALPACA_KEY_ID" \
        -H "APCA-API-SECRET-KEY: $ALPACA_SECRET_KEY" \
  "$ALPACA_TRADING_URL/v2/clock" | jq .
```
✅ Expect `{"timestamp":..., "is_open":false, "next_open":"...", "next_close":"..."}`

You will use `/v2/clock` and `/v2/calendar` everywhere instead of hardcoding market hours. **Do not hardcode 9:30–16:00. Do not hardcode holidays. Do not compute DST yourself.**

### 4.4 The test stream — this is the important one
```bash
npm install -g wscat
wscat -c wss://stream.data.alpaca.markets/v2/test
```
Then paste, line by line:
```
{"action":"auth","key":"YOUR_KEY","secret":"YOUR_SECRET"}
{"action":"subscribe","bars":["FAKEPACA"],"trades":["FAKEPACA"],"quotes":["FAKEPACA"]}
```
✅ Expect a stream of synthetic `FAKEPACA` data.

**The test stream runs 24/7.** The US market opens at **21:30 MYT** (Mar–Nov) or **22:30 MYT** (Nov–Mar) and closes at 04:00/05:00 MYT. Without this endpoint you'd be debugging your ingestor at 3am. With it, you can build and verify almost all of Step 4 at 10am. Use it as your default dev stream.

---

## 5. Capture the Replay Fixture

Everything downstream depends on being able to test without a live market. Do this **once**, during a real US session (yes, stay up late once — once).

```bash
mkdir -p src/test/resources/fixtures

wscat -c wss://stream.data.alpaca.markets/v2/iex \
  -x '{"action":"auth","key":"'"$ALPACA_KEY_ID"'","secret":"'"$ALPACA_SECRET_KEY"'"}' \
  -w 1 \
  | tee src/test/resources/fixtures/iex-session.jsonl
```

Once connected, subscribe to a spread of liquid and illiquid names:
```
{"action":"subscribe","bars":["AAPL","MSFT","SPY","TSLA","F","SIRI"],"trades":["AAPL","TSLA"]}
```

Let it run **30–45 minutes**. Ctrl-C. Commit the `.jsonl`.

**Verify:**
```bash
wc -l src/test/resources/fixtures/iex-session.jsonl   # want >1000 lines
grep -c '"T":"b"' src/test/resources/fixtures/iex-session.jsonl   # want >100 bar messages
```

> Include at least one thin name (`SIRI`) deliberately. On the IEX feed it will have **gaps** — minutes with no bar at all. That gap is the single most important thing your alert engine must handle correctly, and you can't test it without a fixture that contains it.

---

## 6. Project Skeleton

```bash
mkdir stock-tracker && cd stock-tracker
gradle init --type java-application --dsl kotlin --java-version 25
```

### `build.gradle.kts`

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

dependencies {
    // --- Spring Boot 4.1 ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-aop")   // for @Retryable

    // --- DB ---
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- Resilience: CORE modules only, used functionally. See §7. ---
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")
    implementation("io.github.resilience4j:resilience4j-ratelimiter:2.3.0")
    implementation("io.github.resilience4j:resilience4j-micrometer:2.3.0")

    // --- Cache & leader election ---
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("net.javacrumbs.shedlock:shedlock-spring:6.9.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.9.0")

    // --- Metrics ---
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // --- Test ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.wiremock:wiremock-standalone:3.13.0")
    testImplementation("net.jqwik:jqwik:1.9.2")   // property tests for the alert engine
}

tasks.test { useJUnitPlatform() }
```

### Package layout
```
com.yourname.tracker
├── gateway/        # ONLY package that knows Alpaca exists
│   ├── AlpacaMarketDataProvider
│   ├── AlpacaStreamClient
│   └── MarketDataProvider (interface)
├── quote/
├── symbol/
├── alert/
├── notify/
├── api/
└── TrackerApplication
```

---

## 7. The Resilience Decision (read this before you copy someone's tutorial)

Almost every Resilience4j guide online tells you to add `resilience4j-spring-boot3`. **Do not.** Spring Boot 4 runs on Spring Framework 7, and the Resilience4j Spring starter has been catching up — the Spring Cloud team hit compatibility problems with exactly this combination. A `spring-boot4` module exists in Resilience4j's repo, but its release status is a moving target.

Sidestep the whole question:

| Need | Use | Why |
|---|---|---|
| Retry on 429/5xx | Spring Boot 4's **native `@Retryable`** + `@EnableResilientMethods` | Built in. Zero extra dependencies. |
| Rate limit (180 rpm) | Resilience4j **`RateLimiter` core**, called functionally | The core jars have **no Spring dependency at all** — nothing to be incompatible with. |
| Circuit breaker | Resilience4j **`CircuitBreaker` core**, called functionally | Same. |

Functional style, no annotations, no auto-config:

```java
@Component
public class AlpacaRest {
    private final RateLimiter limiter = RateLimiter.of("alpaca",
        RateLimiterConfig.custom()
            .limitForPeriod(180)                          // 10% under the 200/min ceiling
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .timeoutDuration(Duration.ofSeconds(5))
            .build());

    private final CircuitBreaker breaker = CircuitBreaker.of("alpaca",
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .build());

    public <T> T call(Supplier<T> op) {
        return Decorators.ofSupplier(op)
            .withRateLimiter(limiter)
            .withCircuitBreaker(breaker)
            .decorate()
            .get();
    }
}
```

**Verify** this compiles and that a loop of 300 calls in 60s never produces an Alpaca `429`.

---

## 8. `application.yml`

```yaml
spring:
  application.name: stock-tracker
  datasource:
    url: jdbc:postgresql://localhost:5432/stocktracker
    username: tracker
    password: tracker_local_only
  jpa:
    hibernate.ddl-auto: validate     # Flyway owns the schema. NEVER 'update'.
    open-in-view: false
  flyway:
    enabled: true

alpaca:
  key-id: ${ALPACA_KEY_ID}
  secret-key: ${ALPACA_SECRET_KEY}
  data-url: ${ALPACA_DATA_URL}
  trading-url: ${ALPACA_TRADING_URL}
  stream-url: ${ALPACA_STREAM_URL}
  feed: ${ALPACA_FEED:iex}
  rate-limit-per-minute: 180

management:
  endpoints.web.exposure.include: health,metrics,prometheus
  metrics.tags.application: stock-tracker

logging.level:
  com.yourname.tracker.gateway: DEBUG

---
spring.config.activate.on-profile: replay
alpaca.stream-mode: replay
alpaca.replay-file: classpath:fixtures/iex-session.jsonl
```

Two profiles you'll live in:
- **`replay`** — reads the Step-5 fixture. No network. Default for all tests.
- **`test-stream`** — points at `wss://.../v2/test`, symbol `FAKEPACA`. 24/7 live-ish data.

Reserve the real `iex` stream for actual session testing.

---

## 9. `.gitignore`

```
.gradle/
build/
*.env
.stocktracker/
.idea/
*.iml
!src/test/resources/fixtures/*.jsonl
```

That last line is deliberate — the fixture **is** committed. It's test data, not a secret.

---

## 10. Final Verification Gate

Do not start Step 0 until every box is ticked.

- [ ] `java -version` → 25.x
- [ ] `./gradlew build` → success on a clean clone
- [ ] `docker compose ps` → postgres healthy
- [ ] A trivial Testcontainers test spins up Postgres and passes
- [ ] REST snapshot returns AAPL data
- [ ] `feed=sip` on a `latest` endpoint returns error **42210000** *(a pass!)*
- [ ] `/v2/clock` returns the market state
- [ ] `wscat` to the **test stream** yields `FAKEPACA` messages
- [ ] Two Alpaca accounts exist with separate keys
- [ ] Second WS connection on the **same** key → `406` *(a pass!)*
- [ ] First WS connection on the **other** key → succeeds
- [ ] `iex-session.jsonl` fixture committed, >100 bar messages, includes a thin symbol with gaps
- [ ] No secret appears anywhere in `git log -p`

---

## Known Gotchas (you will hit at least three of these)

| Symptom | Cause | Fix |
|---|---|---|
| `406 connection limit exceeded` | Two things using one key — often a forgotten `wscat` tab | One connection per account. Kill the stray terminal. |
| WS connects, then silently drops | You didn't authenticate within **10 seconds** | Send `auth` as the first frame, immediately. |
| Subscriptions vanish after a blip | Subscriptions do **not** survive a reconnect | Re-subscribe in your `onOpen` handler, always. |
| `{"code":42210000}` | Requesting SIP on the free tier | Send `feed=iex` **explicitly**. Never rely on the default. |
| Prices are off by cents | Parsed as `double` | `BigDecimal` everywhere. `price >= 150.00` breaks with binary floats. |
| Random 429s | No client-side limiter | §7. 180 rpm, not 200. |
| Bar for a thin symbol never arrives | IEX is ~2% of volume — that minute genuinely had no IEX print | Absence of a bar ≠ absence of movement. Handle the gap. |
| Testcontainers can't find Docker | Daemon not running | Start Docker Desktop. |
| Jackson `ClassNotFound` | Boot 4 defaults to **Jackson 3** (`tools.jackson.*`) | Don't mix a Jackson-2-based library in without checking. |

---

**When every box above is ticked → begin Step 0.**

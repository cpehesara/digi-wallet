# Idempotent Wallet: Viva Demo Guide and Technology Deep-Dive (IT3133)

Pattern: **Idempotency & Exactly-Once(ish)** · one service (`wallet-service`), PostgreSQL, RabbitMQ.

- **Part A**: what to show in the viva when there is no UI, minute by minute.
- **Part B**: every technology and dependency, and how each is used in the code (including the hashing question).

---

# PART A: THE VIVA DEMO (no UI needed)

## A1. The four windows (this is your "UI")

The system is a back-end, so you show it through four windows. Arrange them before the lecturer arrives.

| # | Window | Role | Why it convinces |
|---|---|---|---|
| 1 | **Postman Runner** (the viva collection) | The "client": sends requests, shows green ticks | Every claim is an assertion that turns green |
| 2 | **IntelliJ Run console** | The "server log" | Shows `Duplicate message ... skipped` live |
| 3 | **Terminal with psql** | The "database truth" | Shows one ledger row after many requests |
| 4 | **Browser: http://localhost:15672** (guest / guest) | RabbitMQ management page: the only web page in the project | Shows the queue and the dead-letter queue |

Also keep IntelliJ tabs open on: `IdempotentExecutor`, `LedgerService`, `RequestHasher`, `OutboxRelay`, `NotificationConsumer`, `V1__init.sql` (details in A6).

**Golden rule:** show the **failure first, then the fix**, and prove every claim in the **database**, not only in the API answer.

## A2. Set-up checklist

**Night before (do it twice):**
1. `cd wallet-service` then `docker compose down -v` then `docker compose up -d` (fresh database, Flyway applies V1 cleanly).
2. `docker compose ps` shows postgres **healthy** and rabbitmq **running**.
3. Start `WalletServiceApplication`. The log must contain `Started WalletServiceApplication` and no stack trace.
4. Run the whole Postman collection once. **Everything green** is the goal. If a test is red, fix it tonight.
5. Run `mvn test -De2e=true` once (service running). Note the result.
6. Save the SQL commands from A4 in a text file so you can paste them.

**30 minutes before the viva:**
1. `docker compose down -v`, then `up -d`, then start the app (clean state means clean counts).
2. Open the four windows and the code tabs.
3. Reset chaos switches: `POST http://localhost:8080/admin/chaos?dropResponse=false&duplicatePublish=false&failConsumer=false`
4. **Do NOT run `down -v` once the demo has started** (it deletes your data and your wallet ID).
5. Phone on silent; laptop on charger; a recorded screen video of the demo as a backup (A7).

## A3. The demo script (about 8 minutes)

Use the Postman collection folders in this order. "Say" is a sentence in your own words, not to be memorised.

| Step | Do | Say | Prove it (database or log) |
|---|---|---|---|
| **0** (30 s) | Draw the lost-reply timeline on paper: request, commit, reply lost, retry | "The server cannot tell a lost request from a lost reply, so retries are unavoidable and duplicates are unavoidable. Our job is to make a duplicate harmless." | none |
| **1** (60 s) failure first | In `requests.http` (or Postman), create a **throwaway wallet** and send two top-ups of 1000 with **different** keys | "Without a stable key, a retry looks like a new request. That is the double-tap bug: balance 2000." | Balance shows 2000 |
| **2** (90 s) the fix, layer 1 | Collection **0.1**, then **1.1** (top-up 1000), then **1.2** (the SAME request again) | "Same key, same payload: the server returns the saved receipt." Point at header `Idempotent-Replayed: false` then `true` | psql query **Q1**: exactly 1 ledger row. Query **Q2**: key row with status COMPLETED and stored response |
| **3** (45 s) | **1.3** (same key, different amount), **1.4** (no key), **1.5** (overdraw) | "Reusing a key for a different request is a client bug, so we answer 422 using a SHA-256 fingerprint of the request. No key gives 400. Overdraft gives 409." | 422, 400, 409 ticks |
| **4** (90 s) **the core story** | Folder **2**: switch ON `dropResponse`, send top-up 500, get **503**, switch OFF, **retry the same key** | "The server committed but the answer was lost. The client saw a failure, so it retried. The retry was a replay, so the money moved once." | Q1 shows one row of 500. Balance is 1500, not 2000 |
| **5** (90 s) **parallel storm** | Folder **3**: 50 identical requests at the same instant | "Fifty requests, one key. The unique index lets one through; the other 49 wait, then get the saved receipt." | Result: 1 created, 49 replays, balance exactly 1000. Open `/actuator/metrics/wallet.duplicates.suppressed` to show the counter. psql **Q1** on the storm wallet: 1 row |
| **6** (90 s) layer 2 | Folder **4**: switch ON `duplicatePublish`, send top-up 200 | "The relay now publishes every message twice, which is at-least-once. The inbox must skip the second copy." | Console line `Duplicate message ... skipped`. psql **Q4**: outbox rows PUBLISHED. **Q5**: one notification per message |
| **7** (30 s) | In psql run the **Q6** `UPDATE` on `ledger_events` | "Even a buggy program cannot rewrite history. The database trigger forbids it." | ERROR: ledger_events is append-only |
| **8** (20 s) | Collection folder **6**: replay endpoint | "The balance can be rebuilt from the events and it matches." | `consistent: true` |
| **Optional A** (40 s) | Folder **5** poison message, then browser to `wallet.events.notification.dlq` | "A bad message retries 3 times then goes to the dead-letter queue. The money was already safe." | Message count on the DLQ in the RabbitMQ page |
| **Optional B** (40 s) TTL | Run **Q7** to age a key by 1 hour past expiry, then resend the same request | "After 24 hours a key is forgotten, so a very late retry is treated as new. The TTL must exceed any realistic retry window." | A new ledger row appears |
| **Close** (20 s) | Say the closing sentence | "We never claim exactly-once delivery. Delivery is at-least-once; the effect happens once." | none |

**If you only have 4 minutes:** steps 0, 2, 4, 5. These four cover the whole pattern.

## A4. SQL cheat sheet (the database truth)

Open a psql shell once and keep it open:

```
docker exec -it wallet-postgres psql -U wallet -d wallet
```

Replace `<WALLET>` with the wallet ID from Postman (console variable `wallet`). Turn on readable output with `\x auto`.

```sql
-- Q1: the ledger is the proof (one row per real money movement)
SELECT seq, type, amount_minor, created_at FROM ledger_events
WHERE wallet_id = '<WALLET>' ORDER BY seq;

-- Q2: the stored receipts (layer 1)
SELECT scope, idem_key, left(request_hash, 12) AS hash_start, status, response_code, expires_at
FROM idempotency_keys ORDER BY created_at DESC LIMIT 10;

-- Q3: stored balance vs last sequence
SELECT * FROM wallet_balance WHERE wallet_id = '<WALLET>';

-- Q4: the outbox (messages committed with the data)
SELECT status, count(*) FROM outbox GROUP BY status;

-- Q5: the inbox and the side effect (layer 2). Both counts must match.
SELECT count(*) AS inbox_rows FROM inbox;
SELECT count(*) AS notifications FROM notification_log;

-- Q6: prove the ledger is append-only (this MUST fail)
UPDATE ledger_events SET amount_minor = 1 WHERE wallet_id = '<WALLET>';

-- Q7 (optional): age a key past its 24 h expiry, then resend the same request
UPDATE idempotency_keys SET expires_at = now() - interval '1 hour'
WHERE idem_key = '<THE KEY YOU USED>';
```

## A5. Pass/fail table for the examiners

| Claim | Evidence | Where |
|---|---|---|
| Double click moves money once | 1 ledger row, header `Idempotent-Replayed: true` | Q1 and Postman 1.2 |
| Lost reply is safe | 503 then replay, 1 row | Postman folder 2 and Q1 |
| 50 parallel requests give one effect | 1 created, 49 replays, balance 1000, counter 49 | Postman folder 3 and metrics |
| Reused key is detected | 422 `IDEMPOTENCY_KEY_REUSED` | Postman 1.3 |
| Duplicate broker message is skipped | log line and `inbox_rows` = `notifications` | console and Q5 |
| History cannot be rewritten | trigger error | Q6 |
| Read model matches events | `consistent: true` | replay endpoint |

## A6. Code tabs to show (what to point at)

| File | Point at | Sentence |
|---|---|---|
| `IdempotentExecutor` | `INSERT ... ON CONFLICT DO NOTHING`, the `inserted == 0` branch, `effect.get()` | "One transaction: the key row and the effect commit together." |
| `LedgerService.append` | the three steps: INSERT event, UPDATE balance WHERE last_seq, INSERT outbox | "Optimistic concurrency, and the outbox row is in the same transaction." |
| `RequestHasher` | `MessageDigest.getInstance("SHA-256")` | "A fingerprint, not a secret." |
| `OutboxRelay` | `FOR UPDATE SKIP LOCKED`, `setMessageId(eventId)` | "At-least-once with a stable message ID." |
| `NotificationConsumer` | `INSERT INTO inbox ... ON CONFLICT DO NOTHING` | "Duplicate: skip. New: act, in one transaction." |
| `V1__init.sql` | `UNIQUE (wallet_id, seq)`, the trigger, the `idempotency_keys` primary key | "The database is the referee." |

## A7. If something goes wrong live

| Problem | Do this |
|---|---|
| App will not start | Say what the error means (read the first line), run `docker compose down -v` and `up -d`, restart. Otherwise play the recorded video. |
| A Postman test is red | Say "this is an assertion on a field name, let me show the database instead", then use Q1 to prove the effect. |
| Storm request shows odd numbers | Use `k6 run k6/duplicates.js` or `mvn test -De2e=true` as the backup proof. |
| RabbitMQ page will not load | Show the console log line and Q5 instead. |
| Asked something you do not know | Say how you would find out. Never bluff. |

## A8. Demo-specific questions to expect

| Question | Short answer |
|---|---|
| "Show me idempotency does not work without the key." | Step 1: different keys give two credits. |
| "How do you know only one row exists?" | Q1 in psql. |
| "What if two requests arrive at exactly the same time?" | Step 5: unique index, one winner, the rest wait and replay. |
| "What if the server crashes after saving but before replying?" | Step 4 is exactly that case. |
| "What if RabbitMQ delivers twice?" | Step 6: inbox. |
| "Can someone edit the ledger?" | Step 7: trigger blocks it. |

---

# PART B: TECHNOLOGIES AND HOW THEY ARE USED IN THE CODE

## B1. Stack at a glance (versions from your own run log)

| Layer | Technology | Version in your run |
|---|---|---|
| Language / runtime | Java (JDK) | 21.0.8 |
| Framework | Spring Boot / Spring Framework | 4.0.8 / 7.0.9 |
| Web server | Embedded Apache Tomcat | 11.0.24 |
| Database | PostgreSQL (Docker image `postgres:16-alpine`) | 16.15 |
| DB driver | PostgreSQL JDBC | 42.7.13 |
| Connection pool | HikariCP | 7.0.2 |
| DB migrations | Flyway (core and PostgreSQL module) | 11.14.1 |
| Message broker | RabbitMQ (Docker image `rabbitmq:3-management-alpine`) | 3.x |
| Broker client | Spring AMQP / RabbitMQ Java client | 4.0.5 / 5.27.1 |
| Validation | Jakarta Validation API / Hibernate Validator | 3.1.1 / 9.0.1.Final |
| JSON | Jackson 3 (`tools.jackson`) | 3.1.5 |
| Metrics | Micrometer / Spring Boot Actuator | 1.16.7 / 4.0.8 |
| Logging | SLF4J / Logback | 2.0.18 / 1.5.38 |
| Build | Maven | n/a |
| Tests | JUnit 5, `java.net.http.HttpClient`, k6, Postman | n/a |
| Environment | Docker Compose, IntelliJ IDEA | n/a |

## B2. Every dependency on your classpath, and what it does

These come from the `-classpath` line in your startup log, so nothing is missed.

| Dependency (artifact) | What it gives | Where it is used in the code |
|---|---|---|
| `spring-boot-starter-webmvc` (with `spring-webmvc`, `spring-web`) | REST controllers, request mapping, JSON conversion | `WalletController`, `ChaosController`, `ApiExceptionHandler` |
| `spring-boot-starter-tomcat` (`tomcat-embed-core` 11.0.24) | The web server inside the jar: listens on port 8080 | Starts automatically; no code |
| `spring-boot-starter-jackson` (`tools.jackson` 3.1.5) | Turns JSON into Java records and Maps into JSON | `@RequestBody CreateWalletRequest` / `AmountRequest` records; `Map` responses in `body(...)` |
| `spring-boot-starter-validation` (`hibernate-validator`, `jakarta.validation-api`, `tomcat-embed-el`) | Declarative checks | `@Valid`, `@NotBlank ownerName`, `@Positive amountMinor` |
| `spring-boot-starter-jdbc` (`spring-jdbc`, `HikariCP`, `spring-tx`) | `JdbcTemplate`, connection pool, `@Transactional` | Every SQL statement; `IdempotentExecutor`, `LedgerService`, `OutboxRelay`, `NotificationConsumer` |
| `postgresql` 42.7.13 | The JDBC driver for PostgreSQL | Used by Hikari behind `JdbcTemplate`; URL in `application.yml` |
| `spring-boot-starter-flyway` + `flyway-core` + `flyway-database-postgresql` | Runs `V1__init.sql` at startup, verifies its checksum | Creates all tables; the checksum error you hit came from here |
| `spring-boot-starter-amqp` (`spring-rabbit`, `spring-amqp`, `amqp-client`, `spring-messaging`) | RabbitMQ publishing and listening | `RabbitConfig`, `RabbitTemplate` in `OutboxRelay`, `@RabbitListener` + `@Header` in `NotificationConsumer` |
| `spring-boot-starter-actuator` | Management endpoints | `/actuator/metrics/wallet.duplicates.suppressed` (exposure: `health,metrics`) |
| `spring-boot-starter-micrometer-metrics` (`micrometer-core`) | The `MeterRegistry` counter API | `meters.counter("wallet.duplicates.suppressed", "layer", "api").increment()` |
| `spring-boot-starter-logging` (`logback-classic`, `slf4j-api`, `jul-to-slf4j`, `log4j-to-slf4j`) | Logging | `Logger log = LoggerFactory.getLogger(...)` in relay, consumer, cleanup |
| `snakeyaml` | Reads `application.yml` | No code |
| `spring-context`, `spring-beans`, `spring-core`, `spring-aop` | Dependency injection and the proxies behind `@Transactional` and `@Scheduled` | Constructor injection everywhere |
| `jackson-databind` 2.21.5 (`com.fasterxml`) | Jackson 2 pulled in by Flyway | Not used by our code |
| `byte-buddy`, `commons-logging`, `jakarta.annotation-api` | Support libraries | No code |

**On your classpath but NOT part of the design (remove them):**

| Dependency | Why it is there | Action |
|---|---|---|
| `spring-boot-starter-data-jpa` (Hibernate 7.2.24, Spring Data JPA 4.0.7, `jakarta.persistence-api`, `jaxb-runtime`, `aspectjweaver`, `antlr4-runtime`) | Added in Initializr by mistake | Delete from `pom.xml`. It is why `entityManagerFactory` appeared in your Flyway error. We use `JdbcTemplate` on purpose. |
| `lombok` 1.18.46 | Added in Initializr | Delete from `pom.xml`. We use Java `record` instead. |

After editing `pom.xml`, reload Maven (the elephant icon in IntelliJ), then run the app. If you keep them, you must be able to say "present but unused".

## B3. HASHING, RANDOMNESS AND SECURITY (the question this lecturer asks)

**Short answer to "what hashing method did you use?"**
> "SHA-256, from Java's `MessageDigest`, applied to a canonical string made of the endpoint scope and the amount. We hash to get a fixed-size fingerprint of the request so we can detect a client reusing an idempotency key for a different request. It is not used for passwords or tokens."

### The code (`RequestHasher.java`)

```java
public static String sha256(String canonicalRequest) {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(md.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
}
```

How it is called (`WalletController.entry`):

```java
String scope = "POST /wallets/" + walletId + "/" + action;     // action = topups or withdrawals
String hash  = RequestHasher.sha256(scope + "|" + req.amountMinor());
```

So the text that gets hashed looks like `POST /wallets/3c27...cb12/topups|1000`.

| Question | Answer |
|---|---|
| Algorithm | **SHA-256** (SHA-2 family, Java standard library `java.security.MessageDigest`) |
| Output | 256 bits = 32 bytes = **64 hex characters** (the unit test asserts length 64) |
| Hex conversion | `java.util.HexFormat` (Java 17+), lower-case |
| Text encoding | **UTF-8** (`StandardCharsets.UTF_8`), so the same text always gives the same bytes on every machine |
| What goes in | Scope (method + wallet + action) + `|` + amount. The separator `|` avoids ambiguity such as `12|3` versus `1|23` |
| Salt? HMAC? | **No salt, no HMAC.** The input is not secret and nothing is being protected from an attacker. Both are for secrets. |
| Where stored | Column `idempotency_keys.request_hash` (type `text`) |
| How compared | `existing.requestHash().equals(requestHash)` in `IdempotentExecutor`; mismatch throws `KeyReuseException`, mapped to **422** |
| Deterministic? | Yes: same input, same hash. That is required for the comparison to work. |

### Why SHA-256 and not something else (be ready for each)

| Alternative | Why we did not use it |
|---|---|
| **MD5 / SHA-1** | Collision attacks are known. We do not need them to be strong against a secret, but there is no reason to choose a broken algorithm when SHA-256 costs the same. |
| **bcrypt / Argon2 / PBKDF2** | These are *password* hashes: deliberately slow and salted so weak passwords resist guessing. We hash high-volume, non-secret data on every request; a slow hash would hurt throughput for no benefit. |
| **HMAC-SHA256** | Needed when the hash must prove who created it (needs a secret key). Our hash only compares two requests inside our own database. |
| **Store the whole body** | Possible, but a hash is fixed-size (64 chars), cheap to compare, and avoids storing request content. |
| **CRC32 / `hashCode()`** | Not collision-resistant; two different requests could look the same. |

**Collision risk:** with a 256-bit hash, the chance that two different requests share a fingerprint is about 2^-128, so negligible. And the hash is only checked after the **same scope and same key** match, so a collision alone could not replay someone else's receipt.

### Tokens, passwords and keys in this project

| Item | In our project | If asked "what would you do in production" |
|---|---|---|
| **Passwords** | None. There is no login. | Spring Security `PasswordEncoder` with **BCrypt** or Argon2 (slow, salted). |
| **Auth tokens (JWT, sessions)** | None. No authentication exists (declared out of scope). | JWT signed with HMAC-SHA256 or RS256; keep the signing key in a secret store. |
| **Idempotency key** | **Not hashed or signed.** Stored as plain text in `idem_key`. It is an opaque string chosen by the client (a UUID per tap), max 128 characters (`requireKey` checks blank and length). | Add the user ID to the key scope so one user cannot collide with another. |
| **API keys (if any)** | n/a | Store only a SHA-256 hash of the key (fast hash is fine because keys are long and random). |
| **Database / broker credentials** | `wallet/wallet` and `guest/guest` in `application.yml` (demo only) | Secrets manager or environment variables; TLS; non-default users. |
| **Transport** | Plain HTTP on localhost | HTTPS/TLS everywhere. |

### Randomness (also fair game)

| Code | Source | Strength |
|---|---|---|
| `UUID.randomUUID()` (wallet IDs, event IDs, outbox rows, fallback trace ID) | UUID **version 4**: 122 random bits from `java.security.SecureRandom` | Cryptographically strong; collisions are practically impossible |
| `ThreadLocalRandom.current().nextLong(5, 25L * attempt)` in `ConflictRetry` | Fast non-cryptographic generator | Fine: it only spreads retry timing (jitter), nothing secret |

### Input safety

- **SQL injection:** every statement uses `JdbcTemplate` with `?` placeholders (parameterised queries); values are never concatenated into SQL.
- **Log injection:** `X-Trace-Id` is accepted only if it matches `[A-Za-z0-9_-]{1,64}`, otherwise a fresh UUID is used.
- **Bad bodies:** `@Valid` with `@NotBlank` and `@Positive` rejects them; the database also has `CHECK (amount_minor > 0)`.

## B4. Request lifecycle through the code (top-up)

1. **Client** sends `POST /wallets/{id}/topups` with header `Idempotency-Key`.
2. **Tomcat** accepts the HTTP request; Spring MVC maps it to `WalletController`.
3. **Jackson** turns the JSON body into the `AmountRequest` record; **Hibernate Validator** checks `@Positive`.
4. `requireKey(key)` rejects a missing or oversize key (400).
5. The controller builds `scope` and the **SHA-256** hash.
6. `ConflictRetry.run(MAX_RETRIES, ...)` calls `executor.execute(...)` (retry sits **outside** the transaction).
7. `IdempotentExecutor.execute` (`@Transactional`) opens a transaction on a **Hikari** connection:
   1. deletes this key if expired;
   2. `INSERT ... ON CONFLICT DO NOTHING`; 0 rows means a repeat: compare hashes, return the stored receipt (replayed = true);
   3. otherwise run the effect: `LedgerService.append` (`MANDATORY` propagation).
8. `LedgerService.append`: read balance and `last_seq`, check funds, INSERT the event (`UNIQUE (wallet_id, seq)`), UPDATE the balance `WHERE last_seq = ?` (0 rows throws `OptimisticConflictException`), INSERT the outbox row.
9. The executor stores `response_code` and `response_body` on the key row; the transaction **commits** (key, event, balance and outbox together).
10. `respond(...)` returns `201` with header `Idempotent-Replayed` (or **503** if `dropResponse` is on, after the commit).
11. A second later `OutboxRelay` (every 1 s) publishes the row to RabbitMQ with `messageId = event_id`.
12. `NotificationConsumer` (`@RabbitListener`) inserts the message ID into `inbox`; a duplicate is skipped, a new one writes `notification_log`.

## B5. Class-by-class reference

| Class (package `lk.kdu.wallet`) | Technology used | What it does |
|---|---|---|
| `WalletServiceApplication` | `@SpringBootApplication`, `@EnableScheduling` | Starts the app and switches on the timers |
| `api.WalletController` | `@RestController`, `@RequestHeader`, `@Valid`, records, `Supplier` lambda | The REST API; builds scope and hash; calls the executor |
| `api.ApiExceptionHandler` | `@RestControllerAdvice`, `@ExceptionHandler` | Maps errors: 400 missing key, 422 key reuse, 404 wallet, 409 funds, 503 contention |
| `api.ChaosController` | `@RequestParam` (Boolean) | Switches the three chaos flags |
| `chaos.ChaosState` | `volatile boolean` fields | Flags visible to all threads at once |
| `common.DomainErrors` | `RuntimeException` subclasses | Unchecked, so Spring rolls the transaction back |
| `idempotency.RequestHasher` | `MessageDigest`, `HexFormat` | SHA-256 fingerprint |
| `idempotency.IdempotentExecutor` | `@Transactional`, `JdbcTemplate`, `MeterRegistry`, `Supplier<Stored>` | Layer 1: claim key, run effect, store receipt |
| `idempotency.ConflictRetry` | `ThreadLocalRandom`, catches `DuplicateKeyException` and `OptimisticConflictException` | Retries with jitter outside the transaction, max attempts then 503 |
| `idempotency.IdempotencyCleanup` | `@Scheduled(fixedDelay = 3_600_000)` | Hourly delete of expired keys |
| `ledger.LedgerService` | `@Transactional(propagation = MANDATORY)`, `JdbcTemplate`, `UUID`, records | Event sourcing: append event, update balance, write outbox, replay |
| `messaging.RabbitConfig` | `TopicExchange`, `Queue` (durable, dead-letter args), `Binding` | Declares `wallet.exchange`, `wallet.events.notification`, the DLX and DLQ |
| `messaging.OutboxRelay` | `@Scheduled(fixedDelay = 1000)`, `@Transactional`, `FOR UPDATE SKIP LOCKED`, `RabbitTemplate` | Publishes pending rows; marks PUBLISHED; counts attempts; DEAD after 10 |
| `messaging.NotificationConsumer` | `@RabbitListener`, `@Header(AmqpHeaders.MESSAGE_ID)`, `@Transactional` | Layer 2: inbox dedupe plus the side effect in one transaction |
| `resources/db/migration/V1__init.sql` | Flyway, SQL, PL/pgSQL | All tables, constraints, trigger, partial index |
| `resources/application.yml` | Spring Boot config | Datasource, RabbitMQ, listener retry, port, actuator exposure |
| `docker-compose.yml` | Docker Compose | PostgreSQL 16 and RabbitMQ 3 with management UI |

## B6. PostgreSQL features used

| Feature | Where | Purpose |
|---|---|---|
| `uuid` primary keys | `wallet_id`, `event_id` | Globally unique IDs without a counter |
| `bigint` for money | `amount_minor`, `balance_minor` | Whole minor units; no floating-point drift |
| `timestamptz` | `created_at`, `expires_at` | Time with time zone, safe across machines |
| `bigserial` | `outbox.id`, `notification_log.id` | Auto-increment, gives the relay a publishing order |
| `PRIMARY KEY (scope, idem_key)` | `idempotency_keys` | The doorman |
| `UNIQUE (wallet_id, seq)` | `ledger_events` | One writer per sequence number (optimistic concurrency) |
| `UNIQUE (message_id)` | `notification_log` | Second line of defence for the side effect |
| `CHECK (amount_minor > 0)` | `ledger_events` | Rule enforced by the database |
| Trigger (PL/pgSQL) | `forbid_ledger_change()` | Append-only ledger |
| Partial index | `idx_outbox_pending ... WHERE status = 'PENDING'` | Fast scan of unpublished rows |
| `INSERT ... ON CONFLICT DO NOTHING` | executor and inbox | Atomic "claim or find" |
| `FOR UPDATE SKIP LOCKED` | relay | Several relays never take the same row |
| `READ COMMITTED` isolation (default) | all transactions | A duplicate insert waits for the first transaction, then sees its result |

## B7. RabbitMQ and Spring AMQP details

| Setting | Value | Meaning |
|---|---|---|
| Exchange | `wallet.exchange` (topic, durable) | Routes by key |
| Routing keys | `wallet.credited`, `wallet.debited` | Bound with pattern `wallet.*` |
| Queue | `wallet.events.notification` (durable) | Holds events for the consumer |
| Dead-letter | `x-dead-letter-exchange = wallet.dlx`, queue `wallet.events.notification.dlq` | Where rejected messages end up |
| Message ID | `setMessageId(event_id)` in `OutboxRelay` | Stable ID so the inbox can detect repeats |
| Payload | `String` (text), built with `String.format` | Simple; a real system would use a typed schema |
| Listener retry | 3 attempts, 500 ms initial, multiplier 2 | Then reject without requeue |
| `default-requeue-rejected: false` | in `application.yml` | After the last failure the message goes to the DLQ |
| Credentials | `guest / guest` | Demo only |

## B8. Java language features worth naming

| Feature | Example in the code |
|---|---|
| Records (Java 16+) | `Stored`, `Existing`, `Row`, `BalanceView`, `EntryResult`, the request bodies |
| Lambdas and `Supplier<T>` | `executor.execute(scope, key, hash, () -> {...})` passes "the work to do" |
| Generics | `ConflictRetry.run` is generic in `<T>` |
| Text blocks (`"""`) | multi-line SQL in `IdempotentExecutor` and `OutboxRelay` |
| `volatile` | `ChaosState` flags |
| Unchecked exceptions | `DomainErrors` extend `RuntimeException` so rollback happens |
| Constructor injection | all services (no field injection) |
| `java.net.http.HttpClient` | end-to-end tests |

## B9. Tests and tooling

| Tool | Used for |
|---|---|
| JUnit 5 (`RequestHasherTest`) | Same input same hash, different input different hash, length 64 |
| `IdempotencyE2ETest` (enabled with `-De2e=true`) | 50 parallel requests, same key different body, overdraft, 8 parallel different keys |
| k6 (`k6/duplicates.js`) | 50 virtual users, one key |
| Postman collection | The viva demo with assertions |
| `requests.http` | Manual calls from IntelliJ |
| Flyway | Versioned schema; checksum protects applied scripts |
| Docker Compose | Repeatable PostgreSQL and RabbitMQ |
| Maven | Dependencies and build (`mvn test`, `mvn test -De2e=true`) |

## B10. Technology questions: quick answers

| Question | Answer |
|---|---|
| What hashing method? | SHA-256 via `MessageDigest`, on `scope + "|" + amount`, hex-encoded, to fingerprint the request. |
| Is the hash salted? Why not? | No. It is not secret and not a password; a salt protects low-entropy secrets from precomputed tables. |
| Why not MD5? | Collisions are known; SHA-256 is as easy to use. |
| Why not bcrypt? | It is a slow password hash; ours is a per-request fingerprint. |
| How do you hash tokens or passwords? | We do not have any; production would use BCrypt/Argon2 for passwords and signed JWTs for tokens. |
| Is the idempotency key hashed? | No. It is a client-chosen opaque string, stored as text and used in the primary key. |
| How are IDs generated? | `UUID.randomUUID()`, version 4, from `SecureRandom`. |
| How do you prevent SQL injection? | Parameterised queries through `JdbcTemplate`. |
| What does `@Transactional` do? | Wraps the method in a database transaction through a Spring proxy: commit on success, rollback on an unchecked exception. |
| What does `Propagation.MANDATORY` do? | `LedgerService.append` refuses to run unless a transaction is already open. |
| What is HikariCP? | The connection pool; reuses database connections between requests. |
| What does Flyway do and what was your checksum error? | Applies versioned SQL once and stores each script's checksum; I edited V1 after it ran, so startup failed. Fix: reset the dev volume, or add V2 in real systems. |
| What are Actuator and Micrometer? | Actuator exposes management endpoints; Micrometer provides the counter `wallet.duplicates.suppressed`. |
| Why `JdbcTemplate`, not JPA? | The SQL is the design: `ON CONFLICT`, `SKIP LOCKED`, guarded `UPDATE ... WHERE`. |
| Why `bigint` for money? | Whole minor units never drift; floating point does. |
| Why RabbitMQ? | Task-style queues, routing keys, acks and dead-lettering out of the box. |
| What is the security gap? | No authentication, plain HTTP, default credentials, open chaos endpoints: demo only. |

# CLAUDE.md

Guidance for Claude Code working in this repository.

## Start here

`PLAN.md` is the source of truth: the phase status table, every design decision (D1–D26) with its
reasoning, the wire and transport contracts, and the family-coordination list. Read it before
changing behavior; a decision recorded there is not re-litigated in code review. Build details live
in `docs/development.md`.

The reference SDKs are siblings: `../sdk-flutter` (mobile semantics) and `../sdk-web` (product
semantics). Out of scope by user instruction: FCM/push and device registration
(`sdk.devices.v1`, `IdentifyRequest.device_id`).

## Commands

```bash
make check       # verify-version + spotlessCheck + detekt + lint + unit tests + release AAR + API dump check
                 # + dead code check + minified sample build (one Gradle run)
make fmt         # spotlessApply (ktlint, android_studio style)
make abi-dump    # rewrite pug/api/pug.api after an intended public API change; review the diff
make protos      # regenerate from proto/ (needs buf): test descriptor set + typed track API
make typed-track # only the typed track API: TrackNamespace.kt, PugEvents.kt, events/Events.kt, WellKnownEvents.kt
make ci          # protos + check-codegen + check: what CI runs
```

Releasing (`make publish-bundle`, `make publish-central`) is manual; the steps are in
`docs/development.md`.

JDK 21 and buf 1.73.0 come from `mise.toml`; the Android SDK path is in the untracked
`local.properties`. Gradle is slow to start: run it once into a log file and grep the file
(Kotlin diagnostics start with `e: ` / `w: `), don't re-run it to try a different filter.

## Architecture (current)

Module `:pug` → `sh.pug:pug-android`, package `sh.pug.android`, explicit API mode. Public: the `Pug`
object (the entry point, `@JvmStatic`), `PugOptions` (opaque, built by `PugOptions.Builder` or the
Kotlin `PugOptions(apiKey) { … }`), `TrackOptions`, `TrackingConsent`, `PugLogger`, `PugStorage`,
`MemoryPugStorage`, the generated `TrackNamespace` (`Pug.track`, a `@JvmField` beside the
`track(kind, …)` and `track(event)` functions) and `PugEvents`, and in `sh.pug.android.events` the
`WellKnownEvent` base of the generated event classes (D31, Java's typed form). Storage defaults to
files under `noBackupFilesDir/pug/`.

- **Public API is pinned** by `pug/api/pug.api`, which `make check` compares with the release AAR
  through `tools/abi-dump` (KGP's ABI validation and BCV do not run under AGP's built-in Kotlin).
  Any change to a public signature, the generated ones included, shows there.
- **Dead code is checked**: detekt's unused rules (`config/detekt.yml`) within a file, and
  `tools/dead-code` across the SDK (R8 keeping only `pug.api`; test-only members are listed in
  `tools/dead-code/allowed.txt`). See `docs/development.md`, Dead code.
- **`sample/`** (`:sample`) is a framework-only app on the SDK, from Kotlin and Java, with StrictMode
  `penaltyDeath` in debug builds; its release build is minified with no keep rules for the SDK.
- **Generated, never edited:** `TrackNamespace.kt`, `PugEvents.kt`, `events/Events.kt` and
  `internal/event/WellKnownEvents.kt` come from `tools/gen-track` (the Gradle module
  `:tools:gen-track`, run by `make typed-track` on a `buf build` JSON image). Change the generator
  and regenerate; `check-codegen` fails on drift, so the generator emits what ktlint would format.
  Typed methods pass their values in schema order to `PugFacade.trackTyped`, event objects hold
  theirs in that order, and `PugClient.trackTyped` names them from the `EventSchema`
  (`internal/event/EventSchema.kt`), formats Durations (D24) and merges `extras` (D25).
- `internal/runtime/` — `PugFacade` is the state behind `Pug`: init validation, pre-init warnings,
  never-throw wrappers, the one shared worker, and the handoff that makes a new client wait for the
  last one's teardown. `PugClient` is one configured instance: calls (and lifecycle callbacks) map
  their arguments on the caller's thread and send an op into a channel that one coroutine runs in
  order on the worker, after restoring state from storage; it also owns the foreground state
  (`app_open`/`app_close`), the current screen (`$url`/`$referrer`) and the D21 opt-in replay.
  `destroy()` closes the channel and returns the job that ends once the client no longer touches
  storage. `EventDispatcher` delivers: size/timer/immediate triggers, one batch in flight, D16
  backoff, `drain()` for explicit flushes, backgrounding and teardown.
- `internal/platform/` — `Platform` is what the runtime takes from the device (storage directory,
  auto-properties, lifecycle observation, posting to the main thread), so `PugClient` runs on a plain JVM in tests.
  `AndroidPlatform` is the real one: `AndroidAutoProperties` (`PLAN.md` §4.10: fixed values read
  once, screen/locale/time zone/network per event) and `ActivityLifecycleTracker` (main thread:
  foreground while an activity is started, resumed activities as screens, seeded from
  `getMyMemoryState`).
- `internal/lifecycle/InstallTracker` — `app_install`/`app_update`: decided at start before
  anything is written, emitted once consent allows.
- `internal/logging/SafeLogger` — the only logger internals call (debug gating, contains a throwing
  host logger). `internal/storage/SafePugStorage` fails over to memory on the first throw;
  `FilePugStorage` is the default store: a file per key, cached, written behind by 1 s and synced
  on backgrounding, flush and teardown (`PLAN.md` §4.6).
- `internal/event/` — `PropertyMapper` / `TraitsMapper` (host values → `PropertyValue` / Struct,
  D15 guards, the D19 double coercion for well-known kinds), `EventFactory` (kind gate, timestamps,
  SDK auto-properties, 4 MiB cap), `EventSchema` and the generated `WellKnownEvents` registry.
- `internal/identity/Identity`, `internal/consent/TrackingConsentController`,
  `internal/queue/EventQueue` — session/profile, consent (D12 purges via callbacks), and the
  persisted lock/commit/rollback queue. Each holds its state in memory, reads storage once at
  construction and writes through; ids are minted on first use so a denied client writes nothing.
- `internal/wire/` — hand-rolled protobuf codec. `Models.kt` holds the domain types the rest of the
  SDK builds (`Event`, `PropertyValue`, `JsonValue` for identify traits, `IdentifyRequest`,
  `BatchCreateResponse`). `PugWire` encodes `BatchCreateRequest` / `IdentifyRequest` and decodes
  `BatchCreateResponse`; `ProtoWriter` / `ProtoReader` are the wire primitives.
- `internal/transport/` — `PugTransport` (suspend `sendBatch` / `identify`), implemented by
  `HttpPugTransport`: Connect unary over `HttpURLConnection`, its sockets opened under a
  TrafficStats tag (StrictMode's `detectUntaggedSockets`). Every failure surfaces as
  `PugTransportException(code, isPermanent)`; `RpcCode` holds the Connect codes, the
  permanent set and the HTTP-status fallback table.

## Invariants

- **Never crash the host** (sdk-web parity). The only host-facing throw is `Pug.init` argument
  validation. Catch `Throwable` at coroutine boundaries and rethrow `CancellationException` first
  (it is an `IllegalStateException`); code that never suspends (ops, host loggers and storage)
  catches it whole.
- **Java nulls** (`PLAN.md` D20): `-Xno-param-assertions` lets a Java null reach a non-null
  parameter, so public entry points check required parameters with `isNull(Any?)` and log-and-drop
  (or, in `Pug.init`, throw `IllegalArgumentException`). Optional parameters are nullable.
- **State lives on the worker.** Only ops and network continuations on the worker read or write
  client state and storage; after any network await, re-check (`disposed`, the anonymous id, the
  purge generation) before touching anything.
- **No I/O on the caller's thread.** `Pug.init` usually runs on the main thread under StrictMode:
  file paths from the `Context`, package info and storage are touched on the worker (`restore()`).
  The main thread only registers activity callbacks, and those catch `Throwable` whole, since they
  run inside the host's own activity callbacks. No public call waits either (`PLAN.md` D28): a
  caller that needs start-up done gets a callback (`Pug.whenConsentReady`).
- **Runtime dependencies are kotlin-stdlib and kotlinx-coroutines-core only**, at the floors in
  `gradle/libs.versions.toml`. protobuf-java, MockWebServer and the org.json artifact are test-only (runtime uses the
  platform's org.json). Do not add
  a runtime dependency without a PLAN.md decision.
- **minSdk 21.** Lint `NewApi` runs with warnings as errors; `java.time`, `java.util.Base64`,
  `Math.floorDiv` and `ConcurrentHashMap.newKeySet()` are all above 21 (use the Kotlin stdlib
  equivalents).
- **Wire pitfalls** (`PLAN.md` §4.4): a selected oneof member is written even when it holds a
  default value; empty ids are omitted (edition 2023 explicit presence); map entries are written
  key-sorted. `PugWireTest` compares against protobuf-java byte for byte; keep it passing.
- **Transport contract** (`PLAN.md` §4.5): permanent = invalid_argument, not_found,
  already_exists, permission_denied, failed_precondition, unimplemented, unauthenticated. A 2xx
  that is not protobuf is transient (captive portals). A timeout or caller cancellation must
  `disconnect()` the connection, because socket reads ignore interrupts, and the body read stops
  at the deadline on its own, because the JDK's `disconnect()` waits for a read in progress.

## Conventions

- ktlint `android_studio` style via Spotless, 120 columns, trailing commas allowed. `make fmt`
  before committing.
- No comments that restate code. Comment only a non-obvious why (a protocol quirk, a workaround,
  a deliberate divergence from a reference SDK).
- Tests are JVM unit tests under `pug/src/test` (JUnit 4 + kotlin.test, plus a few Java tests under
  `src/test/java` for the Java API and Java nulls). Runtime tests use virtual time
  (`TestCoroutineScheduler`), `FakeTransport` and `FakePlatform`. `WellKnownEventsTest` and
  `TrackNamespaceTest` check the generated code against `pug.binpb` through protobuf-java, not
  through the generator. Robolectric (SDK 36, set in `src/test/resources/robolectric.properties`)
  runs the `internal/platform/` tests, `AndroidEndToEndTest`, `MainThreadIoTest` and
  `HttpPugTransportTrafficStatsTest`;
  under it, serve HTTP with the JDK's `com.sun.net.httpserver`, not MockWebServer
  (`docs/development.md`). What only a device shows is checked by running the sample on an emulator
  (`docs/development.md`, Sample app).

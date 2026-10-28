# CLAUDE.md

Guidance for Claude Code when working in this repository: the Pug analytics SDK for Android,
written in Kotlin and published as `sh.pug:pug-android`.

## Scope

The SDK tracks events, identifies users, manages tracking consent and delivers batches to the Pug
API. Push notifications and device registration (`sdk.devices.v1`, `IdentifyRequest.device_id`) are
out of scope.

## Setup

JDK 21 and buf 1.73.0 come from `mise.toml` (`mise install`). Put the Android SDK path in an
untracked `local.properties` (`sdk.dir=…`). The sample app runs in dry run until
`~/.gradle/gradle.properties` sets `pug.sample.projectId` and `pug.sample.apiKey` (and, optionally,
`pug.sample.endpoint`).

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

Work is done when `make check` passes. Gradle is slow to start: run it once into a log file and
grep the file (Kotlin diagnostics start with `e: ` / `w: `) instead of re-running it to try another
filter.

`proto/` is vendored from the Buf Schema Registry at the commit pinned in the `Makefile`. Never edit
it by hand: bump `PROTO_COMMIT`, run `make sync-protos protos` and review the diff.
`make check-proto-pin` proves `proto/` matches the pin.

Releasing (`make publish-bundle`, `make publish-central`) is manual. It needs `CENTRAL_TOKEN` and the
signing key in `ORG_GRADLE_PROJECT_signingInMemoryKey` and `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`.

## Architecture

Module `:pug`, package `sh.pug.android`, explicit API mode. Public: the `Pug` object (the entry
point, `@JvmStatic`), `PugOptions` (opaque, built by `PugOptions.Builder` or the Kotlin
`PugOptions(apiKey) { … }`), `TrackOptions`, `TrackingConsent`, `PugLogger`, `PugStorage`,
`MemoryPugStorage`, the generated `TrackNamespace` (`Pug.track`, a `@JvmField` beside the
`track(kind, …)` and `track(event)` functions) and `PugEvents`, and in `sh.pug.android.events` the
`WellKnownEvent` base of the generated event classes (Java's typed form). Storage defaults to files
under `noBackupFilesDir/pug/`.

- **Public API is pinned** by `pug/api/pug.api`, which `make check` compares with the release AAR
  through `tools/abi-dump` (KGP's ABI validation and BCV do not run under AGP's built-in Kotlin).
  Any change to a public signature, the generated ones included, shows there.
- **Dead code is checked**: detekt's unused rules (`config/detekt.yml`) within a file, and
  `tools/dead-code` across the SDK (R8 keeping only `pug.api`; test-only members are listed in
  `tools/dead-code/allowed.txt`).
- **`sample/`** (`:sample`) is a framework-only app on the SDK, from Kotlin and Java, with StrictMode
  `penaltyDeath` in debug builds; its release build is minified with no keep rules for the SDK.
- **Generated, never edited:** `TrackNamespace.kt`, `PugEvents.kt`, `events/Events.kt` and
  `internal/event/WellKnownEvents.kt` come from `tools/gen-track` (the Gradle module
  `:tools:gen-track`, run by `make typed-track` on a `buf build` JSON image). Change the generator
  and regenerate; `check-codegen` fails on drift, so the generator emits what ktlint would format.
  Events the SDK tracks itself (`app_open`, `app_close`, `app_install`, `app_update`,
  `screen_view`) get no typed method or class. Typed methods pass their values in schema order to
  `PugFacade.trackTyped`, event objects hold theirs in that order, and `PugClient.trackTyped` names
  them from the `EventSchema` (`internal/event/EventSchema.kt`), formats Durations and merges
  `extras`.
- `internal/runtime/`: `PugFacade` is the state behind `Pug`: init validation, pre-init warnings,
  never-throw wrappers, the one shared worker, and the handoff that makes a new client wait for the
  last one's teardown. `PugClient` is one configured instance: calls (and lifecycle callbacks) map
  their arguments on the caller's thread and send an op into a channel that one coroutine runs in
  order on the worker, after restoring state from storage; it also owns the foreground state
  (`app_open`/`app_close`), the current screen (`$url`/`$referrer`) and the replay of what the
  launch could not track once the user opts in. `destroy()` closes the channel and returns the job
  that ends once the client no longer touches storage. `EventDispatcher` delivers: size, timer and
  immediate triggers, one batch in flight, retry backoff, `drain()` for explicit flushes,
  backgrounding and teardown.
- `internal/platform/`: `Platform` is what the runtime takes from the device (storage directory,
  auto-properties, install source, lifecycle observation, posting to the main thread), so
  `PugClient` runs on a plain JVM in tests. `AndroidPlatform` is the real one:
  `AndroidAutoProperties` (fixed values read once, screen/locale/time zone/network per event) and
  `ActivityLifecycleTracker` (main thread: foreground while an activity is started, resumed
  activities as screens, seeded from `getMyMemoryState`).
- `internal/lifecycle/InstallTracker`: `app_install` and `app_update`, decided at start before
  anything is written and emitted once consent allows.
- `internal/logging/SafeLogger` is the only logger internals call (debug gating, contains a throwing
  host logger). `internal/storage/SafePugStorage` fails over to memory on the first throw;
  `FilePugStorage` is the default store: a file per key, cached, written behind by 1 s and synced
  on backgrounding, flush and teardown.
- `internal/event/`: `PropertyMapper` / `TraitsMapper` (host values to `PropertyValue` / Struct,
  size guards, double coercion for well-known kinds), `EventFactory` (kind gate, timestamps, SDK
  auto-properties, 4 MiB cap), `EventSchema` and the generated `WellKnownEvents` registry.
- `internal/identity/Identity`, `internal/consent/TrackingConsentController` and
  `internal/queue/EventQueue`: session and profile, consent (opting out purges through callbacks),
  and the persisted lock/commit/rollback queue. Each holds its state in memory, reads storage once
  at construction and writes through; ids are minted on first use, so a denied client writes
  nothing.
- `internal/wire/`: hand-rolled protobuf codec. `Models.kt` holds the domain types the rest of the
  SDK builds (`Event`, `PropertyValue`, `JsonValue` for identify traits, `IdentifyRequest`,
  `BatchCreateResponse`). `PugWire` encodes `BatchCreateRequest` / `IdentifyRequest` and decodes
  `BatchCreateResponse`; `ProtoWriter` / `ProtoReader` are the wire primitives.
- `internal/transport/`: `PugTransport` (suspend `sendBatch` / `identify`), implemented by
  `HttpPugTransport`: Connect unary over `HttpURLConnection`, its sockets opened under a
  TrafficStats tag (StrictMode's `detectUntaggedSockets`). Every failure surfaces as
  `PugTransportException(code, isPermanent)`; `RpcCode` holds the Connect codes, the permanent set
  and the HTTP-status fallback table.

## Invariants

- **Never crash the host app.** The only host-facing throw is `Pug.init` argument validation.
  Catch `Throwable` at coroutine boundaries and rethrow `CancellationException` first (it is an
  `IllegalStateException`); code that never suspends (ops, host loggers and storage) catches it
  whole.
- **Java nulls**: `-Xno-param-assertions` lets a Java null reach a non-null parameter, so public
  entry points check required parameters with `isNull(Any?)` and log-and-drop (or, in `Pug.init`,
  throw `IllegalArgumentException`). Optional parameters are nullable.
- **State lives on the worker.** Only ops and network continuations on the worker read or write
  client state and storage; after any network await, re-check (`disposed`, the anonymous id, the
  purge generation) before touching anything.
- **No I/O on the caller's thread.** `Pug.init` usually runs on the main thread under StrictMode:
  file paths from the `Context`, package info and storage are touched on the worker (`restore()`).
  The main thread only registers activity callbacks, and those catch `Throwable` whole, since they
  run inside the host's own activity callbacks. No public call waits either: a caller that needs
  start-up done gets a callback (`Pug.whenConsentReady`).
- **Runtime dependencies are kotlin-stdlib and kotlinx-coroutines-core only**, at the floors in
  `gradle/libs.versions.toml` (floors, not targets: they set the oldest versions a host app can be
  on). protobuf-java, MockWebServer and the org.json artifact are test-only; the runtime uses the
  platform's org.json. Do not add a runtime dependency.
- **minSdk 21.** Lint `NewApi` runs with warnings as errors; `java.time`, `java.util.Base64`,
  `Math.floorDiv` and `ConcurrentHashMap.newKeySet()` are all above 21 (use the Kotlin stdlib
  equivalents). Code with an API-level branch takes the level as a constructor parameter
  (`sdkInt`), since `Build.VERSION.SDK_INT` reads 0 in JVM tests and Robolectric runs SDK 36 only.
- **Wire pitfalls**: a selected oneof member is written even when it holds a default value; empty
  ids are omitted (edition 2023 explicit presence); map entries are written key-sorted.
  `PugWireTest` compares against protobuf-java byte for byte; keep it passing.
- **Transport contract**: permanent = invalid_argument, not_found, already_exists,
  permission_denied, failed_precondition, unimplemented, unauthenticated. A 2xx that is not
  protobuf is transient (captive portals). A timeout or caller cancellation must `disconnect()` the
  connection, because socket reads ignore interrupts, and the body read stops at the deadline on its
  own, because the JDK's `disconnect()` waits for a read in progress.

## Conventions

- ktlint `android_studio` style via Spotless, 120 columns, trailing commas allowed. Run `make fmt`
  before committing.
- No comments that restate code. Comment only a non-obvious why: a protocol quirk, a workaround, a
  deliberate design choice.
- No em dashes in code, comments or docs. `proto/` is exempt, since it is vendored.
- Commit messages describe the change itself, in the `feat:` / `fix:` style of the history.
- Record user-visible changes in `CHANGELOG.md` under Unreleased.

## Tests

- JVM unit tests under `pug/src/test` (JUnit 4 + kotlin.test, plus a few Java tests under
  `src/test/java` for the Java API and Java nulls). Runtime tests use virtual time
  (`TestCoroutineScheduler`), `FakeTransport` and `FakePlatform`.
- `WellKnownEventsTest` and `TrackNamespaceTest` check the generated code against `pug.binpb`
  through protobuf-java, not through the generator.
- Robolectric (SDK 36, set in `src/test/resources/robolectric.properties`) runs the
  `internal/platform/` tests, `AndroidEndToEndTest`, `MainThreadIoTest` and
  `HttpPugTransportTrafficStatsTest`. Under it, serve HTTP with the JDK's `com.sun.net.httpserver`,
  not MockWebServer, whose OkHttp resolves to its Android build and needs API 37 classes. Its
  `getInstallSourceInfo` throws `NameNotFoundException` unless the test calls
  `setInstallSourceInfo`, which `AndroidPlatform` reads as no install source.
- What only a device shows (lifecycle, StrictMode, real network) is checked by running `sample/` on
  an emulator against the API or a stub server.

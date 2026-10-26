# sdk-android — implementation plan

Native Kotlin SDK for Pug analytics on Android. This file is the hand-off document between
phases: every phase ends by updating the **Status** table and the phase's **Notes**, so a fresh
session can continue from here without re-reading the reference SDKs.

## Status

| Phase | Scope | State |
| ----- | ----- | ----- |
| 1 | Scaffold, vendored protos, hand-rolled wire codec, Connect transport | ✅ done (2026-10-11) |
| 2 | Core building blocks: options, logger, storage API, ids, property mapping, events, identity, consent, queue | ✅ done (2026-10-11) |
| 3 | Runtime: dispatcher (batching/retry), `PugClient`, `Pug` facade | ✅ done (2026-10-11) |
| 4 | Android platform: file storage, auto-properties, lifecycle, screens, install/update | ✅ done (2026-10-11) |
| 5 | Typed track API (codegen from protos) | ✅ done (2026-10-11) |
| 6 | Release readiness: sample app, docs, publishing, API dump, hardening audit | ✅ done (2026-10-11) |
| 7 | First release: device run on emulators (API 36 and 21), fixes, release commit and tag | ✅ done (2026-10-12); publishing pending |
| 8 | Ergonomics review: consent persisted by default, `autoOpenCloseEvents`, no typed methods for automatic events, consent getters that never wait, event objects for Java, public keys only, other values as text | ✅ done (2026-10-26); not released |
| 9 | Automatic events (D30): `app_crashed` and `autoCrashEvents`, `app_install.installSource` | planned |

**Resuming in a new session:** read this file, then `CLAUDE.md` and `docs/development.md`. Phases
1–8 are done, phase 9 is planned, and `v0.1.0` is committed and tagged locally, though phase 8 changed the public API
after the tag: release from a new tag or move it. What remains is publishing (phase 7, "For
publishing": push, the Central upload and the GitHub release, after one-time setup outside this
repo), phase 9, the family-coordination items of §7, and §8. Work after this ends with `make check`
green and this file updated.

## 1. Scope

In scope: everything the Flutter SDK ships on its public surface, adapted to native Android, with
sdk-web's product semantics where the two disagree (see §3). That is: init/destroy, track (untyped +
typed well-known events), identify, reset, rotate, flush, tracking consent, sessions, persistent
batched queue, auto-properties, app lifecycle events, screen views, install/update events.

Out of scope (user instruction, 2026-10-11): **FCM / push and anything device-registration**:
`sdk.devices.v1.DevicesService` (`Subscribe`/`Unsubscribe`), push tokens, notification helpers, and
the `IdentifyRequest.device_id` push-linking field (sdk-web omits it too: "omit for web SDKs without
push support"). The `notification_*` event kinds stay reachable as ordinary events through `track`.

Also deferred (not on Flutter's public surface either): campaign/deep-link capture (`$utm*`,
click ids), sdk-web's `cookieless` consent state, `beforeSend` hook. See §8.

## 2. References

| Path | Use it for |
| ---- | ---------- |
| `../sdk-flutter/CLAUDE.md`, `lib/src/*.dart` | Mobile semantics: runtime split, queue protocol, lifecycle, install tracker, auto-properties, consent |
| `../sdk-web/CLAUDE.md`, `src/{pug,track,batch,rpc,session,profile,tracking-consent}.ts`, `docs/design-notes/` | Product semantics: never-throw, identify, consent purge, error mapping, retry policy |
| `../sdk-flutter/proto/` (= `buf.build/pugsh/pug:8b5d0680b10c4693b4d9d15d3dbfc663`) | Wire contract; vendored here under `proto/` |
| `../pug/internal/app/server/rpc/sdk/events/handler.go` | Server ingest: UA/geo enrichment (only-if-absent), attribution, cookieless |
| `../pug/internal/useragent/`, `internal/autoprop/` | Values the server would derive for `$os`/`$device`/`$mobile`; typed auto-prop keys |
| `../docs/src/content/docs/reference/auto-properties.mdx`, `api/events.mdx` | Public docs: per-SDK auto-property tables, validation rules |
| `../sdk-rust/docs/development.md` | Latest family conventions for Makefile/CI/docs layout |

## 3. Decisions

Each records what was chosen and why. **(F)** = diverges from a reference SDK and should be raised
with the family (collected again in §7).

- **D1 Coordinates.** Maven `sh.pug:pug-android` (brand domain `pug.sh`, org `pug-sh`), Kotlin
  package `sh.pug.android`, Gradle module `:pug`. Version `0.1.0`, held in `gradle.properties`
  (`VERSION_NAME`) and mirrored by `internal const val SDK_VERSION`; `make verify-version` guards
  drift (Flutter's pattern; a `BuildConfig` would leak into the public API).
- **D2 Platform floor.** `minSdk 21`, `compileSdk 36`, Java 11 bytecode, Kotlin `apiVersion` /
  `languageVersion` 2.0 and `coreLibrariesVersion` 2.0.21 so apps on older Kotlin can consume the
  AAR. Lint `NewApi` is the guard for anything above 21 (`java.time`, `java.util.Base64`,
  `ConcurrentHashMap.newKeySet()` are all API 24-26: do not use).
- **D3 Runtime dependencies: Kotlin stdlib + kotlinx-coroutines-core only.** No protobuf runtime,
  no OkHttp, no AndroidX. Each avoided dependency is a version conflict avoided in host apps:
  protobuf-javalite in particular clashes with Firebase, and a forced-older runtime would break
  generated code at runtime. coroutines is pinned low (1.9.0) so Gradle's highest-wins resolution
  never forces an upgrade on an app.
- **D4 Hand-rolled protobuf codec.** The SDK encodes exactly five messages (§4.4) and decodes one,
  so `internal/wire` writes the protobuf wire format directly. Correctness is pinned by tests that
  parse our bytes with real protobuf-java (`DynamicMessage` over a descriptor set built from the
  vendored protos) and compare against protobuf-java's deterministic encoding byte for byte.
  protobuf-java is a **test-only** dependency.
- **D5 Transport: `HttpURLConnection`** speaking Connect unary + binary protobuf (as sdk-web and
  sdk-flutter hand-roll it). Error mapping follows sdk-web's `rpc.ts` table (§4.5), including
  "other 4xx ⇒ `invalid_argument` (permanent)" so a proxy's 413 cannot retry forever.
- **D6 2xx with a non-protobuf content type is transient (F).** sdk-web treats it as permanent and
  drops the batch. On mobile that response is nearly always a captive portal or proxy, which changes
  with the network, so Android retries it with backoff (bounded by `maxQueueSize`).
- **D7 Never crash (cardinal rule, sdk-web parity).** The only host-facing throw is `Pug.init`
  argument validation (blank `projectId`, an `apiKey` that is not a public key (D32), endpoint not an
  http(s) URL). Every other public call is non-blocking, callable from any thread, and swallows +
  logs. Worker coroutines catch `Throwable` at their boundary, rethrowing `CancellationException`
  first.
- **D8 Public API is non-suspend, fire-and-forget** (the Android analytics norm: Segment, Amplitude,
  PostHog, Mixpanel), Java-friendly via `@JvmStatic`/`@JvmOverloads`. Ordering is guaranteed by one
  serial worker, so callers never need to await.
- **D9 Logging (sdk-web model).** Default logger writes to Logcat (tag `Pug`): warn/error always,
  debug only with `PugOptions.debug = true`. A custom `PugLogger` replaces it; a throwing logger is
  contained. Flutter's default is a silent no-op logger, which hides misconfiguration.
- **D10 Storage in `noBackupFilesDir`, not SharedPreferences (F).** SharedPreferences `apply()`
  writes are drained on the main thread at `Activity.onStop` (`QueuedWork.waitToFinish`), so a
  1 MB queue rewritten per event becomes host-app ANRs; and Auto Backup would restore the anonymous
  id and queue onto a new device. A `PugStorage` key/value API (Flutter's, synchronous, called only
  on the SDK worker) is backed by files with an in-memory cache and coalesced write-behind, forced to
  disk on background/flush/destroy. Key names keep the family scheme `__pug_<projectId>_<name>__`.
- **D11 `destroy()` is a runtime teardown, not a purge (F).** It stops timers and observers, makes a
  final flush bounded at 10 s (in-flight sends and identify calls included; whatever is cut off stays
  queued), persists, and keeps identity, queue, persisted consent and `last_version` (sdk-web:
  "teardown, not logout"). Flutter purges everything but consent, which makes a destroy/re-init look
  like a fresh install and a new user. `reset()` and opt-out are the deliberate clears. A following
  `init()` waits for the previous teardown before touching storage (Flutter's `_pendingTeardown`).
- **D12 Consent (sdk-web semantics, two states).** `GRANTED` | `DENIED`, default `GRANTED`
  (no `cookieless`: see §8). Leaving `GRANTED` purges persisted identity (session, anonymous id,
  external id) and drops the queue **unsent**; re-granting mints a fresh identity lazily (F: Flutter
  only gates). At init, a `DENIED` restored from a persisted record purges the same way; a `DENIED`
  seeded from config drops only the queue. `getTrackingConsent()` returns `null` before init
  (sdk-web: `denied` would make a banner re-prompt someone who opted in). `isConsentPending()` is
  included. The getters answer on the caller's thread without waiting (D28); the choice is
  persisted by default (D27).
- **D13 `reset()`** rotates session and anonymous id and clears the external id (both refs). Queued
  events keep their original `distinctId`, so `reset()` triggers a flush rather than dropping them
  (sdk-web beacons then drops; Flutter leaves them).
- **D14 `identify()`** never throws; blank or `cookieless-`-prefixed ids, pre-init, denied consent
  and `dryRun` are logged and dropped (sdk-web gates `dryRun`; Flutter transmits). Sends
  `anonymous_id` only on the first identify; never `device_id` (§1). On success it re-reads the
  profile and links the external id only if the anonymous id is unchanged (a `reset()` during the
  await wins), and only if no later `identify()` call has linked already (the latest call wins,
  whatever order the answers arrive in; F). Traits map to `google.protobuf.Struct` with nesting
  allowed (sdk-web `JsonValue`);
  Flutter flattens nested values into JSON strings.
- **D15 Client-side guards against whole-batch rejection.** The server validates the whole
  `BatchCreateRequest`, so one bad event drops its batch-mates as `invalid_argument`. Before an event
  is queued: `kind` must match `^[a-zA-Z0-9_.-]+$` (else the event is dropped), custom property keys
  starting with `$` are dropped (warn), strings are truncated to 1024 UTF-8 bytes on a code-point
  boundary, non-finite doubles are dropped, `maxSize` is capped at the server's 1000, and duplicate
  `event_id`s are removed when locking a batch. Requests are also cut at 4 MiB of encoded events,
  and an event that alone exceeds that is dropped at `track`: the server caps bodies at 8 MiB
  (`pug/internal/app/server/rpc/limits.go`) and connect-go answers `resource_exhausted`, which is
  transient, so an oversized batch would otherwise retry forever (sdk-rust does the same). Flutter
  and sdk-web leave all of this to the server.
- **D16 Retry policy.** Transient failure ⇒ rollback + exponential backoff from `maxWaitMs`
  (floor 1 s) doubling to a 5-minute cap with ±20% jitter, reset on success. Backgrounding flushes
  regardless of backoff. (Flutter: fixed `2 × max(maxWaitMs, 1 s)`; sdk-web: next `maxWaitMs`.)
  While a retry is pending, size triggers wait for it; `Pug.flush()` and `destroy()` do not. Any
  answer from the server (a permanent rejection included) resets the backoff and brings a pending
  retry forward to `maxWaitMs`. The first transient failure of an outage warns, later ones log at
  debug.
- **D17 Auto-property values follow the backend, not Flutter, where they differ (F).** The server's
  UA enrichment fills `$os`/`$osVersion`/`$device`/`$mobile`/`$browser` only when absent, and the
  rollups break down on `$os`, `$device` and `$platform`. Android sends `$platform: "android"`
  (family contract) and `$os: "Android"`, matching what the server's ua-parser derives for Android
  web traffic. Flutter sends `$os: "android"`, which splits the `$os` breakdown. Android also sends
  `$device` (a rollup dimension Flutter omits) and `$mobile`, and sets
  `User-Agent: pug-android/<version>` so the server's parser attributes no fake `$browser` to native
  traffic. Full list in §4.10.
- **D18 Screens feed `$url`/`$referrer` (Flutter parity, F).** The current and previous screen
  names ride every event as `$url`/`$referrer`. The server's attribution step counts a non-URL
  `$url` as "degraded" (`url_not_web`), so the family should decide whether native SDKs keep using
  `$url`.
- **D19 Typed track = generated Kotlin from the vendored protos**, filtered to events whose
  `platforms` include `PLATFORM_ANDROID` or are unset (123 kinds at the pinned commit). Untyped
  `track` stays the escape hatch. On the untyped path the registry also coerces integral numbers to
  `double_value` for double-typed well-known fields, so `price: 10` and `price: 10.5` land in one
  column type.

- **D20 Java nulls on the public API.** `:pug` compiles with `-Xno-param-assertions`, so a Java
  caller passing null for a non-null parameter reaches the SDK instead of failing Kotlin's entry
  check with a `NullPointerException` (D7). The facade checks the required parameters through
  `isNull(Any?)` (`internal/JavaNulls.kt`, which the compiler cannot read as always false): `track`
  and `identify` log and drop, `Pug.init` throws `IllegalArgumentException` like any other invalid
  argument, and `PugOptions` replaces a null consent default with `GRANTED`. Optional parameters
  are nullable (`properties`, `traits`, `options`). Kotlin signatures stay non-null, so the typed
  methods keep compile-time required properties; `PugClient.trackTyped` drops (warn) an event
  whose required argument arrives null.

- **D21 Opting in tracks what consent kept from the launch (F).** With consent denied by default, a
  consent-banner app's first launch loses `app_install`, `app_open` and the first `screen_view`.
  When consent goes from denied to granted, Android tracks the `app_install`/`app_update` decided at
  start (the stored version advances only then) and, while the app is on screen, `app_open` and a
  `screen_view` of the current screen. Opting out ends the visit, so no `app_close` follows the
  purged identity's `app_open`. Flutter retries the install only at the next launch, where an
  opt-in during the first launch has already written the keys that make it look like an upgrade:
  that user's `app_install` is never sent.
- **D22 With `autoScreenViews` off, screens belong to the host (F).** Resumed activities then
  neither track `screen_view` nor move `$url`/`$referrer`, so a Compose app's single activity cannot
  overwrite the screen it named with `Pug.screen`. Flutter keeps route state with `autoPageViews`
  off, but its route observer is wiring the app adds; Android's activity callbacks are not.
- **D23 Typed methods only track, and the events Pug tracks itself have none (F).** Every
  `Pug.track.<event>(…)` sends its event and changes no SDK state. `app_open`, `app_close`,
  `app_install`, `app_update` and `screen_view` keep their `PugEvents` constants and registry entries
  but get no method (`tools/gen-track`, `SDK_TRACKED`): those methods tracked the automatic events a
  second time, and `screenView()` looked like `Pug.screen` while neither moving `$url`/`$referrer`
  nor skipping the current screen. `Pug.screen` is the one screen API; with the automatic events
  off, `Pug.track(PugEvents.APP_OPEN)` still works. Flutter generates every method. (Phase 8; the
  methods were in the 0.1.0 tag, which was never published.)
- **D24 Duration properties are `<name>Ms: Long?`, sent as options.proto's text (`"1.5s"`, F).**
  Milliseconds are what Android media and timing APIs return; `kotlin.time.Duration` is a value
  class Java cannot pass, and `java.time.Duration` is API 26. The text form is options.proto's
  canonical encoding, as sdk-rust sends it. Flutter leaves the ten Duration properties out of its
  typed methods.
- **D25 Event schemas are hints (F).** The server validates no well-known schema, so Android
  enforces only the signatures (required arguments are non-null; D20 for Java) and sends values as
  given: no protovalidate ranges or patterns. sdk-rust drops an event that fails protovalidate.
  An untyped well-known event without a required property is still sent, with a debug hint. An
  `extras` entry named like an argument is dropped (warn) when that argument is set and kept when
  it is null (Flutter; sdk-rust always drops it).
- **D26 Options are one setter per field, never defaulted parameters.** `PugOptions.Builder` has
  `batchMaxSize`, `batchMaxWaitMs`, `batchMaxQueueSize`, `sessionIdleTimeoutMs`,
  `sessionMaxDurationMs`, `defaultTrackingConsent` and `persistTrackingConsent` (a Kotlin `var`
  plus a Java fluent method each), and `TrackOptions` is built the same way. The grouped setters
  of phase 2 (`trackingConsent(default, persist)` and the like) reset what a call left out, so
  `trackingConsent(persist = true)` silently put the default back to GRANTED. Adding a parameter
  to a defaulted function or constructor also replaces its synthetic `$default` method and breaks
  compiled Kotlin callers; a new setter on a builder is binary-compatible. Changed before 0.1.0
  was published, so no caller was broken.
- **D27 Consent is persisted by default (F).** `persistTrackingConsent` defaults to `true`. With
  `false`, an opt-out made in an app's settings screen lasted only until the process died, and a
  banner app that forgot the flag asked again on every launch. `false` stays for apps that keep the
  choice in their own consent tool and replay it on each launch. sdk-web defaults `persist` to
  false.
- **D28 No public call waits on the caller's thread.** The consent getters used to wait up to 1 s
  after init for start-up to read a stored choice, which on a slow disk held up the host's first
  frame. They now answer at once: the latest opt-in/opt-out, else the restored state, else unknown
  (`null`, pending, not enabled), warning once when asked before start-up has read consent.
  `Pug.whenConsentReady(Runnable)` is the way to wait: its callback runs once on the main thread
  after start-up has read consent (or failed), posted even when that is already done so it never
  runs inside the caller; a throwing callback is logged. One whose client is destroyed before it
  runs goes to the next client, since the getters it calls already ask that one. The main thread, because the usual next
  step is showing a consent banner; `Runnable`, so that Kotlin and Java lambdas both fit. Before
  init it is ignored with a warning like every other call.
- **D29 `autoTrack` is `autoOpenCloseEvents` (F).** Beside `autoScreenViews` and
  `autoInstallEvents`, `autoTrack = false` read as "no automatic events" while it turned off only
  `app_open` and `app_close`. Flutter and sdk-web keep `autoTrack`.
- **D30 The automatic events (2026-10-25, from mixpanel-android and posthog-android).** Pug tracks
  `app_install`/`app_update`, `app_open`/`app_close`, `screen_view` and, new in phase 9,
  `app_crashed`. Nothing else in the catalog is tracked automatically.
  - `app_open`/`app_close` stay on every foreground/background (Flutter; PostHog's `Application
    Opened`/`Application Backgrounded` fire at the same points). `app_foregrounded` /
    `app_backgrounded` are not sent: they would fire at the same instants, since Android has no
    termination callback that could give `app_close` the separate meaning app_events.proto's
    comments describe (F).
  - `app_install.installSource` (phase 9) is the installer's package name
    (`getInstallSourceInfo().installingPackageName` on API 30+, `getInstallerPackageName` below),
    omitted when there is none (adb, sideload). Raw, not mapped to the server seed's `play_store`:
    the proto names no vocabulary, and a mapping table loses the stores it does not list (F).
  - `app_crashed{errorType = qualified class name, errorMessage = message cut to 1024 bytes}` on an
    uncaught exception, behind `autoCrashEvents` (default on, like Mixpanel's `$ae_crashed`;
    PostHog's exception capture is opt-in). One `Thread.UncaughtExceptionHandler` per process,
    installed at the first init, dormant without a client (another handler may have chained to it,
    so it is never unlinked). It always calls the previous handler, after waiting at most 1 s for
    the worker to queue the event and sync storage, since the process dies next; it does not wait
    when the crashing thread is the worker. Consent gates it like any event; it joins
    `SDK_TRACKED` (D23).
  - Not tracked: deep links (PostHog's `Deep Link Opened` is on by default, but the catalog has no
    kind, and campaign capture is §8); taps, rage and dead clicks (opt-in in both SDKs, and the
    catalog's click kinds are web-only); session start or length (Mixpanel's `$ae_session`: every
    event carries `$sessionId`).
- **D31 Java tracks well-known events as objects.** Each event with properties that has a method on
  `Pug.track` also has a generated class in `sh.pug.android.events` (`AddToCartEvent`, the method
  name plus `Event`, which keeps `PurchaseEvent` clear of Play Billing's `Purchase`), tracked by
  `Pug.track(event, options)`: required properties are constructor arguments, every other one a
  fluent setter, numbers and booleans as primitives (Java's `amount(10)` widens; a `Double?`
  parameter takes only `10.0`), plus `extra(key, value)`. From Java the methods took a row of nulls
  for each optional property before the one wanted (99 of 118 events have optional properties).
  Chosen over a builder ending in `send()` (a forgotten `send()` drops the event silently, and
  `purchase()` would clash with the Kotlin method whose parameters all default) and over property-key
  constants for the untyped map (no types). Kotlin keeps its named-argument methods; those of events
  with a class are `@JvmSynthetic`, so Java has one typed form, and those of events without
  properties keep `@JvmOverloads` for Java (`Pug.track.signup()`). The ABI dump lists synthetic
  methods, so `pug.api` still pins them. `WellKnownEvent` is abstract with an internal constructor; the values sit
  in an array in schema order, which `PugClient.trackTyped` reads during the call, so an object can
  be changed and tracked again. Warnings name the class (`Pug.track(AddToCartEvent)`), not its
  minified name. The internal members are `@JvmSynthetic`, out of Java's reach; a Java subclass
  still compiles (an internal constructor is public in bytecode), but gains nothing over untyped
  `track`, and `sealed` would make each new event a source break for an exhaustive `when`. The classes add about 300 KB to `classes.jar`, which R8 strips from apps that do not
  use them.
- **D32 `Pug.init` takes only a public key (2026-10-26, F).** The API key must start with `pub_`
  and, as in sdk-rust, sdk-go and sdk-ruby, be printable ASCII without spaces; anything else throws
  `IllegalArgumentException`. The server finds the project from the key alone, so a project id
  passed as the key, or the two swapped, started fine and failed only at the first send; and a
  private `prv_` key, which the ingest endpoints accept too, would ship in the APK for anyone to pull
  out. A control character, a trailing newline from a secrets file say, makes Android's
  `HttpURLConnection` throw on every request, which the dispatcher drops as an SDK bug. `dryRun` is
  not exempt: the sample's placeholder is `pub_dry_run`. sdk-web and Flutter check no prefix; the
  server SDKs check `prv_` the same way.
- **D33 Values of other types are sent as their text (2026-10-26, F).** A property, extra or trait
  value that §4.7 has no mapping for is sent as the string its `toString()` returns, at the top
  level and nested in maps and lists; it used to be dropped with a warning.
  `mapOf("userId" to user.id)` compiles whatever `id` is, and a `UUID`, `Uri` or `LocalDate`
  vanished at run time with a Logcat line as the only sign. The cost: an object without its own
  `toString()` sends `Foo@1a2b3c`, and a data class every field it prints; the host chose to pass
  it. Not the fields by reflection (sdk-web's `JSON.stringify`, PostHog's Gson): R8 renames them in
  a minified app. Flutter sends what `jsonEncode` can encode and drops the rest. A `toString()` that
  throws still drops its value alone.

## 4. Architecture

### 4.1 Layout

```
sdk-android/
  PLAN.md  CLAUDE.md  README.md  CHANGELOG.md  LICENSE  Makefile  mise.toml
  settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/libs.versions.toml  gradlew
  buf.yaml  proto/                     vendored protos (pinned), input to tests + typed-track codegen
  pug/                                 :pug Android library → sh.pug:pug-android
    src/main/kotlin/sh/pug/android/    public API (explicit API mode)
      internal/wire/                   protobuf codec (internal)
      internal/transport/              Connect unary client (internal)
      internal/...                     runtime pieces added by later phases
    src/test/kotlin/...                JVM unit tests (+ Robolectric from phase 4)
    src/test/resources/pug.binpb       descriptor set of proto/ (generated by `make protos`)
    api/pug.api                        public API dump of the release AAR, checked by `make check`
  tools/gen-track/                     typed-track generator, `:tools:gen-track` (not shipped)
  tools/abi-dump/                      public API dump and check, `:tools:abi-dump` (not shipped)
  sample/                              sample app, `:sample` (framework only; minified release build)
  docs/development.md
```

### 4.2 Threading

- One serial worker, `Dispatchers.IO.limitedParallelism(1)`, created once by `PugFacade` and shared
  by every client, so two clients never touch storage at once. Every state read/write, storage
  access, and event build runs there, so internals need no locks.
- Public calls read `clock.nowMillis()` and map their arguments (properties, traits) on the
  caller's thread, so `occur_time` is the call time and the host may reuse its map, then send an op
  into the client's unlimited `Channel` and return. One coroutine per client consumes the channel
  on the worker, so ops run in call order; it starts only after the previous client's teardown,
  so calls made in between wait in the channel. Ops never suspend.
- Network calls run as separate coroutines in the client's network scope, suspending on the
  transport, so tracking continues during a send. **After any network await, re-read state; never
  trust a pre-await snapshot** (identify re-checks the anonymous id; the dispatcher checks
  `disposed` before commit/rollback).
- Scope: `SupervisorJob` + `CoroutineExceptionHandler` that logs. A superseded or destroyed client
  touches no shared storage.

### 4.3 Never-crash rules

`catch (e: CancellationException) { throw e }` before every `catch (t: Throwable)`. Audit with
`rg '\bthrow\b|\brequire\(|\bcheck\(|!!' pug/src/main` — every hit must be internal and caught, or
`Pug.init` validation.

### 4.4 Wire (hand-rolled, `internal/wire`)

Messages written (field number: type). All in field-number order; map entries sorted by key (equal
to protobuf-java deterministic output) and always carry both key and value.

| Message | Fields |
| ------- | ------ |
| `sdk.events.v1.BatchCreateRequest` | 1 `repeated Event events` |
| `sdk.events.v1.Event` | 1 `event_id` string · 2 `auto_properties` map<string, PropertyValue> · 3 `custom_properties` map<string, PropertyValue> · 4 `distinct_id` string · 5 `kind` string · 6 `occur_time` Timestamp · 7 `session_id` string · 8 `cookieless` bool (never set) |
| `common.v1.PropertyValue` | oneof: 1 `string_value` · 2 `int_value` int64 · 3 `double_value` double · 4 `bool_value` bool · 5 `timestamp_value` Timestamp |
| `google.protobuf.Timestamp` | 1 `seconds` int64 · 2 `nanos` int32 (proto3: zero fields omitted; pre-1970 uses floorDiv/floorMod so nanos ≥ 0) |
| `sdk.profiles.v1.IdentifyRequest` | 1 `external_id` · 2 `traits` Struct · 3 `anonymous_id` · 4 `device_id` (never set) |
| `google.protobuf.Struct` / `Value` / `ListValue` | Struct: 1 map<string, Value>. Value oneof: 1 `null_value` enum · 2 `number_value` double · 3 `string_value` · 4 `bool_value` · 5 `struct_value` · 6 `list_value`. ListValue: 1 repeated Value |

Read: `sdk.events.v1.BatchCreateResponse` (1 `accepted` uint32 · 2 `dropped` uint32 · 3
`dropped_by_reason` map<string, uint32>), skipping unknown fields.

Pitfalls: a oneof member is **always written when selected, even if zero/empty/false**
(`PropertyValue{int_value: 0}` without field 2 is an empty oneof, which the server's
`oneof.required` rejects, failing the batch). The protos are `edition = "2023"`: singular fields have
explicit presence, so never write an empty `session_id`/`distinct_id`. `occur_time` is always
written.

### 4.5 Transport (`internal/transport`)

- `POST {endpoint}/sdk.events.v1.EventsService/BatchCreate` and
  `/sdk.profiles.v1.ProfilesSDKService/Identify`; trailing slashes on `endpoint` are stripped.
  Default endpoint `https://api.pugs.dev`.
- Headers: `content-type: application/proto`, `connect-protocol-version: 1`, `x-api-key`,
  `user-agent: pug-android/<version>`.
- Deadlines: 10 s per batch, 15 s for identify (sdk-web: 5 s / 15 s; identify is never retried).
  Enforced as connect + read timeouts plus a coroutine deadline that `disconnect()`s on expiry or
  cancellation. The request thread also stops reading a body at the deadline: the JDK's
  `disconnect()` waits for the lock a read in progress holds, then may leave the socket to its
  keep-alive cleaner, so a body trickling in held both the caller and the thread past it.
- Error mapping (sdk-web `rpc.ts` parity): a non-2xx Connect JSON body `{code, message}` gives the
  code; otherwise by HTTP status: 401 `unauthenticated`, 403 `permission_denied`, 404
  `unimplemented`, 408/429 `unavailable`, other 4xx `invalid_argument`, everything else
  `unavailable`. `SocketTimeoutException` / deadline ⇒ `deadline_exceeded`; other `IOException` and
  `SecurityException` ⇒ `unavailable`. Any other throwable (an SDK bug) ⇒ permanent.
- **Permanent** = `invalid_argument`, `not_found`, `already_exists`, `permission_denied`,
  `failed_precondition`, `unimplemented`, `unauthenticated`. Everything else is transient.
- 2xx: a non-empty content type without `proto` ⇒ transient (D6). `BatchCreateResponse.accepted <
  sent` ⇒ warn (sdk-web parity). A 2xx body that fails to decode is still a success.
- Requests run under a TrafficStats tag (`TRAFFIC_STATS_TAG`), and the thread's own tag is put back
  after: StrictMode's `detectUntaggedSockets` (in `detectAll()` from API 26) reports every socket
  opened untagged, which a host whose VM policy has `penaltyDeath` would die of.

### 4.6 Storage and keys

`PugStorage` (public interface): `getString`, `putString`, `remove`, all synchronous and called only
on the SDK worker. `MemoryPugStorage` is public (the persistence opt-out); a `SafePugStorage` wrapper
falls back to memory if the primary throws (Flutter).

Default: `FilePugStorage`, one file per key under `noBackupFilesDir/pug/` (D10). The file name is the
key with everything outside `[A-Za-z0-9_.-]` percent-encoded, so a project id cannot lead outside the
directory, or `sha256-<hex>` beyond 128 characters. Each key is read at most once. A change lands in
memory and reaches the disk 1 s later together with the changes made meanwhile
(`STORAGE_SYNC_DELAY_MS`), or at once on backgrounding, `Pug.flush()` and the end of `destroy()`. A
write goes to `<name>.tmp`, is fsynced and renamed over the file. A failed write is warned once, kept
in memory and retried with the next sync; a file that cannot be opened reads as absent.

Keys (`PugStorageKeys`): `__pug_<projectId>_session__`, `_profile__`, `_external_id__`, `_queue__`,
`_consent__` (only when `persist`), `_last_version__`. No `device_id` key (§1). `priorActivity` =
all keys except `consent` and `last_version` (install detection, Flutter).

Persisted JSON: session `{sessionId, startTime, lastActivityTime}`; profile `{anonymousId,
externalId?}`; queue = JSON array of events in the domain shape (`eventId, kind, sessionId,
distinctId, occurTime, customProperties, autoProperties`; values as `{stringValue|intValue|
doubleValue|boolValue|timestampValue: …}`). Consent is persisted as `granted`/`denied`.

### 4.7 Event pipeline

`track(kind, properties, options)` → (caller thread) timestamp, blank-kind / kind-regex gate,
`occurTime`, map custom properties → (worker) consent gate → resolve session at the call time
(rotate on 30 min idle or 24 h max) and profile → build auto-properties → event (4 MiB gate) →
`dryRun` gate → dispatcher (queue, or immediate send).

Property mapping (custom, traits-flattening excluded): `String`/`CharSequence`/`Char`/`Enum` →
string (truncated, D15); `Boolean` → bool; `Byte`/`Short`/`Int`/`Long`/`AtomicInteger`/`AtomicLong`
→ int; `BigInteger` → int if it fits in 64 bits else string; `Float`/`Double`/other `Number` →
double if finite else dropped (warn); `java.util.Date`/`Calendar` → timestamp; `java.time.Instant`
→ timestamp only behind an `SDK_INT >= 26` guard; `Map`/`Iterable`/arrays → JSON string; `null` →
dropped silently; anything else → its `toString()` (D33).

### 4.8 Identity and sessions

Anonymous id `anon-<uuidv7>`; `distinctId = externalId ?: anonymousId`; session id uuidv7; idle
timeout 30 min, max duration 24 h (both configurable, > 0 else warn + default). A stored external
id starting with `cookieless-` is discarded with a warning, and queued events carrying one are
dropped on load (Flutter).

### 4.9 Lifecycle, screens, install/update

- `ActivityLifecycleTracker` (our own `ActivityLifecycleCallbacks`; no AndroidX lifecycle, D3) runs
  on the main thread, and `start()`/`stop()` hop there. The app is in the foreground while one of
  its activities is started (an identity set, not a count), and an activity stopped with
  `isChangingConfigurations` does not leave. Seed at registration: `getMyMemoryState` reporting
  `IMPORTANCE_FOREGROUND` stands for one activity started before registration. The first activity
  seen starting settles it, since `Application.onCreate` of a cold start already reports foreground,
  and an unseen activity stopping consumes it. Every callback is guarded: a throw would crash the
  host's activity.
- Transitions become ops; the worker keeps `visible` and `opened` (Flutter's captured-or-not
  contract). `autoOpenCloseEvents` (default on, D29): `app_open` on foreground, `app_close` on
  background, sent immediately. Backgrounding always drains the queue, backoff or not, and syncs
  storage at once and again once the sends settle, even with `autoOpenCloseEvents` off.
- `autoScreenViews` (default on): `screen_view{screenName = activity simple name, screenClass =
  qualified name}` on `onActivityResumed`. `Pug.screen(name)` tracks `screen_view{screenName}` for
  Compose and fragments. Both share the dedup (a screen shown again while current is not tracked)
  and the `$url`/`$referrer` state (D18); names are cut to 1024 bytes. Off: activities are ignored
  (D22).
- `autoInstallEvents` (default on): Flutter's `InstallTracker`: `app_install{appVersion}` when no
  prior-activity key exists, `app_update{appVersion, previousVersion}` when the stored version
  differs, the version advanced only once the event is captured. `check()` runs in `restore()`
  before identity and queue are built, `emit()` after `enforceAtInit()` (so before the launch
  `app_open`) and again on opt-in (D21). The event is dated at `Pug.init` (or at the opt-in): the
  launch `app_open` is dated on the main thread when the activity starts, which can precede the
  worker's start-up.
- Init after the first activity resumed (or off the main thread, which posts the registration)
  misses that activity's `screen_view`; the seed still gets `app_open` and `app_close` right.

### 4.10 Auto-properties

| Key | Value |
| --- | ----- |
| `$projectId`, `$sdkVersion` | project id, `SDK_VERSION` |
| `$platform` | `android` |
| `$os`, `$osVersion` | `Android`, `Build.VERSION.RELEASE` |
| `$device`, `$deviceModel`, `$deviceManufacturer` | `Build.MODEL`, `Build.MODEL`, `Build.MANUFACTURER` |
| `$deviceType` | `tv` (leanback / television feature) else `mobile` (Flutter value set) |
| `$mobile` | bool: not a TV and smallest width < 600 dp, per event |
| `$screenWidth`, `$screenHeight`, `$screenScale` | app display metrics in dp (ints, current orientation), density; per event |
| `$locale`, `$timezone` | `Locale.getDefault().toLanguageTag()`, `TimeZone.getDefault().id`; per event |
| `$appName`, `$appPackage`, `$appVersion`, `$appBuild` | from `PackageManager`; `$appBuild` is `longVersionCode` (API 28+) else `versionCode` |
| `$networkType` | `wifi`/`mobile`/`ethernet`/`vpn`/`bluetooth`/`other`/`none`/`unknown`; per event, only when `ACCESS_NETWORK_STATE` is granted (not declared by the SDK). API 23+: the active network's transports in that order (a VPN also carries its underlying transport), `none` without one, `unknown` without capabilities; API 21-22: the active `NetworkInfo` |
| `$url`, `$referrer` | current / previous screen name (D18) |

Fixed values are read once, on the worker at start; per-event ones on the worker for each event. A
value that cannot be read is left out and warned once. The library manifest declares only
`INTERNET`. The user-assigned device name is never collected.

### 4.11 Public API (target shape)

```kotlin
Pug.init(context, "project-id", PugOptions("pub_…") { debug = true })
Pug.track("checkout_started", mapOf("cartId" to "c1"))     // untyped
Pug.track.addToCart(productId = "p1", price = 9.99)        // typed; kinds in PugEvents
Pug.track(AddToCartEvent("p1").price(9.99))                // typed, the form for Java (D31)
Pug.screen("Settings")
Pug.identify("user-42", mapOf("plan" to "pro"))
Pug.reset(); Pug.rotateSession(); Pug.flush(); Pug.destroy()
Pug.optInTracking(); Pug.optOutTracking()
Pug.getTrackingConsent(); Pug.isTrackingEnabled(); Pug.isConsentPending()
Pug.whenConsentReady { if (Pug.isConsentPending()) showBanner() }
```

`PugOptions` has a builder for Java plus a Kotlin DSL factory: `apiKey`, `endpoint`, `debug`,
`dryRun`, `batchMaxSize = 10 ≤ 1000`, `batchMaxWaitMs = 5000 ≥ 0`, `batchMaxQueueSize = 1000 ≥ 1`,
`sessionIdleTimeoutMs = 30 min`, `sessionMaxDurationMs = 24 h`, `defaultTrackingConsent = GRANTED`,
`persistTrackingConsent = true`, `autoOpenCloseEvents`, `autoScreenViews`, `autoInstallEvents`, `logger`,
`storage`. Out-of-range numbers warn and fall back to the default (sdk-web). `TrackOptions` has the
same shape (D26): a Java builder plus the Kotlin factory `TrackOptions { immediate = true }`, with
`immediate = false` and `timestamp: Long? = null`.

## 5. Phases

### Phase 1 — scaffold and wire ✅

Deliverables: Gradle project (AGP 9.4.1 built-in Kotlin, Gradle wrapper, version catalog, explicit
API, lint config); `mise.toml` (JDK 21, buf 1.73.0); `Makefile` (`sync-protos`, `proto-latest`,
`protos`, `check-codegen`, `verify-version`, `build`, `test`, `lint`, `check`, `ci`); vendored
`proto/` at the pinned commit; `buf.yaml`; `internal/wire` (`ProtoWriter`, `ProtoReader`, models,
`PugWire` encode/decode); `internal/transport` (`HttpPugTransport`, `RpcCode`,
`PugTransportException`); CI workflow; `CLAUDE.md`, `docs/development.md`, `README.md` skeleton,
`CHANGELOG.md`, `LICENSE`.

Acceptance: `make check` green. Tests: writer primitives; conformance against protobuf-java
(parse + byte-for-byte deterministic equality) for events with every value type, zero/empty oneof
members, negative ints, pre-1970 timestamps, unicode keys, nested Struct traits; response decode with
unknown fields; transport against MockWebServer (path, headers, body, success, partial-accept warn,
every row of the error table, captive-portal transient, timeout, unreachable host).

**Notes (done 2026-10-11).** `make check` green: 36 tests, lint clean, release AAR 54 KB.
- The partial-accept *warning* belongs to the phase 3 dispatcher; the transport returns the decoded
  `BatchCreateResponse` (or null for an undecodable 2xx) and never logs.
- Transport contract: every failure is a `PugTransportException`, apart from coroutine cancellation
  and `Error`s from the HTTP stack. An unexpected `RuntimeException` from `HttpURLConnection` is
  permanent (sdk-web parity: repeats identically).
- `HttpURLConnection` (the JDK's in tests, OkHttp-backed on Android) silently retries a POST once
  when the connection drops before a response, so a batch can reach the server twice. Each request
  is still valid on its own; duplicates rely on server-side `event_id` dedup (as sdk-web's beacon
  path already does).
- Tooling surprises: AGP 9.4.1 bundles KGP 2.2.10 and accepts `languageVersion 2.0` under
  `allWarningsAsErrors`; Spotless's ktlint ignores `.editorconfig`, so the style is passed with
  `editorConfigOverride` (mirrored in `.editorconfig` for IDEs), and after changing it run
  `./gradlew spotlessApply --rerun-tasks --no-build-cache` or the build cache serves stale output;
  lint's version-nag checks are disabled (floors are deliberate, and they query the network).
- `make check-codegen` reports the untracked descriptor set as drift until the first commit.
- Nothing is committed yet: the repo is `git init`ed only.

### Phase 2 — core building blocks ✅

`PugOptions` (+ builder/DSL, validation), `TrackOptions`, `PugLogger` (+ Logcat default,
`SafeLogger`), `PugStorage` + `MemoryPugStorage` + `SafePugStorage` + `PugStorageKeys`, `PugClock`,
UUIDv7 generator, `PropertyMapper` (§4.7), `TraitsMapper` (→ Struct), event builder with D15 guards,
`Identity` (session + profile, §4.8), `TrackingConsentController` (D12; pure state, purge
callbacks), `EventQueue` (lock / commit / rollback, drop-oldest at `maxQueueSize`, JSON
persistence, reserved-prefix filter on load, dedupe on lock). All pure JVM, unit-tested with fakes.

**Notes (done 2026-10-11).** `make check` green: 150 tests (114 new), lint clean, release AAR 131 KB
(unminified, 99 classes; the growth is our own classes).
- Public so far: `PugOptions` (+ `Builder`; the Kotlin factory `PugOptions(apiKey) { … }` is
  `@JvmSynthetic`), `TrackOptions`, `TrackingConsent`, `PugLogger`, `PugStorage`, `MemoryPugStorage`.
  `PugOptions` is opaque (internal getters). `build()` never throws: out-of-range numbers warn and
  take their default, except `maxSize > 1000`, which is capped at 1000 (D15). Durations are `Long`
  milliseconds (`maxWaitMs`, `idleTimeoutMs`, `maxDurationMs`). Builder `var`s are
  `@set:JvmSynthetic`, so Java sees one fluent setter per option. (The grouped `batch()`/
  `session()`/`trackingConsent()` setters first chosen here were replaced before publishing: D26.)
- D9 refinement: `debug` gates debug messages for any logger, a custom one included (`SafeLogger`);
  a throwing logger is reported once to Logcat. Internals only ever call `SafeLogger`.
- `SafePugStorage`: the first throw moves the client to memory for good (Flutter); a failed read
  returns null. Nothing is lost because components hold their state in memory and only write
  through; storage is read once, at construction.
- Mapping beyond §4.7: unsigned types; `Float` via its decimal string (`0.1f` → 0.1, not
  0.10000000149…); dates outside years 1–9999 dropped (sdk-rust); `Instant` sits behind an injected
  SDK level with `@SuppressLint("NewApi")`, because `@TargetApi` trips lint's `UseRequiresApi` and
  AndroidX's `@RequiresApi` is not a dependency. Collections become JSON text following
  `JSON.stringify` (whole numbers without a fraction, non-finite → `null`, dates → ISO 8601 UTC,
  integers beyond ±(2^53−1) → strings), capped at depth 32 and 10 000 values (else the property is
  dropped: this bounds self-referencing structures). A value that throws while read is dropped
  alone; a map that throws mid-iteration keeps what was read.
- Traits: nesting kept, `null` kept (`null_value`), strings not truncated, non-finite numbers
  dropped at the top level and `null` when nested, the same JSON rules otherwise.
- `EventFactory`: `acceptsKind` (blank or pattern, warn) is meant to run before the consent and
  identity steps; `occurTime` replaces a `TrackOptions.timestamp` outside 1970–9999 with the call
  time (warn, sdk-rust parity); `$projectId`/`$sdkVersion`/`$platform` overwrite provider values;
  an event over 4 MiB encoded is dropped (error). `PugWire.eventSize` computes the exact encoded
  size without allocating and is pinned to the encoder by `PugWireTest`.
- `Identity`: ids are minted lazily, so nothing is written until first use and a denied client
  writes nothing. Restore drops a session id that is not a UUID, an anonymous id without `anon-`,
  and a `cookieless-` external id (healing storage). `reset(now)` mints a new anonymous id and
  session and clears the external id; `clear()` forgets everything (the consent purge);
  `isCurrentAnonymousId` is identify's post-await check and never mints.
- `TrackingConsentController(…, purge)`: `enforceAtInit()` applies D12; every `set(DENIED)` drops
  the queue, then forgets identity. `isPending` stays true until a restored or explicit choice.
  When a configured (not stored) denial meets a device that still has an external id, init warns
  that the id is kept (sdk-web).
- `EventQueue`: each event's JSON is cached, so a change re-joins strings instead of re-encoding the
  queue. Storage holds the locked batch, then pending events, and the key is removed when empty.
  Overflow warns once per episode, then logs at debug. `lock(maxCount, maxBytes)` drops a repeated
  id within the batch, and drops an event that alone exceeds `maxBytes` (error) rather than resending
  it forever. `clear()` invalidates the locked batch, so a stale `commit`/`rollback` is a no-op.
  Restore skips unreadable or invalid events one by one, drops `cookieless-` distinct ids, keeps the
  newest `maxQueueSize`, and rewrites storage only when something was dropped.
- Persisted JSON is written by `internal/json/JsonWriter` and read with `org.json` (a framework
  class on Android, the real library in tests); reads use only `opt` plus type checks, where the
  two implementations agree.

For phase 3:
- Pipeline wiring: `acceptsKind` → consent/`dryRun` gates → `identity.sessionId(callTime)` and
  `distinctId` → `factory.create` → `queue.push` (or immediate send);
  `lock(options.batchMaxSize, MAX_BATCH_BYTES)`. The consent `Purge` is `queue.clear()` (log the
  count) and `identity.clear()`. `reset()` is `identity.reset(now)` when granted and
  `identity.clear()` when denied; `rotateSession()` does nothing while denied (sdk-web writes
  nothing then).
- Kotlin's parameter null-checks throw `NullPointerException` when Java passes null for a non-null
  parameter (`new PugOptions.Builder(null)`, a future `Pug.track(null)`). To keep D7, choose at the
  facade between nullable parameters and `-Xno-param-assertions`.
- Snapshot host property maps on the caller's thread before the worker reads them: a host may
  reuse or mutate its map after `track` returns.

### Phase 3 — runtime ✅

`Dispatcher` (size/timer/immediate triggers, serialized flushes, `flushAll`, permanent vs transient,
D16 backoff, disposed guards around every post-await commit/rollback), `PugClient` (coordinator:
track/identify/reset/rotate/flush/destroy/consent; teardown ordering D11), `Pug` facade (singleton,
init validation, pre-init warnings, never-throw wrappers, pending-teardown handoff). Tests drive
virtual time with `StandardTestDispatcher` and a gated `FakeTransport` (to open the
reset-during-identify and destroy-during-flush windows), plus an end-to-end MockWebServer test
decoding real batches.

**Notes (done 2026-10-11).** `make check` green: 225 tests (75 new), lint clean, release AAR 176 KB.
- Public: the `Pug` object (`@JvmStatic`, `@JvmOverloads`) with `init`, `track`, `identify`,
  `reset`, `rotateSession`, `flush`, `destroy`, `optInTracking`, `optOutTracking`,
  `getTrackingConsent`, `isTrackingEnabled`, `isConsentPending`. `Pug.init(context, projectId,
  options)` has its final shape; until phase 4 the context is only validated.
- `internal/runtime/`: `PugFacade` (the state behind `Pug`: validation, pre-init warnings through a
  Logcat fallback logger, never-throw wrappers, the teardown handoff, the shared worker),
  `PugClient` (one configured instance) and `EventDispatcher` (delivery). Java nulls: D20.
- Start-up, on the worker (§4.2): wait for the previous client's teardown job, then restore consent
  and publish it for the getters, then identity, queue, `enforceAtInit()`, and `dispatcher.start()`
  (restored events go out `maxWaitMs` later). Ops then run until `destroy()` closes the channel,
  and the teardown follows. `destroy()` returns the consumer's job; the facade hands it to the next
  `init()` and drops it once complete (it would keep the old client alive).
- Teardown (D11): `close()` the dispatcher (no timers, no retries), then within 10 s drain the queue
  and join every network child (in-flight and immediate sends, identify calls), then mark
  `disposed`, `dispose()` the dispatcher and cancel the scope; later completions touch nothing.
- Properties and traits are mapped on the caller's thread, not the worker (§4.7 updated): a host's
  `AtomicInteger`, `Date` or nested list changed after `track()` returns would otherwise be read
  late. `EventFactory.create` now takes mapped custom properties.
- Dispatcher: the `maxWaitMs` timer starts with the first pending event and is not reset by later
  ones (sdk-web, and what the `batch()` KDoc promises); a send that leaves fewer than `maxSize`
  restarts it. `flush()` (used by `reset()`) respects a pending retry, `drain()` (`Pug.flush()`,
  teardown, backgrounding in phase 4) does not. A transiently failed immediate send is queued
  unless a consent purge happened meanwhile (a purge generation counter; §7). Partial accepts warn
  with `dropped_by_reason`.
- identify validation (blank, `cookieless-`) runs on the caller's thread and logs at error (sdk-web);
  the latest call wins (D14).
- Tests: `FakeTransport` (scripted answers, `hold()`/`release(i)` to open the race windows),
  `FixedRandom` (no jitter), virtual time via `TestCoroutineScheduler`, `EndToEndTest` (real worker,
  `HttpPugTransport`, MockWebServer, bodies decoded by protobuf-java), and Java tests under
  `src/test/java` (`PugJavaApiTest` pins the Java shape; `JavaCallers` passes nulls). The race guards
  were mutation-checked: removing the teardown wait, the dispose guard, the purge generation, the
  identify ordering or the reset check each fails its test.
- Surprises: `kotlinx-coroutines-test` 1.9.0's scheduler APIs are `@ExperimentalCoroutinesApi`, an
  error under `allWarningsAsErrors` (each test file opts in); `limitedParallelism(1)` is stable.
  ktlint rewraps a long one-line `if (…) a else b` into a broken-up condition: write the block form.

For phase 4:
- Default storage: `PugClient.restore()` uses `options.storage ?: MemoryPugStorage()`. Build
  `FilePugStorage` from the context in `PugFacade.init` instead, and force its write-behind to disk
  (D10) at the end of `tearDown()` (before `disposed`) and when backgrounding.
- Auto-properties: `capture()` passes `emptyMap()` to `factory.create`; add the provider and read it
  on the worker.
- Lifecycle callbacks arrive on the main thread: submit ops, and keep the foreground flag on the
  worker, which alone knows whether consent let `app_open` through (Flutter's captured-or-not
  contract). Backgrounding is `dispatcher.drain()`, regardless of backoff.
- Install tracker: run it in `restore()` after the consent restore and before anything can write a
  project key, so the `priorActivity` probe sees only earlier launches; it precedes the launch
  `app_open`.
- `Pug.screen(name)`: a facade method with the same `isNull` check as `track`, and a worker op that
  updates the `$url`/`$referrer` state (D18).

### Phase 4 — Android platform ✅

`Pug.init(context, …)` wiring; `FilePugStorage` (D10); `AndroidAutoProperties` (§4.10);
`ActivityLifecycleTracker` (foreground + screens); `InstallTracker`; `Pug.screen`. Robolectric tests
for storage (real files), lifecycle (ActivityController), auto-properties.

**Notes (done 2026-10-11).** `make check` green: 289 tests (64 new, 25 on Robolectric 4.17 at SDK
36), lint clean, release AAR 216 KB.
- Public: `Pug.screen(name)`. `Pug.init` now uses its context: the facade hands it to `newClient`,
  which builds an `AndroidPlatform` from `applicationContext`.
- `internal/platform/Platform` is what the runtime takes from the device (storage directory,
  auto-properties, lifecycle observation), so `PugClient` still runs on a plain JVM; tests pass a
  `FakePlatform`. `AndroidPlatform` is the real one. Context I/O (`noBackupFilesDir`, package info,
  the app label) happens on the worker in `restore()`, never on the caller's thread, so `Pug.init`
  on the main thread does no disk reads (StrictMode). The main thread only registers the callbacks
  and asks `getMyMemoryState`.
- `PugClient`: `capture()` reports whether consent let the event through, which `opened` and the
  install tracker follow. Lifecycle callbacks are ops like calls, labelled for logs ("Tracking the
  app moving to the background failed"). `Pug.flush()` also syncs storage. `restore()`: consent,
  auto-properties, `InstallTracker.check`, identity, queue, dispatcher, `enforceAtInit()`,
  `InstallTracker.emit`, `dispatcher.start()`. The teardown syncs file storage after
  `dispatcher.dispose()`.
- Deviations: D21 (opt-in tracks what consent kept from the launch; opt-out ends the visit) and D22
  (`autoScreenViews` off leaves screens to the host). `$networkType` is read per event (Flutter: once
  at start).
- Surprises: Robolectric's Android 16 runtime needs `--add-exports=java.base/jdk.internal.access=
  ALL-UNNAMED` on JDK 21 (`unitTests.all` in `pug/build.gradle.kts`). MockWebServer's OkHttp 5.5
  resolves to `okhttp-android` in unit tests: under Robolectric it loads
  `android.net.ssl.InvalidEchDataException` (API 37) and fails, so `AndroidEndToEndTest` serves with
  the JDK's `com.sun.net.httpserver`; and `unitTests.isIncludeAndroidResources` makes AGP's
  AAR-metadata check reject that artifact (it wants compileSdk 37), so it stays off. Robolectric
  downloads its 213 MB `android-all-instrumented` jar into `~/.m2` at test time, which CI now
  caches. `ActivityController.recreate()` does report `isChangingConfigurations`.
- Mutation-checked: removing the configuration-change check, the seed settlement, the
  stop-before-start guard, the opt-out visit reset, the immediate background sync, the teardown
  sync or the opt-in replay each fails a test.

For phase 5:
- The kind constants `APP_OPEN`, `APP_CLOSE`, `SCREEN_VIEW` (`PugClient`) and `APP_INSTALL`,
  `APP_UPDATE` (`InstallTracker`) can come from the generated `PugEvents.kt`.
- Decide whether the typed `screenView(…)` delegates to `Pug.screen` (moving `$url`/`$referrer`)
  or only tracks the event; Flutter's only tracks. The typed `appOpen`/`appClose`/`appInstall`/
  `appUpdate` duplicate the automatic ones while those options are on: say so in their KDoc.
- Typed methods should reuse `PugClient.track`'s caller-thread mapping and the `isNull` checks
  (D20).

### Phase 5 — typed track ✅

`tools/gen-track` reads `buf build` JSON descriptors (custom options resolve to named keys), emits
`TrackNamespace.kt` (one method per event: required fields first as non-null params, optional as
nullable defaults, then `extras` and `options`; named args win over `extras` with a warning),
`PugEvents.kt` (kind constants), `WellKnownEvents.kt` (field types + required, for the untyped hint
and D19 coercion). `make typed-track`; CI drift check. Verify Kotlin accepts `Pug.track` as both a
`@JvmField val` and a `fun`; if not, use `operator fun invoke` with `@JvmName("event")`.

**Notes (done 2026-10-11).** `make check` green: 307 tests (18 new), lint clean, release AAR 271 KB
(`TrackNamespace` and its Java overloads are most of the 55 KB; an app's R8 drops what it does not
call).
- Public: `Pug.track`, a `@JvmField val` of `TrackNamespace` next to the `track(kind, …)` function
  (Kotlin and Java resolve both: `Pug.track("kind")`, `Pug.track.addToCart(…)`); `TrackNamespace`
  (123 methods, `@JvmOverloads`, internal constructor); `PugEvents` (`const val` kinds, so Java can
  `switch` on them). Decisions D23–D25.
- `tools/gen-track` is the Gradle module `:tools:gen-track` (Kotlin/JVM + org.json, not shipped).
  AGP 9's built-in Kotlin puts KGP on the build classpath, so it applies `org.jetbrains.kotlin.jvm`
  without a version. `make typed-track` runs `buf build --path proto/common/events/v1 -o
  build/typed-track/events.json` (a JSON image: named custom options and source comments), then
  the generator. `make protos` is now `descriptors` + `typed-track`, and `check-codegen` covers the
  three generated files. The generator fails on a repeated kind, `PLATFORM_UNSPECIFIED`, nested
  types/enums/oneofs, repeated, map or unsupported field types, a kind that is not snake_case, and
  names unusable from Kotlin or Java (keywords, `Object` methods, `extras`/`options`). Its output is
  ktlint-clean as written (one line when it fits, else one item per line with a trailing comma),
  which `spotlessCheck` guards.
- Generated KDoc: the proto's leading comment, notes on the events Pug tracks itself and on
  `screenView` (D23), and `@param` notes for Duration (D24) and `pii` properties.
- Methods pass their values positionally, in schema order, to `PugFacade.trackTyped`; the
  `EventSchema` (`internal/event/WellKnownEvents`) names them. `PugClient.trackTyped` maps on the
  caller's thread like `track`: Durations become text, a null required argument drops the event,
  `extras` are mapped first and the arguments override them. The D19 coercion lives in
  `PropertyMapper.mapCustom`, so untyped properties and `extras` get it too. The untyped `track` of
  a well-known kind logs at debug when a required property is missing.
- The kind constants of `PugClient` and `InstallTracker` now come from `PugEvents`.
- Tests: `WellKnownEventsTest` and `TrackNamespaceTest` check the generated code against
  `pug.binpb` read with protobuf-java (`EventProtos`, independent of the generator): the registry,
  the constants, and every method invoked by reflection with distinct arguments (parameter types,
  primitive when required; overload count; the properties sent). Mutation-checked: swapped
  positional arguments, no Java-null check, `extras` winning over arguments, no D19 coercion, a
  wrong required flag in the registry, no Duration conversion.
- Surprises: editions pack repeated options, so `platforms` arrives as one length-delimited
  unknown field when read without the extension registered. Kotlin's `object` takes a property
  and a function of the same name without complaint, so the `invoke` fallback was not needed.

For phase 6:
- README: typed events with Kotlin named arguments, the Java overloads (`Pug.track.addToCart("p1")`
  up to the full list with `extras` and `options`), `PugEvents`, and D23–D25 in user terms.
- The ABI dump will carry ~740 `TrackNamespace` methods; a proto bump changes it with
  `make protos`. A property added to an event inserts a parameter before `extras`, which breaks
  compiled Java callers that pass `extras` or `options` positionally: treat such a bump as an API
  change in the CHANGELOG.
- The sample app can use typed calls; the minified build confirms nothing reflective is needed.

### Phase 6 — release readiness ✅

`:sample` app; README (install, quickstart, options, auto-properties, consent, Java usage, R8);
`CHANGELOG`; publishing (`maven-publish` + signing for Maven Central); ABI dump (Kotlin ABI
validation or BCV) in `make check`; minified sample build to prove no keep rules are needed; final
never-crash, threading and StrictMode audit.

**Notes (done 2026-10-11).** `make check` green: 311 tests (4 new), lint clean, release AAR 271 KB;
the minified sample APK is 141 KB (debug 1.5 MB).
- Sample: `:sample` (`sh.pug.android.sample`), framework widgets only, so no new dependency.
  `SampleApplication` turns on StrictMode in debug builds (main-thread disk reads, disk writes and
  network, `penaltyDeath`) before `Pug.init`; Kotlin activities plus a Java `ConsentActivity`; dry
  run unless `pug.sample.apiKey` is set; `debug = true` in every build so Logcat shows each event.
  Release: R8 with only `proguard-android-optimize.txt`, signed with the debug key. R8 fails on a
  missing class, so the build proves no keep rules are needed; running the minified app was not
  possible here (no emulator), so it is a release step.
- Publishing: `maven-publish` + `signing` on `:pug`; AGP `singleVariant("release")` with sources and
  javadoc jars (Dokka, public API only); POM from `GROUP`/`POM_ARTIFACT_ID`/`VERSION_NAME`: MIT,
  `pug.sh` as developer, `github.com/pug-sh/sdk-android` (the family's `pug-sh/sdk-*`; this repo has
  no remote yet). `centralBundle` zips a local Maven repository (`pug/build/central/repository`,
  emptied first, `maven-metadata` left out) and refuses unsigned artifacts; `make publish-central`
  uploads it through the Central Portal API as `USER_MANAGED`, so releasing stays a click on the
  Portal. The key comes from `ORG_GRADLE_PROJECT_signingInMemoryKey` and `…KeyPassword`;
  `publish-bundle` runs without the configuration cache, which would store it. Checked with a
  throwaway GPG key: every signature and checksum verifies, and an unsigned bundle is refused. No
  release workflow: publishing is manual (sdk-web), and the steps are in `docs/development.md`.
- API dump: KGP 2.2.10's `abiValidation` registers only under its JVM, Android and multiplatform
  plugins, and BCV looks for `kotlin-android`; AGP 9's built-in Kotlin applies `KotlinBaseApiPlugin`,
  whose `kotlin` extension holds only `ext` and `sourceSets`. So `tools/abi-dump` runs the same
  dumper (`org.jetbrains.kotlin:abi-tools:2.2.10`, BCV's format) over the AAR's `classes.jar` into
  `pug/api/pug.api` (983 lines, most of them `TrackNamespace`). `make check` runs `abiCheck`, which
  fails with a unified diff; `make abi-dump` rewrites the file. `verify-version` also requires the
  README's install line to name `VERSION_NAME`.
- Audit, with an independent read-only review of `pug/src/main`. Fixed: (1) a client whose start-up
  threw kept its scope, so a storage sync or a send begun before the failure could write to storage
  the next client already used; the failure path now cancels it. (2) The consent getters waited up
  to 1 s on every call while start-up was pending (behind a slow teardown, say), not once; they now
  wait until a deadline 1 s after `start()`, then answer unknown at once, warning once (D12). (3)
  Opt-in and opt-out on two threads at once could leave the getters reporting the opposite of what
  the worker applied; the write and the enqueue now share a lock. (4) `MemoryPugStorage` threw
  `NullPointerException` on Java nulls; they are ignored. `MainThreadIoTest` pins the StrictMode
  promise: during a session driven from the main thread, the no-backup directory, package manager,
  resources, system services and permission checks are reached from the worker only. Held: every
  public entry point, coroutine scope and activity callback contains failures; no `lateinit` is read
  before `restore()`; every path after a network await re-checks; nothing on the caller's thread
  touches disk, the package manager or the network.
- Mutation-checked: removing the scope cancel, restoring the per-call consent wait, and reading
  `storageDirectory()` in `start()` each fail a test; an added public function fails `abiCheck`. The
  consent lock holds by construction (nothing between the write and the enqueue can force the
  interleaving in a test).
- Docs: the README is written for the release (it says the SDK is on Maven Central); the CHANGELOG
  has the 0.1.0 entry, still marked unreleased, and the rule that schema bumps are API changes.
- Surprises: every build prints AGP's own `Configuration.setVisible` deprecation. `abi-tools`'
  `printJvmDump` takes class files, not jars. AGP leaves `META-INF/*.kotlin_module` out of APKs, so
  the AAR's `pug.kotlin_module` cannot clash with another library's in an app.

For the first release (`docs/development.md`, Releasing):
- One-time setup outside the repo: the Central Portal namespace `sh.pug` (a DNS TXT record on
  `pug.sh`), a Portal user token, a GPG key on a keyserver, and the GitHub repository the POM names.
- Install the minified sample on a device (`./gradlew :sample:installRelease`) and check Logcat
  before tagging.

### Phase 7 — first release ✅

Run the sample on devices before tagging: emulators at the target API level (36) and at `minSdk`
(21), debug builds under StrictMode and minified release builds, sending to a stub backend on the
host. Fix what that finds, then take the release steps of `docs/development.md` up to a local commit
and tag. Publishing waits for the one-time setup.

**Notes (done 2026-10-12).** `make ci` green: 313 tests (2 new), lint clean, release AAR 272 KB,
minified sample APK 142 KB; `make check-proto-pin` passes.
- Emulators: AOSP `default` x86_64 images of Android 16 (API 36) and 5.0.2 (API 21), headless,
  from `sdkmanager` (1.4 GB with the emulator). The sample sent to a stub Connect server on the host
  (`http://10.0.2.2:8080`) that saved each body and answered with `accepted`; the bodies were
  decoded with `buf convert` against `pug.binpb`. Setup: `docs/development.md`, Sample app.
- Seen working on both levels, debug and minified: the first-launch batch (`app_install`,
  `app_open`, `screen_view`) with the device's auto-properties (`$osVersion` 16 and 5.0.2,
  `$networkType` from `NetworkCapabilities` and from `NetworkInfo`, screen metrics); custom, typed
  and Java calls; `Pug.screen` and `$url`/`$referrer`; `identify` (the anonymous id on the first
  call); `reset`; immediate sends; opt-out (queue and identity purged, later events dropped) and the
  D21 replay on opt-in; a rotation (no `app_close`/`app_open`, the screen not tracked again); Home
  (`app_close` sent at once, the queue drained); a force-stop and relaunch (identity, session and
  queued events restored, no second `app_install`); state files 0600 in `no_backup/pug`. On
  Android's `HttpURLConnection` (OkHttp inside): the headers; a 503 and a captive-portal 200 retried
  with backoff under the same event ids; a 401 with and without a Connect body and a 413 dropped; a
  hung server cut off at the 10 s deadline. No crash, and no main-thread StrictMode violation.
- Fixed: (1) every new connection raised StrictMode's `UntaggedSocketViolation` (API 26+), which a
  VM policy with `penaltyDeath` turns into a crash; requests now run under a TrafficStats tag (§4.5;
  `HttpPugTransportTrafficStatsTest`, on Robolectric, which keeps tags per thread). (2) `app_install`
  was dated when the worker finished start-up, after the launch `app_open` that the main thread had
  dated (by 12 ms on the emulator), so a first launch read install-after-open; it is now dated at
  `Pug.init`, and an opt-in replay at the opt-in (§4.9; Flutter dates both in order on one isolate).
  Mutation-checked: dropping the tag, not restoring the thread's own tag, or dating either install
  on the worker fails a test.
- Observed and kept: an event tracked in an activity's `onCreate` carries the previous screen in
  `$url`, since screens change at `onActivityResumed` (§4.9). A release build with an `http://`
  endpoint on API 28+ fails every send with "Cleartext HTTP traffic … not permitted", an
  `IOException`, so it is retried as `unavailable` and nothing is sent until the endpoint is https.
  In a minified app the SDK's stack traces in Logcat are obfuscated by the host's R8, whose mapping
  file reads them.
- Publication: the unsigned publication (`publishReleasePublicationToCentralRepository`, into
  `pug/build/central/repository`) holds the AAR (its manifest declares only `INTERNET`; no consumer
  rules), the sources and javadoc jars, Gradle module metadata, and a POM with kotlin-stdlib 2.0.21
  (compile) and kotlinx-coroutines-core 1.9.0 (runtime). The CI workflow's action versions exist
  (checkout v7, setup-java v6, setup-gradle v6, cache v6, buf-setup v1.50.0), but CI has never run:
  nothing is pushed.
- Committed and tagged `v0.1.0` locally; the CHANGELOG entry is dated 2026-10-12.

For publishing (`docs/development.md`, Releasing):
- `github.com/pug-sh/sdk-android` exists but is private and empty, and the POM's URLs point at it:
  make it public, add it as `origin`, push `main` and `v0.1.0`, and let CI pass (nothing is
  published yet, so a fix can still move the tag).
- One-time setup: the Central Portal namespace `sh.pug` (a DNS TXT record on `pug.sh`), a Portal
  user token, and a GPG key on a keyserver (this machine has none).
- Then `make publish-central`, Publish on the Portal's Deployments page, and the GitHub release
  from the CHANGELOG entry.

### Phase 8 — ergonomics review ✅

A read of the public API from an app developer's side (2026-10-25). Changed: consent is persisted by
default (D27); `autoTrack` became `autoOpenCloseEvents` (D29); `Pug.track` lost the methods for the
events Pug tracks itself (D23); the consent getters never wait, and `Pug.whenConsentReady` replaces
the wait (D28, `Platform.postToMainThread`); the README's leftover pre-D26 option names are gone.
The `v0.1.0` tag predates all of this; `CHANGELOG.md` lists it under Unreleased.

Then, from the open list: Java tracks well-known events as objects (D31). The two questions left
open were decided on 2026-10-26: `Pug.init` takes only a public key (D32), and values of other
types are sent as their `toString()` instead of being dropped (D33).

## 6. Toolchain

- JDK 21 via `mise` (`mise.toml`), Android SDK at `~/Android/Sdk` (platform 36, build-tools 37.0.0;
  `local.properties` → `sdk.dir`, not committed), buf 1.73.0 via `mise`, Gradle 9.8.1 wrapper,
  AGP 9.4.1. Emulator 37.2.12 with the AVDs `pug36` and `pug21` (AOSP x86_64 images, KVM).
- Run Gradle once into a scratch log and grep it; Kotlin diagnostics start `e: ` / `w: `.

## 7. Family-coordination items

Raise these with the other SDKs rather than resolving them silently:

1. D11 `destroy()` purge semantics: Flutter purges, sdk-web and Android keep.
2. D12 opt-out purging identity: sdk-web and Android purge, Flutter only gates.
3. D17 `$os` casing (`Android` vs Flutter's `android`) and Flutter not sending `$device`/`$mobile`.
4. D18 native screen names in `$url`/`$referrer` trip the server's `url_not_web` degradation counter.
5. D6 2xx non-protobuf responses: permanent on web, transient on Android.
6. D15 client-side batch guards: worth adopting in Flutter and sdk-web.
7. `app_install`/`app_update`: no platform gate in Flutter; Android emits them only on Android by
   construction.
8. sdk-web's immediate send queues a transiently failed event again even after a consent purge
   emptied the queue, so an opted-out user's event can still go out; Android checks a purge
   generation first.
9. D14 latest-call-wins: Flutter and sdk-web link overlapping `identify()` calls in the order their
   answers arrive, so an earlier call that answers last overrides a later one.
10. D21: an opt-in during the first launch loses that install's `app_install` in Flutter; Android
    tracks it, with `app_open` and the current `screen_view`, at the opt-in.
11. D22: with automatic screen views off, Android leaves `$url`/`$referrer` to the host; Flutter
    keeps tracking route state.
12. `$networkType`: Flutter reads it once at start, so it goes stale; Android reads it per event.
13. D24: Duration properties are options.proto's `"1.5s"` text on Android and sdk-rust; Flutter's
    typed methods leave them out.
14. D25: sdk-rust drops an event that fails protovalidate; Android and Flutter send it. An `extras`
    entry named like an unset argument is kept by Android and Flutter, dropped by sdk-rust.
15. options.proto's wire-format comment still describes `custom_properties` as
    `map<string, string>` with string encodings for every type; events now carry typed
    `PropertyValue`s, and only Duration still needs a text form. Worth fixing upstream.
16. D27: Android persists consent by default; sdk-web's `persist` defaults to false.
17. D29: Android's `autoTrack` is `autoOpenCloseEvents`; Flutter and sdk-web keep `autoTrack`, which
    reads as covering every automatic event.
18. D23: Android generates no typed methods for the events it tracks itself (`app_open`,
    `app_close`, `app_install`, `app_update`, `screen_view`); Flutter generates them all.
19. D30: app_events.proto's comments set `app_backgrounded`/`app_foregrounded` apart from
    `app_close`/`app_open`, and the server seed emits both pairs; no mobile SDK sends the first
    pair. Fix the comments, or define `app_close` so a mobile SDK can know when to send it.
20. D30: `app_install.install_source` is the installer package name on Android; the seed uses
    `play_store`/`app_store`. The field needs a vocabulary.
21. D30: Android tracks `app_crashed` automatically; Flutter does not.
22. D30: a `deep_link_opened{url, referrer}` kind would let mobile SDKs track deep links the way
    PostHog does by default.
23. D32: Android's `Pug.init` rejects a key that is not `pub_…`, or holds spaces or control
    characters; sdk-web and Flutter take any key, so a swapped project id fails only at the server,
    and a `prv_` key ships.
24. D33: a value of another type is its `toString()` on Android, its fields through
    `JSON.stringify` on sdk-web, and dropped by Flutter unless `jsonEncode` can encode it.

## 8. Later / not planned

Push and device registration (excluded by the user); campaign and install-referrer attribution;
`cookieless` consent mode (needs a server-derived identity that a shared NAT plus one OkHttp UA
would collide on); `beforeSend` redaction hook; multi-process apps (init in the main process only,
documented); WorkManager-backed delivery.

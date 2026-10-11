# Development

Internal guide for working on `sdk-android`. Design decisions and the phase plan live in
[`PLAN.md`](../PLAN.md); end-user docs live in the [README](../README.md).

## Toolchain

| Tool | Version | Where it comes from |
| ---- | ------- | ------------------- |
| JDK | 21 (Temurin) | `mise.toml` (`mise install`), or any JDK 17+ on `PATH` |
| Android SDK | platform 36, build-tools 37.0.0 | `sdk.dir` in `local.properties` (not committed) or `ANDROID_HOME` |
| Gradle | 9.8.1 | the wrapper |
| Android Gradle Plugin | 9.4.1 | built-in Kotlin (no `kotlin-android` plugin) |
| buf | 1.73.0 | `mise.toml`; only needed to sync or rebuild protos |

The library compiles to Java 11 bytecode with Kotlin `apiVersion`/`languageVersion` 2.0 and
`coreLibrariesVersion` 2.0.21, so apps on older Kotlin can consume it (PLAN.md D2).

## Commands

| Command | What it does |
| ------- | ------------ |
| `make check` | `verify-version`, then one Gradle run of `spotlessCheck`, `:pug:lintDebug`, `:pug:testDebugUnitTest`, `:pug:assembleRelease`, the API dump check and the minified sample build |
| `make fmt` | `spotlessApply` (ktlint 1.8, `android_studio` style) |
| `make lint` / `test` / `build` | the individual Gradle steps |
| `make abi-dump` | rewrites `pug/api/pug.api` from the release AAR (below) |
| `make sample` | builds the sample app, debug and minified release |
| `make publish-bundle` / `publish-central` | the signed Maven Central bundle, and its upload ([Releasing](#releasing)) |
| `make protos` | `descriptors` and `typed-track`: everything generated from `proto/` |
| `make descriptors` | `buf build` → `pug/src/test/resources/pug.binpb`, the descriptor set the conformance tests read |
| `make typed-track` | `buf build` → a JSON image, then `tools/gen-track` → the typed track API (below) |
| `make check-codegen` | fails if the committed generated files differ from a fresh `make protos` |
| `make ci` | `protos`, `check-codegen`, `check`: what CI runs |
| `make sync-protos` | re-export `proto/` from the pinned BSR commit (network) |
| `make check-proto-pin` | release gate: `proto/` equals the pinned export (network) |
| `make proto-latest` | print the newest commit on `buf.build/pugsh/pug:main` |
| `make verify-version` | `VERSION_NAME` in `gradle.properties` equals `SDK_VERSION` in `Version.kt`, and the README's install line names it |

## Protos

`proto/` is vendored from `buf.build/pugsh/pug` at the commit pinned in the `Makefile` (the same
commit as sdk-flutter, sdk-node, sdk-go and sdk-rust), allowlisted to `sdk/events`, `sdk/profiles`,
`common/events/v1` and `common/v1/property_value.proto`. `sdk/devices` is excluded on purpose.

No protobuf code is generated for the runtime: `internal/wire` encodes the handful of messages the
SDK sends by hand (PLAN.md D4), which keeps protobuf off the host app's classpath. Correctness is
pinned by `PugWireTest`, which builds the same messages with protobuf-java's `DynamicMessage` from
the descriptor set and asserts byte-for-byte equality.

To take a newer schema: `make proto-latest`, set `PROTO_COMMIT`, `make sync-protos protos`, then
update `internal/wire` for any change to the encoded messages and run `make check`.

### Typed track

The typed API is generated from the well-known event protos (`proto/common/events/v1`, PLAN.md D19,
D23–D25) and committed:

| File | Holds |
| ---- | ----- |
| `pug/src/main/kotlin/sh/pug/android/TrackNamespace.kt` | `Pug.track.<event>(…)`: one method per event for Android, with KDoc |
| `pug/src/main/kotlin/sh/pug/android/PugEvents.kt` | the kinds as `const val`s |
| `pug/src/main/kotlin/sh/pug/android/internal/event/WellKnownEvents.kt` | each event's `EventSchema`: kind, method, properties with type and required |

`make typed-track` writes them: `buf build --path proto/common/events/v1` makes a JSON image (it
keeps the proto comments and names the custom options, `[common.events.v1.kind]`), and
`./gradlew :tools:gen-track:run` reads it. The generator is the Gradle module `tools/gen-track`
(Kotlin/JVM with org.json, not shipped); it fails loudly on schema shapes it does not support
rather than skipping them. Its output is already ktlint-clean, which `spotlessCheck` holds it to;
when ktlint disagrees, fix the generator rather than formatting the files.

## Public API dump

`pug/api/pug.api` lists the public API of the release AAR in the Kotlin ABI validation (and BCV)
format, and `make check` fails when the AAR differs from it, printing the diff. After an intended
API change, `make abi-dump` rewrites it; review the diff before committing, since a removed or
changed line breaks callers compiled against the previous release.

Neither the Kotlin Gradle plugin's `abiValidation` nor BCV works here: both look for the
`kotlin-android` plugin, and AGP 9's built-in Kotlin applies `KotlinBaseApiPlugin` instead. So
`tools/abi-dump` (the Gradle module `:tools:abi-dump`, not shipped) runs the same dumper, the
`org.jetbrains.kotlin:abi-tools` library at the Kotlin Gradle plugin's version, over the
`classes.jar` inside `pug/build/outputs/aar/pug-release.aar`; its `abiDump` and `abiCheck` tasks
depend on `:pug:bundleReleaseAar`. Internal declarations are left out through the Kotlin metadata,
synthetic `$default` methods stay in: they are what Kotlin callers of a function with default
arguments compile against.

Most of the file is the typed track API. A proto bump that adds a property to an event changes its
methods' signatures (PLAN.md, phase 5 notes): treat it as an API change in the CHANGELOG.

## Sample app

`sample/` (the Gradle module `:sample`, package `sh.pug.android.sample`) is an app built on the SDK
the way a host app is: `Pug.init` in `Application.onCreate` with StrictMode on in debug builds
(disk and network detection on the main thread, `penaltyDeath`), typed and custom events, screens,
identify, reset, and a consent screen written in Java. It uses framework widgets only, so it adds no
dependency to the build.

Its release build is minified by R8 with only `proguard-android-optimize.txt`, no rules for the SDK,
and `make check` builds it: R8 fails on a missing class, so this proves the AAR needs no consumer
rules to shrink. That the minified app also runs is checked on a device:
`./gradlew :sample:installRelease` (signed with the debug key) and `adb logcat -s Pug`, which shows
each event in dry run (`debug` is on in every build of the sample). The project, API key and
endpoint come from the Gradle properties `pug.sample.projectId`, `pug.sample.apiKey` and
`pug.sample.endpoint`, read into `BuildConfig`; without an API key the sample runs in dry run.

### On an emulator

Before a release, run the sample on the target API level and on `minSdk`. The AOSP images are the
smallest (about 800 MB for API 36, 280 MB for API 21) and boot headless:

```bash
sdkmanager "emulator" "system-images;android-36;default;x86_64" "system-images;android-21;default;x86_64"
avdmanager create avd -n pug36 -k "system-images;android-36;default;x86_64" -d pixel_6
avdmanager create avd -n pug21 -k "system-images;android-21;default;x86_64" -d pixel_6
emulator -avd pug36 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect &
```

To see what the SDK sends, serve the two Connect methods on the host's port 8080 (the Pug server,
or a stub that saves each body and answers `BatchCreateResponse{accepted}`), build with
`-Ppug.sample.apiKey=test-key -Ppug.sample.endpoint=http://10.0.2.2:8080` (10.0.2.2 is the host
from the emulator), and decode a saved body with
`buf convert pug/src/test/resources/pug.binpb --type sdk.events.v1.BatchCreateRequest --from body.binpb#format=binpb --to -#format=json`.
Debug builds allow cleartext (`src/debug/AndroidManifest.xml`); release builds do not, so on API
28 and later the minified build runs in dry run or against https, while API 21 predates the block.
Watch `adb logcat -s Pug StrictMode AndroidRuntime`: a debug build dies on main-thread disk or
network access, and logs any VM-policy violation, such as a socket without a TrafficStats tag.

## Testing notes

- All tests are JVM unit tests (`pug/src/test`), JUnit 4 + kotlin.test. The Android classes in
  `internal/platform/`, `AndroidEndToEndTest` and `HttpPugTransportTrafficStatsTest` (TrafficStats
  tags are per thread under Robolectric) run on Robolectric
  (`@RunWith(RobolectricTestRunner::class)`, SDK 36 from `src/test/resources/robolectric.properties`).
- `unitTests.isReturnDefaultValues = true`, and the real `org.json` is a test dependency because
  `android.jar`'s copy is a stub.
- Transport tests run `HttpPugTransport` against MockWebServer (`mockwebserver3`) with real
  sockets. The JDK's `HttpURLConnection` retries a POST once when the connection drops before a
  response, so tests that drop connections enqueue the failure twice.
- Summarize results from `pug/build/test-results/testDebugUnitTest/*.xml` rather than scrolling
  Gradle output.
- Shared fakes live in `pug/src/test/kotlin/sh/pug/android/internal/Fakes.kt`: `RecordingLogger`
  (`.safe()` wraps it in `SafeLogger`), `FakeClock`, `SequentialIds` (valid, predictable UUIDs),
  `FixedRandom` (0.5 removes retry jitter) and `ThrowingStorage`. `FakePlatform`
  (`internal/platform/`) is a fixed device whose lifecycle a test drives (`foreground()`,
  `background()`, `resume(screenClass)`), with an optional storage directory for real files.
  `internal/runtime/RecordedRequests.kt` decodes captured requests with protobuf-java.
- Runtime tests (`internal/runtime/`) run the worker on a `StandardTestDispatcher` and drive virtual
  time with its `TestCoroutineScheduler` (`runCurrent`, `advanceTimeBy`; each file opts in to
  `ExperimentalCoroutinesApi`). `FakeTransport` records requests and answers from a script; after
  `hold()` calls wait for `release(i)` or `releaseAll()`, which opens the race windows (a reset
  during identify, a destroy during a flush). Beware `advanceUntilIdle()` while every send fails: the
  retries never run out.
- `EndToEndTest` runs a client on a real worker against MockWebServer and decodes the requests with
  protobuf-java. Java tests live in `pug/src/test/java`: `PugJavaApiTest` pins the API as Java sees
  it, and `JavaCallers` passes the nulls Kotlin cannot (`PLAN.md` D20).
- The typed track API is tested against the protos, not against the generator: `EventProtos` reads
  the Android events from `pug.binpb` with protobuf-java (the custom options arrive as unknown
  fields), `WellKnownEventsTest` compares the registry and `PugEvents` with them, and
  `TrackNamespaceTest` calls every method by reflection with distinct arguments and checks the
  properties sent.
- `MainThreadIoTest` (Robolectric) holds the SDK to its StrictMode promise, which StrictMode itself
  cannot check on the JVM: its `Application` records the SDK's calls for the no-backup directory,
  the package manager, resources, system services and permissions by thread, and a full session
  driven from the main thread (init, calls, an activity's lifecycle, consent, flush, destroy) must
  leave them all to the worker.
- `Build.VERSION.SDK_INT` reads 0 in JVM tests, so code with an API-level branch takes the level as
  a constructor parameter (`PropertyMapper(logger, sdkInt = 26)`). Robolectric runs SDK 36 only, so
  `AndroidAutoProperties` takes it too, and its tests reach the older branches by passing 22 or 27.
- Robolectric notes. Its Android 16 runtime needs
  `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` on JDK 21, set for every unit test task
  in `pug/build.gradle.kts`. It downloads that runtime (213 MB) into `~/.m2` on first use, which CI
  caches. MockWebServer's OkHttp resolves to its Android build in unit tests and needs API 37 classes
  under Robolectric, so Robolectric tests serve HTTP with the JDK's `com.sun.net.httpserver`. Leave
  `unitTests.isIncludeAndroidResources` off: it makes AGP check that same OkHttp artifact's AAR
  metadata, which demands compileSdk 37.

## Source layout

| Path | Responsibility |
| ---- | -------------- |
| `pug/src/main/kotlin/sh/pug/android/Version.kt` | `SDK_VERSION`, sent as `$sdkVersion` and in the User-Agent |
| `Pug.kt` | The public entry point: thin `@JvmStatic` wrappers over `PugFacade`, and the `track` field |
| `TrackNamespace.kt`, `PugEvents.kt` | Generated: the typed methods and the event kinds |
| `PugOptions.kt`, `TrackOptions.kt`, `TrackingConsent.kt` | Public configuration; `PugOptions` validates numbers at `build()` |
| `PugLogger.kt`, `PugStorage.kt` | Public logger and storage interfaces, `MemoryPugStorage` |
| `internal/logging/SafeLogger.kt` | The logger internals call: debug gating, contains a throwing logger; `LogcatLogger` |
| `internal/storage/` | `FilePugStorage` (default store: a file per key, write-behind), `SafePugStorage` (memory failover), `PugStorageKeys` (`__pug_<projectId>_<name>__`) |
| `internal/PugClock.kt`, `Ids.kt`, `Utf8.kt` | Clock, UUIDv7 ids and `isUuid`, UTF-8 length and truncation |
| `internal/JavaNulls.kt` | `isNull(Any?)`, for parameters a Java caller can pass null to (`PLAN.md` D20) |
| `internal/runtime/PugFacade.kt` | State behind `Pug`: validation, pre-init warnings, the shared worker, the teardown handoff |
| `internal/runtime/PugClient.kt` | One configured instance: caller-thread mapping, the op channel, start-up, consent getters, lifecycle and screens, teardown |
| `internal/runtime/EventDispatcher.kt` | Delivery: batching triggers, one batch in flight, retry backoff, drain |
| `internal/json/JsonWriter.kt` | JSON text for persisted state and collection-valued properties |
| `internal/platform/Platform.kt` | What the runtime takes from the device: storage directory, auto-properties, lifecycle observation |
| `internal/platform/AndroidPlatform.kt` | The real `Platform`, from the application context |
| `internal/platform/AndroidAutoProperties.kt` | Device, app, screen, locale and network auto-properties (PLAN.md §4.10) |
| `internal/platform/ActivityLifecycleTracker.kt` | Activity callbacks to foreground/background and screens, on the main thread |
| `internal/lifecycle/InstallTracker.kt` | `app_install` / `app_update`, once per app version |
| `internal/event/` | `PropertyMapper`, `TraitsMapper`, `HostJson` (host values as JSON), `EventFactory` (D15 guards), limits |
| `internal/event/EventSchema.kt`, `WellKnownEvents.kt` | Schema types and Duration text; the generated registry of well-known events |
| `internal/identity/Identity.kt` | Session and profile: minted lazily, held in memory, written through |
| `internal/consent/TrackingConsentController.kt` | Consent state, persistence and the D12 purges (through callbacks) |
| `internal/queue/` | `EventQueue` (lock/commit/rollback, overflow, persistence), `EventJson` (stored event shape) |
| `internal/wire/Models.kt` | Domain types shared by the runtime and the codec |
| `internal/wire/PugWire.kt` | Encode `BatchCreateRequest`/`IdentifyRequest`, decode `BatchCreateResponse`, `eventSize` |
| `internal/wire/ProtoWriter.kt`, `ProtoReader.kt` | Protobuf wire primitives |
| `internal/transport/HttpPugTransport.kt` | Connect unary client: headers, deadlines, cancellation, error mapping, the TrafficStats tag |
| `internal/transport/RpcCode.kt` | Connect codes, permanent set, HTTP-status fallback, `PugTransportException` |
| `tools/gen-track/` | The typed-track generator: `Catalog.kt` reads the image, `Render.kt` writes the sources |
| `tools/abi-dump/` | The public API dump of the release AAR, and its check |
| `pug/api/pug.api` | The committed public API dump |
| `sample/` | The sample app; its minified release build is part of `make check` |

## Releasing

Publishing to Maven Central is manual and cannot be undone: a published version can never be
replaced, only followed by a new one.

### One-time setup

1. A [Central Portal](https://central.sonatype.com) account with the namespace `sh.pug` verified,
   which takes a DNS TXT record on `pug.sh`.
2. A Portal user token (View Account → Generate User Token). `CENTRAL_TOKEN` is the base64 of its
   `username:password`: `printf '%s' '<username>:<password>' | base64 | tr -d '\n'`.
3. A GPG signing key whose public half is on a keyserver Central checks, such as
   `keyserver.ubuntu.com` (`gpg --keyserver keyserver.ubuntu.com --send-keys <id>`). The build reads
   the armored private key and its passphrase from `ORG_GRADLE_PROJECT_signingInMemoryKey` and
   `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`: `gpg --armor --export-secret-keys <id>`.

### Each release

1. `make check-proto-pin`: `proto/` must be exactly the pinned BSR export.
2. Set the version in `gradle.properties` (`VERSION_NAME`), `Version.kt` (`SDK_VERSION`) and the
   README's install line (`make verify-version` checks all three), and date the CHANGELOG entry.
3. `make ci`, commit as `Release vX.Y.Z`, tag `vX.Y.Z` and push both.
4. With the three variables above exported, `make publish-central`. It builds and signs the bundle
   (`make publish-bundle`; `centralBundle` refuses unsigned artifacts) and uploads it as a
   user-managed deployment.
5. On the Portal's Deployments page, wait for validation, check the files, and press Publish. The
   artifact reaches Maven Central within about half an hour.
6. Create the GitHub release for the tag from the CHANGELOG entry.

`make publish-bundle` alone, with a key, leaves the bundle at `pug/build/central/` for inspection;
`publishToMavenLocal` puts an unsigned copy in `~/.m2` to try in another project.

# Pug Android SDK

Native Kotlin SDK for [Pug](https://pug.sh) analytics on Android: event tracking, identity,
sessions, consent, and batched delivery that survives restarts and bad networks.

- `minSdk 21`, usable from Kotlin and Java.
- Runtime dependencies: kotlin-stdlib (2.0.21 or newer) and kotlinx-coroutines-core (1.9.0 or
  newer). No AndroidX, no OkHttp, no protobuf runtime.
- Declares only the `INTERNET` permission. No R8 or ProGuard rules needed.

## Install

The SDK is on Maven Central:

```kotlin
// build.gradle.kts
dependencies {
    implementation("sh.pug:pug-android:0.1.0")
}
```

## Quickstart

Call `Pug.init` once, in `Application.onCreate`:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Pug.init(this, "your-project-id", PugOptions("your-api-key"))
    }
}
```

Then track from anywhere:

```kotlin
Pug.track("checkout_started", mapOf("cartId" to "c1", "total" to 84.50))
Pug.track.addToCart(productId = "sku-1", price = 9.99, currency = "USD")
Pug.identify("user-42", mapOf("plan" to "pro"))
```

From there the SDK tracks `app_open` and `app_close` as the app comes and goes, a `screen_view`
each time an activity resumes on a different screen, and `app_install` or `app_update` on the first launch of each app version.

Every call returns at once, can be made from any thread, and never throws: calls run in order on one
background thread, and failures are logged rather than raised. The one exception is `Pug.init`,
which throws `IllegalArgumentException` for a blank project id or API key, or an endpoint that is
not an http(s) URL. Calls made before `Pug.init` are ignored with a warning, and so is a second
`Pug.init` until `Pug.destroy`. `Pug.init` does no disk or network work on the calling thread, so it
is safe on the main thread under StrictMode, and the SDK's connections carry a TrafficStats tag, so
`detectUntaggedSockets` does not report them. (Right after `Pug.init`, the consent getters may wait
briefly; see [Tracking consent](#tracking-consent).)

## Options

```kotlin
Pug.init(
    this,
    "your-project-id",
    PugOptions("your-api-key") {
        debug = BuildConfig.DEBUG
        batchMaxSize = 20
        batchMaxWaitMs = 10_000
        defaultTrackingConsent = TrackingConsent.DENIED
        persistTrackingConsent = true
    },
)
```

| Option | Default | |
| ------ | ------- | - |
| `endpoint` | `https://api.pugs.dev` | Base URL of the Pug API. |
| `debug` | `false` | Logs debug messages: each event tracked, sent or dropped, and why. Warnings and errors are always logged. |
| `dryRun` | `false` | Builds and logs events without sending anything; `identify` is not sent either. Consent is unaffected. |
| `batchMaxSize` | `10` | Events go out in requests of up to this many (at most 1000): once that many are queued, or `batchMaxWaitMs` after the first of them. |
| `batchMaxWaitMs` | `5000` | How long the first queued event waits for a full batch. |
| `batchMaxQueueSize` | `1000` | Beyond this many undelivered events, the oldest is dropped. |
| `sessionIdleTimeoutMs` | 30 min | A session ends after this long without events. |
| `sessionMaxDurationMs` | 24 h | A session ends this long after it began. |
| `defaultTrackingConsent` | `GRANTED` | See [Tracking consent](#tracking-consent). |
| `persistTrackingConsent` | `false` | See [Tracking consent](#tracking-consent). |
| `autoTrack` | `true` | Tracks `app_open` and `app_close`. |
| `autoScreenViews` | `true` | Tracks a `screen_view` each time an activity resumes on a different screen. |
| `autoInstallEvents` | `true` | Tracks `app_install` and `app_update`. |
| `logger` | Logcat | A `PugLogger` to receive the SDK's messages instead. |
| `storage` | files | A `PugStorage` to keep the SDK's state in instead; `MemoryPugStorage` keeps nothing on the device. |

`batch`, `session` and `trackingConsent` set their whole group: a member left out takes its default.
Nothing here throws: a number out of range is logged and replaced by its default, and a `maxSize`
above 1000 is capped.

## Tracking events

### Custom events

```kotlin
Pug.track("video_shared", mapOf("videoId" to "v1", "network" to "mastodon", "duration" to 31.5))
Pug.track("order_completed", mapOf("orderId" to "o-1"), TrackOptions { immediate = true })
```

An event kind may contain letters, digits, `_`, `.` and `-`; any other kind is dropped with a
warning. Property values map as follows:

| Value | Sent as |
| ----- | ------- |
| `String`, `CharSequence`, `Char`, enum (its name) | string, cut to 1024 UTF-8 bytes |
| `Boolean` | boolean |
| `Int`, `Long`, `Short`, `Byte`, `BigInteger`, unsigned integers, `AtomicInteger`, `AtomicLong` | integer; one beyond the signed 64-bit range as a string of its digits |
| `Double`, `Float`, other `Number` | decimal; NaN and infinities are dropped |
| `Date`, `Calendar`, `Instant` (API 26+) | timestamp |
| `Map`, `Iterable`, arrays | JSON text |
| `null` | left out |

Anything else is dropped with a warning, as is a key starting with `$`, which is reserved for
auto-properties. Properties are read during the call, so the map can be changed or reused as soon
as `track` returns.

`TrackOptions { immediate = true }` sends the event on its own right away instead of with the next
batch; if that send fails it joins the queue for a retry. `TrackOptions { timestamp = … }` sets when
the event happened, in epoch milliseconds, for an event recorded after the fact.

### Well-known events

The events Pug knows have a typed method on `Pug.track`, generated from the event schemas, with
the event's properties as arguments: required ones first and non-null, optional ones nullable with
a default.

```kotlin
Pug.track.search(query = "trail shoes")
Pug.track.purchase(productId = "sku-1", amount = 89.0, currency = "USD", orderId = "o-1")
Pug.track.videoPlay(videoId = "v1", positionMs = 1_500)
Pug.track.addToCart(
    productId = "sku-1",
    extras = mapOf("coupon" to "SPRING"),
    options = TrackOptions { immediate = true },
)
```

- `extras` carries further properties. An extra named like an argument is dropped with a warning
  when that argument is set.
- Durations are milliseconds in a parameter ending in `Ms`, sent as text in seconds:
  `positionMs = 1_500` sends `position: "1.5s"`.
- Values are sent as given: the schemas name and type the properties, but ranges and patterns are
  not enforced on the device.
- The methods only track. `Pug.track.screenView(…)` does not change the current screen (that is
  `Pug.screen`), and `appOpen`, `appClose`, `appInstall` and `appUpdate` would duplicate the events
  the SDK tracks itself unless you turn those off.

The kinds are constants in `PugEvents` (`PugEvents.ADD_TO_CART` is `"add_to_cart"`). Tracking a
well-known kind with `Pug.track(kind, properties)` works too, and a whole number given for a decimal
property is sent as a decimal (`"price" to 10` arrives as `10.0`), so both paths fill the same
column.

## Automatic events

| Event | When | Option |
| ----- | ---- | ------ |
| `app_open` | The app comes to the foreground (an activity starts while none was started). | `autoTrack` |
| `app_close` | The app leaves the foreground (its last started activity stops, not for a rotation). | `autoTrack` |
| `screen_view` | An activity resumes: `screenName` is its class's simple name, `screenClass` the full one. | `autoScreenViews` |
| `app_install` | The first launch with the SDK on this device, with `appVersion`. | `autoInstallEvents` |
| `app_update` | The first launch of a new app version, with `appVersion` and `previousVersion`. | `autoInstallEvents` |

When the app leaves the foreground the SDK sends what is queued and writes its state to disk,
whatever `autoTrack` says, since the process may be stopped from then on.

For screens that are not activities, such as composables or fragments, call `Pug.screen`:

```kotlin
Pug.screen("Settings")
```

It tracks a `screen_view` unless that screen is already the current one, and every event after it
carries the screen as `$url` and the one before as `$referrer`. Activities feed the same state. With
`autoScreenViews` off, activities are left alone entirely, so in a single-activity app the screens
are only the ones you name.

A device where the SDK has run before never gets `app_install`, so adding the SDK to an app update
does not count its existing users as installs. To see the first screen and `app_open` of a launch,
call `Pug.init` in `Application.onCreate`; called later, the SDK tracks from the next screen on.

## Identify, reset and sessions

```kotlin
Pug.identify("user-42", mapOf("email" to "ada@example.com", "plan" to "pro"))
```

`identify` attaches your own user id, and optional traits, to the profile. The first call links the
events tracked anonymously so far to that user. Traits may nest maps and lists. A blank id or one
starting with `cookieless-` (reserved by the server) is rejected with an error in the log.
`identify` is never retried; a failure is logged.

```kotlin
Pug.reset()          // On logout: a new anonymous id and session, and no user id.
Pug.rotateSession()  // End the session; the next event starts a new one.
Pug.flush()          // Send what is queued now, and write the SDK's state to disk.
Pug.destroy()        // Stop the SDK after a final flush of at most ten seconds.
```

Events queued before `reset()` keep the user they were tracked for, and `reset()` sends them without
waiting for the next batch. `destroy()` keeps identity, persisted consent and undelivered events for the next
`Pug.init`, which waits for the teardown to finish.

## Tracking consent

Consent is granted by default. To wait for the user's choice, start denied and persist the answer:

```kotlin
PugOptions("your-api-key") {
    defaultTrackingConsent = TrackingConsent.DENIED
    persistTrackingConsent = true
}
```

```kotlin
if (Pug.isConsentPending()) showConsentBanner()

Pug.optInTracking()   // the user accepted
Pug.optOutTracking()  // the user declined
```

- While consent is denied, events and `identify` are dropped, not kept for later.
- Opting out drops queued events unsent and removes the stored identity; opting in again starts a
  new anonymous identity.
- When the user opts in, the SDK tracks what consent held back: an `app_install` or `app_update` it
  could not track at launch and, if the app is on screen, `app_open` and a `screen_view` of the
  current screen. A consent banner on the first launch therefore loses none of them.
- With `persist = true` the choice is stored and restored by the next `Pug.init`; without it, it
  lasts until `Pug.destroy()` or the process ends.
- `getTrackingConsent()` is the consent in effect (null before `Pug.init`), `isTrackingEnabled()`
  whether it is granted, and `isConsentPending()` whether the user has yet to choose. Right after
  `Pug.init` the getters may wait up to a second for a stored choice to be read.

## Auto-properties

Every event carries these alongside its own properties:

| Property | Value |
| -------- | ----- |
| `$projectId`, `$sdkVersion` | The project id passed to `Pug.init`, and this SDK's version. |
| `$platform` | `android` |
| `$os`, `$osVersion` | `Android`, and the release, such as `15`. |
| `$device`, `$deviceModel` | `Build.MODEL`, such as `Pixel 9`. |
| `$deviceManufacturer` | `Build.MANUFACTURER`, such as `Google`. |
| `$deviceType` | `tv` on Android TV, else `mobile`. |
| `$mobile` | `true` unless a TV or a tablet (smallest width of 600 dp or more). |
| `$screenWidth`, `$screenHeight`, `$screenScale` | The display in dp, in the current orientation, and its density. |
| `$locale`, `$timezone` | Such as `en-US` and `Europe/Berlin`. |
| `$appName`, `$appPackage`, `$appVersion`, `$appBuild` | From your app's manifest: label, package, `versionName`, `versionCode`. |
| `$networkType` | `wifi`, `mobile`, `ethernet`, `vpn`, `bluetooth`, `other`, `none` or `unknown`. Only when your app holds `ACCESS_NETWORK_STATE`, which the SDK does not declare. |
| `$url`, `$referrer` | The current and previous screen. |

The device name is never collected. Screen, locale, time zone and network are read for each event;
the rest once per launch.

## Delivery and storage

- Events are queued and sent in batches, one request at a time. A request that fails
  for a reason that may pass (no network, a timeout, a server error, a captive portal) is retried
  with exponential backoff from `maxWaitMs` (at least a second) up to five minutes. A request the
  server rejects for good is dropped and logged as an error.
- Undelivered events, identity and consent are kept in files under the app's `noBackupFilesDir`,
  so they survive restarts but are not restored onto another device by Auto Backup. Writes are
  grouped and land within a second, and at once when the app leaves the foreground, on
  `Pug.flush()` and on `Pug.destroy()`.
- Requests are capped at 4 MiB. A single event over that, which only very large properties make, is
  dropped when tracked.
- Pass `storage = MemoryPugStorage()` to keep nothing on the device, or your own `PugStorage`. If
  your storage throws, the SDK falls back to memory until `Pug.destroy()` or the process ends.

In a multi-process app, call `Pug.init` in one process only, usually the main one: processes would
otherwise share the SDK's files.

## Logging

Messages go to Logcat under the tag `Pug`: warnings and errors always, debug messages with
`debug = true`. A `PugLogger` receives them instead:

```kotlin
PugOptions("your-api-key") {
    logger = object : PugLogger {
        override fun debug(message: String) = Timber.d(message)
        override fun warn(message: String, throwable: Throwable?) = Timber.w(throwable, message)
        override fun error(message: String, throwable: Throwable?) = Timber.e(throwable, message)
    }
}
```

A logger that throws is contained.

## Java

```java
Pug.init(this, "your-project-id",
        new PugOptions.Builder("your-api-key")
                .debug(BuildConfig.DEBUG)
                .batchMaxSize(20)
                .batchMaxWaitMs(10_000L)
                .defaultTrackingConsent(TrackingConsent.DENIED)
                .persistTrackingConsent(true)
                .build());

Map<String, Object> properties = new HashMap<>();
properties.put("cartId", "c1");
Pug.track("checkout_started", properties);
Pug.track("order_completed", properties, new TrackOptions.Builder().immediate(true).build());

Pug.track.addToCart("sku-1");                  // the required arguments
Pug.track.addToCart("sku-1", 9.99, "USD");     // then the optional ones, in schema order
Pug.track.addToCart("sku-1", 9.99, "USD", null, 2, null, null, null, extras, new TrackOptions.Builder().immediate(true).build());
Pug.track.videoPlay("v1", 1_500L);
```

Typed methods have an overload for each prefix of their optional arguments, then `extras`, then
`options`. `PugEvents` constants work in a `switch`. A `null` passed for a required argument from Java
drops the call with a warning instead of throwing.

When Pug adds a property to an event schema, the new parameter goes before `extras` and `options`,
so Java code that passes those two positionally needs a `null` for it after updating; Kotlin code
with named arguments only needs a recompile. The changelog flags such updates.

## R8 and ProGuard

No rules are needed. The SDK uses no reflection, and kotlinx-coroutines brings its own rules. The
`sample` app's release build is minified with R8 using only the default rules.

## Sample app

[`sample/`](sample) uses the SDK from Kotlin and Java: typed and custom events, screens, identify,
reset, consent, and StrictMode in debug builds. Install it with `./gradlew :sample:installDebug`
(or `:sample:installRelease` for the minified build) and watch `adb logcat -s Pug`. It runs in dry
run, logging events without sending them, until you set a project in `~/.gradle/gradle.properties`:

```properties
pug.sample.projectId=your-project-id
pug.sample.apiKey=your-api-key
# Optional; a debug build may use http, e.g. http://10.0.2.2:8080 for a backend on the emulator's host.
pug.sample.endpoint=https://api.pugs.dev
```

## Development

See [docs/development.md](docs/development.md) for building, testing and releasing, and
[PLAN.md](PLAN.md) for the design decisions.

## License

[MIT](LICENSE)

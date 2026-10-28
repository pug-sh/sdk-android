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
        Pug.init(this, "your-project-id", PugOptions("pub_your_public_key"))
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
which throws `IllegalArgumentException` for a blank project id, an API key that is not the project's
public key (`pub_…`), or an endpoint that is not an http(s) URL. Calls made before `Pug.init` are ignored with a warning, and so is a second
`Pug.init` until `Pug.destroy`. `Pug.init` does no disk or network work on the calling thread, so it
is safe on the main thread under StrictMode, and the SDK's connections carry a TrafficStats tag, so
`detectUntaggedSockets` does not report them. The consent getters never wait either; see
[Tracking consent](#tracking-consent).

## Options

```kotlin
Pug.init(
    this,
    "your-project-id",
    PugOptions("pub_your_public_key") {
        debug = BuildConfig.DEBUG
        batchMaxSize = 20
        batchMaxWaitMs = 10_000
        defaultTrackingConsent = TrackingConsent.DENIED
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
| `persistTrackingConsent` | `true` | See [Tracking consent](#tracking-consent). |
| `autoOpenCloseEvents` | `true` | Tracks `app_open` and `app_close`, nothing else. |
| `autoScreenViews` | `true` | Tracks a `screen_view` each time an activity resumes on a different screen. |
| `autoInstallEvents` | `true` | Tracks `app_install` and `app_update`. |
| `logger` | Logcat | A `PugLogger` to receive the SDK's messages instead. |
| `storage` | files | A `PugStorage` to keep the SDK's state in instead; `MemoryPugStorage` keeps nothing on the device. |

Each option is set on its own; the ones left out keep their defaults. Nothing here throws: a number
out of range is logged and replaced by its default, and a `batchMaxSize` above 1000 is capped.

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
| anything else, such as `UUID` or `Uri` | its `toString()`, cut to 1024 UTF-8 bytes; a data class's lists every field |

A key starting with `$` is reserved for auto-properties and dropped with a warning. Properties are
read during the call, so the map can be changed or reused as soon as `track` returns.

`TrackOptions { immediate = true }` sends the event on its own right away instead of with the next
batch; if that send fails it joins the queue for a retry. `TrackOptions { timestamp = … }` sets when
the event happened, in epoch milliseconds, for an event recorded after the fact.

### Well-known events

The events Pug knows, but those it tracks itself, have a typed method on `Pug.track`, generated from the event schemas, with
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
- The events the SDK tracks itself have no method: `app_open`, `app_close`, `app_install`,
  `app_update` and `screen_view`. For a screen that is not an activity, call `Pug.screen`. With the
  automatic events turned off, `Pug.track(PugEvents.APP_OPEN)` and the like still work.

The kinds are constants in `PugEvents` (`PugEvents.ADD_TO_CART` is `"add_to_cart"`). Tracking a
well-known kind with `Pug.track(kind, properties)` works too, and a whole number given for a decimal
property is sent as a decimal (`"price" to 10` arrives as `10.0`), so both paths fill the same
column.

## Automatic events

| Event | When | Option |
| ----- | ---- | ------ |
| `app_open` | The app comes to the foreground (an activity starts while none was started). | `autoOpenCloseEvents` |
| `app_close` | The app leaves the foreground (its last started activity stops, not for a rotation). | `autoOpenCloseEvents` |
| `screen_view` | An activity resumes: `screenName` is its class's simple name, `screenClass` the full one. | `autoScreenViews` |
| `app_install` | The first launch with the SDK on this device, with `appVersion` and `installSource`. | `autoInstallEvents` |
| `app_update` | The first launch of a new app version, with `appVersion` and `previousVersion`. | `autoInstallEvents` |

`installSource` is the package name of the app that installed yours, such as `com.android.vending`
for Google Play. It is left out when none is recorded, as for an app installed with `adb`.

When the app leaves the foreground the SDK sends what is queued and writes its state to disk,
whatever `autoOpenCloseEvents` says, since the process may be stopped from then on.

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

Consent is granted by default. To wait for the user's choice, start denied:

```kotlin
PugOptions("pub_your_public_key") { defaultTrackingConsent = TrackingConsent.DENIED }
```

```kotlin
Pug.whenConsentReady { if (Pug.isConsentPending()) showConsentBanner() }

Pug.optInTracking()   // the user accepted
Pug.optOutTracking()  // the user declined
```

The choice is saved, so the banner shows only until the user answers, and an opt-out made in your
settings screen still holds after a restart.

- While consent is denied, events and `identify` are dropped, not kept for later.
- Opting out drops queued events unsent and removes the stored identity; opting in again starts a
  new anonymous identity.
- When the user opts in, the SDK tracks what consent held back: an `app_install` or `app_update` it
  could not track at launch and, if the app is on screen, `app_open` and a `screen_view` of the
  current screen. A consent banner on the first launch therefore loses none of them.
- The choice is stored and restored by the next `Pug.init`. If your app keeps the choice itself and
  calls `optInTracking()` or `optOutTracking()` on every launch, set `persistTrackingConsent = false`:
  the choice then lasts until `Pug.destroy()` or the process ends.
- `getTrackingConsent()` is the consent in effect, `isTrackingEnabled()` whether it is granted, and
  `isConsentPending()` whether the user has yet to choose. They answer at once and never wait. Right
  after `Pug.init`, while the SDK is still reading the saved choice on its own thread, they answer
  "not known yet": `null`, `false` and `true`. `Pug.whenConsentReady { … }` runs its block on the
  main thread as soon as they answer for this user, on the main thread's next turn if they already do.

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
  with exponential backoff from `batchMaxWaitMs` (at least a second) up to five minutes. A request the
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
PugOptions("pub_your_public_key") {
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
        new PugOptions.Builder("pub_your_public_key")
                .debug(BuildConfig.DEBUG)
                .batchMaxSize(20)
                .batchMaxWaitMs(10_000L)
                .defaultTrackingConsent(TrackingConsent.DENIED)
                .build());

Pug.whenConsentReady(() -> {
    if (Pug.isConsentPending()) showConsentBanner();
});

Map<String, Object> properties = new HashMap<>();
properties.put("cartId", "c1");
Pug.track("checkout_started", properties);
Pug.track("order_completed", properties, new TrackOptions.Builder().immediate(true).build());

Pug.track(new PurchaseEvent().orderId("o-1").amount(89).currency("USD").extra("coupon", "FALL10"));
Pug.track(new AddToCartEvent("sku-1").price(9.99).quantity(2),
        new TrackOptions.Builder().immediate(true).build());
Pug.track(new VideoPlayEvent("v1").positionMs(1_500));
Pug.track.signup();                            // an event without properties has no class
```

Each well-known event with properties has a class in `sh.pug.android.events`: the properties the
event requires are constructor arguments, every other property has a setter (numbers as primitives,
so `amount(89)` compiles), and `extra(key, value)` adds a property the event does not define. A
property never set is not sent. `Pug.track` reads the object during the call, so it can be changed
and tracked again. A `null` passed for a required argument drops the event with a warning instead of
throwing. `PugEvents` constants work in a `switch`.

From Java, `Pug.track` has methods only for the events without properties, such as
`Pug.track.signup()`, with overloads that add `extras` and then `options`. The others are hidden
from Java, so each event has one typed form there. When Pug adds a property to an event schema, an
event object gains a setter; a new required property changes its constructor, and an event without
properties that gains one moves from `Pug.track.signup()` to an object. The changelog says so.

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
pug.sample.apiKey=pub_your_public_key
# Optional; a debug build may use http, e.g. http://10.0.2.2:8080 for a backend on the emulator's host.
pug.sample.endpoint=https://api.pugs.dev
```

## License

[MIT](LICENSE)

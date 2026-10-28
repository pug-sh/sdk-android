# Changelog

A release that regenerates the typed track API from newer event schemas says so: a property added
to an event inserts a parameter before `extras` and `options` in its Kotlin method, which callers
passing those positionally must follow. From Java, a required property added changes the event
class's constructor, and an event without properties that gains one trades its `Pug.track` method
for a class.

## Unreleased

- Tracking consent is saved by default (`persistTrackingConsent` now defaults to `true`), so an
  opt-out still holds after a restart and a consent banner shows only until the user answers.
- `autoTrack` is now `autoOpenCloseEvents`: it only ever turned `app_open` and `app_close` on or off.
- `Pug.track` has no methods for the events the SDK tracks itself any more (`appOpen`, `appClose`,
  `appInstall`, `appUpdate`, `screenView`). Use `Pug.screen` for screens; the `PugEvents` constants
  remain.
- Java gets an object per well-known event with properties, in `sh.pug.android.events`, tracked
  with `Pug.track(event)`: `Pug.track(new PurchaseEvent().orderId("o-1").amount(89))` instead of a row
  of nulls. Java no longer sees the methods of `Pug.track` for those events; the ones for events
  without properties, such as `Pug.track.signup()`, remain. Kotlin's named-argument methods are unchanged.
- The consent getters never wait. Right after `Pug.init`, until the saved choice is read, they
  answer "not known yet". The new `Pug.whenConsentReady { … }` runs on the main thread once they
  answer for this user.
- `Pug.init` throws for an API key that is not the project's public key (`pub_…`): a private key,
  which must not ship in an app, or the project id passed in the key's place. It also throws for a
  key with spaces, control characters or non-ASCII, such as a trailing newline, with which every
  send failed.
- An activity resuming after only pausing, as under a permission dialog, no longer tracks a
  `screen_view`, so `$url` stays the screen `Pug.screen` named instead of going back to the activity.
- Property and trait values of other types, such as `UUID` or `Uri`, are sent as their `toString()`
  instead of being dropped.
- `app_install` carries `installSource`, the package name of the app that installed yours, such as
  `com.android.vending` for Google Play. It is left out when none is recorded, as for an app
  installed with `adb`.

## 0.1.0 (2026-10-12)

The first release.

- `Pug.init` from `Application.onCreate`, then `track`, `identify`, `screen`, `reset`,
  `rotateSession`, `flush` and `destroy`. Calls return at once from any thread and never throw; only
  `Pug.init` rejects invalid arguments.
- Typed methods for the 123 well-known events, generated from the event schemas:
  `Pug.track.addToCart(productId = "p1", price = 9.99)`, with `extras` for further properties and
  overloads for Java. Their kinds are `PugEvents` constants.
- Automatic `app_open` and `app_close`, a `screen_view` each time an activity
  resumes on a different screen, and `app_install` and
  `app_update` on the first launch of each app version.
- Tracking consent (granted or denied, optionally persisted): opting out drops queued events and
  the stored identity; opting in tracks what consent held back during the launch.
- Device, app, screen, locale and network auto-properties.
- Batched delivery with retry backoff, and undelivered events, identity and consent kept in files
  that Auto Backup leaves out.
- `PugOptions` for the endpoint, batching, sessions, consent, the automatic events, logging,
  `dryRun` and storage, with a Kotlin builder block and a Java builder.
- Runtime dependencies are kotlin-stdlib and kotlinx-coroutines-core only; `minSdk` 21; no R8 rules
  needed.

# Changelog

A release that regenerates the typed track API from newer event schemas says so: a property added
to an event inserts a parameter before `extras` and `options`, which Java callers passing those
positionally must follow.

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

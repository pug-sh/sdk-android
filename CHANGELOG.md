# Changelog

## Unreleased

- Project scaffold, vendored protos, protobuf wire codec and Connect transport.
- Configuration types (`PugOptions`, `TrackOptions`, `TrackingConsent`, `PugLogger`, `PugStorage`,
  `MemoryPugStorage`) and the internal building blocks: property and trait mapping, event
  guards, identity, consent and the persistent event queue.
- The `Pug` entry point: `init`, `track`, `identify`, `reset`, `rotateSession`, `flush`, `destroy`
  and tracking consent, with batched delivery and retry backoff.
- The Android platform layer: state and undelivered events are kept in files that are not backed
  up; device, app, screen, locale and network auto-properties; `app_open` and `app_close` as the
  app enters and leaves the foreground, which also sends queued events; a `screen_view` for each
  activity shown, and `Pug.screen` for screens that are not activities; `app_install` and
  `app_update` on the first launch of each app version.

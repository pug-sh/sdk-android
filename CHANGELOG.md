# Changelog

## Unreleased

- Project scaffold, vendored protos, protobuf wire codec and Connect transport.
- Configuration types (`PugOptions`, `TrackOptions`, `TrackingConsent`, `PugLogger`, `PugStorage`,
  `MemoryPugStorage`) and the internal building blocks: property and trait mapping, event
  guards, identity, consent and the persistent event queue.
- The `Pug` entry point: `init`, `track`, `identify`, `reset`, `rotateSession`, `flush`, `destroy`
  and tracking consent, with batched delivery and retry backoff. Storage is in memory until the
  Android platform layer lands.

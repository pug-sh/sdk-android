package sh.pug.android

import android.content.Context
import sh.pug.android.internal.runtime.PugFacade

/**
 * The Pug SDK. Call [init] once, in `Application.onCreate`. Every other call can be made from any
 * thread, returns at once, and never throws: failures, and calls made before [init], are logged.
 */
public object Pug {
    private val facade = PugFacade.create()

    /**
     * The well-known events, each with a method that takes its properties as typed arguments:
     * `Pug.track.addToCart(productId = "p1", price = 9.99)`.
     */
    @JvmField
    public val track: TrackNamespace = TrackNamespace(facade)

    /**
     * Starts the SDK for [projectId]. A second call is ignored with a warning until [destroy]. Called
     * on the main thread before any activity starts, as in `Application.onCreate`, it sees the first
     * screen; called later, it tracks from the next one on.
     *
     * @throws IllegalArgumentException when [projectId] or the API key is blank, or the endpoint is
     *   not an http(s) URL.
     */
    @JvmStatic
    public fun init(context: Context, projectId: String, options: PugOptions) {
        facade.init(context, projectId, options)
    }

    /**
     * Tracks an event. [kind] may contain only letters, digits, '_', '.' and '-'. [properties] are
     * read during the call, so the map can be reused once it returns; keys starting with '$' are
     * reserved and dropped. The well-known events, listed in [PugEvents], also have typed methods,
     * such as `Pug.track.addToCart(…)`.
     */
    @JvmStatic
    @JvmOverloads
    public fun track(kind: String, properties: Map<String, Any?>? = null, options: TrackOptions? = null) {
        facade.track(kind, properties, options)
    }

    /**
     * Identifies the user as [externalId], your own user id, with optional [traits]. The first call
     * links the events tracked anonymously so far to that user. Never retried; a failure is logged.
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(externalId: String, traits: Map<String, Any?>? = null) {
        facade.identify(externalId, traits)
    }

    /**
     * Records that the screen called [name] is showing: tracks a `screen_view` unless it already was
     * the current screen, and reports [name] as `$url` (and the screen before it as `$referrer`) on
     * the events that follow. For screens that are not activities, such as composables or fragments;
     * activities are tracked on their own unless `autoScreenViews` is off.
     */
    @JvmStatic
    public fun screen(name: String) {
        facade.screen(name)
    }

    /** For a logout: a new anonymous id and session, and no external id. Queued events are still sent. */
    @JvmStatic
    public fun reset() {
        facade.reset()
    }

    /** Ends the current session; the next event starts a new one. */
    @JvmStatic
    public fun rotateSession() {
        facade.rotateSession()
    }

    /** Sends queued events now, and writes what the SDK keeps to storage. */
    @JvmStatic
    public fun flush() {
        facade.flush()
    }

    /**
     * Stops the SDK after a final flush of at most ten seconds. Identity, persisted consent and
     * undelivered events stay stored for the next [init], which waits for this to finish.
     */
    @JvmStatic
    public fun destroy() {
        facade.destroy()
    }

    /** Grants tracking consent. It is stored for the next launch when consent is persisted. */
    @JvmStatic
    public fun optInTracking() {
        facade.optInTracking()
    }

    /**
     * Denies tracking consent: queued events are dropped unsent, the stored identity is removed, and
     * events and [identify] are dropped until [optInTracking]. It is stored for the next launch when
     * consent is persisted.
     */
    @JvmStatic
    public fun optOutTracking() {
        facade.optOutTracking()
    }

    /**
     * The consent in effect, or null before [init]. Right after [init] this may wait up to a second
     * for a stored choice to be read, and is null if it has not been read by then.
     */
    @JvmStatic
    public fun getTrackingConsent(): TrackingConsent? = facade.getTrackingConsent()

    /** Whether consent is granted, regardless of `dryRun`. False before [init]. */
    @JvmStatic
    public fun isTrackingEnabled(): Boolean = facade.isTrackingEnabled()

    /**
     * Whether the user has yet to choose, so consent is still the configured default: the cue to show
     * a consent prompt. True before [init]. Right after [init] this may wait up to a second for a
     * stored choice to be read.
     */
    @JvmStatic
    public fun isConsentPending(): Boolean = facade.isConsentPending()
}
